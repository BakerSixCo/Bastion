"""Tags every page of the kit manuals with the field-guide topics it covers, on the Intel NPU.

Runs a local model (OpenVINO GenAI) over tools/kit/.textcache/*.txt (pages split by form feed, the
same text find.pl searches). Output: tools/npu/out/page-topics.jsonl, one line per page:
  {"doc": ..., "page": <1-based PDF page>, "topics": [...], "raw": "<model output>"}

This is a pre-filter that helps Claude find source pages for cards and flows. It never reaches the
phone and is never treated as true: every line that ships is still quote-checked and reviewed.
Resumable: pages already in the output are skipped.

  tools\\.venv-npu\\Scripts\\python -I tools\\npu\\tag_pages.py [--device NPU] [--model DIR] [--limit N]
  tools\\.venv-npu\\Scripts\\python -I tools\\npu\\tag_pages.py --refilter   # re-check saved answers
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
    # Communications, electronics, navigation, food and herbs (kit additions 2026-11-26,
    # docs/library-plan.md). Ids are chosen so their key words appear on a matching page (see accept()).
    "radio-channels-frequencies": "radio services and their channel or frequency lists (FRS, GMRS, MURS, CB, marine, amateur, NOAA)",
    "distress-call-mayday": "calling for help by radio, MAYDAY and distress procedure",
    "radio-operating-procedure": "radio procedure, prowords, phonetic alphabet, calling a station",
    "radio-licensing-rules": "who needs a licence, radio service rules",
    "field-expedient-antenna": "building or improvising an antenna",
    "weather-radio-programming": "programming a NOAA weather radio, SAME codes, alert codes",
    "voltage-current-resistance": "volts, amps, ohms, Ohm's law, basic circuits",
    "multimeter-measurement": "measuring voltage, current or resistance with a meter",
    "soldering-wiring": "soldering, splicing and wiring",
    "battery-charging": "batteries, charging, battery safety",
    "electrical-troubleshooting": "finding faults in electrical or radio equipment",
    "electrical-shock": "electric shock hazards and rescue",
    "map-reading-contours": "reading topographic maps, contour lines, map symbols",
    "compass-bearing-declination": "using a compass, bearings, magnetic declination",
    "grid-coordinates": "latitude/longitude, UTM, MGRS grid coordinates, plotting a position",
    "pace-count-distance": "estimating distance on the ground, pace count",
    "atak-mapping-tools": "ATAK map tools, markers, routes, sharing positions",
    "mesh-messaging-meshtastic": "Meshtastic mesh radios, channels, messaging",
    "poultry-chickens": "raising chickens and poultry",
    "rabbits-breeding": "raising and breeding rabbits",
    "slaughter-carcass": "slaughtering livestock, handling the carcass",
    "meat-cutting-curing": "cutting, curing and preserving meat",
    "meat-smoking": "smoking meat safely",
    "jerky-drying": "making jerky, drying meat",
    "food-safety-temperatures": "safe cooking and holding temperatures",
    "food-power-outage": "food safety after a power outage, flood or fire",
    "bread-baking": "making bread and baking",
    "butter-cream": "making butter from cream",
    "herbal-supplement": "herbs and supplements: uses, evidence, side effects, interactions",
    "chinese-medicine": "traditional Chinese medicine, acupuncture, Chinese herbal products",
    "helicopter-landing-zone": "preparing a helicopter landing zone",
    "wildfire-entrapment": "surviving when caught by a wildfire",
    "knots-rope": "knots and rope work",
    "hand-signals": "hand and arm signals",
}

# A Markdown or text document is one "page" in the cache (no form feeds); long ones are tagged in
# sections of about this many characters, split at headings or blank lines. Their "page" in the output
# is the section number.
SECTION_CHARS = 3500

# Not tagged: not a card source (find.pl skips the 2001 SOF handbook by default) or pure lookup tables.
SKIP_DOCS = {"Special-Operations-Forces-Medical-Handbook-2001.pdf", "NOAA-Weather-Radio-Stations-US.md"}


def topics():
    t = {}
    for f in sorted(CARDS.glob("*.json")):
        c = json.loads(f.read_text(encoding="utf-8"))
        t[c["id"]] = c.get("title", c["id"])
    t.update(EXTRA)
    return t


def sections(text):
    """Splits a long Markdown/text document at headings or blank lines into ~SECTION_CHARS pieces."""
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


# Words in topic ids too general to show what a page is about.
GENERIC = {"signs", "treatment", "first", "care", "recognition", "basics", "what", "adult", "field",
           "emergency", "method", "methods", "find", "direction", "actions", "contents", "selection",
           "supply", "storage", "conditions", "warning", "check", "management", "prevention", "with",
           "from", "test", "rescue", "safety", "kindling", "fuel", "kits", "card"}


def keywords(topic_id):
    return [w for w in topic_id.split("-") if len(w) >= 4 and w not in GENERIC]


def accept(topic_id, raw_lower, page_lower):
    """Code check on the model's pick: the id must be in its answer (a longer variant like
    radiation-sickness-symptoms counts), and every one of the topic's own key words must be on the
    page. Stops the model padding with topics copied from the list: with "any key word",
    altitude-illness-treatment passed on 118 pages that only said "illness"."""
    if topic_id not in raw_lower:
        return False
    kws = keywords(topic_id)
    return bool(kws) and all(k[:6] in page_lower for k in kws)


def pick(ids, raw, page_text):
    """Accepted topics in the order the model gave them, at most 3."""
    raw_lower, page_lower = raw.lower(), page_text.lower()
    found = [k for k in ids if accept(k, raw_lower, page_lower)]
    return sorted(found, key=lambda k: raw_lower.find(k))[:3]


def refilter(ids):
    """Re-applies the code check to the saved model answers (no model run)."""
    text = {(d, p): t for d, p, t in pages()}
    rows = [json.loads(l) for l in OUT.read_text(encoding="utf-8").splitlines()]
    for r in rows:
        r["topics"] = pick(ids, r["raw"], text.get((r["doc"], r["page"]), ""))
    OUT.write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
    print(f"refiltered {len(rows)} pages")


def prompt(topic_list, text, chars=4500):
    return (
        "You label pages of survival and medical manuals.\n"
        "Topics:\n" + topic_list + "\n\n"
        "Page text:\n\"\"\"\n" + text[:chars] + "\n\"\"\"\n\n"
        "Which topics does this page give practical instructions or key facts about? "
        "Reply with at most 3 topic ids from the list, comma-separated, or the word none. No other words. /no_think"
    )


def main():
    global OUT
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="NPU")
    ap.add_argument("--model", default=str(ROOT / "tools" / "npu" / "models" / "Qwen3-8B-int4-cw-ov"))
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--refilter", action="store_true", help="re-check saved answers, no model run")
    ap.add_argument("--out", default=str(OUT), help="output .jsonl (default: out/page-topics.jsonl)")
    ap.add_argument("--docs-file", default="", help="tag only the documents listed in this file, one per line")
    a = ap.parse_args()
    OUT = pathlib.Path(a.out)
    only = set()
    if a.docs_file:
        only = {l.strip() for l in pathlib.Path(a.docs_file).read_text(encoding="utf-8").splitlines() if l.strip()}

    t = topics()
    ids = set(t)
    if a.refilter:
        return refilter(ids)

    import openvino_genai as ov_genai

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
            if (doc, page) in done or (only and doc not in only):
                continue
            raw = None
            # Dense pages (tables, numbers) can pass the NPU's prompt limit: retry with less of the page.
            for chars in (4500, 3000, 1800):
                try:
                    raw = str(pipe.generate(prompt(topic_list, text, chars), gen))
                    break
                except RuntimeError as e:
                    if "MAX_PROMPT_LEN" not in str(e):
                        raise
            if raw is None:
                print(f"skipped {doc} p{page}: too long even at 1800 chars", flush=True)
                continue
            raw = re.sub(r"<think>.*?</think>", "", raw, flags=re.S).strip()
            found = pick(ids, raw, text)
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
