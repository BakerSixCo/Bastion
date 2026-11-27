"""Draft first-pass answers for planned-card questions on the Arc GPU (llama.cpp Vulkan).

A DRAFTING AID ONLY: every draft is re-grounded, quote-checked, independently reviewed and owner-approved
like any card before anything ships. This never reaches the phone and is never treated as true.

Starts llama-server from tools/kit/pc-eval/llama-vulkan with the Gemma GGUF on the GPU (-ngl 99), posts each
question to the OpenAI-compatible /v1/chat/completions endpoint, and writes tools/gpu/out/drafts.jsonl
(one {id, question, draft} per line). Resumable: questions already drafted are skipped. Stdlib only.

  python -I tools/gpu/gpu_draft.py [--questions FILE] [--port 8092] [--limit N]
"""
import argparse
import json
import pathlib
import subprocess
import sys
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
LLAMA = ROOT / "tools" / "kit" / "pc-eval" / "llama-vulkan" / "llama-server.exe"
MODEL = ROOT / "tools" / "kit" / "pc-eval" / "models" / "gemma-4-E2B-it-Q4_0.gguf"
QUESTIONS = ROOT / "tools" / "npu" / "source-pack-questions.json"
OUT = ROOT / "tools" / "gpu" / "out" / "drafts.jsonl"

SYSTEM = ("You draft a short plain field-reference answer for an offline survival terminal. "
          "Answer in at most 60 words: concrete steps or facts only, no preamble, no disclaimer. "
          "If you are not certain, say plainly what you are unsure about.")


def health(port):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2) as r:
            return b'"ok"' in r.read()
    except Exception:
        return False


def ask(port, question):
    # Raw /completion with the explicit Gemma turn template: the /v1/chat endpoint routes this GGUF's
    # output into reasoning_content and leaves content empty, so we format the turn ourselves and the
    # answer comes back directly. Gemma has no system role, so the instructions prefix the user turn.
    prompt = f"<start_of_turn>user\n{SYSTEM}\n\n{question}<end_of_turn>\n<start_of_turn>model\n"
    body = json.dumps({"prompt": prompt, "temperature": 0.0, "n_predict": 220,
                       "cache_prompt": False, "stop": ["<end_of_turn>", "<eos>"]}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/completion",
                                 data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())["content"].strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--questions", default=str(QUESTIONS))
    ap.add_argument("--port", type=int, default=8092)
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()

    qs = json.loads(pathlib.Path(a.questions).read_text(encoding="utf-8"))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    done = set()
    if OUT.exists():
        done = {json.loads(l)["id"] for l in OUT.read_text(encoding="utf-8").splitlines() if l.strip()}
    todo = [q for q in qs if q.get("id") not in done]
    if a.limit:
        todo = todo[: a.limit]
    if not todo:
        print("all questions already drafted", flush=True)
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
        t0 = time.time()
        with OUT.open("a", encoding="utf-8") as out:
            for i, q in enumerate(todo, 1):
                draft = ask(a.port, q["question"])
                out.write(json.dumps({"id": q["id"], "question": q["question"], "draft": draft}) + "\n")
                out.flush()
                if i % 5 == 0 or i == len(todo):
                    print(f"{i}/{len(todo)} drafted, {(time.time() - t0) / i:.1f} s/q", flush=True)
        print(f"done: {len(todo)} drafts in {(time.time() - t0) / 60:.1f} min -> {OUT}", flush=True)
    finally:
        srv.terminate()
        try:
            srv.wait(10)
        except Exception:
            srv.kill()
    return 0


if __name__ == "__main__":
    sys.exit(main())
