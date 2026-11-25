"""Tags every page of the kit manuals with the field-guide topics it covers, on the Intel NPU.

Runs a local model (OpenVINO GenAI) over tools/kit/.textcache/*.txt (pages split by form feed, the
same text find.pl searches). Output: tools/npu/out/page-topics.jsonl, one line per page:
  {"doc": ..., "page": <1-based PDF page>, "topics": [...], "raw": "<model output>"}

This is a pre-filter that helps Claude find source pages for cards and flows. It never reaches the
phone and is never treated as true: every line that ships is still quote-checked and reviewed.
Resumable: pages already in the output are skipped.

  tools\\.venv-npu\\Scripts\\python -I tools\\npu\\tag_pages.py [--device NPU] [--model DIR] [--limit N]
"""
import argparse
import json
import pathlib
import re
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
CACHE = ROOT / "tools" / "kit" / ".textcache"
CARDS = ROOT / "tools" / "knowledge" / "cards"
OUT = ROOT / "tools" / "npu" / "out" / "page-topics.jsonl"

# Field-guide topics beyond the existing cards (design: docs/field-guide-design.md).
EXTRA = {
    "airway-management": "keeping the airway open, recovery position",
    "pulse-and-breathing-check": "checking pulse and breathing",
    "spinal-injury": "suspected neck or spine injury, moving a casualty",
    "poisoning": "swallowed or contact poisons, what to do",
    "chemical-exposure": "chemical agents, decontamination",
    "radiation-sickness": "radiation exposure, symptoms, care",
    "biological-agents": "infectious or biological agents",
    "childbirth-emergency": "emergency childbirth",
    "medicines": "medicines, doses, essential drugs",
    "tourniquet": "tourniquet application",
    "casualty-evacuation": "carrying or evacuating a casualty, reporting",
    "home-canning": "home canning, food preservation, botulism",
    "hunting-regulations": "hunting seasons, tags, rules",
    "fishing-regulations": "fishing seasons and rules",
    "field-dressing-game": "field dressing and caring for game meat",
    "wild-plants-and-mushrooms": "identifying edible or poisonous plants and mushrooms",
    "sanitation-hygiene": "hygiene, handwashing, waste disposal",
    "weather-and-radio": "weather warnings, NOAA weather radio",
    "cold-weather-operations": "cold weather travel, clothing, equipment",
    "heat-stress-prevention": "preventing heat injury, work/rest, water",
}


def topics():
    t = {}
    for f in sorted(CARDS.glob("*.json")):
        c = json.loads(f.read_text(encoding="utf-8"))
        t[c["id"]] = c.get("title", c["id"])
    t.update(EXTRA)
    return t


def pages():
    for f in sorted(CACHE.glob("*.txt")):
        doc = f.name[:-4]
        for i, text in enumerate(f.read_text(encoding="utf-8", errors="replace").split("\f"), start=1):
            text = re.sub(r"[ \t]+", " ", text).strip()
            if len(text) >= 200:
                yield doc, i, text


# Words in topic ids too general to show what a page is about.
GENERIC = {"signs", "treatment", "first", "care", "recognition", "basics", "what", "adult", "field",
           "emergency", "method", "methods", "find", "direction", "actions", "contents", "selection",
           "supply", "storage", "conditions", "warning", "check", "management", "prevention", "with",
           "from", "test", "rescue", "safety", "kindling", "fuel", "kits", "card"}


def keywords(topic_id):
    return [w for w in topic_id.split("-") if len(w) >= 4 and w not in GENERIC]


def accept(topic_id, raw_lower, page_lower):
    """Code check on the model's pick: the id must be in its answer (a longer variant like
    radiation-sickness-symptoms counts), and one of the topic's own key words must be on the page.
    Stops the model padding with topics copied from the top of the list."""
    if topic_id not in raw_lower:
        return False
    kws = keywords(topic_id)
    return bool(kws) and any(k[:6] in page_lower for k in kws)


def prompt(topic_list, text):
    return (
        "You label pages of survival and medical manuals.\n"
        "Topics:\n" + topic_list + "\n\n"
        "Page text:\n\"\"\"\n" + text[:4500] + "\n\"\"\"\n\n"
        "Which topics does this page give practical instructions or key facts about? "
        "Reply with at most 3 topic ids from the list, comma-separated, or the word none. No other words. /no_think"
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="NPU")
    ap.add_argument("--model", default=str(ROOT / "tools" / "npu" / "models" / "Qwen3-8B-int4-cw-ov"))
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()

    import openvino_genai as ov_genai

    t = topics()
    ids = set(t)
    topic_list = "\n".join(f"{k}: {v}" for k, v in t.items())
    OUT.parent.mkdir(parents=True, exist_ok=True)
    done = set()
    if OUT.exists():
        for line in OUT.read_text(encoding="utf-8").splitlines():
            r = json.loads(line)
            done.add((r["doc"], r["page"]))

    cfg = {"MAX_PROMPT_LEN": 4096, "MIN_RESPONSE_LEN": 64} if a.device == "NPU" else {}
    t0 = time.time()
    pipe = ov_genai.LLMPipeline(a.model, a.device, **cfg)
    print(f"model loaded on {a.device} in {time.time() - t0:.0f} s", flush=True)
    gen = ov_genai.GenerationConfig()
    gen.max_new_tokens = 40
    gen.do_sample = False

    n = 0
    t0 = time.time()
    with OUT.open("a", encoding="utf-8") as out:
        for doc, page, text in pages():
            if (doc, page) in done:
                continue
            raw = str(pipe.generate(prompt(topic_list, text), gen))
            raw = re.sub(r"<think>.*?</think>", "", raw, flags=re.S).strip()
            low = text.lower()
            found = [k for k in ids if accept(k, raw.lower(), low)][:3]
            out.write(json.dumps({"doc": doc, "page": page, "topics": found, "raw": raw[:200]}) + "\n")
            out.flush()
            n += 1
            if n % 25 == 0:
                print(f"{n} pages, {(time.time() - t0) / n:.1f} s/page", flush=True)
            if a.limit and n >= a.limit:
                break
    print(f"done: {n} pages in {(time.time() - t0) / 60:.1f} min", flush=True)


if __name__ == "__main__":
    sys.exit(main())
