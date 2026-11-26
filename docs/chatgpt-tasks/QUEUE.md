# ChatGPT task queue

One task at a time, each on its own branch from `origin/claude/hello-e9r4rq` (or, where a task says so, from the
branch it builds on). Claude reviews each branch, the owner decides merges. Each task file names its previous
and next step.

| # | Task | Status (2026-11-26) | Depends on | Unblocks |
|---|---|---|---|---|
| 1 | `map-1`: driving routes across state packs, indexed snapping | **Merged** 2026-11-26 | — | Cross-state trips on the phones |
| 2 | `device-1`: phone check, `docs/devices.md`, adb report tool | Queued (phones in transit: Galaxy S24+, Pixel 9a) | — | First Verified phone rows |
| 3 | `kit-1`: kit manifest (`sources.json`), `kit.py verify/fetch/trim`, the owner's NIFOG/AUXFOG/ATP trims | Queued | — | kit-2, kit-4, kit-5, kit-3 |
| 4 | `kit-2`: exclude list for unvetted bundle documents (e.g. "under the tongue diagnosis") | Queued | kit-1 | kit-3 |
| 5 | `kit-4`: fetch batch 1 (contingency gap audit, approved with two holds and three conditions) | Queued | kit-1 | kit-3 |
| 6 | `kit-5`: fetch batches 2 and 3 (weapons care, trapping, leather, metals; trades, cradle to grave, law) | Queued | kit-4 | kit-3 |
| 7 | `kit-3`: load the whole new kit on the emulator; `KitRetrievalEvalTest` on the new topics | Queued; runs after Claude moves kit-4/kit-5 files into the kit | kit-1, kit-2, kit-4, kit-5 | Cards for the new topics |

Not for ChatGPT (Claude / Fable / owner): card and flow text (life-safety content), keep-or-kill decisions,
the Meshtastic device pages for the owner's radio (needs the model name), permission requests to Hesperian,
WHO, CVM and FAO, and the storage decision for the large ZIMs.
