"""Flags places where two kit sources give different numbers for the same thing, on the Intel NPU.

Why: the kit now holds overlapping manuals (Ranger Handbook vs the 2026 TCCC manual, NAVCEN vs the
USCG placard, historic food bulletins vs FSIS). When two of them give a different temperature, dose,
frequency, time or distance for the same question, a card drafted from the wrong one would be wrong.
This finds those disagreements for Claude to resolve by hand; it never reaches the phone.

How (code first, model only as a hint):
  1. Code reads the page tags (tools/npu/out/page-topics*.jsonl) and groups pages by topic.
  2. Within a topic, code pulls every "<number> <unit>" fact from the pages (temperatures, frequencies,
     times, distances, doses, voltages, percentages), converts each to a base unit, and pairs facts from
     TWO DIFFERENT documents that share a specific subject word but give values that differ by more than a
     small tolerance. That pairing is objective and is what a human checks.
  3. For each candidate pair the local model only gives an advisory verdict (CONFLICT / DIFFERENT / UNSURE)
     on whether the two really disagree about the same quantity. The verbatim sentences from each page are
     always shown so Claude can judge regardless of what the model said.

Output: tools/npu/out/conflicts.md (grouped, CONFLICT verdicts first) and tools/npu/out/conflicts.jsonl.
Resumable: pairs already in conflicts.jsonl are not re-judged. A drafting/review aid only.

  tools\\.venv-npu\\Scripts\\python -I tools\\npu\\conflict_scan.py [--device NPU] [--no-model] [--limit N]
"""
import argparse
import json
import pathlib
import re
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import tag_pages  # noqa: E402  (page iteration + cache location)
import source_packs as sp  # noqa: E402  (load_tags, norm, STOP)

ROOT = tag_pages.ROOT
OUT = ROOT / "tools" / "npu" / "out"
JSONL = OUT / "conflicts.jsonl"
MD = OUT / "conflicts.md"

# Words that say nothing about WHICH quantity a number describes, so they must not count as a shared
# subject (otherwise "165 F poultry" and "145 F beef" would pair just because both say "cook" and
# "temperature"). Added to source_packs.STOP for the subject test only.
GENERIC = {
    "cook", "cooked", "cooking", "cooks", "temperature", "temperatures", "degree", "degrees", "internal",
    "minimum", "maximum", "safe", "safety", "food", "foods", "heat", "heated", "reach", "reaches",
    "reached", "above", "below", "least", "hold", "holds", "holding", "store", "stored", "storage",
    "water", "time", "times", "about", "approximately", "least", "over", "under", "between", "range",
    "value", "values", "level", "levels", "rate", "amount", "total", "each", "every", "per", "point",
    "number", "numbers", "set", "use", "used", "using", "step", "steps", "table", "figure", "note",
    "should", "must", "will", "than", "then", "within", "into", "onto", "unit", "units", "measure",
    "measured", "reading", "readings", "channel", "channels", "frequency", "frequencies", "band",
    "distance", "length", "weight", "dose", "doses", "dosage", "give", "given", "apply", "applied",
}
# Unit words themselves are never a subject: "4-6 minutes" and "3 minutes" are not about the same thing
# just because both say "minutes". Also drop frequent medical/procedural words that two unrelated
# protocol sentences share by chance.
UNIT_WORDS = {
    "minute", "minutes", "mins", "second", "seconds", "secs", "hour", "hours", "hrs", "day", "days",
    "feet", "foot", "inch", "inches", "yard", "yards", "mile", "miles", "meter", "meters", "metre",
    "metres", "centimeter", "centimeters", "millimeter", "millimeters", "kilometer", "kilometers",
    "gram", "grams", "kilogram", "kilograms", "milligram", "milligrams", "pound", "pounds", "ounce",
    "ounces", "gallon", "gallons", "liter", "liters", "litre", "litres", "milliliter", "milliliters",
    "quart", "quarts", "tablespoon", "tablespoons", "teaspoon", "teaspoons", "cup", "cups", "volt",
    "volts", "kilovolt", "kilovolts", "millivolt", "millivolts", "ampere", "amperes", "amps", "amp",
    "milliamp", "milliamps", "percent", "fahrenheit", "celsius", "degree", "degrees",
}
MED_GENERIC = {
    "patient", "patients", "blood", "pressure", "breathing", "breaths", "breath", "assess", "assessment",
    "monitor", "monitored", "monitoring", "vital", "vitals", "signs", "continue", "continued", "initial",
    "initially", "repeat", "repeated", "begin", "begins", "start", "starts", "started", "normal", "adult",
    "adults", "child", "children", "casualty", "casualties", "provider", "treatment", "treat", "transport",
}
SUBJECT_STOP = sp.STOP | GENERIC | UNIT_WORDS | MED_GENERIC

