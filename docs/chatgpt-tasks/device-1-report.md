# Device-1 report

Branch `chatgpt/device-1`, based on `origin/claude/hello-e9r4rq` at `775b0ed`.

## Changes

- Added **Phone check** in Home below Device manager. All other screen and feature changes live in the new `hw/` package.
- Phone check reads hardware and installed-package facts without requesting permissions. Its requirements show a verdict and the reason for each check. Verification of an end-to-end phone pass is an owner decision.
- The optional measured run uses local assets and a fixed workload. It shows progress and supports cancellation. The report includes observed timing and memory figures and records a missing or unavailable measurement explicitly.
- The screen displays the report and writes `device-check.json` and `device-check.txt` to the primary app media folder. Opening Phone check saves an initial report when the files are absent. **Save report** saves the current screen results. A completed optional run saves its results automatically.
- `docs/devices.md` defines the three tiers and the Verified checklist. `tools/device-report.ps1` pulls a report over USB and prints a Markdown row.

## Validation

- **Compile and package, tested on the PC.** Temurin JDK 21 and the local Android SDK built the main and instrumentation APKs. From `android`, run `.\gradlew.bat --offline --no-daemon '-Pandroid.builder.sdkDownload=false' :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`.
- **Correctness, tested on the PC JVM.** The same command discovered 241 tests, with 240 passing and one state-pack test skipped because `BASTION_MAPS` was unset. All seven added JVM tests and all three unchanged `NetworkGuardTest` cases passed.
- **App launch, tested on the API 37 emulator.** `adb -s emulator-5554 shell am start -W -n com.bastion.terminal/.MainActivity` reported `Status: ok` and took 1,874 ms for the warm start.
- **Facts and save, tested on the API 37 emulator.** Installed the x86_64 debug app and debug test APKs separately with `adb -s emulator-5554 install -r --streaming`, then ran `adb -s emulator-5554 shell am instrument -w -e class com.bastion.terminal.hw.DeviceFactsInstrumentedTest com.bastion.terminal.test/androidx.test.runner.AndroidJUnitRunner`. The one test passed and checked both saved files. `tools/device-report.ps1 -AdbPath <local SDK adb.exe>` pulled the saved JSON and printed the row recorded in `docs/devices.md`.
- **Report tool, tested again on the emulator.** After making the script report an empty pulled file as missing, the same `device-report.ps1` command printed the saved emulator row.
- **Interactive screen and optional benchmark, not tested.** The benchmark samples process VmRSS every 100 ms and can miss shorter peaks. The app can cancel streaming generation and stop between stages. An existing synchronous native load, ZIM lookup or route search may have to return before cancellation completes.

Saved summary excerpt, tested on emulator:

```text
Google sdk_gphone64_x86_64 · emulator — not a phone
Android 17 (SDK 37) · ABIs x86_64, arm64-v8a
RAM reported by Android · 4.10 GB (8.00 GB advertised)
Free phone storage · 1.72 GB
Removable media 1 · 0.53 GB free
GPS provider present · Bluetooth LE present
OpenCL library · not loadable (CPU backend)
Device owner · BASTION is an ordinary app
Meshtastic installed · Kiwix installed · ATAK-CIV installed
Optional measurements have not been run.
```

Android reports arm64 compatibility through the emulator's translation environment. It advertises 8 GB with a 4.10 GB kernel-visible total. The device list records these facts outside the phone tiers.

## Open items

The Galaxy S24+ and Pixel 9a have not had a physical-device pass. Their functional checklist and measured performance are pending. The app does not establish whether an arbitrary model has a physical slot from the absence of mounted removable storage.
