# Outpost Zero Build Sheet — Android pivot (source of truth)

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
| App | Native Kotlin + Jetpack Compose, package `com.outpostzero.terminal` | Scaffolded; see status.md for build evidence |
| Toolchain | Gradle 8.14.3 (wrapper) · AGP 8.7.3 · Kotlin 2.1.0 · Compose BOM 2024.12.01 · min SDK 29 / target 35 | Pinned in `android/gradle/libs.versions.toml` |
| Library index | SQLite FTS4, on-device; originals preserved + SHA-256 provenance | Implemented (v0.1) |
| Import formats | `.txt`, `.md` via SAF picker | Implemented. PDF/EPUB/OCR/ZIM: separate, future capabilities |
| Offline guarantee | No INTERNET permission in the manifest | Implemented |
| Local inference | **Not decided.** Candidates: llama.cpp (JNI/Android bindings), Google MediaPipe LLM Inference, MLC-LLM | Milestone 2 decision, after measuring on real hardware |
| Model | **Not decided.** Phone-RAM-dependent; file size ≠ runtime RAM; license review required before any bundling | Milestone 2 |
| Embeddings/reranking | Deferred — FTS keyword search first; add only when measured retrieval quality demands it | Deliberate |
| Companion apps | Kiwix Android, ATAK-CIV, Meshtastic, Organic Maps — detected & launched, no data integration claimed yet | v0.1 detects/launches |

## Hardware (owned vs. undecided)

**Owned:** Jackery Explorer 300-class + SolarSaga 100W panel, Starlink Mini
(each with case), iPhone (owner's current phone — possible second comms
endpoint later, subject to its actual TAK/Meshtastic app capabilities).

**Not purchased / not decided:** the Android phone itself (model, RAM,
storage, quantity — decide *after* Milestone 2 measurements; expect the RAM
requirement to be set by the model chosen, not by the UI), LoRa node
(Wio Tracker L1 Pro is the candidate — verify hardware/firmware/region
before configuring), GMRS handhelds, RTL-SDR, rugged phone case.

**Budget posture:** historical kit budgets ($900–1,300 KISS; $3,050
discarded DIY) are context, not an approved phone or dev budget. Phone
recommendation comes with measured workload numbers at the end of
Milestone 2.

## Open decisions (tracked)

1. Phone model/RAM/storage/Android version/quantity — after M2 measurements.
2. Inference runtime + model + licensing — M2.
3. ZIM-wide search vs. selected imported documents first — current answer:
   selected documents first; ZIM retrieval is its own capability.
4. Depth of ATAK/Meshtastic integration in first release — M3.
5. Outpost Zero as launcher — not now; revisit after M3.
6. Voice in/out — optional later investigation.
7. Distribution beyond prototype APK (store/sideload/kit image) — M4.
8. Custom ROM — not selected; requires a demonstrated need.

## Licensing watchlist (for resale ambitions)

To review before bundling anything: model licenses (per-model), ZIM content
licenses (CC-BY-SA for Wikipedia and friends), map data (ODbL for OSM),
ATAK-CIV distribution terms (TAK.gov), Meshtastic (GPL-3.0 app — launch/
integrate via supported interfaces, don't embed), Kiwix (GPL-3.0 — same).
A public download is not automatically commercial-redistribution rights.
