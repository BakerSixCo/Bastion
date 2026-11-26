# Task kit-3: load the new kit on the emulator and measure search on the new topics (branch `chatgpt/kit-3`)

**Queue:** after `kit-5` (and after Claude has moved the kit-4 and kit-5 files into the kit). **Previous step:** kit-1 (manifest, `kit.py verify`), kit-2 (exclude list), kit-4 and kit-5 (new documents).
**Next step:** Claude drafts cards from what this run shows is findable; later additions re-run this
measurement with new questions.

```powershell
git fetch origin
git switch -c chatgpt/kit-3 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md` ("Running instrumented tests": `adb install -r` + `am instrument`, never
`connectedAndroidTest`). No network use.

## Why

About 40 documents were added to `tools/kit/docs/` on 2026-11-26 (radio frequencies and operation, electronics,
land navigation, ATAK, food production and safety, NCCIH herbs; ~220 MB) plus `docs/reference/` (Meshtastic
guide, Reference tier). None has been imported or searched on a device yet.

## Do

1. Run `kit.py verify` on Claude's `tools/kit/docs` first; stop on any mismatch.
2. Copy the new documents (the 2026-11-26 rows in `docs/status.md`, plus the trimmed PDFs Claude moved in from
   kit-1) to the emulator: `Android/media/com.bastion.terminal/docs/` and `.../docs/reference/` for the
   Meshtastic guide. **Storage:** the emulator has about 1.6 GB free and the model needs about 860 MB free to
   start; check `df` before and after and stop if less than 1.2 GB would remain. Delete nothing on the emulator.
3. Home → Rescan; report per file: imported / rejected (reason) / skipped, chunk count, and its tier as BASTION
   records it (`SourceTier.forProvenance`): Meshtastic must be `REFERENCE`, everything else `OWN`.
4. Before measuring, write `tools/model-eval/kit-questions.json` (at least 40 questions, at least 4 per topic:
   radio channels and rules, MAYDAY procedure, weather-radio programming, electronics and soldering, land
   navigation, ATAK, raising poultry and rabbits, butchering, food safety temperatures, food after a power
   outage, herbs and Chinese medicine; plus 8 questions the kit cannot answer). Each: question, expected
   source file, a short phrase that must appear in the retrieved passage. Do not tune anything to it.
5. A new instrumented test `KitRetrievalEvalTest` (retrieval only, no model): top-5 passages per question; HIT
   if the expected file and phrase are in them. Report every MISS with the top 3 files returned. For the 8
   unanswerable questions report what came back (these are the risk cases for wrong answers).
6. Do not change retrieval code in this task. If misses show a retrieval problem, describe it with evidence;
   Claude decides the fix (and `RetrievalEvalTest` 13/13, `ZimRetrievalEvalTest` 14/14 must stay).

## Files you may change

`tools/model-eval/kit-questions.json`, the new test under `android/app/src/androidTest/`, your report.

## Done when

All new documents are on the emulator and imported (or each rejection explained), the eval ran on the emulator
with counts per topic, existing evals still pass, and `docs/chatgpt-tasks/kit-3-report.md` is committed.
