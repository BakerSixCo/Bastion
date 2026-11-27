"""Per-page retrieval enrichment for the whole kit corpus, on the Arc GPU (llama.cpp Vulkan).

For every page of every kit manual it writes a one-line searchable summary and a list of specific
key terms (procedures, equipment, standard/place names, numbers-with-units). This is a SEARCH-INDEX
AID ONLY — the same category as the figure captions and the NPU topic tags: it is never shown as an
answer and never treated as true. Every line that ever ships to the phone is still quote-checked,
reviewed and owner-approved. It exists to raise retrieval recall: a page whose prose never uses the
asker's words still becomes findable through these terms.

Reuses the two proven scripts:
  - corpus walk: tools/kit/.textcache/*.txt, pages split on form feed, long Markdown split in
    sections (same text tag_pages.py / find.pl use), so there is NO new PDF extraction.
  - GPU serving: llama-server (-ngl 99) + /completion with the Gemma turn template, exactly as
    gpu_draft.py does. Default model gemma-4-E4B (fast, full-corpus coverage); override with --model.

Resumable: pages already in the output are skipped, so a restart continues where it stopped.

  tools\\.venv-npu\\Scripts\\python -I tools/gpu/enrich_pages.py            # full corpus on the GPU
  tools\\.venv-npu\\Scripts\\python -I tools/gpu/enrich_pages.py --dry      # count work, no GPU/server
  tools\\.venv-npu\\Scripts\\python -I tools/gpu/enrich_pages.py --limit 2  # smoke test
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
MODEL = ROOT / "tools" / "kit" / "pc-eval" / "models" / "gemma-4-E2B-it-Q4_0.gguf"
CACHE = ROOT / "tools" / "kit" / ".textcache"
OUT = ROOT / "tools" / "gpu" / "out" / "page-notes.jsonl"

# Same corpus split as tag_pages.py (kept in sync by hand; both read .textcache).
SECTION_CHARS = 3500
SKIP_DOCS = {"Special-Operations-Forces-Medical-Handbook-2001.pdf", "NOAA-Weather-Radio-Stations-US.md"}

SYSTEM = (
    "You index one page of an offline survival, medical, radio, electronics or field manual for keyword "
    "search. Use only what is on the page. Reply in exactly this form and nothing else:\n"
    "SUMMARY: <one line, at most 20 words, what practical information this page gives>\n"
    "TERMS: <5 to 15 specific search terms from the page, comma-separated: procedures, equipment, "
    "standard or place names, and numbers with their units>"
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
    # Raw /completion with the explicit Gemma turn template, same as gpu_draft.py: the /v1/chat endpoint
    # routes this GGUF's output into reasoning_content and leaves content empty, so format the turn here.
    prompt = (f"<start_of_turn>user\n{SYSTEM}\n\nPage text:\n\"\"\"\n{page_text[:chars]}\n\"\"\"<end_of_turn>\n"
              f"<start_of_turn>model\n")
    body = json.dumps({"prompt": prompt, "temperature": 0.0, "n_predict": 180,
                       "cache_prompt": False, "stop": ["<end_of_turn>", "<eos>"]}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/completion",
                                 data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())["content"].strip()


def parse(raw):
    summary, terms = "", []
    for line in raw.splitlines():
        s = line.strip()
        if s.upper().startswith("SUMMARY:"):
            summary = s[8:].strip()
        elif s.upper().startswith("TERMS:"):
            terms = [t.strip() for t in s[6:].split(",") if t.strip()]
    return summary, terms


def main():
    global OUT, MODEL
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=str(MODEL))
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--port", type=int, default=8093)
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
        allpages = list(pages())
        docs = sorted({d for d, _, _ in allpages})
        print(f"corpus: {len(docs)} docs, {len(allpages)} pages >=200 chars", flush=True)
        print(f"already enriched: {len(done)} | remaining: {len(allpages) - len(done)}", flush=True)
        for d in docs[:5]:
            print("  e.g.", d, flush=True)
        return 0

    if not todo:
        print("all pages already enriched", flush=True)
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
                raw = ask(a.port, text)
                summary, terms = parse(raw)
                out.write(json.dumps({"doc": doc, "page": page, "summary": summary,
                                      "terms": terms, "raw": raw[:300]}) + "\n")
                out.flush()
                if i % 25 == 0 or i == len(todo):
                    print(f"{i}/{len(todo)} enriched | {(time.time() - t0) / i:.1f} s/page", flush=True)
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
