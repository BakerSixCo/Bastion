"""Generate CANDIDATE evaluation questions from the kit corpus, on the Arc GPU (llama.cpp Vulkan).

Why: the eval question sets (cards-40, coverage-156, radio-91) predate the 2026-11-26 kit additions
(electronics, navigation, livestock, butchering, food safety, herbs, more radio), so retrieval on the
new material is unmeasured. For each page this proposes 1-3 short field questions THAT PAGE answers,
with the page recorded as the expected source. These are RAW MATERIAL ONLY: the owner/Claude curate a
small, careful eval set from them (like questions-v2) and the checked-answer pipeline is the real gate.
Nothing here is shown to a user or treated as true.

Reuses the proven corpus walk (tools/kit/.textcache, same as tag_pages.py) and the llama-server pattern
(gpu_draft.py / enrich_pages.py). Default model gemma-4-E4B; override with --model. Resumable.

  tools\\.venv-npu\\Scripts\\python -I tools/gpu/gen_eval_questions.py           # full corpus on the GPU
  tools\\.venv-npu\\Scripts\\python -I tools/gpu/gen_eval_questions.py --dry     # count work, no GPU
  tools\\.venv-npu\\Scripts\\python -I tools/gpu/gen_eval_questions.py --limit 3 # smoke test
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
LLAMA = ROOT / "tools" / "kit" / "pc-eval" / "llama-vulkan" / "llama-server.exe"
MODEL = ROOT / "tools" / "kit" / "pc-eval" / "models" / "gemma-4-E4B-it-Q4_0.gguf"
CACHE = ROOT / "tools" / "kit" / ".textcache"
OUT = ROOT / "tools" / "gpu" / "out" / "candidate-questions.jsonl"

SECTION_CHARS = 3500
SKIP_DOCS = {"Special-Operations-Forces-Medical-Handbook-2001.pdf", "NOAA-Weather-Radio-Stations-US.md"}

SYSTEM = (
    "You write test questions to measure the search of an offline field-reference app. Read one page of a "
    "survival, medical, radio, electronics, navigation or food manual and write 1 to 3 short, specific "
    "questions a person in the field might ask that THIS page actually answers (a concrete fact, step, "
    "number, setting or procedure on the page). No yes/no questions, no questions the page does not answer. "
    "Output one question per line, nothing else."
)


def sections(text):
    blocks = re.split(r"\n(?=#{1,6} )|\n\s*\n", text)
    cur = ""
    for b in blocks:
        if cur and len(cur) + len(b) > SECTION_CHARS:
            yield cur
            cur = ""
        cur += b + "\n\n"
    if cur.strip():
        yield cur


def pages():
    for f in sorted(CACHE.glob("*.txt")):
        doc = f.name[:-4]
        if doc in SKIP_DOCS:
            continue
        raw = f.read_text(encoding="utf-8", errors="replace")
        parts = raw.split("\f") if (doc.lower().endswith(".pdf") or "\f" in raw) else list(sections(raw))
        for i, text in enumerate(parts, start=1):
            text = re.sub(r"[ \t]+", " ", text).strip()
            if len(text) >= 200:
                yield doc, i, text


def health(port):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2) as r:
            return b'"ok"' in r.read()
    except Exception:
        return False


def ask(port, page_text, chars=4500):
    prompt = (f"<start_of_turn>user\n{SYSTEM}\n\nPage text:\n\"\"\"\n{page_text[:chars]}\n\"\"\"<end_of_turn>\n"
              f"<start_of_turn>model\n")
    body = json.dumps({"prompt": prompt, "temperature": 0.0, "n_predict": 160,
                       "cache_prompt": False, "stop": ["<end_of_turn>", "<eos>"]}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/completion",
                                 data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())["content"].strip()


def parse_questions(raw):
    out = []
    for line in raw.splitlines():
        s = re.sub(r"^\s*(?:[-*•]|\d+[.)])\s*", "", line).strip()
        if len(s) >= 8 and "?" in s:
            out.append(s)
    return out[:3]


def main():
    global OUT, MODEL
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=str(MODEL))
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--port", type=int, default=8094)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--dry", action="store_true", help="count work and show resume state; no GPU/server")
    a = ap.parse_args()
    OUT = pathlib.Path(a.out)
    MODEL = pathlib.Path(a.model)
    OUT.parent.mkdir(parents=True, exist_ok=True)

    done = set()
    if OUT.exists():
        for line in OUT.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                done.add((r["doc"], r["page"]))

    todo = [(d, p, t) for d, p, t in pages() if (d, p) not in done]
    if a.limit:
        todo = todo[: a.limit]

    if a.dry:
        allp = list(pages())
        print(f"corpus: {len({d for d, _, _ in allp})} docs, {len(allp)} pages >=200 chars", flush=True)
        print(f"already done: {len(done)} | remaining: {len(allp) - len(done)}", flush=True)
        return 0

    if not todo:
        print("all pages already have candidate questions", flush=True)
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
            for i, (doc, page, text) in enumerate(todo, 1):
                qs = parse_questions(ask(a.port, text))
                out.write(json.dumps({"doc": doc, "page": page, "questions": qs}) + "\n")
                out.flush()
                if i % 25 == 0 or i == len(todo):
                    print(f"{i}/{len(todo)} pages | {(time.time() - t0) / i:.1f} s/page", flush=True)
        print(f"done: {len(todo)} pages -> {OUT}", flush=True)
    finally:
        srv.terminate()
        try:
            srv.wait(10)
        except Exception:
            srv.kill()
    return 0


if __name__ == "__main__":
    sys.exit(main())
