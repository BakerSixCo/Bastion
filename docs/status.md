# Outpost Zero status (formerly BASTION)

**Updated:** 2026-11-21 · **Milestone:** 0 complete, 1 in progress (M1 checklist passed on hardware-accelerated emulator)

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

### Cloud build session (2026-11-21, no emulator)

| Claim | Status |
|---|---|
| Gradle wrapper generated (8.14.3) | Tested in this session (cloud) |
| Project compiles / debug APK builds | **Tested in cloud build session (no emulator — compile and JVM tests only)**: `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL in 59s` (35 actionable tasks: 16 executed, 19 up-to-date). APK: `app/build/outputs/apk/debug/app-debug.apk`, 22,477,521 bytes (~21.4 MiB). No source changes were needed. First attempt failed with a transient HTTP 429 (Too Many Requests) from Maven Central while fetching the Kotlin compiler; an unchanged retry succeeded. One compiler warning (deprecated `Icons.Filled.List` in BastionApp.kt), left as-is. |
| Unit tests pass | **Tested in cloud build session (no emulator — compile and JVM tests only)**: `./gradlew :app:testDebugUnitTest` → `BUILD SUCCESSFUL in 21s`; JUnit XML reports `tests="4" skipped="0" failures="0" errors="0"` (TextUtilTest). No test changes were needed. |

### Cloud container — software-emulated emulator (2026-11-21, no KVM)

| Claim | Status |
|---|---|
| App launches; import/search/reader/persistence work | **Tested on emulator — software-emulated (no KVM), Android 11 (API 30) x86_64, cloud container, 2026-11-21.** Driven via `adb` + uiautomator. Works: launch; Home status cards (Library, Local AI "not installed", 4 companion apps "not installed"); Add sample; duplicate rejected ("Already in the library (identical content)"); reader with provenance + SHA-256; `.txt` import through the system file picker (DocumentsUI); full-text search of the imported file. Two bugs found and fixed (commit after 164cfb5): prefix search never matched (`"term"*` → `"term*"`; "hypoth" now finds Hypothermia — re-verified on emulator) and Import crashed with `ActivityNotFoundException` when no picker app exists (now a plain message — compiles, crash path **not** re-run). **Not tested:** binary-file rejection, 20 MB cap. |
| Offline proof protocol (fresh process, networking off, JUNIPER-7-ACTUAL) | **Tested on emulator (same software-emulated API 30 device).** Airplane mode on (`airplane_mode_on=1`, ping → "Network is unreachable"), app force-stopped and relaunched; Home showed "1 document(s), 1 KB indexed"; Ask tab search for JUNIPER-7-ACTUAL returned the sample passage. Runtime permission list contains no INTERNET. Repeated on the hardware-accelerated emulator below; not yet on a physical phone. |

### Owner's Windows PC — hardware-accelerated emulator (2026-11-21)

**Environment (tested):** Windows 11 Pro 10.0.26200, Android Studio SDK at
`%LOCALAPPDATA%\Android\Sdk`. AVD `Medium_Phone_API_37.0`
(`sdk_gphone64_x86_64`, Android 17 / API 37, x86_64), acceleration **WHPX**
(`emulator -accel-check`: "WHPX(10.0.26200) is installed and usable").
JDK **Temurin 21.0.13**. Android Studio's bundled JBR is JDK 25, which
Gradle 8.14.3 cannot run on (build fails immediately with "25.0.4"), so
`JAVA_HOME` must point at a JDK 17–24. On the first build AGP auto-installed
SDK Platform 35 and Build-Tools 34.0.0 (licenses already accepted). UI was
driven with `adb` (`uiautomator dump` + `input tap/text`) plus screenshots.

Every row below: **tested on the hardware-accelerated emulator** (not a
physical phone).