# <number> <unit> extraction. Each row: (unit regex incl. its own right boundary, family, factor to base).
# Bare one-letter units (m, l, v, a, in) are left out on purpose: they match prepositions and stray
# letters. Temperatures need a degree mark or the word, never a bare F/C.
UNITS = [
    (r"ghz\b", "freq", 1e9), (r"mhz\b", "freq", 1e6), (r"khz\b", "freq", 1e3), (r"hz\b", "freq", 1.0),
    (r"kilometers?\b", "len", 1000.0), (r"km\b", "len", 1000.0),
    (r"miles?\b", "len", 1609.34), (r"yards?\b", "len", 0.9144), (r"yds?\b", "len", 0.9144),
    (r"feet\b", "len", 0.3048), (r"foot\b", "len", 0.3048), (r"ft\b", "len", 0.3048),
    (r"inches\b", "len", 0.0254), (r"inch\b", "len", 0.0254),
    (r"centimeters?\b", "len", 0.01), (r"cm\b", "len", 0.01),
    (r"millimeters?\b", "len", 0.001), (r"mm\b", "len", 0.001),
    (r"meters?\b", "len", 1.0), (r"metres?\b", "len", 1.0),
    (r"hours?\b", "time", 3600.0), (r"hrs?\b", "time", 3600.0),
    (r"minutes?\b", "time", 60.0), (r"mins?\b", "time", 60.0), (r"min\b", "time", 60.0),
    (r"seconds?\b", "time", 1.0), (r"secs?\b", "time", 1.0), (r"sec\b", "time", 1.0),
    (r"days?\b", "time", 86400.0),
    (r"kilograms?\b", "mass", 1000.0), (r"kg\b", "mass", 1000.0),
    (r"milligrams?\b", "mass", 0.001), (r"mg\b", "mass", 0.001),
    (r"grams?\b", "mass", 1.0), (r"pounds?\b", "mass", 453.592), (r"lbs?\b", "mass", 453.592),
    (r"ounces?\b", "mass", 28.3495), (r"oz\b", "mass", 28.3495),
    (r"gallons?\b", "vol", 3.78541), (r"gal\b", "vol", 3.78541),
    (r"liters?\b", "vol", 1.0), (r"litres?\b", "vol", 1.0),
    (r"milliliters?\b", "vol", 0.001), (r"ml\b", "vol", 0.001),
    (r"quarts?\b", "vol", 0.946353), (r"qt\b", "vol", 0.946353),
    (r"tablespoons?\b", "vol", 0.0147868), (r"tbsp\b", "vol", 0.0147868),
    (r"teaspoons?\b", "vol", 0.00492892), (r"tsp\b", "vol", 0.00492892), (r"cups?\b", "vol", 0.236588),
    (r"kilovolts?\b", "volt", 1000.0), (r"kv\b", "volt", 1000.0),
    (r"millivolts?\b", "volt", 0.001), (r"mv\b", "volt", 0.001), (r"volts?\b", "volt", 1.0),
    (r"milliamperes?\b", "amp", 0.001), (r"milliamps?\b", "amp", 0.001), (r"ma\b", "amp", 0.001),
    (r"amperes?\b", "amp", 1.0), (r"amps?\b", "amp", 1.0),
    (r"percent\b", "pct", 1.0), (r"%", "pct", 1.0),
    (r"°\s*f\b", "degF", 1.0), (r"deg(?:rees?)?\s*f(?:ahrenheit)?\b", "degF", 1.0), (r"fahrenheit\b", "degF", 1.0),
    (r"°\s*c\b", "degC", 1.0), (r"deg(?:rees?)?\s*c(?:elsius)?\b", "degC", 1.0), (r"celsius\b", "degC", 1.0),
]
NUM = r"(\d+(?:\.\d+)?)(?:\s*(?:-|–|—|to|through)\s*(\d+(?:\.\d+)?))?"
PATS = [(re.compile(r"(?<![A-Za-z0-9.])" + NUM + r"\s*" + u, re.I), fam, f) for u, fam, f in UNITS]
# Plausible ranges keep stray matches out (a "class C" is not -40..1000 degrees; "5 cups" of flour is fine).
PLAUSIBLE = {"degF": (-80, 1000), "degC": (-80, 600), "pct": (0, 100)}


