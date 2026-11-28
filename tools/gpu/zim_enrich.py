"""Per-article retrieval enrichment of the kit's ZIM corpus (Appropedia, Energypedia, iFixit, the
post-disaster and medical ZIMs): a one-line searchable summary + key terms for every article, so the
large reference ZIMs become findable by the words a field user actually types.

This is the SEARCH-INDEX AID tier for the ZIMs, the same category as the figure captions, the NPU
topic tags and the PDF page-notes: never shown as an answer, never treated as true. The ZIMs stay the
source of record; this only raises retrieval recall. Resumable (per ZIM, keyed by entry path).

Two backends so BOTH accelerators can run it in parallel on different ZIMs:
  --backend llama    (default) Arc GPU via llama.cpp Vulkan + llama-server, like enrich_pages.py
  --backend openvino           Intel NPU/GPU via OpenVINO GenAI LLMPipeline, like tag_pages.py

  tools\\.venv-npu\\Scripts\\python -I tools/gpu/zim_enrich.py --zim tools/kit/appropedia_en_all_maxi_2026-02.zim
  tools\\.venv-npu\\Scripts\\python -I tools/gpu/zim_enrich.py --zim <...> --backend openvino --device NPU
  tools\\.venv-npu\\Scripts\\python -I tools/gpu/zim_enrich.py --zim <...> --dry      # count articles, no model
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
LLAMA_MODEL = ROOT / "tools" / "kit" / "pc-eval" / "models" / "gemma-4-E2B-it-Q4_0.gguf"
OV_MODEL = ROOT / "tools" / "npu" / "models" / "Qwen3-8B-int4-cw-ov"
OUTDIR = ROOT / "tools" / "gpu" / "out" / "zim-notes"

SYSTEM = (
    "You index one article of an offline survival, medical, repair, energy or how-to reference for keyword "
    "search. Use only what is in the article. Reply in exactly this form and nothing else:\n"
    "SUMMARY: <one line, at most 20 words, what practical information this article gives>\n"
    "TERMS: <5 to 15 specific search terms from the article: procedures, equipment, materials, standard or "
    "place names, and numbers with their units>"
)

_TAG = re.compile(r"<(script|style)[^>]*>.*?</\1>", re.S | re.I)
_HTML = re.compile(r"<[^>]+>")


def article_text(item):
    html = bytes(item.content).decode("utf-8", "replace")
    html = _TAG.sub(" ", html)
    text = _HTML.sub(" ", html)
    text = re.sub(r"&[a-z#0-9]+;", " ", text)
    return re.sub(r"\s+", " ", text).strip()


# --relevant-only: enrich the high-value slice first, not every obscure article equally. An article is
# kept when it is English (Appropedia keeps translations as "/es", "/fr" ... subpages) and its title +
# opening text hit at least 2 survival/off-grid terms below. This is a cheap pre-filter on the EXPENSIVE
# model call, not a retrieval tier; the ZIM itself is still fully searched on the phone.
LANG_SUFFIX = re.compile(r"/[a-z]{2,3}(-[a-z]{2,4})?$", re.I)
NAMESPACE = re.compile(r"^(Appropedia|Category|Help|Template|File|User|Talk|Portal|Special|MediaWiki|"
                       r"Image|Module|Property|Form|Concept|Draft):", re.I)
RELEVANT = frozenset((
    # medical / first aid
    "wound", "bleed", "tourniquet", "hemorrhag", "fracture", "splint", "burn", "cpr", "shock", "hypotherm",
    "frostbite", "dehydrat", "diarrhea", "snakebite", "venom", "infection", "antibiotic", "sepsis", "airway",
    "bandage", "first aid", "triage", "poison", "sprain", "dislocat", "concussion", "seizure", "allerg",
    "asthma", "insulin", "wound care", "dose", "medicine", "medical",
    # water
    "water", "purif", "filtrat", "disinfect", "chlorine", "boil", "rainwater", "well ", "spring", "potable",
    # food
    "food", "cook", "preserv", "canning", "ferment", "forag", "edible", "mushroom", "hunt", "fish", "trap",
    "livestock", "poultry", "rabbit", "garden", "seed", "crop", "potato", "compost", "jerky", "smoking",
    "butcher", "slaughter", "nutrition", "root cellar", "beekeep", "goat", "chicken",
    # fire / heat / stove
    "fire", "tinder", "kindling", "stove", "fuel", "firewood", "charcoal", "wood gas", "gasifier", "rocket stove",
    # shelter / weather
    "shelter", "tent", "tarp", "insulat", "lean-to", "bivouac", "cold weather", "heat stress", "wildfire",
    "storm", "lightning", "flood", "tornado", "hurricane", "earthquake", "weather",
    # navigation
    "compass", "navigation", "bearing", "map reading", "contour", "latitude", "longitude", "pace count", "orient",
    # comms / radio
    "radio", "antenna", "frequency", "transceiver", "mesh", "meshtastic", "morse", "distress", "mayday",
    "signal", "repeater", "ham radio", "callsign",
    # power / energy
    "solar", "photovolt", "battery", "generator", "hydro", "wind turbine", "charge", "voltage", "inverter",
    "off-grid", "micro-hydro", "microhydro", "biogas", "charge controller", "power outage", "watt", "ampere",
    # repair / tools / sanitation / rescue / rope
    "repair", "engine", "pump", "wiring", "solder", "weld", "mechanic", "maintenance", "sanitation", "hygiene",
    "latrine", "sewage", "soap", "handwash", "rescue", "flare", "beacon", "whistle", "knot", "lashing", "rope",
    "hitch", "cordage", "survival", "emergency", "disaster",
))


def is_relevant(title, text):
    if LANG_SUFFIX.search(title) or NAMESPACE.match(title):
        return False
    hay = (title + " " + text[:2000]).lower()
    hits = 0
    for k in RELEVANT:
        if k in hay:
            hits += 1
            if hits >= 2:
                return True
    return False


def articles(zim_path, relevant_only=False):
    """Yield (path, title, text) for each real HTML article (skips redirects, menus, tiny stubs;
    with relevant_only, also skips translations and off-topic articles)."""
    from libzim.reader import Archive
    a = Archive(str(zim_path))
    get = a._get_entry_by_id if hasattr(a, "_get_entry_by_id") else a.get_entry_by_id
    for i in range(a.entry_count):
        try:
            e = get(i)
            if e.is_redirect:
                continue
            item = e.get_item()
            if "html" not in item.mimetype:
                continue
            text = article_text(item)
            if len(text) >= 200 and (not relevant_only or is_relevant(e.title, text)):
                yield e.path, e.title, text
        except Exception:
            continue


def parse(raw):
    summary, terms = "", []
    for line in raw.splitlines():
        s = line.strip()
        if s.upper().startswith("SUMMARY:"):
            summary = s[8:].strip()
        elif s.upper().startswith("TERMS:"):
            terms = [t.strip() for t in s[6:].split(",") if t.strip()]
    return summary, terms


# ---- llama backend (GPU) ----
def health(port):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2) as r:
            return b'"ok"' in r.read()
    except Exception:
        return False


def llama_ask(port, text, chars=4500):
    prompt = (f"<start_of_turn>user\n{SYSTEM}\n\nArticle:\n\"\"\"\n{text[:chars]}\n\"\"\"<end_of_turn>\n"
              f"<start_of_turn>model\n")
    body = json.dumps({"prompt": prompt, "temperature": 0.0, "n_predict": 180,
                       "cache_prompt": False, "stop": ["<end_of_turn>", "<eos>"]}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/completion",
                                 data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())["content"].strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zim", required=True)
    ap.add_argument("--out", default="")
    ap.add_argument("--backend", choices=["llama", "openvino"], default="llama")
    ap.add_argument("--device", default="GPU", help="openvino backend: NPU or GPU")
    ap.add_argument("--model", default="")
    ap.add_argument("--port", type=int, default=8095)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--dry", action="store_true", help="count articles and resume state; no model")
    ap.add_argument("--relevant-only", action="store_true",
                    help="enrich only the English survival/power/medical/comms slice (skip translations + off-topic)")
    a = ap.parse_args()

    zim = pathlib.Path(a.zim)
    out = pathlib.Path(a.out) if a.out else OUTDIR / (zim.stem + ".jsonl")
    out.parent.mkdir(parents=True, exist_ok=True)

    done = set()
    if out.exists():
        for line in out.read_text(encoding="utf-8").splitlines():
            if line.strip():
                done.add(json.loads(line)["path"])

    if a.dry:
        n = sum(1 for _ in articles(zim, a.relevant_only))
        tag = "relevant" if a.relevant_only else "articles"
        print(f"{zim.name}: {n} {tag} >=200 chars | already done: {len(done)} | remaining: {n - len(done)}",
              flush=True)
        return 0

    srv = None
    gen = None
    if a.backend == "llama":
        model = pathlib.Path(a.model) if a.model else LLAMA_MODEL
        if not LLAMA.exists() or not model.exists():
            print(f"missing GPU toolchain: {LLAMA if not LLAMA.exists() else model}", flush=True)
            return 1
        srv = subprocess.Popen([str(LLAMA), "-m", str(model), "-c", "4096", "-t", "4",
                                "--port", str(a.port), "--host", "127.0.0.1", "-ngl", "99",
                                "--jinja", "--no-webui"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(180):
            if health(a.port):
                break
            time.sleep(1)
        else:
            print("llama-server did not become healthy", flush=True)
            return 1
        ask = lambda t: llama_ask(a.port, t)
    else:
        import openvino_genai as ov_genai
        model = a.model or str(OV_MODEL)
        cfg = {"MAX_PROMPT_LEN": 4096, "MIN_RESPONSE_LEN": 64} if a.device == "NPU" else {}
        pipe = ov_genai.LLMPipeline(model, a.device, **cfg)
        gen = ov_genai.GenerationConfig()
        gen.max_new_tokens = 180
        gen.do_sample = False

        def ask(t):
            for chars in (4000, 2600, 1600):
                try:
                    raw = str(pipe.generate(f"{SYSTEM}\n\nArticle:\n\"\"\"\n{t[:chars]}\n\"\"\" /no_think", gen))
                    return re.sub(r"<think>.*?</think>", "", raw, flags=re.S).strip()
                except RuntimeError as e:
                    if "MAX_PROMPT_LEN" not in str(e):
                        raise
            return ""

    try:
        n = 0
        t0 = time.time()
        with out.open("a", encoding="utf-8") as fout:
            for path, title, text in articles(zim, a.relevant_only):
                if path in done:
                    continue
                summary, terms = parse(ask(text))
                fout.write(json.dumps({"zim": zim.name, "path": path, "title": title,
                                       "summary": summary, "terms": terms}) + "\n")
                fout.flush()
                n += 1
                if n % 50 == 0:
                    print(f"{n} articles | {(time.time() - t0) / n:.1f} s/article", flush=True)
                if a.limit and n >= a.limit:
                    break
        print(f"done: {n} articles -> {out}", flush=True)
    finally:
        if srv is not None:
            srv.terminate()
            try:
                srv.wait(10)
            except Exception:
                srv.kill()
    return 0


if __name__ == "__main__":
    sys.exit(main())
