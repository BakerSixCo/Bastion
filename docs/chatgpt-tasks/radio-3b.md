# Task: fix the Android crash in radio-3 (same branch `chatgpt/radio-3`)

Claude reviewed `chatgpt/radio-3` (fbac35c) on 2026-11-25. The design is accepted in principle: the evidence
check, the menu score gate, the separate holdout and Ask's unchanged defaults all look right. JVM: 230 tests,
0 failures. It cannot merge yet because **it crashes on Android**.

## The bug (tested on emulator)

`KnowledgeCards.kt:136`:

```kotlin
private val CHOICE_OBJECT = Regex("\\{\\s*\"choice\"\\s*:\\s*(0|[1-9][0-9]*)\\s*}")
```

The JVM accepts a bare `}`; Android's ICU regex engine does not:
`PatternSyntaxException: Syntax error in regexp pattern near index 39`, thrown from `CardRouter.<clinit>`.
Because it is in `CardRouter`'s static initialiser, **every** use of `CardRouter` fails, Ask's card
routing included, not only the radio. `RadioCardEvalTest` failed both methods with
`ExceptionInInitializerError` at `MeshAutoResponder.kt:283`. With the brace escaped (`\\s*\\}`) the eval runs
and the code-only counts match your report exactly (tuning 11/0/48/0/1/31, holdout 1/0/26/1/2/29).
Claude did not commit that edit; the fix is yours.

The JVM tests could not catch this. "Compiled" and "JVM tests pass" were reported, but the code never
ran on Android: say "not run on device" plainly in a report when that is the case.

## Do

1. Escape the brace (or avoid the regex: parse the trimmed text as JSON with `org.json` and require exactly
   one key `choice` holding a non-negative integer).
2. Add a guard that would have caught it:
   - an instrumented smoke test (`androidTest/`) that forces initialisation of every object holding
     `Regex` fields on the Ask and radio paths (`CardRouter`, `RadioCardFallback`, `MeshAutoResponder`,
     `Grounding`, `CheckedAnswer`, `CardStore`, ...) and compiles each once; and
   - a JVM test that scans `src/main/java` for `Regex("...")` literals and fails on a `}` not preceded by
     `\\` and not closing a `{n}` / `{n,m}` quantifier, also `]` / `)` used as literals. Keep it simple and
     explain its limits in the report.
3. Run it on the emulator yourself if you can (`adb install -r` the two APKs, then `adb shell am instrument
   -w -e class com.bastion.terminal.ai.RadioCardEvalTest ...`; never `connectedAndroidTest`). The emulator
   is currently a few MB short of the space the model needs to start (BASTION correctly refuses and says
   so). Do not delete anything on the emulator to make room: report the code-only counts and say the model
   run is pending; Claude runs it once the owner frees space.
4. Add to `radio-3-report.md`: the crash, the fix, the guard, what ran where (JVM / emulator code-only /
   emulator with model / not run).

## Also note in the report (no code change required)

- The holdout reaches 27/42 against 59/61 on the tuning file, with only 1 direct right card. That gap
  says the abbreviation list (`tq`, `ki`, `ors`, `epi pen`) and thresholds fit the tuning wordings. Do
  **not** tune on the holdout. List the holdout misses by cause (slang, missing word, card has no matching
  `asks`) so the owner can decide whether cards need more listed questions, which is card text and not
  yours to write.
- Direct code-only answers fell 16 → 11. Name the 5 questions that moved to a menu and why the evidence
  check rejected them.

## Files you may change

As in `radio-3.md`, plus the new tests. Nothing else.

## Done when

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` (JDK 21) pass, the new
instrumented smoke test passes on the emulator, `RadioCardEvalTest` runs on the emulator without a crash
(code-only counts are enough if the model cannot start), and the updated report is committed on
`chatgpt/radio-3`.
