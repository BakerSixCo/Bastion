# Radio card coverage

The work started from `origin/claude/hello-e9r4rq` at `2bfec47`. The
independently authored holdout was committed as `ffcf045` before matcher
tuning. It contains 59 questions, including 17 expected `none` cases, and
has SHA-256 `e37669be12f27ebc96e57f1a5d536877ff6933f9502df0bdd406879a353e4494`.
The tuning run uses the original 91 questions, including 30 expected
`none` cases. The tuned matcher was committed as `dde38a4` before the
first holdout run.

## Matching

Exact title and example-question matches still let the asker pick a card
directly. The radio can also offer titles when the old subject check finds
no safe match. The fallback ranks titles and example questions with the
existing coordinated BM25 scorer and keeps at most five model options. If
those fields produce zero hits, answer-text matches can give the model
options. Answer-text matches alone provide no evidence for a reply or menu.

A title menu requires the best listed-question score to be at least 5.0.
Other options must reach 35% of that score. BASTION includes at most three
whole titles and counts their UTF-8 bytes, separators, and message prefix.
Evaluation measures the cards actually in that final packet.

The radio model response must be a single JSON object with an integer
choice. A fallback choice must be the only card whose title and example
questions contain evidence for every non-chat query term, including the
requested action. At least two subject terms are required, with at least
one appearing in at most a fifth of the available cards (rounded down,
with a minimum of one). The same evidence check protects a sole code-only
candidate. There is one model request at most per question. A failed
choice leaves the radio to the menu or the fixed not-covered notice.

Radio-only normalization joins `epi pen` and expands `tq`, `ki`, and
`ors` for matching. The evidence check allows a clipped prefix of up to
two characters or a one-character edit, including adjacent transposition,
for unknown terms of at least five characters. It keeps recognized terms
literal. Only the card's title, approved lines, and fixed message wording
reach the radio. Ask uses CardRouter's default scoring and choice parsing,
which the existing JVM tests exercise.

## Verification

| Code-only corpus | Cases | Right card | Wrong card | Menu with expected card | Menu without expected card | Menu on `none` | Not covered |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Tuning | 91 | 11 | 0 | 48 | 0 | 1 | 31 |
| Holdout | 59 | 1 | 0 | 26 | 1 | 2 | 29 |

The tuning run reached 59 of 61 expected cards through a direct reply or
a displayed menu, above the target of 45. It offered a menu on 1 of 30
`none` cases, below the limit of 5. The stronger evidence check reduced
direct replies from 16 in the previous revision to 11.
The independent holdout measured the committed matcher. It reached 27 of
42 expected cards and offered a menu on 2 of 17 `none` cases.

- Tested on the JVM, across all 55 source cards, including drafts. Of 230
  tests, 229 passed and the existing state-road-pack test skipped without
  a configured pack directory. All five existing `CardRouterTest` tests passed.
- Compiled on Windows with Temurin 21.0.13. The arm64-v8a and x86_64 debug
  APKs and the instrumentation APK built successfully.
- App launch, real-model selection, and live radio delivery were not run in
  this worktree. `RadioCardEvalTest` builds into the instrumentation APK and
  reports the same six counts for each corpus with the selected on-device model.

From `android/`, with the installed SDK and JDK 21:

```powershell
.\gradlew.bat --offline --no-daemon '-Pandroid.builder.sdkDownload=false' :app:testDebugUnitTest --tests com.bastion.terminal.mesh.RadioCardCodeEvalTest.tuningCorpusHasNoWrongCodeOnlyMatches --tests com.bastion.terminal.mesh.MeshAutoResponderTest --tests com.bastion.terminal.ai.CardRouterTest
.\gradlew.bat --offline --no-daemon '-Pandroid.builder.sdkDownload=false' :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

## On-device handoff

Install the debug app and test APK on the prepared emulator via
`:app:installDebug :app:installDebugAndroidTest`. Use the emulator's
imported model. From the worktree root:

```powershell
$adb = 'C:/Users/ccie5/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$cards = Get-ChildItem -LiteralPath tools/knowledge/cards -Filter *.json |
    Sort-Object Name | ForEach-Object { Get-Content -LiteralPath $_.FullName -Encoding UTF8 -Raw }
$staged = Join-Path $PWD 'android/app/build/bastion-radio-cards.json'
[IO.File]::WriteAllText($staged, '{"cards":[' + ($cards -join ',') + ']}', [Text.UTF8Encoding]::new($false))
& $adb push $staged /data/local/tmp/bastion-radio-cards.json
& $adb push tools/model-eval/radio-questions.json /data/local/tmp/bastion-radio-questions.json
& $adb push tools/model-eval/radio-questions-holdout.json /data/local/tmp/bastion-radio-questions-holdout.json
& $adb shell am instrument -w -e class com.bastion.terminal.ai.RadioCardEvalTest com.bastion.terminal.test/androidx.test.runner.AndroidJUnitRunner
& $adb logcat -d -s RadioEval:I
```

The whole class evaluates both corpora. Append
`#matchTuningCorpusWithTheLocalModel` or
`#matchHoldoutCorpusWithTheLocalModel` to the class name for one file.

## Files and owner follow-up

- `mesh/RadioCardFallback.kt` ranks the fallback and checks evidence.
- `mesh/MeshAutoResponder.kt` uses the fallback and returns only menu cards
  that fit the outgoing packet.
- `ai/KnowledgeCards.kt` exposes the existing score and constrained choice
  with radio-specific options.
- `MeshAutoResponderTest`, `RadioCardCodeEvalTest`, and `RadioCardEvalTest`
  cover the fallback and the separate corpus counts.
- `tools/model-eval/radio-questions-holdout.json` is the frozen evaluation set.
- `CLAUDE.md:152–158` can record the fallback shortlist, evidence check,
  score gate, and separate holdout results.
