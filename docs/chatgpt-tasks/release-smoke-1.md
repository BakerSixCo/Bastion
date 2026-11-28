# Task release-smoke-1: automate the release-build smoke test (branch `chatgpt/release-smoke-1`)

**Runnable now** (needs Android Studio + the emulator). Independent. Start from Claude's branch:

```powershell
git fetch origin
git switch -c chatgpt/release-smoke-1 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md` ("Build & test", "Running instrumented tests"). No network.

## Why

`CLAUDE.md` requires a release-build smoke test after any R8 keep-rule change, because R8 can break the JNI-bound libraries (LiteRT-LM, libkiwix, PdfBox): "smoke-test a release build (ZIM search, PDF import, an AI answer)". Today that is a manual checklist done by hand. Automate it so it runs the same way every time and can gate future keep-rule changes. The release build is signed with the local **debug key** (R&D sideload) per `CLAUDE.md`; this does not change that.

## Do

Build `tools/release-smoke.ps1` that, against one running emulator/device:
1. Builds the arm64 **release** APK (or x86_64 for the emulator — pick by the device's ABI and say which), via `gradlew` with `JAVA_HOME` set to a JDK 17–24 (Temurin 21; `CLAUDE.md`), and installs it (`adb install -r`). Do not uninstall afterwards (keep the imported model/ZIM).
2. Runs the three R8-risk checks and reports pass/fail per check with the evidence it used (logcat lines / screenshots):
   - **ZIM search** returns a result (skip with a clear "no ZIM present" if BASTION's ZIM folder is empty — do not fake it);
   - **PDF import** of a small text-layer PDF extracts text (reuse a fixture PDF; commit a tiny one under `tools/` if none exists);
   - **an AI answer** is produced for a question the library can answer (skip with "no model imported" if none — never a guessed pass).
   Drive the UI with `adb` (`uiautomator dump` + `input`, screenshots), the way the existing PC scripts do.
3. Print a single PASS/FAIL summary and exit non-zero on any failure. Label every result as **emulator** or the named device; never report a check as passed that was actually skipped.

Preconditions (model, ZIM, PDF fixture) must be **detected and reported**, not assumed. The script must not touch the network and must not run the wipe PIN or any instrumented test that uninstalls the app.

## Files you may change

`tools/release-smoke.ps1` (new), a tiny fixture PDF under `tools/` if needed (say where it came from and its licence — a self-made text PDF is fine), your report. Nothing under `android/` source. If a release build needs a keep-rule fix to pass, **do not change it** — report the failure with the logcat evidence for Claude.

## Done when

`tools/release-smoke.ps1` runs end to end on the emulator, builds+installs the release APK, runs the three checks (or honestly skips with the reason), prints a PASS/FAIL summary, and `docs/chatgpt-tasks/release-smoke-1-report.md` shows a full run with its output and screenshots.
