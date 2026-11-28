# ChatGPT task queue

One task at a time, each on its own branch from `origin/claude/hello-e9r4rq` (or, where a task says so, from
the branch it builds on). ChatGPT works in its worktree `C:\Users\ccie5\StudioProjects\Bastion-chatgpt` with
**Android Studio, the emulator and `adb`** as well as the PC tools; it never pushes or merges. Claude reviews
each branch, the owner decides merges. Each task file holds the full spec.

**ChatGPT should never be idle.** The tasks below are independent — pick any one, and whenever
one finishes there is always another that is not blocked. Keep this list deep (owner 2026-11-26: "NPU should
always be going and we should keep ChatGPT queue full at all times... make it a heavy-lifting workhorse").

**>>> CURRENT HEAD:**
- **`kit-3` is now UNBLOCKED (highest-value pick) — Claude transferred the 46 kit-4/5 files into the kit on
  2026-11-27** (`kit.py verify` on Claude's `tools/kit/docs` = 0 missing, 137 verified, 0 mismatched; the 8
  UNKNOWN are documented owner-gated gaps, not a stop condition — see `kit-3.md`). It loads the whole new kit on
  the emulator and measures retrieval on the 2026-11-26 additions — the core coverage question. **Run it next.**
- **Section D (app quality & UX wave)** — the independent backlog. `apk-pdfbox-audit-1` is **PC-only**, so run it
  (or any Tier-1 PC task) while the emulator is busy with `kit-3`.

Section A is fully merged/closed/done — nothing to pick there. Keep one emulator task and, when possible, one
PC task going at all times.

**Merged 2026-11-27 (Claude reviewed + merged, branch pushed):** `route-perf-1`, `kit-4`, `kit-5`, `kit-6`,
`kitconv-1`, `mapverify-1`, `evalclean-1`. **`release-smoke-1` DONE 2026-11-27 (Claude reviewed, merge-ready,
awaiting owner merge):** tools-only R8 smoke automation; release build + R8 + signature, ZIM search and PDF
import all PASS; the AI-answer leg FAILS on the emulator (WebGPU 600s timeout, then CPU litertlm cache-dir +
storage pressure) — an emulator-environment limit, not a build defect; real AI-answer validation is parked for
the physical phones (section C). **`chunk-tables-1` CLOSED 2026-11-27 (dead end, Claude
measured it):** the task and its re-scope both measure ~+0 on questions-v2. The failing table questions
(canning pressures, doses) fail because PDF extraction scrambles the table columns *before* chunking
(`DocExtract.normalizePdf` flattens whitespace), plus SUBJECT/REFUSAL/OFF_TARGET — none of which
header-with-row chunking fixes. The real upstream lever is PDF table extraction, **deliberately not
built**: table data here is life-safety (wrong canning PSI → botulism, wrong dose → overdose), so
abstaining (NO_ANSWER) is the correct behavior and a flaky auto-parser would create exactly the
wrong-answer risk the fail-closed design prevents. If a specific critical table is ever wanted,
hand-curate it as verified Markdown (card-style), not an auto-parser.

**Backlog (low priority; spec when it reaches the head):** `meshsim-eval-1` — stand up Meshtasticator's
discrete-event sim and stress-test the radio card-responder (`mesh/MeshAutoResponder`, `RadioCardFallback`)
across a multi-hop mesh: does a 4×200-byte card answer actually propagate, hop-limit, airtime cost. PC/Python,
no app code. It's life-safety-adjacent and only 2-node-tested today, so the coverage gap is real but not urgent.

## A. Complete — nothing to pick here (history)

All Section-A tasks are merged, closed, or done. Do not re-pick any of these.

| Task | Status |
|---|---|
| `kit-1`, `device-1` | **Merged** 2026-11-26 (Claude reviewed). |
| `route-perf-1`, `mapverify-1`, `kitconv-1`, `kit-4`, `kit-5`, `kit-6`, `evalclean-1` | **Merged** 2026-11-27 (Claude reviewed, pushed). |
| `release-smoke-1` | **Done** 2026-11-27 — Claude reviewed, **merge-ready, awaiting owner merge**. R8 build/sign + ZIM + PDF PASS; AI-answer leg fails on the emulator only (WebGPU timeout + litertlm cache/storage), parked for the physical phones (C). |
| `chunk-tables-1` | **Closed** 2026-11-27 — dead end, Claude measured ~+0 (see note above). |

## B. Kit download + measure chain (sequential, after `kit-1`)

| Task | What | Depends on |
|---|---|---|
| `kit-2` | Exclude list for unvetted bundle documents (e.g. "under the tongue diagnosis") | kit-1 |
| `kit-4` | Fetch batch 1 (contingency gap audit, approved with two holds and three conditions) | kit-1 |
| `kit-5` | Fetch batches 2 and 3 (weapons care, trapping, leather, metals; trades, cradle to grave, law) | kit-4 |
| `kit-3` | Load the whole new kit on the emulator; `KitRetrievalEvalTest` on the new topics | ✅ **RUNNABLE** — kit-1,2,4,5 merged; Claude transferred the 46 files 2026-11-27 (verify clean). |

## D. App quality & UX wave — runnable now, independent  ← PICK FROM HERE (current head)

Owner 2026-11-26: keep a deep, never-ending queue of real S-tier work — GUI/UX, optimization, compression, features (not make-work). Grounded in a codebase survey; every task file carries file:line evidence, a verify bar, and guardrails keeping it off the owner/Claude-only zones with all evals green. Tier 1 are mutually independent.

| Task | What | Where |
|---|---|---|
| `a11y-insets-1` | Fix edge-to-edge insets on Reader / ZIM / Lock (content draws under the status bar on API 35) | Emulator. |
| `perf-statfs-1` | Move the Home free-space `StatFs` off the main thread (cold-start disk I/O) | Emulator. |
| `perf-strictmode-1` | Debug-only StrictMode to surface main-thread I/O + leaks (release untouched) | Emulator. |
| `test-lint-1` | Turn on Android Lint as a gate + checked-in baseline (no lint/CI today) | PC. |
| `a11y-semantics-1` | Roles, content descriptions, text state on cards + status dots (only 2 a11y labels app-wide) | Emulator. |
| `gui-icon-1` | Add an adaptive launcher icon (app ships with the stock placeholder) | Emulator. |
| `perf-libsearch-1` | Debounce Library live search (runs FTS on every keystroke) | Emulator. |
| `mem-tilecache-1` | Size-aware map tile bitmap cache (count-based now → up to ~51 MB) | Emulator. |
| `feat-reader-textsize-1` | Reader A−/A+ text size, persisted (outdoors/gloved legibility) | Emulator. |
| `apk-pdfbox-audit-1` | Audit (measure + propose) the PdfBox CMap/AFM assets in the release APK; no removal | **PC only** — good when the emulator is busy. |

Deeper backlog, staged from the same survey (Claude writes the full spec when one reaches the head): `test-ui-smoke-1` (first Compose UI test), `feat-reader-find-1` (in-doc find next/prev), `apk-shrink-1` (resource shrink + locale filter), `mem-rgb565-1` (RGB_565 base tiles — pairs with `mem-tilecache-1`), `startup-baselineprofile-1` (Baseline Profile), `mem-reader-1` (bounded Reader read of large originals).

## C. Parked — needs the physical phones (do **not** put at the queue head)

- `device-1` **Verified** rows (the end-to-end functional pass on a named phone) — Galaxy S24+ (12 GB) and Pixel 9a (8 GB) in transit.
- GPU and NPU performance numbers on real hardware (emulator GPU only initialises, then fails).
- Cross-state routing on a phone (first run on the S24+/Pixel).
- Real-radio mesh (two LoRa nodes) — all mesh evidence so far is the WSL simulator.

`map-1` (cross-state routing) — **Merged** 2026-11-26.

## Coordination

- **The emulator is one shared resource.** The emulator tasks (`device-1`, `chunk-tables-1`, `release-smoke-1`,
  `kit-3`) can only run one at a time, and not while Claude or the owner is running an emulator eval. ChatGPT
  does one task at a time, so its own emulator work is already serial; Claude will not start an emulator run
  while an emulator task is in flight.
- **Instrumented tests:** always `adb install -r` + `am instrument` (CLAUDE.md), **never** `connectedAndroidTest`
  — it uninstalls the app and the imported model.
- **Android-source overlap:** `route-perf-1` (map/), `chunk-tables-1` (data/) and `device-1` (new hw/ + one UI
  entry) touch `android/` in different places; Claude reviews and merges them one at a time to avoid conflicts.
  Within wave D: `perf-statfs-1` and `a11y-semantics-1` both touch `ui/HomeScreen.kt` (different lines);
  `mem-tilecache-1` and the staged `mem-rgb565-1` both touch `ui/MapScreen.kt` (do together or sequence). Each
  task branches from `origin/claude/hello-e9r4rq`; Claude merges them serially and resolves any overlap.

## Not for ChatGPT (Claude / Fable / owner)

Card and flow text (life-safety content); the "never show a wrong answer" tuning (`ai/CheckedAnswer.kt`,
`ai/QueryPlan.kt`); keep-or-kill decisions; the Meshtastic device pages for the owner's radio (needs the model
name); permission requests to Hesperian, WHO, CVM and FAO; and the storage decision for the large ZIMs.
