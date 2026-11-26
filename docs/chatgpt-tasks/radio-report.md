# Approved radio replies

The card builder accepts an optional ordered list of one-based *answer* and
*warning* references. The first reference identifies the critical action. The
builder resolves each reference to its existing line and checks the complete
message with the card title, step numbers, and radio prefix included. A failed
check reports the UTF-8 byte count before packet splitting. The app asset
contains resolved lines for approved cards.

The responder matches a unique existing question alias after folding case and
whitespace and removing trailing question marks. It sends the selected lines
in order, with up to four 200-byte UTF-8 parts. Missing, invalid, or oversized
radio versions get the specified unavailable notice. Unmatched and ambiguous
questions get “Not covered by radio.” An exceptionally long title that cannot
fit in the notice gets the same notice without the title. Lookup runs entirely
against bundled cards. Model generation, library retrieval, and the
ten-minute follow-up state were removed from this path.

The complete-answer packer returns a result only if it can fit all of the
content. Navigation retains its existing route summary packing through an
explicit navigation-only call. The shared direct-reply sender checks the UTF-8
limit before handing a packet to Meshtastic.

## Files

- `tools/knowledge/build.pl` validates and resolves the references.
- `tools/knowledge/radio-test-fixtures/` contains the fake-document builder suite.
- `ai/KnowledgeCards.kt` parses optional radio arrays and accepts assets without them.
- `mesh/MeshAutoResponder.kt` performs card lookup, chooses replies, and checks the send boundary.
- `mesh/MeshBridge.kt` packs complete card replies and retains the route summary packer.
- `ui/SetupGuide.kt` and `ui/MeshScreen.kt` describe approved-card replies and setup.
- JVM tests in `mesh/MeshAutoResponderTest.kt` and `mesh/MeshBridgeTest.kt` cover the new reply handling and existing navigation packing.

## Verification

| Check | Result |
| --- | --- |
| Android compile and debug APK | Tested on Windows with Temurin 21.0.13. Kotlin compilation and `:app:assembleDebug` passed. Gradle produced arm64-v8a and x86_64 debug APKs. |
| JVM correctness | Tested with `:app:testDebugUnitTest`. All 210 tests passed in 29 suites, with zero failures, errors, or skips. |
| Builder fixture correctness | Tested with Git for Windows Perl. All 29 checks passed, including malformed references, real prefix costs, UTF-8 byte boundaries, a complete four-part reply, and refusals for oversized units and fifth parts. |
| Builder on the real card set | Tested with Git for Windows Perl and its installed `pdftotext`. Exit 0, 55 cards, and zero validation problems. The inputs have zero radio fields and produced zero approved cards. |
| App launch and Meshtastic delivery | Not tested. Emulator and physical radio were outside this verification run. |

Gradle command from `android/` with Temurin 21 and the installed Android SDK
set through `ANDROID_HOME` and `ANDROID_SDK_ROOT`.

```powershell
.\gradlew.bat --offline --no-daemon '-Pandroid.builder.sdkDownload=false' :app:testDebugUnitTest :app:assembleDebug
```

Builder commands from the worktree root. The real-card run reads the kit PDFs
from the sibling worktree and writes verification output to the Android build
directory. The app asset was left untouched.

```powershell
$radioPerl = 'C:/Program Files/Git/usr/bin/perl.exe'
$env:PATH = 'C:\Program Files\Git\ucrt64\bin;C:\Program Files\Git\usr\bin;' + $env:PATH
& $radioPerl tools/knowledge/radio-test-fixtures/run.pl
$env:KIT_DOCS = 'C:/Users/ccie5/StudioProjects/Bastion/tools/kit/docs'
$env:KNOWLEDGE_OUT = 'android/app/build/outputs/radio-verified-cards.json'
& $radioPerl tools/knowledge/build.pl
```

## Owner follow-ups

- `CLAUDE.md:144–152` should describe the approved card alias lookup and
  four-part cap, and remove the ten-minute retrieval follow-up.
- `CLAUDE.md:159` should describe the first approved reference as the
  critical action and the fixed unavailable notice for cards without a
  sendable radio version.
- `CLAUDE.md:177–180` should describe `CheckedAnswer.twoPaths` as the Ask
  path and document approved text as the radio reply path.
- `CLAUDE.md:203–209` should reflect that the responder no longer searches the
  library. `PackScopeTest` still checks library retrieval scoping.
- `CLAUDE.md:216–218` should say navigation matching precedes card lookup.
- `ui/LibraryScreen.kt:250` still shows the legacy pack setting “Answer over
  the mesh.” Updating that label requires a file outside the task scope.
- `androidTest/…/ai/MeshAnswerEvalTest.kt:71` calls the retired, model-backed
  responder API. Update or retire this instrumentation test before building
  that test source set. It is outside the task's allowed files.
