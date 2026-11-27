# ChatGPT task queue

One task at a time, each on its own branch from `origin/claude/hello-e9r4rq` (or, where a task says so, from
the branch it builds on). ChatGPT works in its worktree `C:\Users\ccie5\StudioProjects\Bastion-chatgpt` with
**Android Studio, the emulator and `adb`** as well as the PC tools; it never pushes or merges. Claude reviews
each branch, the owner decides merges. Each task file holds the full spec.

**ChatGPT should never be idle.** The "Runnable now" tasks below are independent — pick any one, and whenever
one finishes there is always another that is not blocked. Keep this list deep (owner 2026-11-26: "NPU should
always be going and we should keep ChatGPT queue full at all times... make it a heavy-lifting workhorse").

## A. Runnable now — independent (pick any; always have one going)

| Task | What | Where it runs |
|---|---|---|
| `kit-6` | Add the owner-approved operational manuals (ATP 6-02.53 trim + 4 Army + USAMRIID) and close the 17 manifest gaps so `kit.py verify` shows 0 unknown | PC (Python). |
| ~~`device-1`~~ | Phone-check tool + `devices.md` + report tool | **Merged** 2026-11-26 (Claude reviewed). Verified phone rows wait on the phones (C). |
| ~~`kit-1`~~ | Kit manifest + `kit.py verify/fetch/trim` + CISA trims | **Merged** 2026-11-26 (Claude reviewed). Unblocked kit-6 and the kit chain (B). |
| `route-perf-1` | Cut cross-state routing peak memory (~255/256 MiB) with no route change | PC (JVM unit tests, `-PunitTestHeap=256m`). |
| `mapverify-1` | Offline `.bmap` pack validator: structure + cross-pack junction consistency | PC. |
| `kitconv-1` | Golden-file tests + determinism for the `tools/kit-build/` converters | PC (Python). |
| `chunk-tables-1` | Keep table rows with their headers when chunking (wins back answer coverage) | Android + emulator (evals must stay 13/13 and 14/14). Claude reviews closely. |
| `evalclean-1` | `.gitignore` + README for the `tools/model-eval/` harness (clear ~35 untracked files) | PC. Low-risk. |
| `release-smoke-1` | Automate the R8 release smoke test (ZIM search, PDF import, AI answer) | Android + emulator. |

## B. Kit download + measure chain (sequential, after `kit-1`)

| Task | What | Depends on |
|---|---|---|
| `kit-2` | Exclude list for unvetted bundle documents (e.g. "under the tongue diagnosis") | kit-1 |
| `kit-4` | Fetch batch 1 (contingency gap audit, approved with two holds and three conditions) | kit-1 |
| `kit-5` | Fetch batches 2 and 3 (weapons care, trapping, leather, metals; trades, cradle to grave, law) | kit-4 |
| `kit-3` | Load the whole new kit on the emulator; `KitRetrievalEvalTest` on the new topics | kit-1,2,4,5 **and** Claude has moved the kit-4/kit-5 files into the kit |

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

## Not for ChatGPT (Claude / Fable / owner)

Card and flow text (life-safety content); the "never show a wrong answer" tuning (`ai/CheckedAnswer.kt`,
`ai/QueryPlan.kt`); keep-or-kill decisions; the Meshtastic device pages for the owner's radio (needs the model
name); permission requests to Hesperian, WHO, CVM and FAO; and the storage decision for the large ZIMs.
