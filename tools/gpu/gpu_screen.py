"""Screen GPU drafts for likely errors (self-critique pass) on the Arc GPU.

Reads the drafts produced by gpu_draft.py (tools/gpu/out/drafts*.jsonl) and asks the model, acting as a
careful reviewer, to flag each draft that likely has a factual error, a dangerous instruction, or answers a
different question. Writes tools/gpu/out/screens.jsonl: {id, question, verdict, reason, draft}.

A REVIEW-PRIORITISATION AID ONLY. It does not decide anything that ships: every card is still drafted,
quote-checked against real sources, independently reviewed and owner-approved. The flags just tell a human
which drafts to look at first. Resumable: drafts already screened are skipped.

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
SCREEN = OUTDIR / "screens.jsonl"

SYSTEM = ("You are a careful reviewer of short survival and first-aid draft answers. Given the question "
          "and a draft answer, decide if the draft likely contains a factual error, a dangerous or unsafe "
          "instruction, or answers a different question. Reply EXACTLY 'OK' if it looks sound, or "
          "'FLAG: <reason in at most 15 words>' if not. Nothing else.")


def health(port):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2) as r:
            return b'"ok"' in r.read()
    except Exception:
        return False


def ask(port, question, draft):
    prompt = (f"<start_of_turn>user\n{SYSTEM}\n\nQuestion: {question}\nDraft answer: {draft}"
              f"<end_of_turn>\n<start_of_turn>model\n")
    body = json.dumps({"prompt": prompt, "temperature": 0.0, "n_predict": 40,
                       "cache_prompt": False, "stop": ["<end_of_turn>", "<eos>"]}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/completion",
                                 data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())["content"].strip()


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
    if SCREEN.exists():
        done = {json.loads(l)["id"] for l in SCREEN.read_text(encoding="utf-8").splitlines() if l.strip()}
    todo = [d for d in drafts if d["id"] not in done]
    if a.limit:
        todo = todo[: a.limit]
    if not todo:
        print("nothing to screen (no new drafts)", flush=True)
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
        t0, flagged = time.time(), 0
        with SCREEN.open("a", encoding="utf-8") as out:
            for i, d in enumerate(todo, 1):
                v = ask(a.port, d["question"], d["draft"])
                verdict = "FLAG" if v.strip().upper().startswith("FLAG") else "OK"
                reason = v.split(":", 1)[1].strip()[:120] if verdict == "FLAG" and ":" in v else ""
                flagged += verdict == "FLAG"
                out.write(json.dumps({"id": d["id"], "question": d["question"], "verdict": verdict,
                                      "reason": reason, "draft": d["draft"]}) + "\n")
                out.flush()
                if i % 20 == 0 or i == len(todo):
                    print(f"{i}/{len(todo)} screened, {flagged} flagged, {(time.time()-t0)/i:.1f} s/item", flush=True)
        print(f"done: screened {len(todo)}, {flagged} flagged -> {SCREEN}", flush=True)
    finally:
        srv.terminate()
        try:
            srv.wait(10)
        except Exception:
            srv.kill()
    return 0


if __name__ == "__main__":
    sys.exit(main())
