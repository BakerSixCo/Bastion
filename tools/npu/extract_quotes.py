"""Build a verbatim key-quote index over the kit corpus, on the Intel NPU (OpenVINO GenAI).

Why: card-writing and the project's "quotes must be word-for-word" rule need real sentences pulled from
the source pages. `source_packs.py` only covers 40 targeted card questions; this indexes the whole
corpus. For each page it asks the model for the 1-3 most load-bearing sentences (a key instruction, a
number/dose/frequency/temperature, a safety warning), then KEEPS ONLY the ones that are a verbatim
substring of the page (normalized whitespace) and drops anything the model paraphrased. Fail-closed:
a page whose "quotes" are not actually on the page contributes nothing.

A card-sourcing / retrieval AID ONLY: never shown to a user, never treated as true; every line that
ships is still quote-checked and reviewed. Reuses tag_pages.py's corpus walk and OpenVINO pipeline.
Resumable.

  tools\\.venv-npu\\Scripts\\python -I tools/npu/extract_quotes.py              # full corpus on the NPU
  tools\\.venv-npu\\Scripts\\python -I tools/npu/extract_quotes.py --dry        # count work, no model
  tools\\.venv-npu\\Scripts\\python -I tools/npu/extract_quotes.py --limit 3    # smoke test
"""
import argparse
import json
import pathlib
import re
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
CACHE = ROOT / "tools" / "kit" / ".textcache"
OUT = ROOT / "tools" / "npu" / "out" / "page-quotes.jsonl"

SECTION_CHARS = 3500
SKIP_DOCS = {"Special-Operations-Forces-Medical-Handbook-2001.pdf", "NOAA-Weather-Radio-Stations-US.md"}


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


def norm(s):
    return re.sub(r"\s+", " ", s).strip().lower()


def verbatim(cands, page_text):
    """Keep only candidate sentences that really occur on the page (whitespace-insensitive)."""
    hay = norm(page_text)
    kept = []
    for c in cands:
        c = re.sub(r"^\s*(?:[-*•]|\d+[.)])\s*", "", c).strip().strip('"')
        n = norm(c)
        if len(n) >= 20 and n in hay and c not in kept:
            kept.append(c)
    return kept[:3]


def prompt(text, chars=4000):
    return (
        "Copy word-for-word the 1 to 3 most important sentences from this page: the ones that state a key "
        "instruction, a number (dose, frequency, temperature, distance, time), or a safety warning. Copy "
        "them EXACTLY as written, do not change or summarize them. One sentence per line, nothing else.\n\n"
        "Page:\n\"\"\"\n" + text[:chars] + "\n\"\"\" /no_think"
    )


def main():
    global OUT
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="NPU")
    ap.add_argument("--model", default=str(ROOT / "tools" / "npu" / "models" / "Qwen3-8B-int4-cw-ov"))
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--dry", action="store_true", help="count work and show resume state; no model")
    a = ap.parse_args()
    OUT = pathlib.Path(a.out)
    OUT.parent.mkdir(parents=True, exist_ok=True)

    done = set()
    if OUT.exists():
        for line in OUT.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                done.add((r["doc"], r["page"]))

    if a.dry:
        allp = list(pages())
        print(f"corpus: {len({d for d, _, _ in allp})} docs, {len(allp)} pages >=200 chars", flush=True)
        print(f"already done: {len(done)} | remaining: {len(allp) - len(done)}", flush=True)
        return 0

    import openvino_genai as ov_genai

    cfg = {"MAX_PROMPT_LEN": 4096, "MIN_RESPONSE_LEN": 64} if a.device == "NPU" else {}
    t0 = time.time()
    pipe = ov_genai.LLMPipeline(a.model, a.device, **cfg)
    print(f"model loaded on {a.device} in {time.time() - t0:.0f} s", flush=True)
    gen = ov_genai.GenerationConfig()
    gen.max_new_tokens = 180
    gen.do_sample = False

    n = 0
    t0 = time.time()
    with OUT.open("a", encoding="utf-8") as out:
        for doc, page, text in pages():
            if (doc, page) in done:
                continue
            raw = None
            for chars in (4000, 2600, 1600):   # dense pages can exceed the NPU prompt limit
                try:
                    raw = str(pipe.generate(prompt(text, chars), gen))
                    break
                except RuntimeError as e:
                    if "MAX_PROMPT_LEN" not in str(e):
                        raise
            if raw is None:
                print(f"skipped {doc} p{page}: too long even at 1600 chars", flush=True)
                continue
            raw = re.sub(r"<think>.*?</think>", "", raw, flags=re.S).strip()
            quotes = verbatim(raw.splitlines(), text)
            out.write(json.dumps({"doc": doc, "page": page, "quotes": quotes}) + "\n")
            out.flush()
            n += 1
            if n % 25 == 0:
                print(f"{n} pages | {(time.time() - t0) / n:.1f} s/page", flush=True)
            if a.limit and n >= a.limit:
                break
    print(f"done: {n} pages -> {OUT}", flush=True)


if __name__ == "__main__":
    sys.exit(main())
