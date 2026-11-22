# BASTION Build Sheet — Android pivot (source of truth)

**Date:** 2026-11-21. **Supersedes** the hardware-kit sheets now in
[archive/](archive/) (12V DIY build; KISS three-module build). This pivot
replaces the mini-PC terminal with an Android phone. The physical kit
concepts that survive (case, owned power, nested-bag EMP doctrine) return at
Milestone 4; nothing here re-opens the discarded 12V bus or home automation.

## Reconciliation with the previous sheets

| Old element | Disposition |
|---|---|
| Mini PC terminal (MeLE/N150, Pelican 1500) | **Replaced** by an Android phone (model not yet selected) |
| Jackery 300-class + SolarSaga 100W | **Owned** (confirmed by owner, 30 Jul 2026 session). Kit power module; the phone runs on its own battery |
| Starlink Mini | **Owned** (same confirmation). Optional uplink, own case; irrelevant to field-mode software |
| Wio Tracker L1 Pro, Baofeng/Wouxun handhelds, RTL-SDR v4, switched USB hub | **Planned, not verified owned.** Verify inventory before any test that assumes them |
| Kiwix / local AI / library | **Carried over** as Android components (see reuse assessment) |
| Home Assistant / home agent / N.O.M.A.D. stack | **Dropped** — out of scope |
| Nested Faraday-bag EMP doctrine | **Carried over** as a physical requirement; never a software claim |

## Current requirements (phone-centered)

1. Local AI assistant running on the phone (Milestone 2).
2. Offline reference library stored on the phone; search grounded in it with
   sources the user can open (Milestone 1 — in progress).
3. Offline mapping alongside ATAK/TAK (Milestone 3).
4. Meshtastic messaging + location via external LoRa hardware (Milestone 3).
5. Practical UI that can become a repeatable kit/install package (Milestone 4).
6. Field functions work with no internet, no cellular, no companion computer.

## Software choices (current)

| Component | Choice | Status |
|---|---|---|
| App | Native Kotlin + Jetpack Compose, package `com.bastion.terminal` | Scaffolded; see status.md for build evidence |
| Toolchain | Gradle 8.14.3 (wrapper) · AGP 8.7.3 · Kotlin 2.4.21 · Compose BOM 2024.12.01 · min SDK 29 / target 35 | Pinned in `android/gradle/libs.versions.toml` (Kotlin raised from 2.1.0 for LiteRT-LM, 2026-11-21) |
| Library index | SQLite FTS4, on-device; originals preserved + SHA-256 provenance | Implemented (v0.1) |
| Packs | Named sets of documents with fill-in templates (trip, hunting, bug-out, team, contacts, reference), an on-phone editor, and per-pack "Use in Ask" / "Answer over the mesh" switches (mesh off by default, including the general library) | Implemented 2026-11-22; tested on emulator incl. a mesh question answered from a pack |
| Import formats | `.txt`, `.md`, **PDF** (text layer, PdfBox-Android 2.0.27.0, Apache-2.0), **EPUB** (spine order) via SAF picker; original kept, extracted text indexed | Implemented 2026-11-22; tested on emulator (`DocImportTest`). No OCR: scanned PDFs are rejected with a clear message |
| Offline guarantee | Works with no internet; never connects to the internet. INTERNET is declared only for the loopback link to Meshtastic's local TAK server (owner decision 2026-11-22, replacing "no INTERNET permission"); enforced by code + `NetworkGuardTest`, not by Android | Implemented |
| Local inference | **Provisional: Google LiteRT-LM 0.19.1** (official Kotlin artifact; MediaPipe LLM Inference is maintenance-only; MLC-LLM dropped). Behind the `LocalModel` interface; **llama.cpp** (GGUF via JNI) is the fallback adapter if model choice requires it | Spike works on the emulator (2026-11-21, see status.md); final after physical-phone measurements. Compute setting Auto (GPU with a one-token warm-up, else CPU) / GPU / CPU — emulator falls back to CPU; GPU untested on hardware |
| Model | **Provisional: Gemma 4 E2B** (`litert-community/gemma-4-E2B-it-litert-lm`, file `gemma-4-E2B-it.litertlm`, 2,588,147,712 bytes, **Apache-2.0**, not gated). User-imported, never bundled. Smaller fallback for 4 GB phones: Gemma 3 1B (Gemma license — review before use) | Emulator: ~1.5 GB app RAM; phone numbers pending |
| Retrieval for AI answers | Paragraph-aligned passages (DB v2) ranked by BM25 × coordination + query-term coverage, from FTS4 `matchinfo` | Implemented 2026-11-21; 13/13 on `RetrievalEvalTest` |
| Embeddings/reranking | Deferred — lexical retrieval first; add only when measured retrieval quality demands it (known gap: no synonyms) | Deliberate |
| Offline reference in AI answers | libkiwix 2.7.0 reads every Kiwix ZIM in BASTION's folders (phone storage + SD card); ZIM full-text search → article text → same chunking/BM25 as the library; cited articles open in BASTION's reader | Implemented 2026-11-21; tested with WikiMed (Wikipedia Medicine, 1.0 GB) on the emulator; exact-title lookup + section-aware ranking, 14/14 on `ZimRetrievalEvalTest`. Field target: `wikipedia_en_top1m_nopic` (16 GB) or full English |
| Mesh (M3) | Meshtastic app owns the radio; BASTION sends via Android text share (`MeshBridge`), user presses Send in Meshtastic. Receiving: BASTION reads Meshtastic's message notifications (`MeshInboxService`; user grants notification access; no network/Bluetooth permission), since Meshtastic 2.8.4 exports no third-party service. PC dev: two simulated nodes in WSL (`tools/meshsim`) | Send, inbox and the ask-over-mesh loop tested on emulator against simulated nodes 2026-11-22; no real radio yet |
| TAK / units (M3) | BASTION connects directly (loopback, mutual TLS) to the Meshtastic app's local TAK server; shows units + TAK chat and sends TAK chat. The separate "BASTION Link" app was merged in at the owner's decision | Tested on emulator with simulated nodes 2026-11-22; real ATAK users and radios untested |
| Companion apps | Kiwix Android, ATAK-CIV, Meshtastic, Organic Maps — detected & launched; Kiwix's own ZIMs are not shared with BASTION (scoped storage), so a ZIM is placed in BASTION's folder too | v0.1 detects/launches |

