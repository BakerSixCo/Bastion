"""HOSTILE, severity-rated review of GPU drafts, on the Arc GPU.

Assumes every draft is wrong and could get someone killed, and gives no benefit of the doubt — but instead
of a flat pass/kill (which killed ~99% and buried the signal), it rates each draft:
  DANGEROUS  — a wrong value/dose/temperature or a deadly/unsafe instruction (top-priority fix),
  WEAK       — not outright dangerous but incomplete, vague, or missing an important step,
  OK         — it genuinely cannot find a fault.
Fail-closed: anything it cannot clearly clear is at least WEAK.

Reads gpu_draft.py's drafts (tools/gpu/out/drafts*.jsonl) and writes tools/gpu/out/reviews.jsonl:
{id, question, severity, fault, draft}. A REVIEW-PRIORITISATION AID ONLY — it decides nothing that ships;
every card is still drafted, quote-checked against real sources, reviewed and owner-approved. The DANGEROUS
list is what to fix first (after a human confirms the fault is real — this small model also mis-flags).
Resumable.

  python -I tools/gpu/gpu_screen.py [--port 8092]
"""
import argparse
import glob
import json
import pathlib
import re
import subprocess
import sys
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
LLAMA = ROOT / "tools" / "kit" / "pc-eval" / "llama-vulkan" / "llama-server.exe"
# A STRONG reviewer, deliberately a different family from the drafter (gemma-4-E4B): the review gate
# must not be the same small model that wrote the draft. Qwen2.5-14B-Instruct Q4_K_M is the largest
# model the 16 GB Arc 140V runs at usable burst speed. Override with --model.
MODEL = ROOT / "tools" / "kit" / "pc-eval" / "models" / "Qwen2.5-14B-Instruct-Q4_K_M.gguf"
OUTDIR = ROOT / "tools" / "gpu" / "out"
REVIEWS = OUTDIR / "reviews.jsonl"

SYSTEM = ("You are a HOSTILE reviewer of survival and first-aid answers written for people in danger. "
          "Assume the draft is wrong and could get someone killed; give no benefit of the doubt. Rate it: "
          "reply 'DANGEROUS: <fault>' if it states a wrong value, dose or temperature, or a deadly or unsafe "
          "instruction; 'WEAK: <what is missing>' if it is not outright dangerous but is incomplete, vague, or "
          "omits an important step; or 'OK' only if you genuinely cannot fault it. At most 20 words after the "
          "colon. No other words.")


def health(port):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2) as r:
            return b'"ok"' in r.read()
    except Exception:
        return False


def attack(port, question, draft):
    # Chat-completions with the model's own template (--jinja). Qwen takes a real system role, unlike
    # Gemma. Read content, fall back to reasoning_content (some GGUFs route the answer there), and strip
    # any <think>...</think> block so a reasoning model's scratch work never lands in the verdict.
    body = json.dumps({
        "messages": [{"role": "system", "content": SYSTEM},
                     {"role": "user", "content": f"Question: {question}\nDraft answer: {draft}"}],
        "temperature": 0.0, "max_tokens": 80, "cache_prompt": False,
        "chat_template_kwargs": {"enable_thinking": False},
    }).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/chat/completions",
                                 data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=180) as r:
        msg = json.loads(r.read())["choices"][0]["message"]
    text = (msg.get("content") or msg.get("reasoning_content") or "").strip()
    return re.sub(r"(?s)<think>.*?</think>", "", text).strip()


def rate(text):
    """Fail-closed: a clear OK passes, DANGEROUS/WEAK as stated, anything else is at least WEAK."""
    t = text.strip()
    up = t.upper()
    if up.startswith("DANGEROUS"):
        return "DANGEROUS", t.split(":", 1)[1].strip()[:140] if ":" in t else ""
    if up.startswith("OK"):
        return "OK", ""
    if up.startswith("WEAK"):
        return "WEAK", t.split(":", 1)[1].strip()[:140] if ":" in t else ""
    return "WEAK", ("unclear review: " + t[:110])


def main():
    global MODEL
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8092)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--model", default=str(MODEL))
    a = ap.parse_args()
    MODEL = pathlib.Path(a.model)

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
        t0, counts = time.time(), {"DANGEROUS": 0, "WEAK": 0, "OK": 0}
        with REVIEWS.open("a", encoding="utf-8") as out:
            for i, d in enumerate(todo, 1):
                severity, fault = rate(attack(a.port, d["question"], d["draft"]))
                counts[severity] += 1
                out.write(json.dumps({"id": d["id"], "question": d["question"], "severity": severity,
                                      "fault": fault, "draft": d["draft"]}) + "\n")
                out.flush()
                if i % 20 == 0 or i == len(todo):
                    print(f"{i}/{len(todo)} reviewed | {counts['DANGEROUS']} DANGEROUS {counts['WEAK']} WEAK "
                          f"{counts['OK']} OK | {(time.time()-t0)/i:.1f} s/item", flush=True)
        print(f"done: {counts['DANGEROUS']} DANGEROUS, {counts['WEAK']} WEAK, {counts['OK']} OK -> {REVIEWS}", flush=True)
    finally:
        srv.terminate()
        try:
            srv.wait(10)
        except Exception:
            srv.kill()
    return 0


if __name__ == "__main__":
    sys.exit(main())
