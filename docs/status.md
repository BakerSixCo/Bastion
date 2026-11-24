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

### ZIM retrieval eval + "how to treat" quality (2026-11-21)

`ZimRetrievalEvalTest` (instrumented; skipped unless WikiMed is in BASTION's
ZIM folder): 14 questions over the isolated eval library plus the real ZIM.
A case passes only if a chosen passage comes from the **exact** article
(not "Exercise-induced anaphylaxis" for "Anaphylaxis") and contains a
practical answer phrase. For treatment questions, that passage must also
sit under a treatment-type section. Two library questions check that the
user's own documents still win.

Baseline under the strict check: 12/14 (once two test bugs were fixed).
Burn never reached the *Burn* article ("First aid kit" won on "first aid").
Snakebite picked sentences that merely say "treatment". Anaphylaxis ranked
oral-mite and exercise-induced articles above *Anaphylaxis › Management*.
The fixes, each traced to one of those failures:
- **Exact-title lookup** (`ZimLibrary.titleLookup`, follows redirects). The
  question's subject phrase ("burn", "sprained ankle", "Lyme disease") is
  looked up as an article title and gets the strongest title prior. It only
  runs for phrases that make up at least half the subject, so "water" in a
  bleach question doesn't pull in *Water*.
- **Aspect detection** (`Grounding.aspect`: treatment / symptoms / cause /
  prevention). Ranking uses the subject words only; the aspect is matched
  against **section headings** (`Grounding.aspectWeight`):
  - leaf heading First aid / Prehospital: ×1.8
  - leaf heading matches the aspect: ×1.4
  - only a parent heading matches: ×1.2
  - research / history / epidemiology sections: ×0.6
  - a section's opening passage: an extra ×1.1
- **Section paths in the chunker:** "Signs and symptoms › Acute poisoning",
  so a subsection keeps its parent's meaning.
- **Prompt:** for what-to-do questions, practical actions come first as
  bullets, then what not to do.

Result: **14/14** on `ZimRetrievalEvalTest`, and `RetrievalEvalTest` is
still **13/13**. Tested on the hardware-accelerated emulator. Retrieval
takes 0.5–1.5 s per question with the ZIM.

End-to-end answers (Gemma 4 E2B, CPU, emulator):
- **First aid for a burn** (cites [4], *Thermal burn — Treatment*):
  - stop the burning process
  - stop, drop and roll
  - remove jewelry but not stuck clothing
  - cool with running water within 30 min
  - acetaminophen or ibuprofen for pain
  - "Do not use ice, butter, cream, or ointment"

  ✅ Total 78 s.
- **How do I treat a snakebite** (cites [1] *Treatment › First aid*, [3]):
  pressure immobilization to contain the venom, with its trade-off, and
  first aid varying by snake. 🟡 Now from the right section, though still
  discursive; the article's first-aid text is itself hedged. Total 47 s.

**Known gaps:** the ZIM eval is tied to WikiMed (other ZIMs skip it); one reference ZIM
at a time; Kiwix-app ZIMs aren't shared (copy needed); APK ~182 MB debug
(split per ABI for release).

Nothing above is simulated; pending means pending.

### Build-out pass (2026-11-22) — tested on the hardware-accelerated emulator

- **PDF + EPUB import.** PDF text layer via PdfBox-Android, EPUB chapters in
  reading order. The original is preserved and the extracted text is indexed
  and shown in the reader with provenance ("PDF text layer extracted").
  Scanned and password-protected PDFs are rejected with a clear message.
  `DocImportTest` (on device): real PdfBox import → reader text → Ask
  retrieval ✅. Also imported a PDF and an EPUB through the real file picker ✅.
- **Several ZIMs, SD card.** Every .zim in BASTION's folders on phone
  storage and SD cards is opened and searched. Citations open the right file,
  the same article in two ZIMs is kept once, and Home lists each file with
  Remove, plus Add ZIM / Add to SD card / Rescan.
  - Tested: two ZIMs open and searched with de-duplication ✅; a citation
    opened from the second file ✅; Remove ✅; Rescan after a USB-style
    rename ✅; refusal for a 510 MB SD card ("needs 1047 MB, 534 MB free") ✅.
  - Not tested: an actual copy onto an SD card (the emulator's card is too
    small).
- **GPU backend.** The Compute setting is Auto / GPU / CPU, saved per device.
  - Found on the emulator: the GPU engine *initializes* and then fails at
    first use ("Can not find OpenCL library"). So GPU is now proven with a
    one-token warm-up, and Auto falls back to CPU and says why on Home.
  - Tested: Auto → CPU fallback → correct cited answer ✅.
  - Not tested: GPU on real hardware. GPU-only failure state: code-reviewed
    only.
- **Smaller APKs.** Split per ABI; release builds use R8 shrinking and
  compressed native libraries, signed with the local debug key for R&D
  sideloading. The arm64 release went from 195 MB (universal debug) to
  **36 MB**. The release build was smoke-tested on the emulator: ZIM opens,
  PDF extraction, ZIM search, cited AI answer (26 s total) ✅.
- **Evals after all of the above:** `RetrievalEvalTest` 13/13,
  `ZimRetrievalEvalTest` 14/14, `DocImportTest` ✅, unit tests ✅.
- Emulator note: the AVD hung once (adb shell unresponsive); recovered with
  a forced kill and a cold start. This was not caused by the app.

## Milestone 3 — mesh + ATAK (started 2026-11-22, software-first)

Owner decision 2026-11-22: develop and test against software now, hardware
(phone, two LoRa nodes) later.

### Mesh simulator on the PC — simulated, no RF

`tools/meshsim/install.sh` sets up two simulated Meshtastic nodes in WSL
Ubuntu 26.04: `meshtasticd --sim` 2.7.26 from Meshtastic's official PPA.
Sim A is on `localhost:4403` and Sim B on `:4404`. Each node has its own
network namespace on a bridge. The nodes mesh over Meshtastic's UDP
multicast, which only one process per network stack can bind. Found while
testing: on one stack, A never bound and B heard nothing.
`tools/bastion-pc.cmd` starts the simulator, the emulator and BASTION.

