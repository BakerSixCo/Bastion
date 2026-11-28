# Task kit-6: add the owner-approved operational manuals and close the manifest gaps (branch `chatgpt/kit-6`)

**Runnable now** (PC/Python, after kit-1 which is merged). Start from Claude's branch:

```powershell
git fetch origin
git switch -c chatgpt/kit-6 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md`. Read the kit docs in Claude's `tools/kit/docs`, write only `tools/kit/sources.json`
and your report; trimmed PDFs go to the gitignored `tools/kit/out/`.

## Why

`kit.py verify` on the current kit reports **17 unknown** (unmanifested) documents. **The owner reviewed these on
2026-11-26 and decided to INCLUDE the five operational references that kit-1 withheld** (`docs/library-plan.md`,
"Owner decisions, 2026-11-26"): they are public-release US-government manuals already in the kit. Close every
manifest gap so the kit is fully provenance-tracked (S-tier: nothing in the kit whose origin can't be named).

## Do

1. **Add `sources.json` entries for all 17 unknown documents.** Seed each from `docs/library-plan.md` and the hash
   tables in `docs/status.md` (publisher, edition, URL or acquisition method, licence/distribution, tier). List in
   the report any you genuinely cannot seed from those files rather than guessing — Claude fills those.
2. **The five operational manuals are owner-approved — include them** (origin `owner-download`, tier `own`):
   `Army-ATP-6-02.53-Tactical-Radios-and-Retransmission-2025.pdf`, `Army-ATP-3-50.21-Survival-2018.pdf`,
   `Army-TC-21-3-Cold-Weather-Operations-Survival-2025.pdf`, `Army-TC-3-21.76-Ranger-Handbook-2025.pdf`,
   `USAMRIID-Medical-Management-Biological-Casualties-10th-2025.pdf`. Confirm each file's own
   **public-release / Distribution A / "approved for public release"** marking and quote it in the report. **Do not
   withhold on content grounds** — the owner overruled that. Only if a file actually shows FOUO, CUI, or
   "Distribution authorized to …" on the document itself, skip it and report the exact marking.
3. **Produce the ATP 6-02.53 trim** (the one kit-1 withheld): keep **appendices A, E, G, H plus the cover/title
   page**. Find the printed→PDF page mapping by text (as kit-1 did for NIFOG/AUXFOG), store it in the manifest
   `trim` field as PDF ranges with the printed ranges beside them, output the trimmed PDF + `.provenance.txt` to
   `tools/kit/out/`, and show the first and last line of every kept range in the report. Same rigor as kit-1's
   trims (byte-reproducible; record the output SHA-256).
4. **Re-run `kit.py verify`** on Claude's `tools/kit/docs`: it must report **0 unknown and 0 mismatched** (or list
   exactly which remain and why).

## Files you may change

`tools/kit/sources.json`, your report (`docs/chatgpt-tasks/kit-6-report.md`). Trim output to `tools/kit/out/`
(gitignored). Nothing else — this task does not change `kit.py` (use it as-is from kit-1).

## Done when

`kit.py verify` shows 0 unknown / 0 mismatched, the ATP trim + provenance are in `tools/kit/out/` with its hash
and page maps, and `docs/chatgpt-tasks/kit-6-report.md` (per document: provenance source, distribution quote for
the five manuals, verify output) is committed.
