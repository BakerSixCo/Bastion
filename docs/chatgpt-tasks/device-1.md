# Task: phone check and compatible-device list (new branch `chatgpt/device-1`)

Queued after `map-1`. Start from Claude's branch when you begin:

```powershell
git fetch origin
git switch -c chatgpt/device-1 origin/claude/hello-e9r4rq
```

Same rules: `AGENTS.md`, `CLAUDE.md` (BASTION never connects to the internet; honest states; heavy work off
the main thread with progress and cancellation; label every result). You write code, tests and the list
template, never card or protocol text.

## Why

`docs/build-sheet.md` open decision 9: the kit needs a compatible-device list with three tiers:
- **Verified:** a named phone, tested end to end.
- **Expected:** meets the requirements, not tested.
- **Not supported:** with the reason.

No physical phone has been tested yet. Build the tool that tests a phone the same way every time, and the
list it feeds, so that each phone pass adds one honest row.

## Goal

1. **Phone check in the app** (new package `hw/`; one entry point in an existing settings or Home screen,
   smallest UI change, listed in the report).
   - **Facts, read without any permission it doesn't already have:**
     - maker, model, Android version and SDK, ABIs, total RAM;
     - free storage on the phone and on each SD card (`externalMediaDirs`);
     - GPS provider present, Bluetooth LE present, OpenCL library loadable (GPU backend possible);
     - device-owner state;
     - Meshtastic, Kiwix and ATAK-CIV installed (the existing package checks).
   - **Requirement verdicts in code, each with its reason:**
     - Android 10+, arm64-v8a, GPS, BLE;
     - storage for the imported model plus the free space `LocalAi` already requires to start it;
     - RAM ≥ 8 GB, with 12 GB noted for Gemma 4 E4B. Take the thresholds from `docs/build-sheet.md`
       item 9, as named constants with a comment pointing there.
   - **Optional measured run (a button; cancellable; progress shown):**
     - model load time, prompt and decode tokens/s, peak app RAM (`VmRSS`), using the imported model;
     - one ZIM search time if a ZIM is present;
     - one driving route on the largest road pack present, if any;
     - battery % and temperature before and after.
     Missing model, ZIM or pack → that line says "not measured: no model imported", never a guess.
   - **The result** is shown on screen and saved as JSON plus a short text summary in BASTION's media
     folder (`Android/media/com.bastion.terminal/device-check/`):
     - include no user data, no location, no account names, no serial numbers or IMEI;
     - the file can be pulled over USB.
   - **Tier the check may set:** "Meets requirements (Expected)" or "Not supported: <reasons>", and
     nothing higher. **Verified** is set only by the owner after the functional pass in step 2. The
     emulator is always labelled "emulator — not a phone".
2. **`docs/devices.md`**:
   - the list with the three tiers;
   - the hard requirements, each linked to where the code enforces it;
   - the checklist for a Verified pass, from build-sheet item 9: model answers, ZIM search, maps and
     routing, GPS, Meshtastic share and inbox, ATAK-CIV, device-manager setup, SD card;
   - one row from the emulator, labelled as such. The Verified tier stays empty.
3. **`tools/device-report.ps1`**:
   - pulls the check's JSON over `adb` from one connected device;
   - prints a ready-to-paste row for `docs/devices.md`;
   - never edits the list itself, and runs no network command.

## Measure it (required)

- JVM tests for the verdict logic with fake facts: each requirement failing alone, all passing, the
  emulator label, missing model / ZIM / pack lines.
- An instrumented test that runs the facts part on the emulator (never `connectedAndroidTest`; use
  `adb install -r` and `adb shell am instrument`). The measured run with the model is optional on the
  emulator: report it if run, with its numbers labelled "emulator".
- Show the check never touches the network: `NetworkGuardTest` must still pass unchanged.

## Files you may change

- new `hw/` files and their tests;
- one small UI entry point (list it);
- `docs/devices.md` and `tools/device-report.ps1`;
- your report `docs/chatgpt-tasks/device-1-report.md`.

Nothing else. List anything else that should change (for example the wipe keep-list, or
`docs/build-sheet.md`) rather than changing it.

## Done when

- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` (JDK 21) pass;
- the instrumented facts test passes on the emulator;
- the report is committed on `chatgpt/device-1`, with screenshots or the saved summary text, and what
  was tested how.