def facts(sentence):
    """Yield (family, lo_base, hi_base, raw) for each number+unit in the sentence. Spans already taken by
    a longer unit are not re-used, so '162 MHz' is one freq fact, not also a bare-number anything."""
    taken = []
    for pat, fam, factor in PATS:
        for m in pat.finditer(sentence):
            if any(m.start() < e and s < m.end() for s, e in taken):
                continue
            lo = float(m.group(1))
            hi = float(m.group(2)) if m.group(2) else lo
            if fam in PLAUSIBLE and not (PLAUSIBLE[fam][0] <= lo <= PLAUSIBLE[fam][1]):
                continue
            taken.append((m.start(), m.end()))
            yield fam, min(lo, hi) * factor, max(lo, hi) * factor, m.group(0).strip()


def subject(sentence):
    return {w for w in re.findall(r"[a-z]+", sentence.lower()) if len(w) >= 4 and w not in SUBJECT_STOP}


def sentences(text):
    for p in re.split(r"(?<=[.!?])\s+|\n+", text):
        p = p.strip()
        if 10 <= len(p) <= 400:
            yield p


def differ(a, b):
    """True if two base-unit [lo,hi] ranges disagree beyond tolerance (disjoint and far enough apart)."""
    (la, ha), (lb, hb) = a, b
    if la <= hb and lb <= ha:   # overlap → not a disagreement
        return False
    gap = lb - ha if lb > ha else la - hb
    mid = max(abs(la + ha) / 2, abs(lb + hb) / 2, 1e-9)
    return gap > 3 if (ha <= 600 and hb <= 600 and mid < 10) else gap / mid > 0.05


# A single fact pairs with at most this many others (stops one sentence spawning dozens of pairs), and a
# topic yields at most this many pairs (the highest subject-overlap ones). The model and Claude do the
# rest of the filtering; these only keep the candidate list bounded and useful.
CAP_PER_FACT = 4
CAP_PER_TOPIC = 40


def candidates(tags, text):
    """Code pre-filter: within each topic, pair number facts from two different docs in the same unit
    family that share a specific subject word (>=5 chars) and give values that differ. Kept per-fact and
    per-topic bounded, deduplicated across topics, best (most-shared-words) first."""
    by_topic = {}
    for (doc, page), tops in tags.items():
        for t in tops:
            by_topic.setdefault(t, []).append((doc, page))
    seen, out = set(), []
    for topic, pages in by_topic.items():
        rows = []   # (doc, page, sentence, subject_set, family, lo, hi, raw)
        for doc, page in pages:
            for sent in sentences(text.get((doc, page), "")):
                subj = subject(sent)
                if not subj:
                    continue
                for fam, lo, hi, raw in facts(sent):
                    rows.append((doc, page, sent, subj, fam, lo, hi, raw))
        pairs = []
        for i in range(len(rows)):
            for k in range(i + 1, len(rows)):
                a, b = rows[i], rows[k]
                if a[0] == b[0] or a[4] != b[4]:          # same doc, or different unit family
                    continue
                shared = a[3] & b[3]
                if not any(len(w) >= 5 for w in shared):  # need one specific shared subject word
                    continue
                if not differ((a[5], a[6]), (b[5], b[6])):
                    continue
                pairs.append((len(shared), i, k, sorted(shared)))
        pairs.sort(key=lambda p: -p[0])
        deg, kept = {}, 0
        for _, i, k, shared in pairs:
            if deg.get(i, 0) >= CAP_PER_FACT or deg.get(k, 0) >= CAP_PER_FACT:
                continue
            a, b = rows[i], rows[k]
            key = tuple(sorted([(a[0], a[1], round(a[5], 3), round(a[6], 3)),
                                (b[0], b[1], round(b[5], 3), round(b[6], 3))])) + (a[4],)
            if key in seen:
                continue
            seen.add(key)
            deg[i] = deg.get(i, 0) + 1
            deg[k] = deg.get(k, 0) + 1
            lo_side, hi_side = (a, b) if a[0] < b[0] else (b, a)
            out.append({"topic": topic, "family": a[4], "shared": shared,
                        "a": {"doc": lo_side[0], "page": lo_side[1], "sentence": lo_side[2], "value": lo_side[7]},
                        "b": {"doc": hi_side[0], "page": hi_side[1], "sentence": hi_side[2], "value": hi_side[7]},
                        "key": "|".join(f"{p[0]}:{p[1]}:{p[2]}:{p[3]}" for p in key[:2]) + "|" + key[2]})
            kept += 1
            if kept >= CAP_PER_TOPIC:
                break
    return out


