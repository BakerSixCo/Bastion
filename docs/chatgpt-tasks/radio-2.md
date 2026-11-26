# Task: radio card matching, label and no cut-off routes (branch `chatgpt/radio`, continue on it)

Follow-up to `docs/chatgpt-tasks/radio.md`; Claude reviewed your commits f9cecf0 / 2dc00b0 (tests and
builder pass). Same rules as before: read `AGENTS.md`, `CLAUDE.md` ("Mesh (M3) notes", "Checked answers").
Over the radio BASTION sends **only approved card text** (or the fixed notices). The AI may help **choose**
which approved card, never write or change text. You write code and tests; you do not write card text.

## 1. Matching (owner decision 2026-11-25)

Exact-wording matching misses almost every real radio question. Replace `answerFor`'s lookup with these
steps, in order, reusing `ai/CardRouter` (`candidates`, `subjectMatches`, `route`) rather than new logic:

1. **Exact:** the question equals a card's `title` or one of its `asks` (your `questionKey` folding) →
   that card. (Titles count so the menu in step 4 works.)
2. **Code only:** take `CardRouter.candidates(question, cards)`; keep those passing
   `CardRouter.subjectMatches`. Exactly one → that card, no model.
3. **AI picks from the shortlist:** two or more pass → `CardRouter.route` with the local model, only over
   those cards. Its pick must still pass `subjectMatches` (route already does this). Model missing,
   busy past the existing wait, error, or choice 0 → go to step 4. Wait for an Ask generation to finish
   before using the model, as the old code did (`AskSession.generating`).
4. **Ask the asker:** two or more candidates but no safe pick → reply
   `Not sure which. Send one: ?<title 1> / ?<title 2> / ?<title 3>` (best first, up to 3, drop titles
   until it fits one 200-byte message).
5. Nothing passes → `Not covered by radio.` (unchanged).

Then the found card goes out as its radio version, or the "Not available by radio: <title>…" notice,
exactly as now. Every card reply starts with the card's title (it already does) so a reader can see a
wrong card at once.

## 2. Label

Card text is approved, not AI-written. Replace the card prefixes `AI: ` / `AI n/m: ` with
`BASTION: ` / `BASTION n/m: ` (notices too). Keep `NAV` for navigation. Update `build.pl`'s byte budget
to the new prefix (it must match the phone exactly; add a fixture at the new boundary) and the inbox
label. Unnumbered multi-part notices: number them like card replies.

## 3. No cut-off route messages

`splitForNavigation` can still end a message with "…". Make navigation follow the same rule as cards:
send only whole lines (summary first, then whole legs) that fit the 3 parts; if legs are left over, the
last part ends with a fixed `+N more legs, too long for radio.` that is itself counted in the budget.
Never a partial line. Then remove the "…" path so nothing in `MeshBridge` can truncate silently.

## 4. Measure it (required before merge)

- `tools/model-eval/radio-questions.json`: at least 80 radio-style wordings (short, misspelt, slang,
  no question mark) → expected card id or `none`, over the real cards in `tools/knowledge/cards/`.
  Include traps that share words with a card but ask something else (the `CardRouter.SYSTEM` examples:
  welding with a car battery, tick bite making you sick) and at least 20 `none` questions. These are
  test inputs, not card text.
- JVM test over the real card files (all 55, approved or not): run steps 1, 2, 4, 5 (no model).
  Print counts: right card, wrong card, menu, not covered. **Wrong card in the code-only steps must be 0**
  (the test fails otherwise). Right-card count is reported, not asserted.
- An instrumented test `RadioCardEvalTest` (steps 1–5 with the real model) that logs the same counts
  under tag `RadioEval`. Claude runs it on the emulator; you may run it with
  `adb shell am instrument -w -e class …` — never `connectedAndroidTest`.

## 5. The leftovers from your report

- `androidTest/…/ai/MeshAnswerEvalTest.kt`: retire it (delete) — `RadioCardEvalTest` replaces it.
- `ui/LibraryScreen.kt`: the pack switch "Answer over the mesh" → wording that says the pack is not used
  for radio replies any more, or remove the switch only if nothing else reads `mesh_ok` (check
  `PackScopeTest` / `Scope.MESH` first; if unsure, change the wording only and say so in the report).
- CLAUDE.md stays Claude's: list any further lines in the report.

## Files you may change

Everything allowed in `radio.md`, plus `ai/KnowledgeCards.kt` (`CardRouter` only if a fix is needed —
explain it), `ui/LibraryScreen.kt` (that switch only), `tools/model-eval/radio-questions.json`, the new
tests under `android/app/src/test/` and `android/app/src/androidTest/`, deleting `MeshAnswerEvalTest.kt`,
and your report.

## Done when

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` (JDK 21) pass, the
builder fixtures and the real-card build pass, the code-only wrong-card count is 0, and
`docs/chatgpt-tasks/radio-2-report.md` (counts, what was tested how) is committed on `chatgpt/radio`.
