"""Describe the figures extracted from the kit PDFs so their visual content becomes searchable text.

Reads the figure extractor's index (tools/figures/out/index.json) and its PNGs (images/<id>.png), captions
each with a vision-language model, and writes tools/figures/out/figure-descriptions.jsonl:
{id, doc, title, page, caption, confidence, description}. Downstream retrieval can index `description` so a
question like "antenna ground plane" or "bowline" reaches a diagram that has no searchable text of its own.

MODEL / DEVICE (owner-approved figure-descriptions job, 2026-11-26): Microsoft Phi-3.5-vision-instruct (MIT
licence, GPL-3.0-safe), int4 OpenVINO IR (OpenVINO/Phi-3.5-vision-instruct-int4-ov). It runs on the **Arc GPU**
(device "GPU"), NOT the NPU: OpenVINO GenAI's VLMPipeline does not yet support vision-language models on the
NPU (text/speech only), so the NPU keeps its text jobs and this uses the otherwise-idle GPU. --device overrides.

A SEARCH-INDEXING AID. The description is model-generated text ABOUT an image; it is never treated as a verified
fact or shown as an answer, and figure captions/labels the extractor already read from the text layer are kept
verbatim alongside it. Resumable: figures already described are skipped.

  tools\\.venv-npu\\Scripts\\python -I tools\\npu\\tag_figures.py [--limit N] [--device GPU] [--model DIR]
"""
import argparse
import json
import pathlib
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
FIGDIR = ROOT / "tools" / "figures" / "out"
INDEX = FIGDIR / "index.json"
OUT = FIGDIR / "figure-descriptions.jsonl"
MODEL = ROOT / "tools" / "npu" / "models" / "Phi-3.5-vision-instruct-int4-ov"

# Transcribe-then-explain: labels and table cells are what make a diagram findable; the explanation adds the
# words a searcher would actually type. Kept factual and bounded.
PROMPT = ("Describe this figure from a survival/first-aid manual so it can be found by text search. "
          "First transcribe every visible label, number, and table cell exactly. Then, in one or two "
          "sentences, say what the figure shows or how to read it. Do not invent anything not visible.")
MAX_SIDE = 1344   # bound Phi-3.5-vision's dynamic cropping: downscale the long side to keep speed/memory sane.


def load_index(path):
    data = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    return data if isinstance(data, list) else data.get("figures", [])


def load_image(path):
    import numpy as np
    from PIL import Image
    img = Image.open(path).convert("RGB")
    w, h = img.size
    if max(w, h) > MAX_SIDE:
        s = MAX_SIDE / max(w, h)
        img = img.resize((max(1, round(w * s)), max(1, round(h * s))), Image.LANCZOS)
    return np.array(img, dtype=np.uint8)[None]   # NHWC, batch 1


def caption(pipe, ov, cfg, arr):
    tensor = ov.Tensor(arr)
    try:
        res = pipe.generate(PROMPT, images=[tensor], generation_config=cfg)
    except TypeError:
        res = pipe.generate(PROMPT, image=tensor, generation_config=cfg)   # older GenAI kwarg
    return " ".join(res.texts[0].split()).strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--index", default=str(INDEX))
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--model", default=str(MODEL))
    ap.add_argument("--device", default="GPU")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--max-new-tokens", type=int, default=320)
    a = ap.parse_args()

    index_path = pathlib.Path(a.index)
    if not index_path.exists():
        print(f"no figure index: {index_path} — run the extractor (tools/figures) first", flush=True)
        return 1
    model_dir = pathlib.Path(a.model)
    if not (model_dir / "openvino_model.xml").exists() and not list(model_dir.glob("*.xml")):
        print(f"missing OpenVINO model dir: {model_dir}", flush=True)
        return 1

    figs = load_index(index_path)
    figdir = index_path.parent   # the extractor lists `file` relative to the index it wrote
    out_path = pathlib.Path(a.out)
    done = set()
    if out_path.exists():
        done = {json.loads(l)["id"] for l in out_path.read_text(encoding="utf-8").splitlines() if l.strip()}
    todo = [f for f in figs if f.get("id") and f["id"] not in done and (figdir / f.get("file", "")).exists()]
    if a.limit:
        todo = todo[: a.limit]
    if not todo:
        print(f"nothing to describe ({len(figs)} in index, {len(done)} already done)", flush=True)
        return 0

    import openvino as ov
    import openvino_genai as ov_genai
    print(f"loading {model_dir.name} on {a.device} (first run compiles, then caches)…", flush=True)
    t_load = time.time()
    pipe = ov_genai.VLMPipeline(str(model_dir), a.device)
    cfg = ov_genai.GenerationConfig(max_new_tokens=a.max_new_tokens)
    print(f"loaded in {time.time()-t_load:.0f}s; describing {len(todo)} figures", flush=True)

    t0 = time.time()
    with out_path.open("a", encoding="utf-8") as out:
        for i, f in enumerate(todo, 1):
            try:
                desc = caption(pipe, ov, cfg, load_image(figdir / f["file"]))
            except Exception as e:                      # one bad PNG must not kill the batch
                desc = ""
                print(f"  ! {f['id']}: {type(e).__name__}: {e}", flush=True)
            # Field names follow the extractor's index.json: figure caption = "caption", document
            # title = "docTitle", 1-based source page = "pdfPage" (what lets retrieval cite the page).
            out.write(json.dumps({"id": f["id"], "doc": f.get("doc", ""), "title": f.get("docTitle", ""),
                                  "page": f.get("pdfPage"), "caption": f.get("caption", ""),
                                  "confidence": f.get("confidence", ""), "description": desc}) + "\n")
            out.flush()
            if i % 10 == 0 or i == len(todo):
                print(f"{i}/{len(todo)} described | {(time.time()-t0)/i:.1f} s/fig", flush=True)
    print(f"done: {len(todo)} figures -> {out_path}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
