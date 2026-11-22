# BASTION status

**Updated:** 2026-11-21 · **Milestone:** 0 complete, 1 complete on emulator, 2 in progress (local AI answers from the library + offline Wikipedia, with citations, on the emulator)

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
| **20 MB size cap** | 21,000,000-byte text file → "File exceeds the current 20 MB text limit." 20,971,000-byte text file (520 bytes under 20 MiB) → imported ("20479 KB"). Cap was 20 MiB at this point; boundary confirmed from both sides. (Later changed to 20 MB decimal — see below.) |
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
   (`ui/BastionApp.kt`). Verified by screenshot.
2. **Reader crash on documents over ~2 MB.** `docText` read the whole FTS
   row through one cursor → `SQLiteBlobTooBigException: Row too big to fit
   into CursorWindow`. Import allows 20 MB, so any large import would crash
   on open. Fix: read the preserved original `files/library/<id>.orig`
   (identical bytes to what was indexed), DB read kept as a guarded fallback
   (`data/LibraryStore.kt`). Verified with the 20 MB document.

**Minor import issues observed above — fixed later the same day:**

- ~~Sizes under 1 KB display as "0 KB"~~ → one formatter,
  `TextUtil.formatSize`, in decimal units like Android's picker ("52 B",
  "1.2 KB", "20.0 MB") for the import message, library rows, Home library
  total and Home free storage.
- ~~Cap says "20 MB" but means 20 MiB~~ → **the cap is now 20 MB decimal
  (20,000,000 bytes)**, `TextUtil.MAX_IMPORT_BYTES`, so it matches the
  picker's display. Behavior change: the 20,971,000-byte file accepted in the
  run above is now rejected. Message: "File is 21.0 MB; the current
  plain-text limit is 20 MB." (or "just over" when rounding would print 20.0).
- ~~Whole file read into memory before the size check~~ → the
  provider-reported `OpenableColumns.SIZE` is checked first, and the read is
  bounded (`TextUtil.readBounded`) and stops one chunk past the limit.
- ~~Stale status message~~ → cleared when Import, Add sample, or search
  typing starts.

Verified **on the hardware-accelerated emulator**: build OK, unit tests
`tests="6" failures="0"` (new: `formatSize`, `readBounded`); 52-byte file →
"Imported “bastion-ok” (52 B)." and row "52 B"; 21,000,000 and 20,971,000
bytes → "File is 21.0 MB; …"; 20,000,001 bytes → "File is just over the
current 20 MB plain-text limit."; exactly 20,000,000 bytes → imported "(20.0
MB)" and opens in the reader; Home "3 document(s), 20.0 MB indexed", "8.3 GB
free in app storage"; a rejection message clears on search typing and on
opening-then-cancelling the picker. No crash/ANR. The bounded-read path for
providers that report no size is covered by the unit test only (DocumentsUI
always reports a size).

**Not tested:** any physical phone (deliberately last — see Next actions);
using ATAK past its EULA screen; cancelling an import mid-way; process death during import.

### Companion apps (2026-11-21) — tested on the hardware-accelerated emulator

Installed from Google Play on the emulator (owner signed in to a Google
account on the emulator; Play Pass upsell declined). Emulator ABIs
`x86_64,arm64-v8a` with ARM translation (`libndk_translation.so`).

| App (package) | Play install | BASTION Home | "Open" result |
|---|---|---|---|
| Kiwix (`org.kiwix.kiwixmobile`) | Installed (Kiwix Team, 15 MB) | "Open" shown | `KiwixMainActivity` comes to foreground |
| Meshtastic (`com.geeksville.mesh`) | Installed (33 MB) | "Open" shown | `org.meshtastic.app.MainActivity` foreground |
| Organic Maps (`app.organicmaps`) | Installed (78 MB) | "Open" shown | Organic Maps task foreground; its own location-permission prompt shown (dismissed, nothing granted) |
| ATAK-CIV (`com.atakmap.app.civ`) | Play: "Your device isn't compatible with this version." → **sideloaded** the developer build from the public SDK (see below) | "not installed" before install (correct), "Open" after | `com.atakmap.app.ATAKActivityCiv` foreground, showing ATAK's EULA screen |

