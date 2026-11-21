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
| Project compiles / debug APK builds | **Tested in cloud build session (no emulator — compile and JVM tests only)**: `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL in 59s` (35 actionable tasks: 16 executed, 19 up-to-date). APK: `app/build/outputs/apk/debug/app-debug.apk`, 22,477,521 bytes (~21.4 MiB). No source changes were needed. First attempt failed with a transient HTTP 429 (Too Many Requests) from Maven Central while fetching the Kotlin compiler; an unchanged retry succeeded. One compiler warning (deprecated `Icons.Filled.List` in BastionApp.kt), left as-is. |
| Unit tests pass | **Tested in cloud build session (no emulator — compile and JVM tests only)**: `./gradlew :app:testDebugUnitTest` → `BUILD SUCCESSFUL in 21s`; JUnit XML reports `tests="4" skipped="0" failures="0" errors="0"` (TextUtilTest). No test changes were needed. |
| App launches; import/search/reader/persistence work | **Tested on emulator — software-emulated (no KVM), Android 11 (API 30) x86_64, cloud container, 2026-11-21.** Driven via `adb` + uiautomator. Works: launch; Home status cards (Library, Local AI "not installed", 4 companion apps "not installed"); Add sample; duplicate rejected ("Already in the library (identical content)"); reader with provenance + SHA-256; `.txt` import through the system file picker (DocumentsUI); full-text search of the imported file. Two bugs found and fixed (commit after 164cfb5): prefix search never matched (`"term"*` → `"term*"`; "hypoth" now finds Hypothermia — re-verified on emulator) and Import crashed with `ActivityNotFoundException` when no picker app exists (now a plain message — compiles, crash path **not** re-run). **Not tested:** binary-file rejection, 20 MB cap. |
| Offline proof protocol (fresh process, networking off, JUNIPER-7-ACTUAL) | **Tested on emulator (same software-emulated API 30 device).** Airplane mode on (`airplane_mode_on=1`, ping → "Network is unreachable"), app force-stopped and relaunched; Home showed "1 document(s), 1 KB indexed"; Ask tab search for JUNIPER-7-ACTUAL returned the sample passage. Runtime permission list contains no INTERNET. Not yet repeated on a hardware-accelerated emulator or a physical phone. |

Nothing above is simulated; pending means pending.

## Next actions

1. ~~Build session on `bastion` env~~ — **done 2026-11-21** (see Test
   evidence above; no code fixes were required).
2. Development moves to the owner's Windows laptop (Android Studio,
   hardware-accelerated emulator, Claude Code running locally). First
   local tasks: binary-file rejection and 20 MB cap tests; re-run the M1
   checklist on the accelerated emulator; confirm the no-picker message.
3. Then: M2 runtime/model selection with measured numbers on the real target
   class of phone.

## Blockers needing the owner

- None for code progress. For emulator evidence: an Android Studio install
  on your computer (or a purchased test phone) is the only path — cloud
  sessions cannot host an accelerated emulator.
