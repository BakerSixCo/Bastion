# BASTION knowledge cards

Verified answers prepared on the PC, so the phone does not have to write them. The phone's model
only picks which card a question is about (`ai/CardRouter.kt`); the card's text is shown exactly
as written here, with its source and quote. Owner requirement 2026-11-24: never a wrong answer,
and everything is critical in an emergency.

## One card = one JSON file in `cards/`

```json
{
  "id": "water-boil-time",
  "kind": "fact",
  "title": "How long to boil water to make it safe",
  "asks": ["how long do I boil water", "boil creek water how long", "is boiling enough to make water safe"],
  "answer": [
    "Bring the water to a rolling boil for 1 minute.",
    "Above 5,000 feet (1,000 m), boil for 3 minutes."
  ],
  "warnings": ["Boiling does not remove chemicals, heavy metals or salt."],
  "quotes": [
    { "doc": "EPA-Emergency-Disinfection-of-Drinking-Water-2026.md",
      "text": "Bring water to a rolling boil for at least one minute. At altitudes above 5,000 feet (1,000 meters), boil water for three minutes." }
  ],
  "status": "draft",
  "drafted_by": "claude-fable-5-2 (max)",
  "reviewed_by": "",
  "approved_by": ""
}
```

- `kind`: `fact` (a short answer) or `protocol` (ordered steps: `answer` is the steps, in order).
- `asks`: ways people ask it, slang included. The router searches these.
- `answer` and `warnings`: what the phone shows, one line per item, nothing added.
- `quotes`: every claim in `answer`/`warnings` must rest on a quote. `doc` is a file in
  `tools/kit/docs/`; `text` must appear in it **word for word** (spacing and quote marks aside).
- `status`: `draft` → `reviewed` (a second model checked it against the quotes) → `approved`
  (the owner signed it off). **Only `approved` cards reach the phone.**

## Find passages locally (no AI tokens)

    perl tools/knowledge/find.pl "rattlesnake bite do not cut suck" -n 8 [-doc JTS-CPG]

Searches every kit document (text cached in `tools/kit/.textcache/`, same extraction as the
verifier, so its paragraphs can be quoted directly) and prints the best paragraphs with document
and page. Drafters and reviewers use it instead of reading whole manuals.

## Owner review page

https://claude.ai/artifact/H8nmtGzCwuGvoh54FEj2dz (private to the owner; source `review/card-review.html`).
Each card shows exactly what the phone shows, its source sentences, the second model's review and
any conflict; the owner picks Approve, Needs fix or Reject. `review/sync.pl export` packs reviewed
cards for the page (each with a content fingerprint); `review/sync.pl import <dir>` applies the
owner's decisions to the card files. A decision for a card that changed since is ignored.

## Build

    perl tools/knowledge/build.pl            # verify every card, write the app's cards file
    perl tools/knowledge/build.pl -v         # also print each card's check

`build.pl` checks every quote against the document text (`pdftotext` for PDFs), rejects a card
whose numbers in `answer`/`warnings` are not in its quotes, and writes only approved, verified
cards to `android/app/src/main/assets/knowledge/cards.json`. A failed check is an error, never a
warning.
