# BASTION guided flows

A flow is an emergency guide run one step at a time on the phone (design:
`docs/field-guide-design.md`, sections 2, 3, 4, 6, 9). It is **data, not code**: one JSON file per
flow in this folder, checked on the PC by `tools/knowledge/flows.pl` under the same rules as the
knowledge cards (every step backed by a verbatim quote from the kit, no number from nowhere;
draft → independent review → owner approval). **Only `approved` flows reach the phone.**

## One flow = one JSON file `flows/<id>.json`

```json
{
  "id": "example-id",
  "title": "Shown on the emergency button",
  "entry": true,
  "status": "draft",
  "drafted_by": "", "reviewed_by": "", "approved_by": "",
  "review": { "verdict": "", "notes": "", "by": "" },
  "start": "first",
  "nodes": {
    "first": { "type": "question", "text": "…?", "quote_refs": ["q1"],
               "answers": [ { "label": "Yes", "next": "do-it" }, { "label": "No", "next": "done" } ] },
    "do-it": { "type": "action", "text": "…", "quote_refs": ["q1"], "warnings": ["…"],
               "image": "figure-id", "log": "… done", "next": "count" },
    "count": { "type": "tool", "text": "…", "quote_refs": ["q2"],
               "tool": { "kind": "metronome", "rate_bpm": 0, "compressions": 0, "breaths": 0, "cycle_seconds": 0 },
               "next": "done" },
    "done":  { "type": "end", "text": "…" }
  },
  "quotes": [ { "id": "q1", "doc": "File-In-tools-kit-docs.pdf", "page": 12, "text": "verbatim words" } ],
  "links": ["card-id"]
}
```

Fields:

- `id` — must equal the file name. `title` — shown on the button and at the top of each step.
- `entry` — `true`: the flow gets a big button on the Emergency screen. `false`: reached only
  from elsewhere (later: the topic tree).
- `status` — `draft` | `reviewed` | `approved`; `approved` needs `approved_by`. `drafted_by`,
  `reviewed_by`, `approved_by` and `review {verdict, notes, by}` record who did what, as on cards.
- `start` — id of the first node.
- `nodes` — map of node id → node:
  - `type` — `question` (needs `answers`, at least 2, each `{label, next}`; no `next`), `action`
    (needs `next`), `tool` (needs `next` and `tool`), `end` (no `next`, no `answers`).
  - `text` — the step as shown, huge. `warnings` — extra lines shown under it.
  - `quote_refs` — ids from `quotes`; at least one on every question/action/tool node. The source
    badge on the phone shows these quotes with document and page.
  - `image` — optional figure id (figures pipeline, build step 5; not drawn yet).
  - `log` — text written to the casualty log when the step is confirmed (default: the step text;
    a question logs "text — answer").
  - `tool` — `{kind, …}`:
    - `metronome`: `rate_bpm` (beats per minute), `compressions` (count 1..N, then a breaths
      prompt; 0 = continuous), `breaths` (number of breaths to prompt), `cycle_seconds`
      (reminder every so many seconds of running time; 0 = none).
    - `timer`: `seconds` (countdown), `label` (shown and logged when it runs out).
    - `timestamp`: `label` — one tap logs "label at HH:mm".
- `quotes` — `{id, doc, page, text}`: `doc` is a file in `tools/kit/docs/`; `text` must appear in it
  word for word (case, spacing, quote marks and line-end hyphens aside — `build.pl`'s rule).
  `page` (optional) is the PDF page index as `find.pl` prints it (`p.95`), not the printed page
  number; the quote must start on that page.
- `links` — ids of related knowledge cards (files in `tools/knowledge/cards/`).

## Checks (`flows.pl`; any failure = the flow is not built, and the run exits non-zero)

- Structure: `start` exists; every `next` / answer target exists; every node reachable from
  `start`; from every node an end is reachable (no trap loops); questions ≥ 2 answers; tool kinds
  and parameters valid.
- Sources: every question/action/tool node has ≥ 1 `quote_ref`, each names a quote of the flow;
  every quote is verbatim in its kit document, on the stated page.
- Numbers: every number in a node's text, warnings, answer labels, log text, tool label and tool
  parameters appears in that node's own referenced quotes (spelled-out numbers count, as in
  `build.pl`; a duration in seconds also passes as whole minutes or hours, e.g. 120 ↔ "2 minutes").

The phone repeats the structural checks at load (`guide/FlowValidator.kt`) and refuses an invalid
flow; it does not re-check quotes.

## Build and test

    perl tools/knowledge/flows.pl -v                  # check all flows, write the app's flows.json
    perl tools/knowledge/flows-test-fixtures/run.pl   # checker tests against fake fixtures

Output: `android/app/src/main/assets/knowledge/flows.json` (`{"flows": [], "version": 1}` when none
are approved). The fixtures (`flows-test-fixtures/`) use a fake document and fake text only.

## Testing a draft on the phone (debug builds only)

Copy a flow file to `Android/media/com.bastion.terminal/flows-test/` on the phone or emulator.
Debug builds also load flows from there, with a red **TEST FLOW — NOT APPROVED** banner on every
screen of that flow; they still pass the phone's structural checks. Release builds never read that
folder.
