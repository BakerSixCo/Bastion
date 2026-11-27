# Release smoke automation

Section A has no unstarted runnable entry at fetched tip `d7c7fc0`. Kit-3 is waiting for Claude’s kit transfer. A filename comparison against the live kit found 0 of the 19 accepted kit-4 files and 0 of the 27 accepted kit-5 files.

## Changes

`tools/release-smoke.ps1` selects a running target, uses a local JDK and Gradle cache to assemble release, selects the APK from Gradle’s metadata by ABI, and installs it with `adb install -r`. The checks use the app’s accessibility tree and fresh logcat markers. The script reports explicit precondition skips, saves PNG evidence, prints one summary, and returns a nonzero exit code on failure.

The smoke uses the checked-in `android/app/src/androidTest/assets/qa/field-pdf.pdf` under the repository’s GPL-3.0 license. The source is 624 bytes with SHA-256 `3d96b550d591d89efaa7a7aa914ba81069f39385565c2954d301771c3d3d5435`. The script replaces an 11-character test marker with a same-length, per-run marker in a scratch copy so the app can distinguish the imported document on repeated runs. Device scratch files are removed after the run. Any successfully imported QA document remains in the library.

## Verification

- **PowerShell syntax and offline cache guard, tested on PC.** Parsed `tools/release-smoke.ps1` using `System.Management.Automation.Language.Parser.ParseFile` with zero errors. Invoked the guard both with the installed wrapper cache and with a missing cache. The installed cache passed preflight, and the missing cache stopped before any Gradle invocation.
- **Skip and prerequisite branches, tested on PC with mocked UI state.** Both absent-asset cases reported `SKIP` with their required reasons. A present model with an unverified fixture was rejected.
- **Default release build, blocked offline.** Temurin 21.0.13 and Gradle 8.14.3 compiled release Kotlin. Android Gradle Plugin’s vital lint analysis required uncached `com.android.tools.lint:lint-gradle:31.7.3`. A retry reached the Compose mapping producer, which required uncached `org.jetbrains.kotlin:compose-group-mapping:2.4.21`. The Compose mapping merge requires that producer’s file. The explicit `-SkipUncachedAuxiliaryTasks` switch excludes vital lint and those two mapping tasks for offline smoke validation.
- **R8 release build and signature, tested on PC.** R8 generated release dex and Gradle packaged both splits with the offline switch. APK Signature Scheme v2 verification passed with `CN=Android Debug`. The tested x86_64 APK is 37,738,735 bytes with SHA-256 `69f97fb1283519208fd6164b3e9066855c252b7c2b5ae78af1ab34ca009d4e69`.
- **Install and launch, tested on emulator.** `emulator-5554` (`Medium_Phone_API_37.0`, `sdk_gphone64_x86_64`) accepted `adb install -r` on the x86_64 APK and MainActivity launched with `Status: ok`. The Ask screen detected a readable ZIM collection and an imported model.

### Emulator smoke

```powershell
& .\tools\release-smoke.ps1 -Serial emulator-5554 -SkipUncachedAuxiliaryTasks
```

- **ZIM search, tested and passed on emulator-5554.** A fresh `BastionZim` line showed 20 results from `wikipedia_en_medicine_nopic_2026-10.zim`. The Ask screen showed the Sources list. [ZIM screenshot](../../android/build/release-smoke/9bb2ea008d/zim-search.png)
- **PDF import and extraction, tested and passed on emulator-5554.** Imported `release-smoke-9bb2ea008d.pdf` through Android’s document picker, searched Library for its unique marker, and opened the result. Reader showed `PDF text layer extracted`, the run marker, and the expected body phrase. [PDF screenshot](../../android/build/release-smoke/9bb2ea008d/pdf-extracted.png)
- **Model preflight and retrieval, tested on emulator-5554.** The app reported a model installed. Ask retrieved the current fixture as its sole source and started on-device generation. [Preflight screenshot](../../android/build/release-smoke/9bb2ea008d/preflight.png)
- **AI answer with Auto compute, tested and failed on emulator-5554.** The app initialized a LiteRT WebGPU environment and delegated the decode subgraph to WebGPU. It produced no accepted answer within the script’s 600-second timeout. The script exited 1 with `RELEASE SMOKE FAIL [emulator emulator-5554 (sdk_gphone64_x86_64)] ZIM=PASS, PDF=PASS, AI=FAIL`. The run established a timeout on this emulator and backend. [Run log](../../android/build/release-smoke-validation-focus.txt) [AI timeout screenshot](../../android/build/release-smoke/9bb2ea008d/ai-failed.png)
- **AI answer with CPU, tested and failed on emulator-5554.** Repeated the full build, install, ZIM, PDF and Ask sequence after temporarily selecting CPU in the app. Ask retrieved `release-smoke-96cf30042c` as its sole source. Android's storage monitor logged failed attempts to free 1,041,114,316 bytes on `/data`, with about 555 MB free during model initialization. LiteRT then logged the cache-directory failure below. The timing suggests storage pressure contributed. The CPU run exited 1 with `ZIM=PASS, PDF=PASS, AI=FAIL`. The emulator’s original Auto setting was restored. [Run log](../../android/build/release-smoke-validation-cpu.txt) [Filtered logcat](../../android/build/release-smoke/96cf30042c/ai-logcat.txt) [ZIM screenshot](../../android/build/release-smoke/96cf30042c/zim-search.png) [Reader screenshot](../../android/build/release-smoke/96cf30042c/pdf-extracted.png) [AI check screenshot](../../android/build/release-smoke/96cf30042c/ai-failed.png)

```text
W/BastionAI(28398): backend CPU failed: Failed to create engine: INVALID_ARGUMENT: Cache directory does not exist or is not writable: /data/user/0/com.bastion.terminal/cache/litertlm
```

The final script reports a fresh CPU load error immediately. That branch was tested on PC by replaying the captured failure text. An accepted on-device generated answer and the root cause of the cache-directory failure are still unverified. Physical arm64 execution and live missing-asset cases were not tested.
