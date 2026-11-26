# ChatGPT task queue

One task at a time, each on its own branch from `origin/claude/hello-e9r4rq`. Claude reviews each branch,
the owner decides merges, and Claude hands over the next task. Each task file names its previous and next step.

| # | Task | Status (2026-11-26) | Depends on | Unblocks |
|---|---|---|---|---|
| 1 | `map-1`: driving routes across state packs, indexed snapping | In progress | — | Cross-state trips on the phones |
| 2 | `device-1`: phone check, `docs/devices.md`, adb report tool | Queued (phones in transit: Galaxy S24+, Pixel 9a) | — | First Verified phone rows |
| 3 | `kit-1`: kit manifest (`sources.json`), `kit.py verify/fetch/trim`, the owner's NIFOG/AUXFOG/ATP trims | Queued | — | kit-2, kit-3, kit-4 |
| 4 | `kit-2`: exclude list for unvetted bundle documents (e.g. "under the tongue diagnosis") | Queued | kit-1 | kit-3 |
| 5 | `kit-3`: load the new kit on the emulator; `KitRetrievalEvalTest` on the new topics | Queued | kit-1, kit-2 | Cards for radio, electronics, food; kit-4 re-measure |
| 6 | `kit-4`: fetch the owner-approved gap-fill documents | Queued; **needs the owner's approval of the list** in `docs/library-plan.md` | kit-1, kit-3 | Next round of cards and evals |

Not for ChatGPT (Claude / Fable / owner): card and flow text (life-safety content), the keep-or-kill
decisions, the Meshtastic device pages for the owner's radio (needs the model name), permission requests to
Hesperian, WHO, CVM and FAO.
