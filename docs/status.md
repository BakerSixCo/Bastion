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