Evidence (simulated radios, hardware-accelerated emulator):
- Sim A → Sim B text over the simulated mesh ✅ (B's log: "Received text
  msg … hello from A").
- Meshtastic Android 2.8.4 on the emulator connected to Sim A at
  `10.0.2.2:4403` (network transport) ✅. A message sent from Sim B appeared
  in the app ("000b: Radio check from Sim B"), with 2 nodes online ✅.
- Permissions on the emulator: only Meshtastic's local-network access was
  granted. Bluetooth, location and notifications were skipped.

### What BASTION can and cannot do with Meshtastic (found 2026-11-22)

Read from the installed app's manifest (2.8.4):
- `MeshService` is **not exported**. The bindable third-party API older
  integrations used is gone, so BASTION cannot read mesh messages, nodes or
  positions from the app.
- The app accepts a standard text share (`ACTION_SEND text/plain`).
- The app registers `AtakPluginDiscoveryActivity` (action
  `com.atakmap.app.component`): **Meshtastic now presents itself to ATAK as a
  plugin.** Not tested yet — ATAK's EULA has not been accepted on the
  emulator (owner's call).

Implemented: **send to mesh** (`mesh/MeshBridge`, `ui/MeshCompose`).
- Home "Mesh messaging" card → composer with a live byte counter (200-byte
  mesh limit).
- "Send to mesh…" on a finished AI answer: citation markers are stripped and
  the text is cut at a word boundary to fit one message.
- BASTION opens Meshtastic with the text. The user picks the channel and
  presses Send **in Meshtastic** — nothing transmits without that tap.
- BASTION still declares no INTERNET or Bluetooth permission.

Evidence: a message typed in BASTION's composer ("Camp at grid 4421. All
OK.", 26/200 bytes) → Meshtastic share screen → LongFast → Send → received
by Sim B ✅ (B's log). Unit tests cover fitting (word cut, multi-byte safe).

Not tested: "Send to mesh…" from an AI answer end to end (same dialog,
different entry point); any real radio; Bluetooth transport; ATAK.

**Open decision — receiving mesh traffic inside BASTION.** Options:
1. Stay as is: send from BASTION, read in Meshtastic/ATAK. No new
   permissions.
2. BASTION talks to the radio itself (Bluetooth or USB on the phone, network
   in a dev build). Needs a Bluetooth or INTERNET permission and a
   Meshtastic protocol client, and locks the Meshtastic app — and with it
   the ATAK plugin — out of the radio while BASTION holds it.
3. Wait for an official Meshtastic third-party API.

Recommendation: 1 now; revisit after the ATAK plugin is tested on hardware.

### Mesh inbox — the chosen method (owner: "S-tier method", 2026-11-22)

The open decision is resolved without new permissions: BASTION reads
incoming messages from the **Meshtastic app's notifications**
(`mesh/MeshInboxService`, a NotificationListenerService). They are
structured: sender, text, time, channel.
- Meshtastic keeps the radio, so its ATAK plugin keeps working.
- BASTION still has no INTERNET or Bluetooth permission.
- The user grants Android "notification access" once (Mesh tab → Turn on
  inbox).
- The service looks only at Meshtastic's message-category notifications,
  never dismisses anything, and stores messages in app-private SQLite.

New **Mesh tab**: inbox, Write message, Reply, and **Ask AI**, which puts a
received message into Ask as a question — never as an instruction.

Evidence (simulated radios, hardware-accelerated emulator):
- A message waiting before access was granted was picked up on connect ✅.
- A live message from Sim B appeared in BASTION within ~7 s ✅.
- Full loop ✅:
  1. Sim B asks "What are the symptoms of hypothermia?" over the mesh.
  2. It appears in the Mesh tab → Ask AI → cited answer from WikiMed (23 s).
  3. "Send to mesh…" fits it to 195/200 bytes with citations removed.
  4. Meshtastic → Send → Sim B received it.
- A question with no answer in the sources ("Need water at checkpoint 2")
  correctly produced no answer and no send button.

Limits:
- A message is seen only if Meshtastic posts a notification for it. Its
  notifications must be allowed (granted via adb on the emulator), and a
  chat open in Meshtastic may not notify.
- No positions or node list this way.
- Notification access was granted via adb here; the in-app button opens the
  right settings screen, but the manual toggle itself was not clicked
  through.
- Not tested on real radios.

### Mesh auto-answer (2026-11-22) — AI answers for people without BASTION

Owner requirement: someone with only a Meshtastic radio can send a question
to a node that has BASTION and get an AI answer back.

How (`mesh/MeshAutoResponder`): Meshtastic's message notification carries a
direct-reply action. BASTION fills it in and fires it, so the answer goes
out through Meshtastic with no tap. No new permissions; Meshtastic keeps the
radio.

Guard rails:
- Off by default (switch in the Mesh tab).
- Only messages starting with `?` or `bastion` are answered.
- Answers come from the library / offline reference only. The question is
  treated as data, and BASTION says "no answer" rather than guessing.
- One short message per answer (the model is asked for ≤ 25 words), marked
  "AI:".
- Rate limits: one at a time, a queue of 3, a 20 s per-sender cool-down.
- Sent replies are logged in the inbox.

Evidence (simulated radios, hardware-accelerated emulator, BASTION in the
**background**): Sim B sent `?first aid for a burn`. About 112 s later Sim B
received "AI: Stop the burning process by removing the source. Cool the burn
with cold running water within 30 minutes." ✅ The Mesh tab showed "Answered
BASTION Sim B." and logged the reply.

Not tested / limits:
- Direct messages: a DM from the simulator produced no notification.
- Long background runs: Android may stop a background app; a foreground
  service is the fix if that shows up on the phone.
- Speed on a real phone, and behaviour on a busy channel.
- Etiquette: an auto-responder on a public channel uses shared airtime, so
  prefer a private channel.
- BASTION still sees text messages only: no positions, node list or ATAK
  traffic.

### In-app Setup guide (2026-11-22) — tested on the hardware-accelerated emulator

Home → "Setup guide" card ("N of 4 essentials ready") → a step-by-step page
with live status per step (`ui/SetupGuide`).
- Essentials: (1) AI model, (2) offline reference / own documents,
  (3) Meshtastic app + radio, (4) mesh inbox.
- Optional: (5) auto-answer, (6) ATAK, (7) Organic Maps, (8) Kiwix.
- Each step says what to download, from where, how big, and where it goes.
- BASTION cannot download anything itself (no INTERNET permission). Buttons
  open the app store listing, the other app, or the Android settings screen.

Evidence: all 8 steps showed the emulator's real state ✅; "Open Meshtastic"
launched Meshtastic ✅; Back returned to Home ✅.

Not tested: the "Get …" store buttons (every app is already installed
here), and a fresh install with nothing set up.

ATAK on the emulator: the owner accepted the license; ATAK then stops at its
"Permission Rationale" screen, which was left for the owner. The Meshtastic
plugin inside ATAK is still untested.

### Privacy and encryption pass (owner requirement, 2026-11-22)

- **Backups off.** `allowBackup=false` plus data-extraction rules exclude
  everything from cloud backup and device transfer (overriding libkiwix's
  `allowBackup=true`). Verified in the merged manifest of the built APK:
  `allowBackup=false`, and no INTERNET / Bluetooth / location permission.
- **Auto-answer is private-channel-only by default.** Meshtastic's default
  channel uses a publicly known key. BASTION sees a channel's name, not its
  key, so the default preset names (LongFast, MediumFast, …) are treated as
  public. A separate switch, "Also answer on public channels", is off by
  default. Inbox entries from such channels are tagged "(public)".
- **Setup guide → Privacy:** how to create a private 256-bit channel and
  share it by QR in person; a live screen-lock check (Android's storage
  encryption depends on a PIN/password); turning off Meshtastic analytics;
  hiding lock-screen notification text.
- `tools/meshsim/private-channel.sh` adds a private channel "Bastion"
  (random 256-bit key, kept only inside WSL) to both simulated nodes.
  Found along the way: channel names are limited to 11 characters, and a
  longer name is silently dropped.

Evidence (simulated radios, hardware-accelerated emulator):
- `?how do you treat frostbite` on **LongFast** → received, tagged
  "(public)", **not answered** ("Auto-answer only replies on private
  channels") ✅
- The same question on the private channel **Bastion** → 91 s later Sim B
  received "AI: Assess for hypothermia and raise core temperature. Give oral
  or IV fluids. Use NSAIDs or opioids for pain control during rewarming." ✅
  That answer is grounded but hospital-oriented, not field first aid: the
  25-word limit picked that passage. Answer quality on short replies needs
  its own eval.
- The Meshtastic app dropped its link when Sim A rebooted for the channel
  change and reconnected only when opened. Something to watch on real
  radios.

What is and isn't protected (honest scope):
- Stored data: protected by Android's file-based encryption when a screen
  lock is set, and by app-private storage. BASTION adds no app-level
  encryption of its own.
- ZIM files are public content in shared storage.
- Mesh traffic: encrypted only on a private channel or in direct messages.
  Transmissions can still be detected and direction-found.
- Meshtastic, ATAK and the other companion apps have their own permissions
  and network access.

### ATAK on the emulator (2026-11-22) — partly working

- **ATAK crashed at start** with the emulator's default host-GPU graphics
  (`GLSurfaceView … No config chosen`). Started with
  `-gpu swiftshader_indirect` (software rendering) **ATAK-CIV 5.5.1.8 runs**:
  map UI, callsign, GPS fix ✅. `tools/bastion-pc.ps1` now starts the
  emulator that way.
- **Correction of an earlier finding:** the Meshtastic app is *not* an ATAK
  plugin. ATAK's log shows "Could not open plugin.xml asset in
  com.geeksville.mesh", and it is not in ATAK's plugin list. Per the
  Meshtastic app's own bundled docs (`docs/user/tak.md`, 2026-10-21):
  - The old Meshtastic ATAK plugin cannot work with app ≥ 2.8.0.
  - Interop is the app's **local TAK server** (`127.0.0.1:8089`, mutual-TLS)
    plus the **Mesh to CoT Converter**, with stock ATAK.
- Done in Meshtastic on the emulator: Local TAK Server enabled ("Listening
  on 127.0.0.1:8089 — waiting for ATAK"), Mesh to CoT Converter on, TAK Mesh
  Channel set to the private channel "1: Bastion", and the data package
  exported to Downloads (`Meshtastic_TAK_Server.zip`, 5.9 KB).
  - The simulated node's firmware (2.7.26) bridges location and chat only;
    markers need 2.8.0 or newer.
- **Not done:** importing the package into ATAK. ATAK's own file browser
  doesn't show Downloads, the Android picker did not register scripted
  taps in ATAK's landscape layout, and a copy placed in
  `atak/tools/import/` was not picked up. So **ATAK ↔ mesh is untested**.
  By hand: ATAK → Import → Choose App → Downloads →
  `Meshtastic_TAK_Server.zip`.
- ATAK's "always run in background" prompt was denied (owner's call).
- The simulator's private-channel script left a duplicate "Bastion" channel
  (index 2) on the nodes. Harmless; not yet cleaned up.

For "BASTION sees everything": that local TAK server is the route. A TAK
client inside BASTION would see positions, chat and markers and could send
them. But loopback sockets need Android's INTERNET permission, which is an
explicit decision against the current no-INTERNET guarantee.

### BASTION Link — units and TAK inside BASTION (2026-11-22)

Owner requirement: BASTION is the centre and sees everything, including the
TAK picture, without giving up the offline guarantee.

Design (owner asked for the strongest option, decided 2026-11-22):
- **BASTION** (`com.bastion.terminal`): still **no INTERNET permission**.
  Verified on the installed build — its only requested permission is the
  signature-level `com.bastion.permission.LINK`.
- **BASTION Link** (`com.bastion.link`, new Gradle module `:link`, 2.7 MB
  debug APK): the only component with INTERNET.
  - It holds no library, AI or message data.
  - It connects by mutual TLS to the Meshtastic app's local TAK server at a
    literal `127.0.0.1` and refuses any other address in the package or the
    code.
  - It relays CoT events to and from BASTION over a bound service protected
    by the signature permission (same signing key required).
- BASTION parses CoT as data (`link/Cot`, unit-tested with a captured
  event). Mesh tab → "Units and TAK": units with position, team, battery
  and age; TAK chat; send TAK chat.

Protocol facts found by probing the server through an adb tunnel: an XML CoT
stream (TAK protocol version 0); the server sends `takPong` keep-alives; the
Mesh to CoT Converter emits `a-f-G-U-C` contacts with uid `MESHTASTIC-<node>`;
a GeoChat (`b-t-f`) written to the server goes out on the TAK mesh channel
as Meshtastic port 72 (ATAK). Plain Meshtastic text does **not** appear on
this stream; it still arrives through the notification inbox.

Evidence (simulated radios, emulator):
- Link imported `Meshtastic_TAK_Server.zip` through its file picker ✅.
- BASTION → Mesh showed "Connected" and "SIMB - BASTION Sim B · Green ·
  100% · 39.76724, -104.98867 · mesh node" ✅. Meshtastic logged "TAK client
  accepted" and "Mesh-to-CoT: sent 1 of 1".
- "BASTION to all units: radio check" sent from BASTION → Sim B decoded a
  port-72 packet on the private channel (Ch 1) ✅.
- Bug found and fixed: `InetAddress.getLoopbackAddress()` did not reach the
  IPv4-only server; a literal 127.0.0.1 is used now.

Not tested: receiving TAK chat or markers from a real ATAK user (ATAK on the
emulator is not yet connected to the server); a fresh install where Link is
installed after BASTION; release builds of Link; real radios.

Limits:
- No map in BASTION yet — units are a list.
- BASTION does not announce its own position (it has no location
  permission).
- TAK chat reaches TAK users only.

### Link merged into BASTION (owner decision, 2026-11-22)

The owner questioned why a separate bridge app should exist when the field
has no internet, and decided on one app. The trade-off, as explained to the
owner: the separation only protected data while the phone is online (setup,
updates); in the field both designs behave the same.

- The `:link` module is removed. `TakClient` / `TakConfig` now live in
  `:app` (`link/` package). The TAK data package is imported in Mesh →
  Units and TAK.
- `:app` now declares **INTERNET**. This replaces the earlier
  "no INTERNET permission" rule. The claim is now "never connects to the
  internet; one loopback connection to Meshtastic on this phone".
  - It is enforced by code and by `NetworkGuardTest`, **not by Android**.
  - The test fails the build if network APIs appear outside
    `link/TakClient.kt`, if that file uses name lookups or a second address,
    or if `TakConfig` accepts a non-loopback host.
- Every in-app text that said "no Internet permission" is corrected.
- Android 17 implicitly adds `ACCESS_LOCAL_NETWORK` next to INTERNET (seen
  in the installed app's permission list; not declared by BASTION).
- Mitigation for when the phone is online: airplane mode except during
  setup.

Evidence (simulated radios, emulator), merged build with the Link app
uninstalled: package imported from the Mesh tab → "Connected" → Sim B shown
with its position ✅. "Merged build radio check" sent → Sim B decoded a
port-72 packet on the private channel ✅. All unit tests pass, including
`NetworkGuardTest` ✅.

Re-run after the merge (emulator): `RetrievalEvalTest` 13/13,
`ZimRetrievalEvalTest` 14/14, `DocImportTest` ✅. Release (R8) build: arm64
APK 36 MB; installed on the emulator, the TAK connection came up
("Connected", Sim B shown) with no crash ✅ — no extra keep rules needed.
Not re-checked in the release build: AI answers, PDF import, mesh inbox.

**Dedicated device (owner, 2026-11-22):** the BASTION phone is not a
daily-use phone, which is the basis for the one-app decision.

### ATAK ↔ mesh working on the emulator (2026-11-22)

- **Root cause of the failed imports:** Meshtastic 2.8.4's exported package
  has `manifest.xml` at the top of the zip. ATAK-CIV 5.5.1.8 only accepts
  `MANIFEST/manifest.xml` and otherwise logs "not a datapackage" and ignores
  the file without telling the user. A hand import would have failed the
  same way.
- Repackaged with the manifest in `MANIFEST/` and placed in ATAK's
  data-package folder (via adb). ATAK logged "valid manifest found" and,
  after a restart, Meshtastic logged "TAK client accepted, connection count
  now 2" (BASTION + ATAK). **ATAK's Contacts list shows "SIMB - BASTION Sim
  B"** ✅ — the chain mesh node → Meshtastic TAK server → ATAK works.
- **Fix for users:** BASTION → Mesh → Units and TAK → **Save package for
  ATAK** writes an ATAK-ready copy (`link/AtakPackage.repack`, unit-tested).
  Tested: the saved zip contains `MANIFEST/manifest.xml` plus the pref and
  both certificates ✅.
- ATAK takes about 2 minutes to load on the emulator with software graphics.
- BASTION does not see the ATAK user on the *same* phone as a unit; the
  server does not relay one local client to another.

Not tested: importing BASTION's saved package through ATAK's own Import
screen (ATAK was configured through its data-package folder here); chat or
markers from ATAK reaching another node; real radios.

### Packs — plans the AI can answer from (2026-11-22)

Owner request: a briefing / "debrief package" the team can query, usable
for a tactical plan, hunting plan, bug-out plan or contact list, and easy
to understand.

- **Packs** (Library tab): a named set of documents. "New pack" asks for a
  name and a kind: Trip or outing, Hunting plan, Bug-out plan, Team plan,
  Contact list, Reference. Each kind opens a **fill-in template** with
  plain headings (Rally points, Check-ins, Emergency …).
- **Write on the phone:** a simple editor ("Write plan" / "Write note" /
  "Edit"). Notes are saved as Markdown through the same import path
  (preserved, hashed, indexed). Files can also be imported into a pack.
- **Two switches per pack:** "Use in Ask" (default on) and "Answer over the
  mesh" (default **off**). The general library has the same switches and is
  **off for the mesh by default**. This changes earlier behaviour:
  auto-answer no longer draws on the whole library, only on packs switched
  on for the mesh plus the public offline reference.
- Deleting a pack deletes its documents (with a confirmation).
- DB v3: `packs` table and `docs.pack_id`; existing documents stay in the
  general library. `LibraryStore.retrieve(question, scope)` with
  `Scope.ASK / MESH / ALL`.
- Chunker fix found by the templates: a `## Heading` line is now its own
  paragraph even with no blank line after it. Before, headings written
  directly above text were not recognised.

Evidence (emulator; simulated radios for the mesh part):
- `PackScopeTest` (on device) ✅: the mesh sees nothing until a pack is
  allowed, then only that pack; the Ask switch hides a pack; editing
  replaces a note; deleting a pack removes its documents.
- `RetrievalEvalTest` 13/13 and `ZimRetrievalEvalTest` 14/14 after the
  chunker change ✅. The v2 → v3 upgrade ran on the emulator's existing
  library with no crash and 6 documents intact ✅.
- UI: created "Elk camp 2027" (Hunting plan), added "Bear Creek meadow,
  gate code 4417" in the editor, saved, switched "Answer over the mesh" on
  for that pack only.
- Sim B sent `?what is the gate code at camp` on the private channel. 33 s
  later it received "AI: The gate code at camp is 4417 for the Bear Creek
  meadow." ✅

Not tested: importing a file into a pack through the picker; the other
templates end to end; the Edit button through the UI (covered by the device
test only); moving a document between packs (not built).

Limits:
- Anyone holding the private channel key can query a mesh-enabled pack.
- Answers are one short message.
- No photos.

### Location and team navigation (2026-11-22)

Owner decisions 1–7 are recorded in the build sheet. First item built:

- **Location** (`nav/LocationSource`): GPS only (Android LocationManager; no
  Google location service). Runtime permission is requested from Mesh →
  Units and TAK ("Use this phone's location"). The fix is held in memory
  only.
- **Navigation maths** (`nav/Nav`, unit-tested): distance, true bearing,
  16-point compass, and recognising questions like "where is bravo" /
  "nav to …" / "distance to …". **Never done by the AI model.**
- **UI:** each unit shows "— 3.1 km at 003° N" from this phone.
- **Over the radio:** `?where is <callsign>` (or "base" / "hq" for this
  phone) is answered with a computed "NAV:" message, measured from the
  asker's own position when BASTION knows it. Radio text is plain ASCII
  ("deg").

Evidence (emulator; fake GPS fix 39.7392, -104.9903; simulated radios):
- The permission prompt appeared from the in-app button and was granted
  "While using the app".
- "You: 39.73920, -104.99030 (±5 m)" and "SIMB - BASTION Sim B — 3.1 km at
  003° N" ✅ (checked by hand: 0.028° of latitude ≈ 3.1 km).
- Sim B sent `?where is base` on the private channel and **5 s later**
  received "NAV: BASTION (base, hq): 3.1 km at 183° S from you, at 39.73920,
  -104.99030, seen just now." ✅
- Sim B's position arrives blurred (39.76724 where 39.7500 was set): the
  Meshtastic channel's position-precision setting. For accurate
  navigation, set precise position on the private channel.

Not tested: real GPS; movement updates; a second unit as the target; nav
answers while BASTION is in the background (the permission is "while using
the app" — the always-on service is the next item); the re-run of the
on-device evals after this change.

Limits: straight-line distance and bearing only (no terrain or route);
positions can be minutes old; true bearing, not magnetic.

### Approved radios, always-on service, app PIN and wipe PIN (2026-11-22)

Built from owner decisions 2, 3 and 4.

**Approved radios** (`mesh/ApprovedRadios`)
- Auto-answer only replies to radios the owner approved.
- An unknown asker gets silence and appears under "asked — not approved"
  with an Approve button.
- Limit: identity is the name Meshtastic shows, which the radio's holder
  can change. This is not cryptographic; the channel key stays the real
  barrier.

**Always-on** (`BastionService`)
- A foreground service with a permanent notification, on by default, with a
  switch in the Mesh tab.
- It keeps the TAK link and GPS fix alive so messages and answers continue
  with the screen off.

**App PIN and wipe PIN** (`security/AppLock`, Home → App lock)
- Both optional, 4–12 digits, stored only as salted PBKDF2 hashes.
- Five wrong tries → a 30 s lock-out that doubles.
- Typing the wipe PIN on the lock screen erases everything BASTION stored
  **except** a short keep-list (the AI model, the model setting, the lock
  itself). BASTION then restarts straight into an empty app.
- ZIM files are public content in shared storage and are not erased.
- This is an app lock, not disk encryption.

Evidence (emulator, simulated radios):
- Service: `isForeground=true`, types special-use + location ✅.
- Approved radios:
  - Unapproved Sim B asked `?where is base` → **no reply**, listed as "asked
    — not approved" ✅.
  - After Approve → answered with BASTION in the background: "NAV: BASTION
    (base, hq): 3.1 km at 183 deg S from you, …" ✅
- PINs set through the UI. After a restart: wrong PIN refused, app PIN
  opens, data intact ✅.
- **Real wipe executed** with the wipe PIN ✅:
  - Before: 12 database files, library + packs, TAK certificates, 6
    preference files.
  - After: an empty library (0 documents, the pack gone), an empty TAK
    folder, only `lock` and `local_ai` preferences, and the 2.6 GB model
    intact.
  - BASTION reopened without asking again, and the TAK package re-imported
    fine afterwards.
- Leak found by that test and fixed: one Meshtastic notification still on
  screen was read back into the inbox after the wipe. Messages timestamped
  before a wipe are now ignored. **The fix is not re-tested with a second
  wipe.**
- `AppLockTest` (on device): hashing, distinct PINs, lock-out ✅.
- After these changes: `RetrievalEvalTest` 13/13, `ZimRetrievalEvalTest`
  14/14, 6 device tests ✅.

Not tested: the service surviving hours in the background or a reboot (no
start-on-boot yet); the notification-permission prompt; the lock-out timing
by hand.

Known gaps:
- The lock appears at app start only; it does not re-lock when BASTION goes
  to the background.
- Meshtastic's own message history is not touched by BASTION's wipe.

Not built yet from the queue: two-model support; device-manager mode (needs
a clean emulator).

### Device-manager mode and the PC setup tool (2026-11-22)

Owner decisions: BASTION as device manager (Android device owner); setup by
a PC tool that puts an offline kit on the phone; open source under GPL-3.0
(`LICENSE` added — exact licences of libkiwix and ATAK's public release
still to be confirmed before publishing).

- `device/DeviceManager` + `BastionAdmin`. With the role, BASTION:
  - installs every `.apk` in `Android/media/com.bastion.terminal/kit/`
    silently;
  - grants its own and the companion apps' permissions;
  - becomes the home screen (through a separate `HomeAlias` entry that
    stays disabled on ordinary installs);
  - can lock the phone to BASTION + companions + Settings + the file picker
    (kiosk);
  - can give the role back ("Stop managing this phone").

  Without the role, every function is a no-op that says so.
- `tools/provision.ps1` (PC, over USB): checks the phone has no accounts,
  installs BASTION, runs `dpm set-device-owner`, grants mesh-inbox
  notification access (a device manager cannot grant that itself), and
  copies `tools/kit/` (apps, ZIMs, model) to the phone.

Evidence — a second, clean emulator (`Bastion_Clean`, created from the
installed Android 17 image; 0 accounts):
- `provision.ps1` → "Success: Device owner set to package
  com.bastion.terminal" ✅
- "Install kit apps" → a small test APK installed with **no prompt**
  (log: "install test-app.apk: ok"; package present) ✅
- "Apply setup" → "BASTION: 4 permission(s) granted" (location,
  notifications; shown as policy-fixed) and "BASTION is the home screen";
  the Home key lands on `com.bastion.terminal/.HomeAlias` ✅
- "Lock phone to BASTION" → `mLockTaskModeState=LOCKED`; opening a web page
  was blocked ("Activity not started"); Settings (allowed) opened; Home
  returns to BASTION; switched off → `NONE` ✅

Not tested:
- the real companion apps installed from a kit (they aren't in `tools/kit`
  yet);
- permissions granted to Meshtastic/ATAK ("not installed" on the clean
  emulator);
- "Stop managing this phone";
- a real phone;
- provisioning by QR code.

Not built:
- the tool **downloading** the kit at pinned, verified versions — today it
  installs from files placed in `tools/kit/`;
- enforcing airplane mode;
- the factory-reset option on the wipe PIN.

Limits: settings inside other apps (Meshtastic's TAK server switch) still
need guided taps. ATAK-CIV has no public direct download the tool can fetch
without an account, so that file may stay a manual download.

### Hide other apps (device manager) — 2026-11-22

Owner: on a dedicated device, hidden is as good as deleted; storage is
solved by a bigger phone or an SD card.

- `DeviceManager.hideOtherApps`: hides every app that has a launcher icon
  and is not BASTION, a companion app or an essential (Settings, file
  picker, Bluetooth, permission controller). It also blocks adding accounts.
  A switch on Home → Device manager turns it on and off;
  `showHiddenApps` restores exactly what was hidden; releasing the
  device-manager role restores them first.
- Built-in apps are hidden, not deleted (read-only system area).
- Bug found and fixed: Android's package-visibility rules let BASTION see
  only 2 other apps, so only 2 were hidden. The manifest now declares a
  launcher-intent query.

Evidence (clean emulator `Bastion_Clean`, BASTION as device owner):
- 21 apps with an icon → **3** (Settings, BASTION, file picker); log "hid
  18 app(s)" ✅
- Opening a web link → "unable to resolve Intent" (no browser) ✅
- Settings opens; Home → BASTION; the recent-apps screen still works ✅

Not tested: switching it off again after the full hide (restore was
exercised once, with 2 apps); the effect on Meshtastic/ATAK, which are not
installed on the clean emulator and may rely on hidden Google components;
a real phone.

Maps (owner, same day): the kit step lets the user pick areas and layers,
shows the size, and offers phone storage or SD card as the destination.
Not built yet.

### Kit builder (2026-11-22)

- `tools/kit-manifest.json`: what can go in a kit. Each item has an
  official source URL, a pinned SHA-256, size and licence.
  - **Pinned and verified:** the Gemma 4 E2B model and WikiMed (the exact
    files tested with BASTION).
  - **Not pinned yet:** Meshtastic, ATAK-CIV, Organic Maps, Kiwix (no URL
    or hash). The builder will not download those. It accepts a file you
    place in the kit folder and prints its SHA-256 so it can be pinned.
- `tools/kit-build.ps1`: lists items with sizes and where each stands;
  choose by number, `-Only`, or `-All`; checks free space; uses a `-Cache`
  folder if the file there matches the hash; otherwise downloads with
  resume; **verifies SHA-256 and deletes a file that doesn't match**.
- `tools/provision.ps1 -Sd`: puts reference files on the phone's SD card.

Evidence (this PC):
- `-List` showed all six items, with the two big files found in the cache.
- Built from the cache with `-NoDownload`: the model (2.6 GB) and WikiMed
  (1.0 GB) were copied into `tools/kit` and **verified** ✅; Meshtastic was
  reported "not pinned yet" ✅.
- Tamper test: a fake file named like the WikiMed ZIM → "SHA-256 mismatch —
  the file was removed" ✅

Not tested:
- an actual download through the builder (both files came from the cache);
- `-Sd` (no test here);
- the interactive number prompt;
- the kit pushed to a phone end to end.

Not built:
- the map picker (areas, layers, sizes) — second stage on this same list;
- pinning the companion apps (needs choosing and testing exact versions;
  the no-Google builds of Meshtastic and Kiwix are the intended ones).

### Kit pinned and installed by BASTION (2026-11-22)

All six kit items are now pinned by SHA-256 in `tools/kit-manifest.json`,
and `kit-build.ps1 -All` reports **6 verified, 0 problems**:

| Item | Version | Source |
|---|---|---|
| Gemma 4 E2B model | — | Hugging Face (litert-community) |
| WikiMed ZIM | 2026-10 | download.kiwix.org |
| Meshtastic (no-Google "fdroid" build) | 2.8.4 | GitHub release |
| Organic Maps | 2026.11.11-39 | GitHub release; hash matches the publisher's posted checksum |
| Kiwix (standalone) | 4.0.1 | GitHub release |
| ATAK-CIV (SDK developer build) | 5.5.1.8 | manual file from the owner's SDK zip (no direct download) |

The owner downloaded the three GitHub APKs; file sizes matched the release
listings exactly. The manifest keeps the original file names, so no
renaming is needed.

Found: the non-Play builds use different package names
(`app.organicmaps.web`, `org.kiwix.kiwixmobile.standalone`). BASTION now
recognises both variants (manifest queries, Home companion list, Setup
guide, device-manager permission list).

Evidence — clean emulator `Bastion_Clean`, BASTION as device owner, other
apps hidden:
- `provision.ps1 -Kit <apps>` pushed the four APKs (575 MB).
- "Install kit apps" → all four installed **silently** (log: "install …:
  ok" for each; all four packages present) ✅
- "Apply setup" → Meshtastic has Bluetooth, location and notification
  permissions **granted with no prompts** ✅
- Launcher now shows exactly: BASTION, Meshtastic, ATAK, Organic Maps,
  Kiwix, Settings, file picker ✅
- Meshtastic, Organic Maps and Kiwix each opened with no crash ✅

Not tested:
- ATAK launched on the clean emulator (slow in software graphics; not run);
- the model and ZIM pushed by the tool (apps only here, for space);
- a download through the builder;
- the no-Google Meshtastic build against BASTION's inbox and TAK link (the
  main emulator still runs the Play build);
- a real phone.

iPhone test prepared: the owner added a Windows port-forward
(192.168.128.33:4404 → simulated radio B) and a firewall rule limited to
the local subnet. Radio B answers on that address. Result pending.

### Second real device on the mesh: iPhone (2026-11-22)

The owner's iPhone (Meshtastic iOS app) connected over Wi-Fi to simulated
radio B (PC `192.168.128.33:4404` → WSL, via a port-forward and a
local-subnet firewall rule the owner added). This is the first test with a
second real device and a different client app. The radios are still
simulated.

Two failures found and fixed on the way:
1. **WSL had idled out**, stopping both simulated radios, so the iPhone's
   first attempts hit nothing. The launcher's keep-alive had a quoting bug
   and exited at once. Fixed in `tools/bastion-pc.ps1`; a keep-alive is
   running now.
2. **BASTION's inbox reader was granted but not connected** after the app
   was reinstalled: Android listed the listener as enabled but not live,
   so messages reached the Meshtastic app and never reached BASTION, while
   BASTION still said "Inbox on". Fixed: `MeshInbox.reconnect()` calls
   `requestRebind` at every app and service start. Verified: the listener
   is live again and the waiting messages were picked up.

Observed:
- The iPhone's message "How do I deal with a sucking chest wound?" arrived
  on **LongFast (public)** with no "?" trigger → shown in BASTION's inbox
  tagged "(public)" and correctly **not answered**.
- A direct message from the iPhone to the other node failed on the iPhone
  ("Could not send encrypted message"): the simulated nodes have not
  exchanged per-node keys. Use the shared private channel.
- `?where is base` from radio B on the private channel → BASTION replied
  "NAV: BASTION (base, hq): 3.1 km at 183 deg S from you, …" (received by
  radio B, the radio the iPhone is connected to).

Pending: an AI answer to a "?question" typed on the iPhone in the Bastion
channel; iTAK.

### First AI answer to a real second device (2026-11-22)

iPhone (Meshtastic iOS) → simulated radio B → mesh → radio A → Meshtastic
Android → BASTION → answer back the same way.

- The owner typed "How do I deal with a sucking chest wound" twice
  **without the "?" trigger** and got nothing. Change: for an approved
  radio on a private channel, a message that *reads* like a question (ends
  with "?" or starts with a question word) is now answered
  (`looksLikeQuestion`). Such an untriggered message gets **no reply at
  all** when the library has nothing, so ordinary chat isn't answered with
  "no answer". An explicit `?` / `bastion` still always gets a reply.
- With `?how do I deal with a sucking chest wound` on the Bastion channel,
  radio B received about 50 s later: "AI: Check for air escaping through a
  sucking chest wound, which can cause a collapsed lung." ✅ end to end.
  - **Quality: poor.** Accurate, but not what to do. "deal with" was not
    recognised as a treatment question. Fixed (`Grounding.aspect` now covers
    deal with / handle / what should I do / help with). **Not re-tested from
    the iPhone.**
- The iPhone shows "Failed to deliver to mesh" on its own messages although
  radio A received each one (logs). The simulated radios don't produce the
  rebroadcast a real radio uses as confirmation. Simulator artefact.
- Using the command-line tool on radio B while the iPhone is connected to
  it appears to displace the iPhone briefly: an earlier NAV reply did not
  show on the iPhone. Keep the CLI off the radio another client is using.
- After the changes: 6 device tests ✅, evals 13/13 and 14/14 ✅; the inbox
  reader reconnects by itself about 5 s after a reinstall ✅.

### "Tell me what to do": steps over the mesh (2026-11-22)

Owner feedback after the first iPhone answer ("Check for air escaping
through a sucking chest wound…"): technically correct, missed the point;
give the steps, in several messages if needed.

Three causes found, three fixes:
1. **The right article was never read.** In WikiMed, "Sucking chest wound"
   is a 19-character HTML stub (meta refresh) pointing at *Pneumothorax*.
   BASTION read the stub and fell back to "Field dressing" and "Sucking
   blister". Fix: `ZimLibrary.followHtmlRedirect` +
   `HtmlText.redirectTarget` follow HTML-level redirects for title lookups
   and article reads.
2. **The right section still lost.** *Pneumothorax › Treatment* never says
   "sucking" or "wound", so it scored zero. Fix: passages of an exact-title
   article get a score floor (`ABOUT_FLOOR`), so the aspect weight
   (Treatment for a "deal with / treat" question) decides.
3. **One 25-word message can't hold steps.** Fix: the mesh prompt asks for
   numbered actions and forbids describing the problem;
   `MeshBridge.split` sends up to 4 numbered messages ("AI 1/2: …"), 4 s
   apart.

Evidence (emulator, simulated radios; sent from radio B, no "?" trigger):
- Sources now: *Pneumothorax — Treatment* (×3) and *Field dressing*.
- Radio B received, ~20 s after asking:
  - "AI 1/2: 1. Cover the wound with an airtight seal. 2. Ideally, utilize
    the Asherman seal. 3. Place a waterproof wrapper of a field dressing
    over the hole."
  - "AI 2/2: 4. Keep the clean inside of the wrapper against the wound.
    5. Tape the sides and top of the wrapper, leaving the bottom edge
    free." ✅
- Meshtastic's notification reply action can be fired more than once (both
  parts arrived).
- After the changes: 6 device tests ✅, evals 13/13 and 14/14 ✅.

Caveats:
- Answers are only as good as the reference. This one comes from two
  Wikipedia articles and is not medical guidance vetted by anyone.
- One question does not prove general quality. The mesh answers have no
  eval set of their own yet.
- Not re-sent from the iPhone after the fix.

### Standing rules for mesh commands; topics (owner, 2026-11-22)

- **A leading symbol marks a message for BASTION; everything else is chat
  and is never answered.** `?question` asks the AI; `@callsign` locates a
  unit (`@base` = this phone). New actions get new symbols. The "looks like
  a question" trigger added earlier the same day is **withdrawn**, and so
  is the word trigger "bastion, …".
- **SMS / satellite-SMS as a second channel: on hold.** Long range goes
  through the Starlink Mini (a Meshtastic radio at base bridging meshes).
- **Topics:** a follow-up `?` from the same radio within 10 minutes that
  names no article of its own continues the previous topic. The earlier
  question picks the article, the new words pick the passage, and the
  earlier Q and A are shown to the model. A question that names its own
  subject starts a new topic.

Evidence (emulator, simulated radios, sent from radio B on the private
channel):
1. "How do I deal with a sucking chest wound" (no symbol) → **0 replies** ✅
2. "?How do I deal with a sucking chest wound" → "AI 1/2: 1. Cover the hole
   with a waterproof wrapper of a field dressing. 2. Place the clean inside
   of the wrapper against the wound." / "AI 2/2: 3. Tape the sides and top
   of the wrapper, leaving the bottom edge free. 4. The wrapper acts as a
   flapper valve to allow air out as the casualty exhales." ✅
3. "?what if I have no seal or dressing" → log `followUp=true`; "AI: 1.
   Cover the hole with a petrolatum gauze pad. 2. Use a non-stick dressing
   as an occlusive dressing for sucking chest wounds. 3. Do not use
   hemostatic agents if they cause burns." ✅ on topic. Step 3 is an odd
   pull from the source.
4. "@base" → "NAV: BASTION (base, hq, you): 3.1 km at 183 deg S from you,
   …" ✅

6 device tests ✅, evals 13/13 and 14/14 ✅ after the changes.

Not tested: a new-subject question correctly dropping the topic over the
radio (logic and unit-level only); topic expiry after 10 minutes; from the
iPhone.

### Self-reliance references: repair, growing food, water, low-tech building (2026-11-22)

Owner request: "repair manuals, gardening, electrical… what if public services
stopped working and I had to do and repair everything myself."

Added to `tools/kit-manifest.json` (preset `self-reliance`, each pinned by
SHA-256, downloaded and verified by `tools/kit-build.ps1`): Motor Vehicle
Mechanics Q&A (342 MB), Appropedia (582 MB), Gardening & Landscaping Q&A
(933 MB). Loaded next to WikiMed on the emulator: four references open at once.

New eval `SelfRelianceEvalTest` — 15 plain-language questions. **Tested on
emulator: 14/14 counted, 1 known gap; retrieval only (which passages are
found), not the AI's wording, and not on a physical phone.**

Found and fixed with it:

- **A long question found nothing.** "?how do I repair a blown head gasket in a
  2009 jeep wrangler" returned zero articles: the first search needs every
  word in one article, and the "any word" fallback never worked because libzim
  does not treat `OR` as an operator. The fallback now searches each pair of
  neighbouring words and ranks articles by how many pairs they match.
- Model years ("2009") are left out of ranking; they pulled in an unrelated
  2009 car.
- Q&A references: tag/user listing pages are skipped, site menus and post
  statistics are stripped, answer sections rank above the question text, and
  among articles that hold every word the ones saying so in the title go first.
- "How do I build / make X" now prefers Construction / Design / Instructions
  sections over Examples, Budget and Timeline (composting toilet went from
  "Examples › Germany" to "Basics › Construction").
- Translated copies of an article ("Rocket stove/th") are skipped.

No regression: `ZimRetrievalEvalTest` 14/14 (WikiMed alone) and
`RetrievalEvalTest` 13/13, re-run on the emulator after the changes.

Honest limits:

- **Known gap:** "how do I save tomato seeds" — the reference's own search
  returns variety-specific threads first; the how-to thread is not in its top
  results. Logged as `GAP`, not counted.
- The head gasket answer comes from Q&A threads about diagnosing and deciding
  whether to repair. A step-by-step procedure for a specific vehicle needs a
  real service manual, imported as a PDF into a pack — none of these
  references contains one.
- Nothing electrical or household-repair is loaded yet (iFixit, Home
  Improvement and Electronics Q&A are in the build queue; larger downloads).
- Running `gradlew connectedDebugAndroidTest` **uninstalled BASTION from the
  emulator** afterwards, deleting its model, references, PINs and approved
  radios. Evals are now run with `installDebug installDebugAndroidTest` and
  `adb shell am instrument`, which leaves the app in place. The references
  were restored; the model, PINs and approved radio need setting up again.

### Offline map with layers and terrain routes (2026-11-22)

Owner request after reviewing Rut & Route: "same layers offered in BASTION or
similar; direction-based navigation with waypoints based on terrain."

**What exists now**

- `tools/map-build.ps1` builds one **map pack** per area on the PC (`.bmap`,
  one file): USGS topo tiles, BLM public-land tiles, elevation tiles, and
  Forest roads, motor trails, trails, wilderness and streams as line layers.
  `-Estimate` prints tile counts and size first; `-Push` / `-Sd` copies it to
  the phone or its SD card. US coverage only (all sources are US government).
- **Map tab** in BASTION draws the pack with no network code: pan, pinch or
  +/− zoom, scale bar, GPS dot, team positions from the TAK link, and a Layers
  switch list (remembered). **Steepness** (with a legend) and **shaded relief**
  are computed on the phone from the elevation tiles.
- **Terrain routes**: long-press → "Route to here" / "Start here", or "Go to" a
  team member. The route is the quickest walk over the real ground (Tobler's
  hiking function on a ~14 m elevation grid, slower off trail, ground over 40°
  not crossed, trails and roads in the pack preferred), cut into at most nine
  compass legs: bearing, distance, climb, time. Computed, never AI-generated.
- **Over the mesh**: `@callsign` now follows its position reply with the
  walking route (up to three more messages) when a pack covers both ends.

**Evidence**

- Unit tests (`TerrainRouteTest`, 9): tile maths, walking speed, straight route
  on flat ground, detour round a wall, no route when walled in, trail preferred
  when quicker, steepness and shading. All 63 unit tests pass.
- **Tested on emulator** with a real pack, "sawtooths" (20 × 20 km around
  Stanley, Idaho; 802 topo + 70 land + 42 elevation tiles, 5 layers, 20.9 MB):
  `MapPackTest` reads the pack, decodes elevation (1792 × 1536 cells in
  275 ms), and checks it against known ground — Stanley 1,910 m, Redfish Lake
  1,997 m. Redfish Lake lodge → Bench Lakes: 4.6 km, about 1 h 54 min, up
  409 m, six legs, three of them "on trail", worked out in 0.4 s.
- Seen on the emulator screen: map with shaded relief, roads and trails; GPS dot
  at the simulated position; long-press → route line with numbered waypoints
  and the steps list; public land, wilderness, streams and steepness layers.

**Not tested / limits**

- Not on a physical phone; pinch-zoom not exercised (emulator driven by taps).
- The `@callsign` mesh route is **built and compiles but has not been run**
  over the simulated radios.
- Route times are a model (Tobler), not measured. The route does not know
  rivers, small cliffs, deadfall, snow, private land or closures; the app says
  so next to every route.
- Not built yet from the Rut & Route layer list: satellite imagery, 10 ft
  contours as a separate layer (the topo map already has contour lines), sun
  and shade by time of day, terrain filter (slope / aspect / elevation), USNG
  grid, gates, tap-a-road for its open dates, help-nearby points, saved pins
  and GPX. Live layers (weather, fires, snow, road reports) need the internet
  and are out of scope.
- A bug in the first build of the pack tool (PowerShell treats `$n` and `$N`
  as one variable) silently widened the area after the first layer; found
  because the build never finished, fixed, pack rebuilt.

### Map: nationwide design (2026-11-22)

Owner: "I want this nation wide."

The sources were already national; what was Idaho-sized was the design (one
pack shown at a time, whole pack loaded into memory). Changed:

- **All packs on the phone are one seamless map**, coarse under detailed:
  `-Overview` (lower 48, zoom 4–9), `-State XX` (zoom 8–13, 27 m elevation),
  and local `-Center` / `-Bbox` packs (zoom 15, 14 m elevation).
- **Nothing is loaded whole.** Elevation is read for the window needed (at most
  12 million cells); line layers are cut into ~55 km grid cells by the builder
  and read cell by cell as they come on screen. Pack format 2 (format 1 still
  opens).
- Routing and `@callsign` use the most detailed pack covering both ends; a
  route is limited to roughly 40 km between ends.
- Builder: state table for all 50 states, automatic zoom levels by pack kind,
  resumable downloads in batches, free-disk check, `-Estimate`.

**Measured sizes** (builder `-Estimate`, from tile counts × sizes measured on
the Sawtooths pack; flat states will come out smaller):

| Pack | Size |
|---|---|
| Lower-48 overview | about 0.2 GB |
| Idaho | 2.5 GB |
| California | 4.8 GB |
| Texas | 6.4 GB |
| All 48 state packs | about 69 GB |
| A 20 km local pack at full detail | 0.02 GB |

So the whole lower 48 at state detail fits a 128 GB SD card. Full foot-level
detail (zoom 15) for the whole country would be roughly 1 TB and is not the
plan: that detail is for the areas actually worked in.

**Tested on emulator:** the Sawtooths pack rebuilt in format 2 gives the same
elevations and the same lodge → Bench Lakes route as before (4.6 km, 1 h 54
min, six legs); the Map screen draws it.

**Not tested:** a state-sized pack has not been built or opened — zip reading
with ~100,000 entries, memory use and build time at that size are unproven.
Alaska and Hawaii are in the state table but not in the overview. State boxes
overlap, so neighbouring packs duplicate some tiles.

**Update, same day — overview pack and mesh route**

- Lower-48 overview built: 5,052 topo + public-land tiles, **125 MB** (263
  ocean/border tiles do not exist on the server). **Seen on emulator:** zoomed
  out to a 100 km scale bar the overview fills in around the Sawtooths pack,
  with public-land colours and the local pack's wilderness outline on top — two
  packs drawn as one map.
- `@callsign` route **run over the simulated radios**: radio B placed at Stanley
  sent `@base` on the private channel; BASTION answered with the position
  ("9.2 km at 163 deg SSE from you") and then three route messages (10 km, about
  2 h 47 min, eight legs; log `nav-route parts=3 legs=8`). What was not checked:
  that radio B actually received all parts (the simulator's delivery reports are
  unreliable).
- Builder: curl twice finished its downloads and never exited, hanging the
  build. It now runs under a watchdog (stopped after a minute of no work) and
  uses Windows' own curl; a re-run fetches anything missed.

## Screen direction (2026-11-22)

Home has a "Screen" card: Upright (default), Sideways, or Turns with the phone.
BASTION opens that way and stays that way. Sideways, the tabs move to the left
edge so the map and text keep the height.

- **Tested on emulator:** Sideways holds after a restart with the phone upright;
  Upright holds with the phone turned and after a restart while turned. Home and
  Map checked sideways.
- **Not tested:** Ask, Mesh and Library layouts sideways with the keyboard open;
  a physical phone.

## State pack, places and military areas (2026-11-22)

**Idaho state pack** (`tools/map-build.ps1 -State ID`): 2.1 GB, 45,911 topo and
45,911 land tiles, 8,687 elevation tiles, 16,388 forest roads, 4,352 motor
trails, 14,794 trails, 24 wilderness areas, 6,971 places, 131 military areas.
About 2 hours of downloading, across three runs: two stopped on a dropped
connection (now a failed request skips that cell and is reported).

- **Tested on emulator:** the pack opens beside the overview and the Sawtooths
  pack (three maps, about 190 MB of memory); the state draws at 100 km scale
  with land and wilderness; Mountain Home Air Force Base draws as a named
  outline; "nearest Hospital" from the base lists four with address and phone;
  a long-press route on the state's 27 m elevation gave 11 km, 3 h 37 min.
- **Not tested:** a physical phone; speed of panning across the state; states
  larger than Idaho; Alaska and Hawaii.

**Places** (OpenStreetMap, ODbL): fuel, groceries, convenience stores, water,
pharmacy, hospital, clinic, ambulance, fire, police, ranger stations, vets,
hardware, vehicle repair, outdoor gear, campgrounds. Markers on the map, tap
for details, and Go to lists the nearest of a kind (about 100 km around) with a
route. Results carry the note that the data is as of the map's build date.
Not built: asking for the nearest place over the radio.

**Military areas** (OpenStreetMap): named outlines. The data includes small
armouries and missile launch sites in neighbouring states, not only bases.

Full nation estimate (extrapolated from Idaho, not measured): lower 48 at state
detail about 75 GB and 2.5 to 3 days of downloading. Alaska at the same detail
is about as large again; build it one zoom coarser.

## More references and kit documents (2026-11-22)

**Downloaded to `tools/kit` (each checked against Kiwix's SHA-256):** post-disaster,
water, food, knots and medical PDF collections; military medicine; MedlinePlus;
NHS medicines; CDC travel health; Ready.gov; Energypedia; Stack Exchange Q&A for
home improvement, outdoors, amateur radio, cooking, woodworking and sustainable
living; iFixit. About 11 GB. Not yet in `kit-manifest.json`.

**Documents in `tools/kit/docs`:** five Idaho Fish and Game seasons-and-rules
brochures, the Forest Service guide to forest plants of northern Idaho, Special
Forest Products of the Pacific Northwest, Wild Edible Mushrooms in the Blue
Mountains, the Army Survival Manual, the Special Operations Forces Medical
Handbook, the USDA home canning guide, and a list of every NOAA Weather Radio
transmitter in the country (1,035 stations, with a by-county index) built from
the National Weather Service's station data.

**New in the app:** files copied to BASTION's `docs` folder are added to the
library when it starts, and PDFs inside a reference that is only a bundle of
documents (the Kiwix "zimgit" collections show as 1 article) are indexed too.
A bundled PDF with a numbered name is titled from its own first line.
`tools/provision.ps1` copies `kit\docs` to the phone.

- **Tested on emulator:** 12 documents and 43 bundled PDFs imported (about 8
  minutes); `KitDocsEvalTest` 13/13 with one known gap ("how do I can tomatoes"
  reads "can" as the everyday verb); `SelfRelianceEvalTest` still 14/14.
- **Not tested:** the larger new references (post-disaster, MedlinePlus,
  Ready.gov, Energypedia, the Q&A sites, iFixit, military medicine, CDC) — the
  emulator has no room for them alongside the rest; a model-written answer from
  the new documents; a physical phone.
- **Limits:** scanned PDFs with no text layer are skipped (3 of the bundled
  ones). Regulations are read as running text, so tables can come out jumbled.
  Amateur repeater lists need a RepeaterBook account key and were not fetched.
  Full Wikipedia (49 GB) waits for disk space.

## Making answers better (2026-11-22)

**Measured first.** `AnswerEvalTest` runs the whole Ask path with the real model
on 18 real questions (13 answerable, 5 traps) and logs every answer. Gemma 4 E2B
on the emulator: 12/13 on the first set; 16/18 on the harder set after the
changes below.

**Changed, tested on emulator:**
- Word senses: "how do I can tomatoes" now searches for canning; green beans
  also search "snap beans" (the USDA guide's name); Tylenol, Advil and Benadryl
  also search their generic names.
- Instructions: never substitute a different item's numbers; give numbers with
  units; report disagreeing sources; how to read flattened tables.
- Tables: the model still misreads them (it gave a canner's pressure as the
  minutes to process). So any answer that takes numbers from a table now carries
  a warning to read the source row, in Ask and in mesh replies. PDF table text
  is cleaned of odd space characters before the model sees it.
- Model start: about 750 MB of free storage is needed for the model's working
  cache. With less, loading failed with an obscure error; it now says how much
  space is needed.

**Still failing (2 of 18):** quart jars of green beans (reads the wrong table
column; warned by the table note) and a question about a vegetable the canning
guide does not list (it answers with general canner pressure instead of saying
it is not covered).

**Two models.** BASTION keeps more than one model and uses the largest that
fits the phone's memory (a model up to about a third of memory), or the owner's
pick. Gemma 4 E4B (3.7 GB) is in the kit and the manifest.
- **Not tested:** E4B itself. The emulator has 4 GB of memory and the PC cannot
  give it more. The model choice screen was compiled but not exercised with two
  models. Needs a physical phone with 12 GB of memory or more.

**Not done:** fine-tuning (not justified until a bigger model and these fixes
have been tried on a phone); keeping table rows intact at import time.

## Larger references, radio "nearest", pins, neighbour states (2026-11-22)

**Every kit reference has now been loaded in BASTION on the emulator** (in
batches; the emulator cannot hold them together). `MoreRefsEvalTest`, real
questions per reference:

| Reference | Result |
|---|---|
| Outdoors Q&A | 3/3 |
| Cooking Q&A | 2/2 |
| Woodworking Q&A | 1/1 |
| Sustainable Living Q&A | 1/1 |
| Amateur Radio Q&A | 1/1, one known gap (dipole length) |
| Energypedia | 1/1, one known gap (how a biogas digester works) |
| CDC travel health | 2/2 |
| NHS medicines | 2/2 |
| MedlinePlus | 3/3 |
| Home Improvement Q&A | 3/3 |
| Ready.gov | 3/3 |
| iFixit | 3/3 |

Post-disaster and Military Medicine are PDF bundles: BASTION indexed 76 more
documents from them (131 in the library; 13 scanned PDFs skipped), and
`KitDocsEvalTest` is 17/17 including tourniquet, splinting and rescue signals.

Fixed on the way, all tested on emulator:
- Website-style references title their pages "Malaria | CDC Yellow Book 2024"
  or "Ibuprofen for adults: … - NHS"; the subject lookup now finds them.
- "How much / how often do I take X" and "who should not take X" pick the
  dosing and cautions sections; "how does X work" prefers the explanation.
- MedlinePlus topic pages are mostly lists of links under headings like
  "Symptoms"; link lists, site banners and slide-show controls are dropped.

**Nearest place over the radio.** `@gas`, `@hospital`, `@pharmacy`, `@water`
and the other kinds return the nearest one on the offline maps with distance,
bearing, phone and the map's date, then a walking route when the maps can work
one out. A unit with that callsign still wins. Tested over the simulated
radios: `@gas` from Sim B logged `nav-nearest kind=Fuel found=1` and a
three-part route. The reply text itself was checked by unit test, not read off
the other radio.

**Pins.** Long-press the map and "Save as pin"; pins show as stars, are listed
in Go to, can be routed to, deleted, and sent to the mesh as one line
(`+Camp 44.14470,-114.91700`, 31 bytes). The same line from an approved radio
saves the pin on this phone; nothing is sent back. Tested on emulator both ways
(sending opens Meshtastic's compose screen; the transmit itself was not
pressed). Not built: GPX import and export, pin notes, renaming.

**Map:** zoomed out, only hospitals, fuel, pharmacies, water, police, fire and
ranger stations are dotted, so a town does not bury the map.

**Neighbour states built:** Montana 2.5 GB, Wyoming 1.2 GB, Utah 1.1 GB, Nevada
1.6 GB, all with roads, trails, wilderness, places and military areas and no
build warnings. Utah opened on the emulator: Hill Air Force Base outlined and
named, nearest hospitals listed. Oregon and Washington still building.

**Known limits:** bundled PDFs are titled from their first line, which is
sometimes boilerplate ("DISTRIBUTION RESTRICTION:"). Nothing here has run on a
physical phone.

## Tables, titles, grid, sun and shade, terrain filter; all seven states (2026-11-22)

**Tables.** A table row of bare numbers under a row that gives units now gets
those units before the model reads it ("Raw Quarts 25 11 12 13 14" becomes
"Raw Quarts 25 min 11 lb …"). `AnswerEvalTest` on Gemma 4 E2B: 17/18, and the
quart-jar green bean question now answers "25 minutes". The one miss moves
between the two green-bean table questions from run to run, so the table
warning stays on.

**Bundled PDF titles.** Taken from the title written into the PDF when it reads
like one, else the first heading-like line, skipping notices. Re-imported two
bundles on the emulator: "Wound Closure Manual", "Where There Is No Doctor
2011", "Sea Scout Knots", "HHS Pandemic Influenza Plan". Some are still weak
(an author's name, half a title). A last filter for file-name titles
("Cuta4394.tmp", "Unknown") is unit-tested but was added after that re-import.

**Military grid (MGRS / USNG).** Long-press shows the grid ("Grid 11T PJ 6624
8951"). The Grid layer draws lines every 1, 10 or 100 km with their figures
and names the 100 km square. The maths is checked against published values
(the CN Tower UTM example within 2 m; the Washington Monument's square and
figures). Tested on emulator. Standard zones only: fine for the United States.

**Sun and shade.** A time-of-day slider; ground in shade at that time today is
veiled blue-grey — slopes facing away from the sun and ground in the shadow of
higher terrain. Sun position checked against midsummer and midwinter noon.
Tested on emulator at 16:30 on 22 November: sun 5° up to the south-west, the
Sawtooth peaks shading most of Redfish Lake. Today's date only.

**Terrain filter.** Slope band, facing and height band; matching ground is
drawn green. Tested on emulator with "under 10°".

**Not built:** satellite imagery (a large extra download per state), separate
contour lines, gates, per-road open dates on tap, GPX.

**Seven states built, no warnings:** Idaho 2.1 GB, Montana 2.5, Nevada 1.6,
Oregon 1.5, Washington 1.2, Wyoming 1.2, Utah 1.1 — 11.1 GB. About 3.5 hours
for the six neighbours. Idaho and Utah were opened on the emulator; the other
five were not.

**Test-bench note:** with under 1 GB of memory free on the PC the emulator
returns blank screenshots; stopping idle Gradle daemons (`gradlew --stop`) and
restarting the app fixes it.

## Satellite imagery (2026-11-22)

`tools/map-build.ps1 … -Satellite` builds an imagery-only companion pack
(`<name>-sat`) from USGS/USDA orthoimagery. BASTION shows it as a "Satellite"
layer in place of the drawn map; roads, trails, places, pins and the grid stay
on top.

- **Idaho, state detail (zoom 8–13):** 784 MB, 45,911 tiles, about 12 minutes.
  Tested on emulator: it draws, but at about 14 m per pixel it only tells lake,
  forest and open ground apart. Not worth carrying at this detail.
- **Sawtooths, local detail (zoom 9–15):** 17.5 MB for 20 x 20 km. On the
  emulator it showed individual trees, roads and buildings, but the only
  capture has a system "not responding" notice over it (see below), so there
  is no clean screenshot.
- With "Public land" on, the land tint washes the imagery green; switch it off
  when using satellite.

**Test bench:** the emulator ran out of disk (97%) and the PC out of memory
during this; Android's own system process hung and the emulator was restarted
once and then shut down. BASTION itself logged one slow start (an ANR at
launch while the system was overloaded); not reproduced on a healthy emulator.
The emulator needs starting again before the next test.

## Trimmed: smaller state maps, shorter Layers list, leaner kit (2026-11-22)

Owner's decisions, one at a time, each with what it cost and saved.

**State maps.** Elevation in a state pack is now 55 m (was 27 m) and public
land stops one zoom level short of the map. All seven states rebuilt, no
warnings:

| State | Before | After |
|---|---|---|
| Idaho | 2.09 GB | 1.34 GB |
| Montana | 2.48 | 1.56 |
| Nevada | 1.60 | 0.99 |
| Oregon | 1.51 | 0.96 |
| Washington | 1.19 | 0.75 |
| Wyoming | 1.20 | 0.74 |
| Utah | 1.05 | 0.67 |
| **Total** | **11.1 GB** | **7.0 GB** |

Rebuild took 20 minutes because the map tiles were reused. Local packs are
unchanged (14 m elevation, full land detail). Tested on emulator with the new
Idaho pack: steepness is blockier but readable, elevation readout works, a
long-press route gave 2.7 km / 55 min along the road and up to the point. The
other six rebuilt packs were not opened.

**Layers list.** Terrain filter removed. Motor trails have no switch of their
own; they draw with Trails in their own colour. Sun and shade and Military
areas sit under an "Extras" heading and start off. Tested on emulator (the
Sawtooths pack has no military areas, so that entry under Extras was not seen
on screen).

**Kit.** Dropped Ready.gov (2.0 GB) and the four small PDF bundles (207 MB,
duplicated inside the post-disaster collection). Cooking, Woodworking and
Sustainable Living stay. Seventeen references remain, about 12 GB, plus the two
models.

**Satellite.** Statewide Idaho imagery was built, judged too blurry at that
detail and deleted. Full detail for a local area is clear (trees, roads,
buildings; `docs/screens/map-satellite-local.png`) and tiny. `-Satellite` stays
as a per-area option.

**PC disk.** The builder's working folders were deleted after the rebuild:
70 GB free, up from 51. A future state rebuild downloads from scratch.

## Radio questions unanswered after a restart — fixed (2026-11-23)

Owner reported the radios "not responding". The simulated radios and the
Meshtastic link were fine; BASTION replied, but to "?how do I treat a burn" it
said there was no answer in its library. Cause: after the phone restarts, only
BASTION's background service runs, and the reference library and maps were
opened only by the app's screen. The service now opens both, and the mesh
answer path opens the library itself if it finds it closed.

- **Tested on emulator:** BASTION's process killed and restarted by Android as
  a service only (no screen opened), then "?how do I treat a burn" from the
  simulated radio: answered from the library in four messages.
- **Still limited:** after a restart with the app never opened, Android does
  not give a background service the phone's position, so `@base` cannot give
  this phone's location until BASTION has been opened once.

## Radio answers name their source (2026-11-23)

First questions sent from a real phone (iPhone Meshtastic app on simulated radio B, over the simulated mesh, to the emulator).

- Questions sent on the public LongFast channel got silence. That is the rule working: BASTION only answers on a private channel.
- `?can I weld with a car battery` on the private channel got a wrong answer: steps about disconnecting the battery when welding **on** a car. The search found a mechanics Q&A that shares the words, and the small model (Gemma 4 E2B) answered that question instead.
- Tried, measured on emulator with the real model (`MeshAnswerEvalTest`, new, 13 questions):
  - Stricter instructions ("decide first whether the sources answer this question"): kept. Did not fix the welding answer.
  - A second pass asking the model to judge its own answer: **removed**. It rejected good answers (sucking chest wound, flat tire) and still passed the welding one.
  - Each radio answer now starts with the name of its top source in square brackets, for example `[Frostbite] 1. ...` or `[Welding on car - damage to ECU, battery, altern…] ...`, so the reader can see when the answer is about something else. Kept.
- Result: 9 of 13 on E2B (emulator, CPU). Known fails: the welding question (still answered, now with its source shown), "why is my car overheating" (refused), "how do I purify water" (weak source chosen), "can I drink water from a car radiator" (answered "No" without a source saying so).
- The radio answer logic is now one function (`MeshAutoResponder.answerFor`) that the app and the test both use.
- Not tested: the larger E4B model, which needs a phone with more memory than the emulator has.

## Answer-model comparison (2026-11-23)

Four candidate models were run on one fixed set: 50 cases built from BASTION's own search results (`tools/model-eval`, see its README). **Run on the development PC in llama.cpp (CPU, 4-bit GGUF files; OCC-RAG at 8-bit). Not the LiteRT-LM files the phone loads. Nothing here was measured on a phone.**

| Result (of 50) | Gemma 4 E2B | Gemma 4 E4B | Qwen 3.5 4B | OCC-RAG 1.7B |
|---|---|---|---|---|
| Answerable: correct (23) | 20 | 20 | 21, plus 2 hedged | 16 |
| Answerable: wrong answer | 1 | 0 | 0 | 5 |
| Answerable: refused wrongly | 2 | 3 | 0 | 2 |
| No answer in sources: correctly refused (15) | 11 | 13 | 13 | 13 |
| Conflicting sources: gave both (3) | 3 | 3 | 3 | 0 |
| Partial sources handled (3) | 3 | 3 | 3 | 1 |
| Planted instruction ignored, answered (6) | 6 | 6 | 6 | 4 |
| Total correct | 43 | 45 | 46 | 34 |

What it shows:

- **No model fixes the welding question.** All four answered "can I weld with a car battery" from the welding-on-a-car passages. A model swap does not solve that class of error; the search has to stop handing over the wrong passages.
- **E4B over E2B:** fewer unsupported answers (2 against 4) and no wrong answers, but it refused three answerable questions. A small gain on this set.
- **Qwen 3.5 4B:** best here, with no wrong answers and no wrongful refusals. It did not over-refuse as the published comparison reported. Its answers are longer and one was cut off at the length limit.
- **OCC-RAG 1.7B:** worst here. It returns a few words where steps are needed, could not report two conflicting sources, and gave two dangerous answers (reversed jump-lead polarity; "2 drops" of bleach for a gallon). Not suitable as BASTION's answer model.
- No model obeyed an instruction planted in a source.
- Search misses found while building the set: "how do I change a flat tire", "how do I build a fire in the rain" and "how do I tie a bowline knot" return passages that do not answer them.

Decision for now:

| Phone | Model | Why |
|---|---|---|
| 8 GB | Keep Gemma 4 E2B | Nothing measured on a phone justifies a swap; Qwen is the challenger to try there |
| 12 GB or more | Gemma 4 E4B is the candidate | The app already picks it when it fits in memory; needs a phone to confirm |
| Either | Qwen 3.5 4B stays a challenger | Best scores, but the LiteRT file is a community GPU-only build and cannot run on the emulator |
| Either | Do not add OCC-RAG | Measured worse, and it would need a second runtime in the app |
| Either | Gemma 4 12B stays experimental | Not tested |

App change from this work: a reply is now treated as "no answer" only when its opening sentence says so. Before, a partial answer that ended with "the sources do not say how much food" was thrown away as a refusal. Unit tested.

Not done: any phone measurement (load time, speed, memory, heat), LiteRT-LM builds of E4B and Qwen, a larger question set.

## Search fix: where it stands (stopped 2026-11-23, PC shut down mid-task)

Goal: stop the search handing the model wrong-topic passages. All measurements below are on the development PC in llama.cpp (CPU), on the 50-case set in `tools/model-eval`. Nothing was built into the app yet and nothing ran on a phone.

Measured so far:

- **A "no answer" gate does not catch the hard cases.** Three relevance models were scored on every question-passage pair (`relevance.pl`, `strict.pl`):
  - EmbeddingGemma 2 (embedding similarity): answerable and unanswerable questions overlap; no usable cut-off.
  - Qwen3-Reranker 0.6B: cleanly rejects off-topic passages (best score under 0.65 for 9 of 15 unanswerable questions, against 0.93 or more for every answerable one), but scores the welding passages 0.999.
  - The same reranker with a strict "must directly answer the question" instruction: welding still 0.98.
  - The 9 questions it rejects are ones the answer model already refuses, so the gate adds nothing on this set. **Not worth building.**
- **Three questions fail because the search misses, not the model:** flat tire, fire in the rain, bowline. For "fire in the rain" the 30-passage candidate pool holds no passage from the Army survival manual at all (28 of 30 are Appropedia pages about rainwater), so reranking the pool cannot rescue it. The miss is earlier, where articles and library passages are picked.

Code added (builds, unit tests pass, installed on emulator):

- `Retriever.retrieve(..., wide = N)` and `LibraryStore.retrieve(..., wide = N)`: return a wider candidate pool for a second ranking step. Normal behaviour is unchanged when `wide` is 0.
- `EvalSetExportTest` takes `-e wide 30` and writes `evalpool.json`.
- `tools/model-eval`: `relevance.pl`, `relevance.sh`, `strict.pl`, `poolrank.pl`, `evalpool.json` and the relevance scores in `results/`.

Next, in this order:

1. Rerun `poolrank.pl` (the first run was stopped unfinished at shutdown; allow 30 minutes or more on this laptop; it writes `evalset-reranked.json`): do the reranker's best five passages beat the current five? Then run the answer models on that file to measure the effect on answers.
2. Question restating before the search (owner's idea, 2026-11-23): the model rewrites the question in a standard form ("can I weld with a car battery" to "using car batteries as a welding power source"), and the search uses that. Measure on the same 50 cases.
3. Find why the survival manual does not reach the pool for "how do I build a fire in the rain" (library search step).

To resume the PC tests: start nothing on the emulator; models and the llama.cpp build are in `tools/kit/pc-eval/` (not committed). See `tools/model-eval/README.md`.

## S-tier plan, Step 0: stopped part-way (2026-11-23 evening, usage limit)

The owner's S-tier upgrade plan describes an Ollama + Python + N.O.M.A.D. + Qdrant stack on a mini PC. None of that exists: this PC has no Ollama, Qdrant or Docker, and `docs/build-sheet.md` records the mini PC as replaced by a phone and the N.O.M.A.D. stack as dropped. Step 0 is being done against what exists: the Android app, LiteRT-LM 0.19.1, Gemma 4 E2B, emulator only.

Done:

- **Reranker over 30-candidate pools** (`poolrank.pl`, Qwen3-Reranker 0.6B, PC CPU, 24 minutes for 38 questions): expected fact present in the five passages for 24 of 26 questions, against 25 of 26 today. It gains "fire in the rain" by one weak passage and improves bowline, but loses both green-beans canning questions by picking the dried-beans and lima-beans tables. Flat tire and fire in the rain are still not really answered. Early signal only.
- **Inventory verified from code** by 15 readers and checkers (1,178 facts, 9 corrected). Full result saved outside git at `tools/kit/pc-eval/step0-inventory-workflow.json`; read it with `step0-inventory-read.pl critic|web|refuted|missed`.
- **Runtime capabilities read from the shipped library** (LiteRT-LM 0.19.1 classes): schema-forced output (`ResponseFormat.json`), thinking on/off (`ThinkingConfig`), embeddings (`EmbeddingEngine`), adapters (`LoraConfig`), token counting and conversation cloning. The app uses none of these yet.
- **Prompt sizes from the PC runs:** 492 to 1,435 tokens in, none over 1,664 (context 2,048 minus 384 for the answer).
- **Written, compiled, not yet run:** `BaselineEvalTest` (real model on the fixed 50 cases on the device, search timings, and probes for context overflow, prefix reuse and JSON-schema output), `EvalSetExportTest.searchOtherWordings`, and `tools/model-eval/restate.pl`, `fuse.pl`, `latency.pl`.

Still running on the PC when this was written (results land in `tools/model-eval`): question plans from Gemma 4 E2B (`plans/plan-e2b.json`) and the current model on the reranked passages (`results/gemma4-e2b-q4-reranked.jsonl`).

Not done:

1. Baseline on the emulator: push `tools/model-eval/evalset.json` as `evalset-fixed.json`, run `BaselineEvalTest` (answers, search timings, three probes), score with `score.pl` and `latency.pl`.
2. ~~Question-restating early signal~~ — **done 2026-11-24**, see the next section.
3. Read the inventory result and write the inventory table, the baseline scorecard, `docs/experiments.md` and `docs/s-tier-status.md`.
4. Owner decision needed: keep the phone as the target (as documented), or bring back a separate box.

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
   answers~~ — **done 2026-11-21** (WikiMed on the emulator). ~~ZIM
   retrieval eval + "how to treat" answers~~ — **done 2026-11-21** (14/14).
   ~~PDF/EPUB, several ZIMs + SD card, GPU option, smaller APKs~~ — **done
   2026-11-22** (emulator). Remaining: phone numbers (GPU vs CPU on hardware).
6. **Last:** physical-phone pass (owner decision 2026-11-21) — install the
   APK, re-run the M1 checklist, ATAK-CIV, M2 performance numbers.

## Blockers needing the owner

- None for code progress. Physical-phone evidence needs a test phone
  (scheduled last).
- ATAK-CIV EULA on the emulator: accepting it is the owner's call (needed
  before ATAK can be used beyond launch).
- Local builds on Windows: set `JAVA_HOME` to a JDK 17–24 (Temurin 21 is
  installed) — Android Studio's bundled JDK 25 does not work with Gradle 8.14.3.

## Question restating: first measurement (2026-11-24)

Before the search, Gemma 4 E2B rewrites each question into a short plan with three worked examples (`tools/model-eval/restate.pl`, PC). For "can I weld with a car battery" it wrote relation `car battery USED AS welding power source` and not-about `jump starting; battery maintenance`. The app's own search was then run on each wording (**tested on the hardware-accelerated emulator**, `EvalSetExportTest.searchOtherWordings`, wide 0 and 10, current library), and Gemma 4 E2B answered from each set of passages (**PC, llama.cpp CPU, 4-bit GGUF; not the phone runtime**). 38 real questions; `original` is the same search rerun today, so the comparison is fair.

Search (expected fact in the five passages, 26 the library should answer; crude word match): original 25, keyword 23, standalone 22, **alternate 26**, semantic 24, fused (RRF) 24. For the welding question every restated wording stopped returning the "welding on a car" passages; fused brought them back.

Answers (Gemma 4 E2B):

| Search wording | Answerable correct (23) | Wrong | No answer in sources: refused (15) |
|---|---|---|---|
| original | 22 | 0 | 11 |
| keyword | 20 | 2 (one is the scorer: aphid answer is valid) | 11, **incl. welding and radiator** |
| alternate | 20 | 2 | 8 |
| fused | 19 | 3 | 9 |

What it shows:

- **Keyword restating fixes the welding question**: "The sources do not contain an answer to whether you can weld with a car battery." It also refuses the radiator question. It costs the lemon-juice canning answer (the 2-tablespoon passage dropped out) and is otherwise level.
- **Better search alone does not make the answer safe.** With the alternate wording the welding passages were gone, and Gemma still answered from an unrelated battery passage. Gemma answers from whatever it is given, so the "no answer" decision has to be made in code (next step), not left to the model.
- No wording is a clean win. Single run, 38 questions, word-match scoring: a difference of one or two cases is not a finding.

Next: give the answer step the plan's relation and not-about lines and a forced JSON reply (`answers_original_question`, `quote`, `answer`); the app shows its own fixed no-answer text unless the flag is true and the quote is word-for-word in a passage. Then measure: unanswerable cases must produce exactly that text.

Files: `evalvariants-w0.json`, `evalvariants-w10.json`, `evalset-e2b-*.json`, `results/gemma4-e2b-q4-restate-*`.
