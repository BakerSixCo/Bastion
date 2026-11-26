"""Source packs for planned cards, on the Intel NPU: for each planned card question, the local model copies
the sentences that answer it from the most likely kit pages, and code keeps only sentences that appear
word for word on that page.

Input:  tools/npu/source-pack-questions.json  [{"id", "question", "topics": [tag ids]}]
        tools/npu/out/page-topics*.jsonl (tags from tag_pages.py) and tools/kit/.textcache (page text)
Output: tools/npu/out/source-packs/<id>.md and tools/npu/out/source-packs/summary.jsonl

A drafting aid only: it never reaches the phone. Card text is still drafted, quote-checked (build.pl),
reviewed and approved as usual. Resumable: questions with a finished pack are skipped.

  tools\\.venv-npu\\Scripts\\python -I tools\\npu\\source_packs.py [--device NPU] [--pages 10]
"""
import argparse
import json
import math
import pathlib
import re
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import tag_pages  # noqa: E402  (page splitting and the cache location)

ROOT = tag_pages.ROOT
QUESTIONS = ROOT / "tools" / "npu" / "source-pack-questions.json"
OUTDIR = ROOT / "tools" / "npu" / "out" / "source-packs"
STOP = set("the and for are with that this from what how you your can should when does into have has was were "
           "not but use used using will which who why their there they them then than also its it's".split())


def words(s):
    return [w for w in re.findall(r"[a-z0-9]+", s.lower()) if len(w) >= 3 and w not in STOP]


def norm(s):
    s = s.replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"')
    return re.sub(r"\s+", " ", s).strip().lower()


def load_tags():
    tags = {}
    for f in sorted((ROOT / "tools" / "npu" / "out").glob("page-topics*.jsonl")):
        for line in f.read_text(encoding="utf-8").splitlines():
            r = json.loads(line)
            tags.setdefault((r["doc"], r["page"]), set()).update(r["topics"])
    return tags


def rank(question, topic_ids, page_list, tags, n):
    """Pages tagged with one of the question's topics first, then the best word matches overall."""
    q = set(words(question))
    df = {}
    toks = []
    for _, _, text in page_list:
        ws = words(text)
        toks.append(ws)
        for w in set(ws) & q:
            df[w] = df.get(w, 0) + 1
    N = len(page_list) or 1

    def score(i):
        ws = toks[i]
        if not ws:
            return 0.0
        tf = {}
        for w in ws:
            if w in q:
                tf[w] = tf.get(w, 0) + 1
        return sum((1 + math.log(c)) * math.log(1 + N / df[w]) for w, c in tf.items()) / (1 + len(ws) / 600)

    scored = sorted(range(len(page_list)), key=score, reverse=True)
    tagged = [i for i in scored if tags.get(page_list[i][:2], set()) & set(topic_ids) and score(i) > 0]
    best = [i for i in scored if score(i) > 0]
    out = []
    for i in tagged[: n - 3] + best:
        if i not in out:
            out.append(i)
        if len(out) >= n:
            break
    return [page_list[i] for i in out]


def prompt(question, text, chars):
    return ("You copy text from a manual page. Question: " + question + "\n\n"
            "Page text:\n\"\"\"\n" + text[:chars] + "\n\"\"\"\n\n"
            "Copy, word for word, the sentences from the page that answer the question, one per line. "
            "Do not change, shorten or add any words. If the page does not answer it, reply NONE. /no_think")


def verified(raw, page_text):
    page = norm(page_text)
    keep, rejected = [], 0
    for line in raw.splitlines():
        s = re.sub(r"^\s*(?:[-*•]|\d+[.)])\s*", "", line).strip().strip('"').strip()
        if not s or s.upper() == "NONE" or len(s) < 25:
            continue
        if norm(s) in page:
            keep.append(s)
        else:
            rejected += 1
    return keep, rejected


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="NPU")
    ap.add_argument("--model", default=str(ROOT / "tools" / "npu" / "models" / "Qwen3-8B-int4-cw-ov"))
    ap.add_argument("--pages", type=int, default=10, help="pages read per question")
    a = ap.parse_args()

    qs = json.loads(QUESTIONS.read_text(encoding="utf-8"))
    OUTDIR.mkdir(parents=True, exist_ok=True)
    page_list = list(tag_pages.pages())
    tags = load_tags()

    import openvino_genai as ov_genai
    cfg = {"MAX_PROMPT_LEN": 4096, "MIN_RESPONSE_LEN": 256} if a.device == "NPU" else {}
    t0 = time.time()
    pipe = ov_genai.LLMPipeline(a.model, a.device, **cfg)
    print(f"model loaded on {a.device} in {time.time() - t0:.0f} s", flush=True)
    gen = ov_genai.GenerationConfig()
    gen.max_new_tokens = 300
    gen.do_sample = False

    with (OUTDIR / "summary.jsonl").open("a", encoding="utf-8") as summary:
        for q in qs:
            md = OUTDIR / f"{q['id']}.md"
            if md.exists():
                continue
            t1 = time.time()
            lines = [f"# Source pack: {q['question']}", "",
                     f"Topics: {', '.join(q['topics'])}. Every quote below was found word for word on the page "
                     "named (whitespace and quote marks normalised). Drafting aid only: re-check with build.pl.", ""]
            total, rejected_total, pages_with = 0, 0, 0
            for doc, page, text in rank(q["question"], q["topics"], page_list, tags, a.pages):
                raw = None
                for chars in (4500, 3000, 1800):
                    try:
                        raw = str(pipe.generate(prompt(q["question"], text, chars), gen))
                        break
                    except RuntimeError as e:
                        if "MAX_PROMPT_LEN" not in str(e):
                            raise
                if raw is None:
                    continue
                raw = re.sub(r"<think>.*?</think>", "", raw, flags=re.S).strip()
                keep, rejected = verified(raw, text)
                rejected_total += rejected
                if keep:
                    pages_with += 1
                    total += len(keep)
                    lines += [f"## {doc}, page {page}", ""] + [f"> {s}" for s in keep] + [""]
            if total == 0:
                lines += ["No sentence on the pages read answered this word for word. Search by hand (find.pl).", ""]
            md.write_text("\n".join(lines), encoding="utf-8")
            summary.write(json.dumps({"id": q["id"], "quotes": total, "pages": pages_with,
                                      "rejected_not_verbatim": rejected_total,
                                      "seconds": round(time.time() - t1)}) + "\n")
            summary.flush()
            print(f"{q['id']}: {total} quotes from {pages_with} pages, {rejected_total} rejected", flush=True)
    print(f"done in {(time.time() - t0) / 60:.1f} min", flush=True)


if __name__ == "__main__":
    sys.exit(main())
