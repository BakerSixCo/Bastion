# BASTION

**B**ackcountry **A**I & **S**ituational **T**erminal for **I**nformation
& **O**ff-grid **N**etworking — an Android-phone-centered off-grid terminal:
local AI, an offline reference library with grounded search, offline mapping
alongside ATAK/TAK, and Meshtastic messaging via external radio hardware.

## Current state (2026-11-21)

**Milestone 1 in progress — offline library/search foundation.**

What exists: a native Kotlin/Jetpack Compose app (`android/`) with

- **Home** — honest status: library size, Local AI *not installed* (stated,
  not simulated), companion apps (Kiwix / Meshtastic / ATAK-CIV / Organic
  Maps) detected and launchable if present, storage.
- **Ask** — labeled retrieval-only: full-text search over imported documents;
  no generated answers until a real local model lands (Milestone 2).
- **Library** — import `.txt`/`.md` via the Android file picker, SQLite FTS4
  full-text index, snippet results, reader with highlights and provenance
  (filename, import date, SHA-256). Binary/PDF/EPUB/ZIM rejected with a clear
  message — those are planned, separate capabilities.
- **No Internet permission at all** — the offline guarantee is in the
  manifest, not a promise.

Build/test status lives in [docs/status.md](docs/status.md) with each result
labeled (emulator / device / built-only / not tested).

## Build

```bash
cd android
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:testDebugUnitTest # unit tests
```

Prerequisites: JDK 17+, Android SDK platform 35 + build-tools 35.0.0.
Or open `android/` in Android Studio and run on an emulator/device
(min Android 10 / API 29).

## Documentation

- [docs/build-sheet.md](docs/build-sheet.md) — **source of truth**: specs,
  hardware ownership vs. plans, component choices, open decisions.
- [docs/status.md](docs/status.md) — milestone state and test evidence.
- [docs/reuse-assessment.md](docs/reuse-assessment.md) — what we reuse vs. build.
- [CLAUDE.md](CLAUDE.md) — scope, decisions, conventions.
- [docs/archive/](docs/archive/) — the pre-pivot hardware kit designs (KISS
  three-module build, 12V build, buy lists). Historical context; the physical
  kit (case, power, Faraday bags) returns at Milestone 4.
