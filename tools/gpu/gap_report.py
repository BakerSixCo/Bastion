"""Aggregate the GPU hostile review (tools/gpu/out/reviews.jsonl) into a human gap list.

Third stage of the GPU pipeline (gpu_draft.py -> gpu_screen.py -> gap_report.py). Reads the severity-rated
reviews and writes tools/gpu/out/gap-report.md: DANGEROUS first (fix/confirm first), then WEAK, with the
reviewer's fault and a draft snippet for each, and a count summary. OK drafts are counted, not listed.

A REVIEW-PRIORITISATION AID ONLY. The 14B reviewer also mis-flags; a human confirms every fault is real
before acting, and nothing here ships without the normal draft -> quote-check -> review -> owner-approval
path. Stdlib only.

  python -I tools/gpu/gap_report.py [--reviews FILE] [--out FILE]
"""
import argparse
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUTDIR = ROOT / "tools" / "gpu" / "out"
REVIEWS = OUTDIR / "reviews.jsonl"
REPORT = OUTDIR / "gap-report.md"

ORDER = {"DANGEROUS": 0, "WEAK": 1, "OK": 2}


def load(path):
    rows = []
    for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def snippet(text, n=200):
    t = " ".join((text or "").split())
    return t[:n] + ("…" if len(t) > n else "")


def section(out, title, rows):
    out.append(f"## {title} ({len(rows)})\n")
    if not rows:
        out.append("_None._\n")
        return
    for r in rows:
        out.append(f"### {r['id']}")
        out.append(f"- **Q:** {snippet(r.get('question', ''), 160)}")
        if r.get("fault"):
            out.append(f"- **Reviewer fault:** {snippet(r['fault'], 200)}")
        out.append(f"- **Draft:** {snippet(r.get('draft', ''), 300)}")
        out.append("")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--reviews", default=str(REVIEWS))
    ap.add_argument("--out", default=str(REPORT))
    a = ap.parse_args()

    src = pathlib.Path(a.reviews)
    if not src.exists():
        print(f"no reviews file: {src}", flush=True)
        return 1
    rows = load(src)
    if not rows:
        print(f"reviews file is empty: {src}", flush=True)
        return 1

    rows.sort(key=lambda r: (ORDER.get(r.get("severity", "WEAK"), 1), r.get("id", "")))
    by = {s: [r for r in rows if r.get("severity") == s] for s in ("DANGEROUS", "WEAK", "OK")}
    n = len(rows)

    out = []
    out.append("# GPU hostile-review gap list\n")
    out.append("Severity-rated by the 14B reviewer (Qwen2.5-14B-Instruct) over the E4B drafts. "
               "A prioritisation aid, not a verdict: confirm each fault before acting, and nothing here "
               "ships without the normal quote-check + review + owner-approval path.\n")
    out.append(f"**{n} drafts reviewed** — "
               f"{len(by['DANGEROUS'])} DANGEROUS, {len(by['WEAK'])} WEAK, {len(by['OK'])} OK.\n")
    section(out, "DANGEROUS — confirm and fix first", by["DANGEROUS"])
    section(out, "WEAK — incomplete / vague / missing a step", by["WEAK"])
    out.append(f"## OK ({len(by['OK'])})\n")
    out.append("_Reviewer found no fault; still subject to the normal review path._\n")

    pathlib.Path(a.out).write_text("\n".join(out), encoding="utf-8")
    print(f"wrote {a.out}: {n} reviewed "
          f"({len(by['DANGEROUS'])} DANGEROUS, {len(by['WEAK'])} WEAK, {len(by['OK'])} OK)", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
