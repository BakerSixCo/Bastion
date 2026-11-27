"""HOSTILE review of GPU drafts, on the Arc GPU.

Assumes every draft is WRONG and dangerous and tries to KILL it: it hunts for a wrong value, a dangerous
step, a missing critical safety step, or an answer to a different question, and passes a draft only if it
genuinely cannot find a fault. Fail-closed: anything it cannot clearly clear is killed.

Reads the drafts from gpu_draft.py (tools/gpu/out/drafts*.jsonl) and writes tools/gpu/out/reviews.jsonl:
{id, question, verdict (PASS|KILL), fault, draft}. A REVIEW-PRIORITISATION AID ONLY — it decides nothing
that ships. Every card is still drafted, quote-checked against real sources, reviewed and owner-approved;
the KILLs just say which drafts to distrust first and surface the dangerous ones. Resumable.

  python -I tools/gpu/gpu_screen.py [--port 8092]
"""
import argparse
import glob
import json
import pathlib
import subprocess
import sys
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
LLAMA = ROOT / "tools" / "kit" / "pc-eval" / "llama-vulkan" / "llama-server.exe"
MODEL = ROOT / "tools" / "kit" / "pc-eval" / "models" / "gemma-4-E2B-it-Q4_0.gguf"
OUTDIR = ROOT / "tools" / "gpu" / "out"
REVIEWS = OUTDIR / "reviews.jsonl"

SYSTEM = ("You are a HOSTILE reviewer of survival and first-aid answers written for people in danger. "
          "Assume the draft is WRONG and could get someone killed, and prove it. Do NOT be fair or generous "
          "and give NO benefit of the doubt. Attack every fact, number, dose, ratio, temperature, time and "
          "step; look for a dangerous instruction, a wrong value, a missing critical safety step, or an "
          "answer to a different question. Pass it only if you genuinely cannot find a single real fault and "
          "it is concretely correct and safe. Reply EXACTLY 'PASS' if it survives, otherwise "
          "'KILL: <the single worst specific fault, in at most 20 words>'. No other words.")


def health(port):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2) as r:
            return b'"ok"' in r.read()
    except Exception:
        return False


def attack(port, question, draft):
    prompt = (f"<start_of_turn>user\n{SYSTEM}\n\nQuestion: {question}\nDraft answer: {draft}"
              f"<end_of_turn>\n<start_of_turn>model\n")
    body = json.dumps({"prompt": prompt, "temperature": 0.0, "n_predict": 48,
                       "cache_prompt": False, "stop": ["<end_of_turn>", "<eos>"]}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/completion",
                                 data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())["content"].strip()


def verdict_of(text):
    """Fail-closed: PASS only on a clear PASS; anything else is a KILL."""
    t = text.strip()
    if t.upper().startswith("PASS"):
        return "PASS", ""
    if t.upper().startswith("KILL"):
        return "KILL", t.split(":", 1)[1].strip()[:140] if ":" in t else t[:140]
    return "KILL", ("unclear review, treated as failed: " + t[:110])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8092)
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()

    drafts, seen = [], set()
    for f in sorted(glob.glob(str(OUTDIR / "drafts*.jsonl"))):
        for line in pathlib.Path(f).read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            d = json.loads(line)
            if d.get("id") and d["id"] not in seen and d.get("draft", "").strip():
                seen.add(d["id"])
                drafts.append(d)
    done = set()
    if REVIEWS.exists():
        done = {json.loads(l)["id"] for l in REVIEWS.read_text(encoding="utf-8").splitlines() if l.strip()}
    todo = [d for d in drafts if d["id"] not in done]
    if a.limit:
        todo = todo[: a.limit]
    if not todo:
        print("nothing to review (no new drafts)", flush=True)
        return 0
    if not LLAMA.exists() or not MODEL.exists():
        print(f"missing GPU toolchain: {LLAMA if not LLAMA.exists() else MODEL}", flush=True)
        return 1

    srv = subprocess.Popen([str(LLAMA), "-m", str(MODEL), "-c", "4096", "-t", "4",
                            "--port", str(a.port), "--host", "127.0.0.1", "-ngl", "99",
                            "--jinja", "--no-webui"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(180):
            if health(a.port):
                break
            time.sleep(1)
        else:
            print("llama-server did not become healthy", flush=True)
            return 1
        t0, killed = time.time(), 0
        with REVIEWS.open("a", encoding="utf-8") as out:
            for i, d in enumerate(todo, 1):
                verdict, fault = verdict_of(attack(a.port, d["question"], d["draft"]))
                killed += verdict == "KILL"
                out.write(json.dumps({"id": d["id"], "question": d["question"], "verdict": verdict,
                                      "fault": fault, "draft": d["draft"]}) + "\n")
                out.flush()
                if i % 20 == 0 or i == len(todo):
                    print(f"{i}/{len(todo)} reviewed, {killed} KILLED, {(time.time()-t0)/i:.1f} s/item", flush=True)
        print(f"done: reviewed {len(todo)}, KILLED {killed}, passed {len(todo)-killed} -> {REVIEWS}", flush=True)
    finally:
        srv.terminate()
        try:
            srv.wait(10)
        except Exception:
            srv.kill()
    return 0


if __name__ == "__main__":
    sys.exit(main())
