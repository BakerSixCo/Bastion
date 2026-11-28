# Task chunk-tables-1: keep table rows with their headers when chunking (branch `chatgpt/chunk-tables-1`)

**Runnable now** (needs the emulator for the evals — Android Studio / `adb`). Touches the retrieval path, so it is **guard-railed by evals that must not regress.** Start from Claude's branch:

```powershell
git fetch origin
git switch -c chatgpt/chunk-tables-1 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md` ("Running instrumented tests": `adb install -r` + `am instrument`, **never** `connectedAndroidTest` — it uninstalls the app and its model). No network. **Do not touch** `ai/CheckedAnswer.kt` or `ai/QueryPlan.kt` (the "never show a wrong answer" gate stays with Claude) or any card/flow text.

## Why

`docs/status.md` ("Checked answers", 2026-11-24) names the main lever to win back answer coverage **without** wrong answers: section-aware chunking of the canning / temperature tables. Several real misses were a table row split away from its header — "the cited passage did not say 'quart'", "the cited passage lacked 'purify'/'gallon'" — so the checked-answer code (rightly) withheld a correct answer. When a passage is one table row, it must carry the table's caption/heading and its column headers, so "Tomatoes … 2 tablespoons lemon juice per quart" keeps the words `quart` and `tomatoes`.

## Do

1. In `data/Chunker` (the import-time passage splitter), detect Markdown/plain-text tables and chunk each row (or a small group of rows) **together with** the table's nearest heading/caption and its header row. A one-row passage should read as a self-contained fact with its column labels, not a bare row. Keep ordinary prose chunking unchanged (~700 char target, 1000 max, heading-tagged — see `docs/status.md` "Retrieval v2").
2. **Guard-rail — these must stay green on the emulator, unchanged, not loosened:** `RetrievalEvalTest` 13/13 and `ZimRetrievalEvalTest` 14/14. Run them the CLAUDE.md way (`:app:installDebug :app:installDebugAndroidTest`, then `am instrument -e class …`). The ZIM eval needs WikiMed in BASTION's ZIM folder; if it is not present, say "skipped (no WikiMed)", do not count it as a pass.
3. **Add table-lookup eval cases** (3–4) to the existing eval set in the `RetrievalEvalTest` format: e.g. lemon juice per quart of tomatoes, a food-safety holding temperature read from a table, a canning process time. Each: question, expected source file, a phrase that must be in a retrieved passage. Show they **miss before** your change and **hit after** (paste both runs).
4. Re-chunking changes existing libraries: the DB migration re-chunks preserved originals on upgrade (`docs/status.md` "Retrieval v2"). Keep that working. Only bump the DB version if you must, and migrate; say so loudly in the report.
5. Do not tune anything to the new cases beyond the general "row keeps its header" rule; report the avg passage length change.

## Files you may change

`android/app/src/main/java/com/bastion/terminal/data/Chunker.kt` (and private helpers it owns), the retrieval eval question set + `RetrievalEvalTest` only to **add** cases (never to weaken a check), your report. Not `CheckedAnswer`/`QueryPlan`, not `cards/*`, not the ZIM eval's existing cases.

## Done when

`RetrievalEvalTest` and `ZimRetrievalEvalTest` are still 13/13 and 14/14 on the emulator (ZIM skipped only if WikiMed absent), the new table cases go from miss → hit, the report shows before/after retrieval for them, states the migration is intact and the avg passage-length change, and `docs/chatgpt-tasks/chunk-tables-1-report.md` is committed. Claude reviews this one closely before any merge.