| Claim | Result |
|---|---|
| Debug APK builds | `gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL` (first run 6m 55s incl. SDK downloads). Same deprecation warning as cloud. |
| Unit tests pass | `:app:testDebugUnitTest` → `tests="4" skipped="0" failures="0" errors="0"`; re-run after each fix below, still 4/4. |
| APK installs and launches | `adb install -r` → `Success`; Home renders the status cards (Library empty, Local AI not installed, 4 companion apps "not installed", storage free). |
| Picker offers .txt and .bin | DocumentsUI lists `.txt` and `.bin` files (`text/*` + `application/octet-stream`). |
| **Binary file rejected** | `bastion-binary.bin` (4,096 random bytes) → "This looks like binary data. v0.1 indexes plain text and Markdown only; PDF, EPUB, and ZIM support are planned, separate capabilities." A PNG-header binary renamed `.txt` → same message (verified after a different message was shown, so not stale). Library stayed empty. |
| **20 MB size cap** | 21,000,000-byte text file → "File exceeds the current 20 MB text limit." 20,971,000-byte text file (520 bytes under 20 MiB) → imported ("20479 KB"). Cap is 20 MiB; boundary confirmed from both sides. |
| **No file-picker app message** | With `com.google.android.documentsui` disabled (`pm disable-user`; `query-activities` for OPEN_DOCUMENT → "No activities found"), tapping Import document → "This device has no file picker app, so documents cannot be imported here." No crash — this is the first run of the crash path fixed in e908191. DocumentsUI re-enabled afterwards. |
| Import + duplicate rejection | Small `.txt` imported; re-importing it → "Already in the library (identical content)." Sample added; adding again → same duplicate message. |
| Search (prefix) | Library search "OSPR" finds `OSPREY-3-ECHO` (prefix fix from e908191 confirmed on device); snippet shown. |
| Open source / reader | Reader shows title, provenance line (method · date · SHA-256 prefix) and highlighted term. **Bug found and fixed** (see below). |
| Large document in reader | Opening the 20 MB document **crashed** the app → **fixed** (see below); after the fix it opens with "Large document: showing the first 400,000 characters." No crash/ANR in logcat. |
| Restart persistence | After `am force-stop` + relaunch (new PID), Home shows "3 document(s), 20480 KB indexed"; all docs open. |
| **Offline proof protocol** | App force-stopped; airplane mode on, Wi-Fi and mobile data off (`Active default network: none`, ping "Network is unreachable"); fresh launch (new PID); Ask → "JUNIPER-7-ACTUAL" → sample doc hit with snippet; opening it shows the phrase highlighted with provenance. Re-run on the final build with both fixes: pass. Networking restored afterwards. |

**Bugs found on the emulator and fixed in this session:**

1. **Reader text nearly invisible.** `ReaderScreen` is rendered outside the
   `Scaffold`, so no Surface supplied a content color; title and body text
   fell back to black on the dark window background (only provenance and
   highlights were readable). Fix: wrap the reader in a themed `Surface`
   (`ui/BastionApp.kt`, now `ui/OutpostZeroApp.kt`). Verified by screenshot.
2. **Reader crash on documents over ~2 MB.** `docText` read the whole FTS
   row through one cursor → `SQLiteBlobTooBigException: Row too big to fit
   into CursorWindow`. Import allows 20 MB, so any large import would crash
   on open. Fix: read the preserved original `files/library/<id>.orig`
   (identical bytes to what was indexed), DB read kept as a guarded fallback
   (`data/LibraryStore.kt`). Verified with the 20 MB document.

**Observations, not fixed (minor):**

- Sizes under 1 KB display as "0 KB" (import message and list row).
- The cap message says "20 MB" but means 20 MiB; DocumentsUI shows the
  accepted 20,971,000-byte file as "20.97 MB" — slightly confusing at the
  boundary.
- `importFromUri` reads the whole file into memory before the size check;
  a very large pick (hundreds of MB) could hit memory limits before being
  rejected. Checking `OpenableColumns.SIZE` / streaming with a byte limit
  would avoid that.
- The status message under the Library buttons is never cleared, so an old
  result stays visible until the next action.

**Not tested:** any physical phone; launching companion apps (none
installed on the emulator); cancelling an import mid-way; process death
during import.

### Rename to Outpost Zero (2026-11-21) — tested on the hardware-accelerated emulator

App ID and package `com.bastion.terminal` → `com.outpostzero.terminal`
(also `BastionApp`/`BastionTheme`/`Theme.Bastion` → `OutpostZero*`, DB file
`outpostzero.db`, sample document text). Clean build `BUILD SUCCESSFUL`,
unit tests `tests="4" failures="0"` under the new package. Old app
uninstalled from the emulator (test data only); new APK installed — launcher
label "Outpost Zero", Home header "OUTPOST ZERO", no INTERNET permission
(`dumpsys package`). Smoke test: Add sample → fresh start in airplane mode
(`Active default network: none`) → Ask "JUNIPER-7-ACTUAL" → sample hit opens
in the reader with provenance. No crash/ANR. A web search found an existing
"Outpost Zero" video game (tinyBuild, Steam 2018/2019) and an Image Comics
series; no trademark clearance has been done — required before any public
release.

Nothing above is simulated; pending means pending.

## Next actions

1. ~~Build session on `bastion` env~~ — **done 2026-11-21**.
2. ~~Move development to the owner's Windows PC; test binary rejection,
   20 MB cap, no-picker message; re-run the M1 checklist on the accelerated
   emulator~~ — **done 2026-11-21** (API 37, WHPX); two reader bugs found and
   fixed.
3. Optional M1 polish: the minor observations above (size display, memory-safe
   size check, clearing stale messages).
4. Then: M2 runtime/model selection with measured numbers on the real target
   class of phone.

## Blockers needing the owner

- None for code progress. Physical-phone evidence needs a test phone.
- Local builds on Windows: set `JAVA_HOME` to a JDK 17–24 (Temurin 21 is
  installed) — Android Studio's bundled JDK 25 does not work with Gradle 8.14.3.
