# Radio card matching and route packets

Radio questions check titles and example questions for an exact match, then
use CardRouter's candidate ranking and subject check. With multiple eligible
cards, the responder waits up to 180 seconds for an idle Ask session and
model acquisition. It uses the model's constrained choice to select a card.
Missing or unsuccessful choices produce one message listing up to three
whole titles, best first. Exact card titles take precedence over aliases and
can be selected even when they begin with a navigation verb such as “Find.”
Explicit `@` commands still go directly to navigation.

The first corpus run found four cases where card answers or source quotes
mentioned a different topic and made it pass the subject check. A sole
code-only candidate now needs support from its title and example questions.
The additional evidence check is specific to that radio branch. Ask and
multi-candidate CardRouter choice retain their existing subject check.

Card replies and notices use `BASTION: ` or the numbered `BASTION n/m: `
prefix. The Perl builder budgets the same bytes. Navigation uses whole
summary and leg lines, up to three route packets after the first NAV reply.
The last route packet accounts for omitted legs when a complete route
cannot fit. An oversized summary or position reply gets a fixed notice.
The manual share editor retains over-length text for review and editing.

## Changes

- `mesh/MeshAutoResponder.kt` handles exact and ranked matches, model
  choice, title menus, labels, and full-line navigation replies.
- `ai/KnowledgeCards.kt` adds the listed-question evidence option to
  `CardRouter.subjectMatches` for the measured cross-reference failures.
- `mesh/MeshBridge.kt` shares bounded packetization and removes truncation.
- `ui/MeshScreen.kt`, `ui/SetupGuide.kt`, and `ui/LibraryScreen.kt` describe
  the reply flow and the saved mesh retrieval preference.
- `tools/knowledge/build.pl` and its fake fixtures use the new prefix.
- `tools/model-eval/radio-questions.json` contains 91 cases across all 55
  source cards, including 30 questions that expect `none`.
- The JVM tests cover the responder, byte packing, and the full corpus.
  `RadioCardEvalTest` replaces `MeshAnswerEvalTest` for on-device evaluation.

## Verification

| Check | Result |
| --- | --- |
| Kotlin compile, debug APKs and instrumentation APK | Tested on Windows with Temurin 21.0.13. `:app:assembleDebug` and `:app:assembleDebugAndroidTest` passed. |
| JVM suite | Tested with `:app:testDebugUnitTest`. All 222 tests in 30 suites passed, with no errors or skips. |
| Code-only corpus, 55 cards and 91 questions | Tested. Right card 16, wrong card 0, menu 5, not covered 70. The unchanged corpus initially measured 20, 4, 5, and 62 before the stronger code-only subject check. |
| Builder fixtures | Tested. All 29 assertions passed. The new boundary cases accept a 200-byte single packet and a 187-byte multipart body, and reject a 188-byte multipart unit. |
| Builder on real cards | Tested. Exit 0, 55 files checked, zero validation problems, and zero approved cards written. |
| On-device model selection and radio delivery | Not run. The instrumented test APK was built for the emulator evaluation. |

From `android/`, with the local Android SDK and Temurin 21 selected:

```powershell
.\gradlew.bat --offline --no-daemon '-Pandroid.builder.sdkDownload=false' :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

The JVM corpus test prints `RadioEval code-only` in its test output. Use
`:app:testDebugUnitTest --rerun` after changing only an external corpus
or source card file to refresh the result.

From the worktree root, the builder verification used the existing Git for
Windows Perl and `pdftotext`. It read the sibling checkout's kit PDFs and
wrote the rebuilt JSON into the worktree's ignored build output.

```powershell
$radioPerl = 'C:/Program Files/Git/usr/bin/perl.exe'
$env:PATH = 'C:\Program Files\Git\ucrt64\bin;C:\Program Files\Git\usr\bin;' + $env:PATH
& $radioPerl tools/knowledge/radio-test-fixtures/run.pl
$env:KIT_DOCS = 'C:/Users/ccie5/StudioProjects/Bastion/tools/kit/docs'
$env:KNOWLEDGE_OUT = 'android/app/build/outputs/radio-2-verified-cards.json'
& $radioPerl tools/knowledge/build.pl
```

## Emulator handoff

Use the debug app and instrumentation APK on the emulator with an imported
model. Install with `:app:installDebug :app:installDebugAndroidTest`.
From the worktree root, stage the test-only data and run the class directly:

```powershell
$adb = 'C:/Users/ccie5/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$cards = Get-ChildItem -LiteralPath tools/knowledge/cards -Filter *.json |
    Sort-Object Name | ForEach-Object { Get-Content -LiteralPath $_.FullName -Encoding UTF8 -Raw }
$staged = Join-Path $PWD 'android/app/build/bastion-radio-cards.json'
[IO.File]::WriteAllText($staged, '{"cards":[' + ($cards -join ',') + ']}', [Text.UTF8Encoding]::new($false))
& $adb push $staged /data/local/tmp/bastion-radio-cards.json
& $adb push tools/model-eval/radio-questions.json /data/local/tmp/bastion-radio-questions.json
& $adb shell am instrument -w -e class com.bastion.terminal.ai.RadioCardEvalTest com.bastion.terminal.test/androidx.test.runner.AndroidJUnitRunner
& $adb logcat -d -s RadioEval:I
```

## Owner follow-ups

- `CLAUDE.md:144–152` should describe card matching, model choice, BASTION/NAV
  labels, and packet limits. The ten-minute retrieval follow-up is retired.
- `CLAUDE.md:159` should describe the first approved reference and the fixed
  unavailable notice. `CLAUDE.md:177–180` should limit the checked-answer
  text-generation rule to Ask.
- `CLAUDE.md:203–209` should reflect that `Scope.MESH` still checks the pack
  preference when requested, and the responder uses bundled cards.
  `PackScopeTest` still exercises the preference, so the switch was relabeled.
- `CLAUDE.md:216–218` should describe explicit navigation and card-title
  precedence, with whole route legs and the omitted-leg count.
