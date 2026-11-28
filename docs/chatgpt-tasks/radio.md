# Task: radio answers are approved text only (branch `chatgpt/radio`)

Read `AGENTS.md` and `CLAUDE.md` first — especially "Mesh (M3) notes", the owner rule **"Radio answers are
for people without BASTION"**, and `docs/field-guide-design.md` §8 (risk-based fallback: over the radio,
approved text only; otherwise "not covered"). Your own review (`docs/reviews/chatgpt-review-2026-11-25.md`,
Task 5) found that verified cards are cut off over the radio. This task fixes that class of problem.

You write **code and tests only**. You do not write or choose any card's radio text — that is
life-safety content for the card pipeline. Test fixtures use obviously fake cards.

## Changes

1. **Cards get an optional radio version, made only of the card's own approved lines.**
   Card JSON field `radio`: a list of references to existing `answer`/`warnings` lines (e.g.
   `["a1","a3","w1"]`), in sending order. No new prose. The first reference is the one critical action.
   - `tools/knowledge/build.pl`: validate references, and that the radio text, packed by the same rules as
     the phone (`MeshBridge.split`, 200-byte messages, at most 4 parts, with the radio title/prefix),
     **fits without truncation**; otherwise fail the build with the byte count. Write the resolved radio
     text into `cards.json` for approved cards.
   - `ai/KnowledgeCards.kt`: parse it.
2. **`mesh/MeshAutoResponder.kt`:**
   - A matched card is sent only as its radio version. If it has none: send exactly
     "Not available by radio: <title>. Too long to send safely. Get to someone with BASTION or medical help."
   - **No AI-written answers over the radio at all** (owner decision): remove the `CheckedAnswer.twoPaths`
     path from the auto-responder; questions with no card get "Not covered by radio." NAV (`@`), places,
     and pins are unchanged.
   - Never send a truncated message: if any packed answer would exceed the parts limit, send the
     "Not available by radio" text instead. Add a single guard in `MeshBridge`/the responder so no future
     path can truncate silently.
3. Update the mesh help text / setup guide wording that promises AI answers over the radio, and the
   "Mesh (M3) notes" in `CLAUDE.md` is **not** yours to edit — list the lines that need changing in your report.

## Tests

- `build.pl`: fixture cards (fake) — valid radio refs pass; bad ref fails; too-long radio fails with bytes.
- JVM unit tests: responder sends the radio version, the "not available" text when none, "Not covered by
  radio." when no card, never calls the AI path, and no output part ever exceeds 200 bytes or 4 parts.
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` (JDK 21) must pass; `perl tools/knowledge/build.pl`
  must still pass on the real cards (none have `radio` yet — that is expected).
- Optional: emulator check with the simulated mesh nodes (`tools/meshsim`, see CLAUDE.md) — label it
  "simulated radio". Never run `connectedAndroidTest`.

## Files you may change

`tools/knowledge/build.pl`, a new fixture folder under `tools/knowledge/`, `android/app/src/main/java/com/bastion/terminal/mesh/*`,
`ai/KnowledgeCards.kt`, `ui/SetupGuide.kt`, `ui/MeshScreen.kt` (wording only), their tests under
`android/app/src/test/`, and your report. Nothing else.

## Done when

Tests pass, behaviour above holds, and `docs/chatgpt-tasks/radio-report.md` is committed on `chatgpt/radio`.