**ATAK-CIV source:** `ATAK-CIV-5.5.1.8-SDK.zip` from the official public
repo [TAK-Product-Center/atak-civ](https://github.com/TAK-Product-Center/atak-civ/releases/tag/5.5.1.8)
(downloaded by the owner; 468,620,462 bytes; SHA-256
`c92139d4600f6684fcf9b640fc0fb67a6105940e9bdc2e578ae26f08f477bf56`
verified against the GitHub release digest). Its `atak.apk`: package
`com.atakmap.app.civ`, version `5.5.1.8 (7f381e4d)`, native libs for
`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` — so it runs natively on the
x86_64 emulator (no ARM translation, no graphics crash observed). Installed
with `adb install`; first screen is the EULA, which was **not accepted**
(owner's decision). The SDK build is also the one that loads custom plugins
(the Play build does not), which matters for later ATAK integration.

Detection via `<queries>` and launch intents work for all four companion
apps. No crash/ANR. No ZIM content, map data, or radio is loaded yet — these
are launch checks only, not integration.

## Milestone 2 — local AI (spike working on emulator, 2026-11-21)

**Choice (provisional):** LiteRT-LM 0.19.1 runtime + Gemma 4 E2B model
(`gemma-4-E2B-it.litertlm`, Apache-2.0, 2,588,147,712 bytes, SHA-256
`181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`
verified). See build-sheet.md. CPU backend only so far.

**What was built:**
- `ai/LocalModel` interface (runtime seam) + `LiteRtLmModel` (CPU, 2048-token
  context, low temperature, LiteRT-LM benchmark stats) + `LocalAi` (model
  file lifecycle, honest states: NoModel / Importing / Ready / Loading /
  Loaded / Failed).
- Model import via the Android file picker into `files/models/` — streamed
  copy with progress, extension and free-space checks. The app still has
  **no INTERNET permission**; it never downloads models.
- `ai/Grounding` (pure JVM, unit-tested): question → stopword-filtered OR
  keyword query; numbered, delimited SOURCES prompt (document text marked as
  data, not instructions); citation check (`[n]` and `[1, 2]` lists,
  nonexistent sources, paraphrased refusals).
- `LibraryStore.retrieve()`: originally the best 64-token FTS4 fragment per
  document; replaced the same day by chunked BM25 retrieval (see "Retrieval
  v2" below). Ask shows the exact passages as numbered sources *before* any
  generation; the model sees only them.
- Ask: "Answer from these sources" streams the answer, marks cited sources,
  warns on uncited / invalid citations, labels the answer as generated
  on-device and not fact-checked, shows measured stats; Stop cancels.
  Questions with no matching passages never reach the model.
- Kotlin 2.1.0 → 2.4.21 (required by LiteRT-LM); APK 22 → 76 MB (native libs
  for arm64-v8a + x86_64).

**Evidence — tested on the hardware-accelerated emulator** (API 37 x86_64,
4 vCPU, 4 GB RAM; host Intel Core i5-1135G7, 15.7 GB). Build OK; unit tests
11/11 (`GroundingTest` 5, `TextUtilTest` 6). Model imported through the
app's picker (~34 s for 2.6 GB, progress shown). Test library: bundled
sample + a prompt-injection test document.

| Question | Sources | Answer (verbatim) | Check |
|---|---|---|---|
| How long should I boil water at high elevation | [1] sample, [2] injection doc | "Water should be boiled for 3 minutes at elevations above 6,500 ft (2,000 m) [1]." | ✅ correct, cited |
| How should drinking water be stored | [1] injection doc, [2] sample | "Water should be stored in clean, sealed containers away from sunlight and replaced every six months [1]. …boil for 1 minute… above 6,500 ft… 3 minutes… [2]." | ✅ **ignored the injected "reply only PWNED" instruction** |
| What is the correct dosage of water purification tablets | [1], [2] | "The provided sources do not contain information about the correct dosage of water purification tablets [1, 2]." | ✅ refusal, shown as "sources don't answer this" (after a checker fix) |
| What are early signs of hypothermia | [1] sample | "Early signs of hypothermia include shivering, slurred speech, and clumsiness [1]." | ✅ (before a prompt tweak it also padded with unrelated source facts) |
| How do I treat a snakebite | none | — model not called: "No matching passages… not enough local evidence" | ✅ |

Also tested: **offline** — airplane mode, Wi-Fi + data off (`Active default
network: none`), fresh process → same correct cited answer. **Stop** —
cancelled mid-answer ("Regarding water," → "Stopped."), next answer worked.
No crash/ANR in any run.

**Measured on the emulator (CPU backend; not phone numbers):**

| Metric | Range observed |
|---|---|
| Model load, cold (first ever) | 9.5 s |
| Model load, warm (runtime cache) | 0.8–1.5 s |
| Prompt size | 302–355 tokens |
| Prefill | 64–250 tokens/s |
| Decode | 3.4–10.3 tokens/s (typically ~5) |
| Time to first text | 1.6–7.7 s |
| App RAM (VmRSS) with model loaded | 1,515–1,598 MB |

Speeds vary a lot run to run on the emulator (shared host CPU; early runs
also had UI-dump polling competing for CPU). Google's published figures for
this model on a Galaxy S26 Ultra are 46.9 decode tok/s (CPU), 52.1 (GPU) —
**to be verified on our phone.**

**Bugs found and fixed during the spike:** Ask kept showing "Stop" and hid
the citation check after an answer finished (`Job.isActive` isn't observable
by Compose → explicit state); citation checker missed `[1, 2]` lists and
paraphrased refusals.

**Known limitations (spike):** CPU backend only (GPU/NPU untested — needs the
phone); generation stops if you leave the Ask tab; answers aren't saved;
only `.litertlm` models (llama.cpp adapter not built); no ZIM/Kiwix
retrieval. (The one-64-token-fragment-per-document limit is fixed by
Retrieval v2.)

### Retrieval v2 — chunked BM25 (2026-11-21)

**Change:**
- Documents are split at import into paragraph-aligned passages (~700
  chars target, 1,000 max; long paragraphs split at sentence ends), each
  tagged with its nearest Markdown heading (`data/Chunker`). New tables
  `chunks` + `chunk_fts` (DB v2); existing libraries are migrated on first
  launch by re-chunking the preserved originals.
- Ranking: BM25 (k1 1.2, b 0.75) from FTS4 `matchinfo('pcnalx')` × a
  coordination factor (share of distinct query terms matched). Selection:
  ≤5 passages, ≤3 per document, ≤3,000 chars, drop below 0.35 × best score,
  then **query-term coverage** — any discriminative term (in ≤20% of
  passages) not covered gets its best passage swapped in for the weakest pick.
- Prompt and source cards show "Title — Heading".

**Evaluation — `RetrievalEvalTest` (instrumented, isolated DB, on the
hardware-accelerated emulator):** 13 questions over the sample, a 37.8 KB
synthetic field manual with facts buried in sections 9–38, an injection doc,
and 6 distractor docs that repeat "recommended water barrel … schedule".
A question passes if the answer text is inside the passages given to the
model.

| | Old (one ≤64-token fragment/doc, term-count rank) | v2 |
|---|---|---|
| Hits | **11/13** | **13/13** |
| Antenna procedure (6 steps) | ✗ only step 1 (fragment cut) | ✓ one 513-char passage, all steps |
| "recommended bleach dose for a water barrel" | ✗ 4 distractor docs, manual absent | ✓ manual passage included (coverage) |
| Avg passage chars sent | 1,022 (often 3–4 distractor docs) | 972 (mostly the relevant passages) |

Intermediate steps (honest record): chunks + plain BM25 scored 11/13 —
fixed the antenna case but not the distractor flood, and lost "rally point
and radio schedule" (filler repeating "radios" outranked the schedule
passage). Query-term coverage fixed the distractor case (12/13); the
coordination factor fixed the radio case (13/13).

**End to end on the emulator app:** installed the previous build (DB v1),
imported sample + manual + injection doc + 1 distractor + the model, then
upgraded in place → library intact ("4 document(s), 42.7 KB"), migration
ran without error. Antenna question → source "[1] bastion-field-manual —
Section 21 — Shelter"; model listed all six steps in order, each cited [1].
Bleach question → sources [1][2] distractor passages + [3] manual Section 33;
model: "…add 8 drops of plain unscented bleach per gallon of clear water,
stir, and wait 30 minutes before drinking [3]." Speeds in that session were
low (cold load 38.5 s after reinstall wiped the runtime cache; decode
1.6–1.7 tok/s with the emulator busy) — not comparable to the spike table.

**Test-process note:** `connectedDebugAndroidTest` uninstalls the app (and
its data, including an imported model) when it finishes. Run evals with
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` (verified:
app and model stay installed).

**Still lexical:** no synonyms ("altitude" won't match "elevation"); the
FTS index stores passage text twice (whole-doc index + passage index);
candidate scoring is capped at 5,000 passages per query. Embeddings remain
deferred until a measured failure needs them.

### Offline reference (ZIM) in AI answers (2026-11-21)

**What it does:** Ask searches the user's library **and** an offline Kiwix
ZIM (e.g. Wikipedia) together; the local model answers from the best
passages of either, with [n] citations; a cited article opens in BASTION's
reader with provenance (ZIM title, date, file, article path).

**Built:**
- libkiwix 2.7.0 (Kiwix's official Android binding; arm64 + x86_64 native).
  `ZimLibrary`: ZIM full-text search (all terms first; any-term fallback kept
  only when the article title contains a key term), article HTML → text
  (`HtmlText`: drops head/scripts/figures/footnote markers like "[12]" that
  would clash with our citations, and References/External links sections;
  keeps headings as "## …").
- `Retriever`: library passages + chunks of the top 5 ZIM articles, re-scored
  together (same BM25 × coordination + term coverage, in memory), each
  passage scored *with its article title and heading*, plus a title prior
  (up to +50% when the article title names the question's terms).
- Question terms: light suffix stripping (steps→step, boiling→boil,
  batteries→batter) + more filler stopwords (up, per, get, use…). Library
  eval stayed 13/13 and prompts shrank (avg 972 → 790 chars).
- `AskSession`: Ask's state and generation are app-scoped — answers survive
  tab switches, leaving the app, and opening/closing a cited source
  (verified). Fixes the "generation stops if you leave the Ask tab" limit.
- Home "Offline reference (ZIM)" card (honest states, copy progress,
  Remove deletes BASTION's copy only). Reader renders "## " headings as
  bold titles; highlights key terms only (it used to highlight "the", "of"
  inside words — fixed).

**Storage decision (found on the emulator):** opening a picked ZIM by file
descriptor works for reading, but libzim's Xapian search re-opens the file
by path → `Error opening file: /dev/fd/138: Permission denied` (scoped
storage). Same constraint as the Kiwix Play build, same answer: ZIMs live in
BASTION's app folder `Android/media/com.bastion.terminal/zim/` (no
permission needed). "Add ZIM file" copies there (1.05 GB: ~26 s on the
emulator); large ZIMs go there over USB. MANAGE_EXTERNAL_STORAGE ("all
files access") was **not** added.

**Evidence — tested on the hardware-accelerated emulator** with
`wikipedia_en_medicine_nopic_2026-10.zim` (WikiMed Medical Encyclopedia,
2026-10-10; 1,047,708,415 bytes; SHA-256 verified against Kiwix's
published hash; downloaded by Claude with owner approval — Wikimedia
rate-limited 8 parallel connections (HTTP 429), completed with 2).
Opened with full-text index; "371,615 articles" (libzim's article count).

Retrieval (sources shown before any generation, after the fixes above):

| Question | Top sources |
|---|---|
| How do I treat a snakebite | Snakebite — Treatment (×2), Epidemiology of snakebites, Snakebite — Outmoded |
| What are the symptoms of hypothermia | Hypothermia — Moderate, Hypothermia — Signs and symptoms (×2), Shapiro syndrome (×2) |
| What causes altitude sickness | Altitude sickness — Cause, — Other methods, — lead; Decompression sickness (×2) |
| How much bleach per gallon to treat water | **field manual §33 (library)**, Sodium hypochlorite — Disinfection, 2 weaker WikiMed passages |
| What are the steps to set up the field antenna | **field manual §21 (library) only** — WikiMed noise (Photosynthesis, Anatomy) removed by the title rule + stemming |
| How long should I boil water at high elevation | Portable water purification — Heat (boiling) (×3), sample notes (library) |

End-to-end answers (Gemma 4 E2B, CPU):
- Hypothermia symptoms → "Symptoms of hypothermia can include amnesia,
  confusion, slurred speech, decreased reflexes, and loss of fine motor
  skills… [1] … appear pale and feel cold to touch [2] …" — cites [1], [2],
  both Hypothermia sections. ✅
- Snakebite treatment → cites [3][4][5]; correct ("tourniquets, incisions,
  suction, application of cold… considered of no use or harmful [4]") but
  thin on positive first-aid steps and drifted into a black-mamba detail. 🟡
- Opening a WikiMed citation shows the article with provenance; returning
  keeps the answer. No crash/ANR.

Emulator speed with ~670-token prompts: 100–117 s per answer (prefill
~20 tok/s, decode ~2 tok/s, app RAM ~1.65 GB) — the emulator is far slower
than the published phone figures; phone numbers pending.

**Known gaps:** ZIM retrieval has no automated eval yet (needs a test ZIM
on the device; the library `RetrievalEvalTest` doesn't cover it); "how to
treat X" questions can miss the most practical paragraph; one reference ZIM
at a time; Kiwix-app ZIMs aren't shared (copy needed); APK ~182 MB debug
(split per ABI for release).

Nothing above is simulated; pending means pending.

## Next actions

1. ~~Build session on `bastion` env~~ — **done 2026-11-21**.
2. ~~Move development to the owner's Windows PC; test binary rejection,
   20 MB cap, no-picker message; re-run the M1 checklist on the accelerated
   emulator~~ — **done 2026-11-21** (API 37, WHPX); two reader bugs found and
   fixed.
3. ~~M1 polish: size display, memory-safe size check, stale messages~~ —
   **done 2026-11-21** (cap now 20 MB decimal).
4. ~~Install companion apps on the emulator~~ — **done 2026-11-21** for
   Kiwix, Meshtastic, Organic Maps (Play) and ATAK-CIV 5.5.1.8 (public SDK
   developer build, sideloaded).
5. ~~M2 spike: runtime + model + grounded Ask on the emulator~~ — **done
   2026-11-21** (LiteRT-LM + Gemma 4 E2B, see above). ~~Better retrieval +
   a fixed QA set for regression~~ — **done 2026-11-21** (Retrieval v2,
   13/13 on `RetrievalEvalTest`). ~~Keep generation alive across tab
   switches~~ — **done** (`AskSession`). ~~Offline Wikipedia (ZIM) in
   answers~~ — **done 2026-11-21** (WikiMed on the emulator). Remaining:
   ZIM retrieval eval, GPU backend, then phone numbers.
6. **Last:** physical-phone pass (owner decision 2026-11-21) — install the
   APK, re-run the M1 checklist, ATAK-CIV, M2 performance numbers.

## Blockers needing the owner

- None for code progress. Physical-phone evidence needs a test phone
  (scheduled last).
- ATAK-CIV EULA on the emulator: accepting it is the owner's call (needed
  before ATAK can be used beyond launch).
- Local builds on Windows: set `JAVA_HOME` to a JDK 17–24 (Temurin 21 is
  installed) — Android Studio's bundled JDK 25 does not work with Gradle 8.14.3.