def verdict_prompt(a, b):
    return ("Two manual passages each give a value for a measurement.\n\n"
            f'A: "{a["sentence"]}"\n'
            f'B: "{b["sentence"]}"\n\n'
            "Do they give DIFFERENT values for the SAME quantity under the same conditions? "
            "Answer one word: CONFLICT (same quantity, values disagree), DIFFERENT (they measure different "
            "things or conditions), or UNSURE. Then a dash and at most 10 words why. /no_think")


def render(rows):
    order = {"CONFLICT": 0, "UNSURE": 1, "DIFFERENT": 2, "unjudged": 1}
    rows = sorted(rows, key=lambda r: (order.get(r.get("verdict", "unjudged"), 3), r["topic"]))
    out = ["# Kit source conflicts (NPU-flagged, for Claude's review)", "",
           "Code paired numbers from two different documents that share a subject word under one topic but "
           "give values that differ. The model's verdict is an advisory hint only — check every pair against "
           "the real pages (find.pl). Never shown on the phone.", "",
           f"{sum(1 for r in rows if r.get('verdict') == 'CONFLICT')} marked CONFLICT, "
           f"{len(rows)} pairs total.", ""]
    for r in rows:
        v = r.get("verdict", "unjudged")
        why = (" — " + r["why"]) if r.get("why") else ""
        out += [f"## [{v}] {r['topic']} — shared: {', '.join(r['shared'])}{why}",
                f"- **{r['a']['doc']}**, p.{r['a']['page']} ({r['a']['value']}): > {r['a']['sentence']}",
                f"- **{r['b']['doc']}**, p.{r['b']['page']} ({r['b']['value']}): > {r['b']['sentence']}", ""]
    MD.write_text("\n".join(out), encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="NPU")
    ap.add_argument("--model", default=str(ROOT / "tools" / "npu" / "models" / "Qwen3-8B-int4-cw-ov"))
    ap.add_argument("--no-model", action="store_true", help="code candidates only, no NPU verdict")
    ap.add_argument("--limit", type=int, default=0, help="max model verdicts this run (0 = all)")
    a = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)

    tags = sp.load_tags()
    if not tags:
        print("no page tags yet (tools/npu/out/page-topics*.jsonl); run tag_pages first", flush=True)
        return 0
    text = {(d, p): t for d, p, t in tag_pages.pages()}
    cands = candidates(tags, text)
    done = {}
    if JSONL.exists():
        for line in JSONL.read_text(encoding="utf-8").splitlines():
            r = json.loads(line)
            done[r["key"]] = r
    print(f"{len(cands)} candidate pairs from {len(tags)} tagged pages; {len(done)} already judged", flush=True)

    pending = [c for c in cands if c["key"] not in done]
    if a.no_model or not pending:
        rows = list(done.values()) + [dict(c, verdict="unjudged") for c in pending]
        render(rows)
        print(f"wrote {MD} ({len(rows)} pairs){' (no model run)' if a.no_model else ''}", flush=True)
        return 0

    import openvino_genai as ov_genai
    cfg = {"MAX_PROMPT_LEN": 2048, "MIN_RESPONSE_LEN": 64} if a.device == "NPU" else {}
    t0 = time.time()
    pipe = ov_genai.LLMPipeline(a.model, a.device, **cfg)
    print(f"model loaded on {a.device} in {time.time() - t0:.0f} s", flush=True)
    gen = ov_genai.GenerationConfig()
    gen.max_new_tokens = 48
    gen.do_sample = False

    n = 0
    with JSONL.open("a", encoding="utf-8") as jl:
        for c in pending:
            raw = str(pipe.generate(verdict_prompt(c["a"], c["b"]), gen))
            raw = re.sub(r"<think>.*?</think>", "", raw, flags=re.S).strip()
            first = raw.split("\n", 1)[0]
            m = re.match(r"\s*(CONFLICT|DIFFERENT|UNSURE)\b[\s:–-]*(.*)", first, re.I)
            verdict = m.group(1).upper() if m else "UNSURE"
            why = (m.group(2).strip() if m else first)[:80]
            rec = dict(c, verdict=verdict, why=why)
            done[c["key"]] = rec
            jl.write(json.dumps(rec) + "\n")
            jl.flush()
            n += 1
            if n % 20 == 0:
                print(f"{n}/{len(pending)} judged, {(time.time() - t0) / n:.1f} s/pair", flush=True)
            if a.limit and n >= a.limit:
                break
    render(list(done.values()))
    conflicts = sum(1 for r in done.values() if r.get("verdict") == "CONFLICT")
    print(f"done: judged {n} pairs in {(time.time() - t0) / 60:.1f} min; "
          f"{conflicts} CONFLICT. Wrote {MD}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
