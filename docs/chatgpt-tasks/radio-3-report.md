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
- Original radio-3 handoff: not run on device. The Android verification for
  the radio-3b fix is below.

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

## Radio-3b Android regex fix

Claude reproduced a startup crash in `CardRouter` on the emulator. Android's
ICU engine rejected the bare closing brace of `CHOICE_OBJECT` with
`PatternSyntaxException`. The static initializer wrapped that failure in
`ExceptionInInitializerError`, so both Ask and radio card routing were
unavailable. The fix escapes the closing brace. It also escapes the literal
closing bracket in `MeshBridge.fit`'s citation pattern.

- `RegexInitializationSmokeTest` forces initialization of the Ask and radio
  regex holders, including `CardRouter`, `RadioCardFallback`,
  `MeshAutoResponder`, `Grounding`, `CheckedAnswer`, and `CardStore`.
  It also exercises regexes created during routing, parsing, grounding,
  document processing, and navigation, with a fixed model double.
- `RegexLiteralPortabilityTest` scans direct Kotlin `Regex("...")`
  constructor literals under `src/main/java`. It flags bare closing braces
  beyond counted quantifiers, brackets outside character classes, and
  parentheses without an open group. The scanner handles escaped and quoted
  delimiters. It bypasses interpolated or composed patterns and leaves regex
  execution to the smoke test. Four exact pre-existing closing brackets,
  accepted by ICU, are allowed one occurrence each because those files are
  outside this task's edit scope. They are in `CheckedAnswer.kt:70`,
  `CheckedAnswer.kt:85`, `Grounding.kt:420`, and `kiwix/HtmlText.kt:41`.
- `RadioCardEvalTest` accepts `-e codeOnly true` to exercise both corpora
  on Android while leaving the model unloaded.

| Radio-3b check | Result |
| --- | --- |
| Compile and APK assembly | Tested on Windows with Temurin 21. The debug app and test APK tasks both passed. |
| JVM tests | Tested. 232 passed, one state-road-pack test skipped, and zero failed across 32 suites. All three regex-literal checks passed. Both corpus rows matched the radio-3 JVM results above. |
| Android regex smoke | Tested on `emulator-5554`, API 37, x86_64. All four tests passed, including both card choice parsers. |
| Android code-only tuning | Tested on the same emulator. 11 right, 0 wrong, 48 menus with the expected card, 0 without it, 1 menu on `none`, 31 not covered. |
| Android code-only holdout | Tested on the same emulator. 1 right, 0 wrong, 26 menus with the expected card, 1 without it, 2 menus on `none`, 29 not covered. |
| Android evaluation with the real model | Not run. Pending emulator storage clearance by the owner. |
| App UI and live radio delivery | Not run. |

The build used this command from `android/` with Temurin 21 and the
installed Android SDK:

```powershell
.\gradlew.bat --offline --no-daemon --continue '-Pandroid.builder.sdkDownload=false' :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

The app and test APKs were installed in place. All three staged fixture hashes
matched the worktree, so the runs reused the existing copies. Both evaluation
methods passed in 49.8 seconds total and logged zero model requests.

```powershell
$adb = 'C:/Users/ccie5/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$serial = 'emulator-5554'
$runner = 'com.bastion.terminal.test/androidx.test.runner.AndroidJUnitRunner'
& $adb -s $serial install -r --streaming android/app/build/outputs/apk/debug/app-x86_64-debug.apk
& $adb -s $serial install -r --streaming android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $adb -s $serial shell am instrument -w -e class com.bastion.terminal.ai.RegexInitializationSmokeTest $runner
& $adb -s $serial shell am instrument -w -e codeOnly true -e class com.bastion.terminal.ai.RadioCardEvalTest $runner
& $adb -s $serial logcat -d -s 'RadioEval:I' '*:S'
```

### Questions that moved to a menu

A JVM diagnostic called the production matcher and reconstructed the radio-2
code-only path from commit `2bfec47`, reproducing its 16 direct replies.
These five tuning inputs kept their expected title as the first menu option.
The newer evidence check requires every non-chat term to be supported in the
title or example questions.

| Tuning case | Question | Evidence missing from the expected card |
| ---: | --- | --- |
| 36 | thunderstorm closing in is truck safe from lightning | `clos` and `truck`. The listed wording uses `close` and `car`. |
| 38 | nuke just went off whats the very first thing we do | `thing`. The earlier subject check treats it as filler. |
| 41 | need to ford a fast creek on foot where do we cross | `creek`. The listed wording uses `river` or `stream`. |
| 46 | blizzard above trees how do we dig a snow cave | `tree`. The listed wording refers to `treeline`. |
| 54 | how rinse dirt out of this gash no saline | `dirt`. The listed wording uses `dirty`. |

### Holdout misses

The same diagnostic traced scores and menu packing for the 15 expected-card
misses. The radio-3 abbreviation list and gate were chosen on the tuning file.
The 27/42 holdout result against 59/61 on tuning is consistent with narrower
coverage when the wording changes. The held-out questions were used here for
diagnosis. The matcher and corpora were kept frozen.

Fourteen misses had a best listed-question score below 5.0. The first column
uses one-based IDs in the frozen holdout. Scores shown are the best option's
score, rounded to two decimal places.

| Case | Expected card | Cause | Best score |
| ---: | --- | --- | ---: |
| 1 | Altitude recognition | Slang. The listed questions say “short of breath at rest” where the input says “puffed sitting still.” | 4.09 |
| 2 | Altitude treatment | Misspelling plus a wording gap. `altitood` and “sleep here/go lower” have weak overlap with the descent and rest wording. | 2.56 |
| 5 | Severe limb bleeding | Misspelling and slang. `turniket` and `thru` get no lexical hit. `bleedin` can match in the evidence check after ranking. | 4.79 |
| 7 | Carbon monoxide | Slang and term cap. `ppl` and `nite` add no useful listed matches, and `co` is ninth in an eight-term scoring query. | 1.75 |
| 11 | Dangerous animals | Listed-question gap. There is little overlap for pack proximity and the surrounding context. | 3.50 |
| 13 | Dehydration recognition | Misspelling. `dehydratd` misses the prefix scorer, and the evidence does not distinguish dehydration from oral rehydration. | 1.05 |
| 14 | Diarrhea field care | Sparse listed evidence. `diarhea` is already listed, but the `camp` context adds no match. | 3.03 |
| 15 | Emergency food supply | Listed-question gap. “Bulging can of beans” has weak overlap with “swollen can.” | 0.73 |
| 17 | Fallout shelter | Listed-question gap. The no-basement room wording differs from “part of the building.” | 3.87 |
| 22 | Camp food storage | Listed-question gap. `raccoons`, `stash`, and “after dark” differ from the generic animal and storage wording. | 0.82 |
| 33 | Sprain and strain | Misspelling and missing context. `sprane` misses lexical ranking and `after` is missing from the listed questions. | 4.35 |
| 36 | Water bleach dose | Sparse listed evidence. `bleech` is listed. `dropper` appears in the answer, and `drinkin` misses lexical ranking. | 2.52 |
| 41 | Lightning safety | Misspelling and listed-question gap. `lightnin` misses lexical ranking, and the lakeside hiking context adds no listed match. | 3.46 |
| 42 | Shelter site selection | Listed-question gap. `gully` and `overnight` differ from “dry creek bed” and “for the night.” | 3.59 |

Case 35 reached the menu gate. The expected potassium iodide title ranked
second at 4.46 behind fallout shelter at 6.62. The two full titles and
packet framing totaled 219 UTF-8 bytes. The 200-byte limit kept the
first title, so the result counted as a menu without the expected card.
