# Task: radio card coverage (new branch `chatgpt/radio-3`)

Your radio work (`chatgpt/radio`) is merged into `claude/hello-e9r4rq`. Start a new branch from it:

```powershell
git fetch origin
git switch -c chatgpt/radio-3 origin/claude/hello-e9r4rq
```

Same rules: `AGENTS.md`, `CLAUDE.md` (updated "Mesh (M3) notes": over the radio only approved card text,
the model may only pick from a code shortlist). You write code and tests, never card text.

## Where it stands (measured by Claude, 2026-11-25)

`radio-questions.json`, 91 questions (61 expect a card, 30 `none`):
code only 16 right / 0 wrong / 5 menu / 70 not covered; on the emulator with Gemma E2B (CPU)
21 right / 0 wrong / 0 menu / 70 not covered (5 model requests, all right). About 40 real card
questions get "Not covered by radio." because nothing passes the strict subject check
(slang, misspelling, words the card does not use: "wheezin", "tq", "epi pen"), and the model is only
asked when two or more cards pass it.

## Goal

Rescue those questions **without ever sending a wrong card**.

1. **When keyword candidates exist but none passes the subject check**, do not give up:
   - with the model: let it pick from the top candidates (at most 5, choice 0 allowed), then accept its
     pick only if a code check passes. Design that check (for example: a distinctive question term,
     matched on a word prefix so "wheezin" meets "wheezing", in the card's title or asked wordings;
     the pick among the top BM25 candidates). Explain it in the report.
   - otherwise, or if the pick fails the check: the title menu (best first, up to 3), but only when the
     best candidate is strong enough (a score threshold you choose and measure), so unrelated
     questions still get "Not covered by radio."
2. Light spelling tolerance in the radio path is allowed (prefix or edit-distance matching of terms)
   if measured. **Do not change Ask's behaviour:** anything in `CardRouter`, `CheckedAnswer` or
   `Grounding` that Ask uses keeps its current default; add parameters, prove Ask is unchanged with the
   existing tests.
3. No new AI-written text anywhere on the radio path. The model only returns a choice number.

## Measure it (required)

- Before tuning anything, write `tools/model-eval/radio-questions-holdout.json`: at least 40 new radio
  wordings (at least 12 `none`, include word-sharing traps). Tune only on `radio-questions.json`;
  report both files separately so thresholds are not fitted to the test.
- Count per file: right card, wrong card, **menu containing the expected card**, menu without it,
  menu on a `none` question, not covered. Code-only in the JVM test; with the model in
  `RadioCardEvalTest` (Claude runs it on the emulator; you may via `adb shell am instrument`, never
  `connectedAndroidTest`).
- JVM test fails if wrong card > 0 on either file. Targets (report, don't fake): right + menu-with-card
  ≥ 45 of 61 on the tuning file; `none` questions getting a menu ≤ 5 of 30.

## Files you may change

`mesh/*`, `ai/KnowledgeCards.kt` (`CardRouter` only, Ask defaults unchanged), their tests under
`android/app/src/test/` and `RadioCardEvalTest` under `androidTest/`, the two corpus files, and your
report. Nothing else; list anything else that should change.

## Done when

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` (JDK 21) pass, wrong
card is 0 on both files, and `docs/chatgpt-tasks/radio-3-report.md` (all counts, the check design, what was
tested how) is committed on `chatgpt/radio-3`.
