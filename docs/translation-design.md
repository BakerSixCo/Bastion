# Translation & language — design (DRAFT for owner review, 2026-11-27)

Status: **proposal.** Nothing here is built yet. It respects the standing rules; the one real
decision for the owner is flagged in **Open decisions** at the end.

## Why

BASTION's "information & off-grid networking" pillars imply a language capability: help the user
**understand an incoming foreign-language message** and **say a needed phrase to a non-English
speaker**. We have zero language content today (no dictionary, phrasebook, or course).

## Hard constraints (these shape the whole design)

1. **Over the radio BASTION sends only approved card text or fixed notices — never AI-written text**
   (owner 2026-11-25). A free machine translation *is* AI-written text.
2. **Never show a wrong answer** (owner 2026-11-24). A free translation is best-effort, not a grounded
   card; it must never be presented as a verified answer.
3. **Offline, never connects** — translation runs on the phone's own Gemma model (LiteRT-LM), already
   multilingual. No new model, no download. (Android ML Kit offline translation is rejected: it
   downloads per-language models, which fights the never-connect rule.)
4. **Received text is data** — it is passed to the model as content to render, never as an instruction
   (same rule as `mesh/MeshInboxService` + "Ask AI").

## Feature A — Translate on receive  (RECOMMENDED, low risk)

An incoming foreign-language message → the user taps "Translate" (beside the existing "Ask AI" on a
received message) → Gemma translates it → the screen shows **both the original and the translation**,
the translation **labeled "machine translation — may be wrong."**

- It is a **display/understanding aid, not an answer.** It does NOT go through `ai/CheckedAnswer`
  (that gates grounded card answers); it is a separate, clearly-labeled path so it can never be
  mistaken for verified truth.
- Never auto-acted-upon. The user reads it and decides.
- Quality is good for major languages (Spanish, French, …), poor for low-resource ones — the label
  sets that expectation.

## Feature B — Phrasebook send  (RECOMMENDED, on-brand)

To *say something* to a non-English speaker over the radio, the user picks from a **verified
phrasebook** ("I need water", "Where is the casualty?", "Do you have fuel?") and BASTION sends the
**pre-translated, verified** phrase.

- This is the radio rule satisfied: the transmitted text is **approved, verified phrase text**, drafted
  → reviewed → owner-approved exactly like cards, not a live model translation. Model never writes what
  goes out.
- Source the phrase set from verified references (Wikivoyage phrasebooks, FSI / Peace Corps / DLI
  survival phrases) and treat each language's set like a card deck (its own approval + a `radio`-style
  check). Start with the languages the owner names.

## Explicitly OUT of scope without a separate owner decision

- **Free machine translation transmitted over the radio** (auto-answer or responder) — this violates
  constraint 1 outright. Not built.
- **User-typed free text → Gemma translates → user presses Send** (via `mesh/MeshBridge`): technically
  the user's own message, not BASTION auto-sending AI text — but the user usually cannot verify the
  translation, so it carries real risk. Left as an **Open decision**, off by default.

## Content to load (fills the dictionary/phrasebook gap)

- **Wiktionary ZIM** — offline dictionary (definitions + translations). REFERENCE tier.
- **Wikivoyage ZIM** — travel **phrasebooks** for many languages; the phrase-set source. REFERENCE.
- **FSI / Peace Corps language courses** (public-domain US-gov) and **DLIFLC Language Survival Kits** —
  phrase sets and deeper study. OFFICIAL/OWN when imported.

## Open decisions (owner)

1. Which languages first (for the phrasebook decks + which course material to load)?
2. Allow Feature B's riskier cousin — user-reviewed free-MT send via MeshBridge — or phrasebook-only?
3. Storage: Wiktionary (full English) is ~14 GB; acceptable, or ship a smaller subset / Wikivoyage only?

## Recommended defaults (Claude's S-tier rec, 2026-11-27; owner to confirm)

1. **Languages:** Spanish first (highest-value for US off-grid), then add on demand.
2. **Send:** phrasebook-send only — verified phrases, no user-reviewed free-MT send. Keeps the radio
   rule intact and the user never ships a translation they can't verify.
3. **Storage:** **Wikivoyage phrasebooks on the phone, not the 14 GB Wiktionary.** Gemma handles
   translate-on-receive; Wikivoyage covers verified phrases. Keep Wiktionary on the PC as an optional
   offline dictionary the owner can push later if they want it, but don't burden the phone with 14 GB.

## Code touchpoints (when approved — not changed yet)

- Receive path: `mesh/MeshInboxService` → a new "Translate" action next to "Ask AI".
- Send path: `mesh/MeshBridge` (user presses Send in Meshtastic) for the phrasebook phrase.
- `mesh/MeshAutoResponder` is **not** touched — auto-answer must never emit a translation.
- Translation path is **separate from `ai/CheckedAnswer`** — it is a labeled aid, not a checked answer.
