# BASTION status

**Updated:** 2026-11-21 · **Milestone:** 0 complete, 1 in progress

## Milestone 0 — environment & architecture (complete)

Environment audit (all facts below: **tested in this session** unless noted):

- Execution context: **Anthropic cloud container**, not the owner's computer.
  Ubuntu 24.04, x86_64, 4 cores, 15 GB RAM, ~61 GB free disk.
- **No /dev/kvm** → no hardware-accelerated emulator in this context, ever.
  Emulator runs happen on the owner's computer or a physical device.
- JDK 21 (OpenJDK 21.0.13) and Gradle 8.14.3 preinstalled. No Android SDK,
  no adb, no AVDs, no attached devices.
- No Android plugin enabled on the Claude account (checked: empty list).
- Network: Maven Central / Gradle portal reachable; **dl.google.com and
  maven.google.com blocked** in the default environment → Android SDK and
  AGP cannot download here. Remedy executed: created personal cloud
  environment **`bastion`** (allows exactly those two hosts, linked to this
  repo). This session could not switch onto it mid-run; a build-verification
  session runs there instead.
- Architecture decision: **Option B** (dashboard + owned library/search,
  companions launched) — see docs/reuse-assessment.md.

## Milestone 1 — offline application foundation (in progress)

Implemented (v0.1.0, `android/`):

- Home with honest status cards (library stats, Local AI **not installed**,
  companion-app detection via `<queries>` + launch, storage).
- Ask: labeled retrieval-only search (no fabricated answers).
- Library: SAF import of .txt/.md, binary sniff + 20 MB cap + duplicate
  (SHA-256) rejection with clear messages; FTS4 index; snippet results;
  reader with term highlighting and provenance line; originals preserved
  under `files/library/`.
- Labeled sample document (contains unique marker `JUNIPER-7-ACTUAL` for the
  offline-proof protocol).
- No INTERNET permission in the manifest.
- Unit tests for the pure-JVM text helpers (binary sniff, FTS query
  sanitizing, highlight ranges).

## Test evidence

| Claim | Status |
|---|---|
| Gradle wrapper generated (8.14.3) | Tested in this session (cloud) |
| Project compiles / debug APK builds | **Pending** — running in a `bastion`-environment build session; this session's network cannot fetch AGP/SDK |
| Unit tests pass | **Pending** — same build session |
| App launches; import/search/reader/persistence work | **Not tested** — requires emulator (owner's computer) or a physical device; no KVM here |
| Offline proof protocol (fresh process, networking off, JUNIPER-7-ACTUAL) | **Not tested** — device/emulator required |

Nothing above is simulated; pending means pending.

## Next actions

1. Build session on `bastion` env: install SDK (cmdline-tools + platform 35 +
   build-tools 35.0.0, ~600 MB), `./gradlew :app:assembleDebug` and
   `:app:testDebugUnitTest`, commit fixes, update this file with real output.
2. Owner: when convenient, open `android/` in Android Studio on your
   computer → run on an emulator (any x86_64 API 29+ image) → walk the
   M1 checklist (import, search, open source, restart, airplane-mode use).
3. Then: M2 runtime/model selection with measured numbers on the real target
   class of phone.

## Blockers needing the owner

- None for code progress. For emulator evidence: an Android Studio install
  on your computer (or a purchased test phone) is the only path — cloud
  sessions cannot host an accelerated emulator.