## Hardware (owned vs. undecided)

**Owned:** Jackery Explorer 300-class + SolarSaga 100W panel, Starlink Mini
(each with case), iPhone (owner's current phone — possible second comms
endpoint later, subject to its actual TAK/Meshtastic app capabilities).

**Not purchased / not decided:** the Android phone itself (model, RAM,
storage, quantity — decide *after* Milestone 2 measurements; expect the RAM
requirement to be set by the model chosen, not by the UI), LoRa node
(Wio Tracker L1 Pro is the candidate — verify hardware/firmware/region
before configuring), GMRS handhelds, RTL-SDR, rugged phone case.

**R&D buy scope (owner decision, 2026-11-22):** only the phone and the
mesh/ATAK hardware for now — nothing else until R&D says otherwise.
- **Dedicated device (owner, 2026-11-22):** the BASTION phone is not a daily-use
  phone. Keep it in airplane mode except during setup and updates; this is why
  one app holding the loopback-only network permission is acceptable.
- **Phone:** cheapest phone that meets the floor — **not a flagship**
  (owner decision, 2026-11-22). Floor: ≥8 GB RAM (12 preferred), Android 10+
  64-bit, a mid-range chip (Dimensity 7300 / Snapdragon 6 Gen 3 class or
  better), microSD slot preferred, 6.5"+ screen. Candidates: CMF Phone 2 Pro
  (8/12 GB, microSD), Moto G Stylus 2025 (8 GB, microSD, IP68), or a used
  12 GB flagship from 2022–23. The floor is a judgment from emulator numbers;
  the phone pass decides whether it holds.
- **Meshtastic:** two LoRa nodes (Wio Tracker L1 Pro candidate; US 915 MHz)
  so messaging can be tested end to end; the owner's iPhone can drive the
  second node via the Meshtastic iOS app.
- **ATAK:** software only (ATAK-CIV, free); no hardware to buy.
- Deferred: power bank, Faraday bags, GMRS, RTL-SDR, rugged case.

**Budget posture:** historical kit budgets ($900–1,300 KISS; $3,050
discarded DIY) are context, not an approved phone or dev budget. Phone
recommendation comes with measured workload numbers at the end of
Milestone 2.

## Open decisions (tracked)

1. Phone model/RAM/storage/Android version/quantity — after M2 measurements.
2. Inference runtime + model + licensing — M2. Provisionally LiteRT-LM +
   Gemma 4 E2B (Apache-2.0); confirm on the physical phone (CPU vs GPU).
3. ZIM-wide search vs. selected imported documents first — current answer:
   selected documents first; ZIM retrieval is its own capability.
4. Depth of ATAK/Meshtastic integration in first release — M3.
5. BASTION as launcher — not now; revisit after M3.
6. Voice in/out — optional later investigation.
7. Distribution beyond prototype APK (store/sideload/kit image) — M4.
8. Custom ROM — not selected; requires a demonstrated need.

## Licensing watchlist (for resale ambitions)

To review before bundling anything: model licenses (per-model), ZIM content
licenses (CC-BY-SA for Wikipedia and friends), map data (ODbL for OSM),
ATAK-CIV distribution terms (TAK.gov), Meshtastic (GPL-3.0 app — launch/
integrate via supported interfaces, don't embed), Kiwix (GPL-3.0 — same).
A public download is not automatically commercial-redistribution rights.

## Owner decisions, 2026-11-22 (asked one by one)

| # | Decision | Answer |
|---|---|---|
| 1 | BASTION uses the phone's GPS location (adds the location permission) | **Yes** — needed for navigation, map, sharing own position |
| 2 | Who may query the AI over the radio | **Approved radios only** (allow-list; unknown radios get no answer) |
| 3 | Run in the background permanently (foreground service + permanent notification) | **Yes** — dedicated device |
| 4 | Emergency wipe (duress PIN erasing library, packs, inbox, TAK certificates) | **Yes**, optional, off until set |
| 5 | Voice between team members | **Keep the plan**: Meshtastic text/positions, GMRS voice, OpenMANET later |
| 6 | AI model | **Middle ground**: support two models side by side (small/fast for radio answers, larger for on-phone Ask); pick the larger model after phone measurements |
| 7 | Audience | **Team now, product later** — no shortcuts that would block selling it (licences, signing key, setup polish stay on the list) |

Also discussed: **BASTION as the OS.** Recommendation accepted in principle:
(1) BASTION as the home screen, (2) BASTION as Android *device owner* on the
dedicated phone (installs/updates companion apps, grants their permissions,
locks the phone to chosen apps, enforces airplane mode, can wipe) — both on
stock Android, after the real-phone pass. A custom ROM only if selling
multiple units and level 2 hits a wall (GrapheneOS is Pixel-only; patching
burden). Emulator graphics: ATAK crashes in every hardware-GPU mode on this
PC, so the emulator runs software graphics when ATAK is needed.

Build queue from these: location + team navigation → approved-radio list →
always-on service → two-model support → wipe PIN → offline map → missed
check-in alarm → structured reports → pack sharing.
