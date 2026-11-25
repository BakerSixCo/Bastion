# Field guide design (owner-approved direction, 2026-11-25)

All of BASTION's verified knowledge as a browsable topic tree with guided, step-by-step emergency
protocols. Works with no AI loaded. The AI only finds the right topic or step and answers follow-ups
from that topic's own sources. Same rules as the verified cards: every line backed by a verbatim quote,
drafted and independently reviewed by two models, approved by the owner.

Owner decisions (2026-11-25): guided steps — yes; casualty report to mesh and ATAK — yes; write this
design — yes. The AI stays on the phone (no base station).

## 1. Structure

- **Tree:** branch → topic → page. Proposed branches: Medical; Water; Fire & shelter; Navigation &
  signals; Food; Hazards; Disasters; Hunting & shooting. (Owner may add/remove — open.)
- **Topic page, top to bottom:** Do now (3–5 big steps) → Pictures → Details → Warnings (source
  disagreements shown and attributed) → Sources (tap opens the manual page) → Radio version (the
  approved short text sent to radio users without BASTION).
- **Existing cards** become the first topic pages; their `quotes`, review and approval carry over.

## 2. Guided steps (the core)

- Emergencies run as a flow of single questions or actions with big Yes/No/Done buttons; every
  branch leads to a step. The plain page is always one tap away, and any step can be jumped to.
- **Medical order:** massive bleeding → airway → breathing → circulation → hypothermia (the Army
  combat-casualty order in the kit). Entry points for the eight fastest killers: severe bleeding,
  not breathing, choking, anaphylaxis, burns, hypothermia, heat stroke, poisoning.
- **Emergency screen:** one tap from Home, those eight as huge buttons. No typing, no AI.
- **Data format:** a flow is data, not code — nodes (question / action / tool / end) and edges with
  conditions — built and checked on the PC (like `tools/knowledge/build.pl`), so a flow can be reviewed,
  diffed and approved like a card. The checker rejects dead ends, unreachable steps, loops without an
  exit, and any step line without a backing quote.

## 3. Tools inside steps

- CPR metronome (rate from the approved CPR card), 30:2 counter, 2-minute switch reminder.
- One-tap timestamps ("Tourniquet applied 14:32"), recheck timers, rewarming checks.
- Calculators in place (bleach dose in Water; others as added). All computed, never AI.

## 4. Casualty log and report

- Every step tapped is logged with time: what happened, what was done, how the person is.
- One button turns it into a short standard casualty report (mechanism, injuries, signs, treatment,
  times) and sends it to the team: over Meshtastic as text (fits the 200-byte packets) and to ATAK as a
  marker at the casualty's position via the TAK link. Sending is always a user tap.
- Logs are private data: kept on the phone, erased by the wipe PIN (the wipe keep-list does not include them).

## 5. Knows the situation (from data only)

- Kit from the trip / bug-out pack lists: steps using items you carry come first; "don't have one?"
  leads to the improvised method from the manual.
- Team medical notes from the contacts pack (allergies, conditions), shown where relevant.
- Location, altitude, time of day and sunset from GPS and computed sun position.

## 6. Usable under stress

- Big targets, one hand, gloves; red night mode; landscape.
- Voice: steps read aloud with the phone's offline voice; "next", "yes", "no" spoken back (offline
  speech recognition only; if none is available, buttons only — said plainly).
- Instant: the guide never waits for the model.

## 7. Pictures

- Figures extracted on the PC from the kit's manuals, each with source, page and licence. US
  government works are public domain; WHO and other material is checked per item (often
  non-commercial). Each figure is reviewed (right figure, right step) and approved with its topic.

## 8. The AI's lane

- **Find:** typed or spoken words → a topic/step. A code check requires the topic's subject words to
  match the question; if unsure, show the 2–3 closest topics as buttons.
- **Follow-ups:** answered only from that topic's own sources, through the existing checked-answer
  pipeline; otherwise "not covered" plus the nearest topic.
- **Not covered by the tree (owner decision 2026-11-25, risk-based):** medical or other life-safety
  questions get no AI-written answer — "not covered", the nearest topics, and the matching source passages
  highlighted to read. Other questions may get a checked AI answer labelled "AI summary, not verified —
  check the sources below", with sources shown. Over the radio: approved text only; otherwise "not
  covered". What counts as life-safety is decided by a code check (medical/hazard terms, the tree's
  medical branch), erring toward "life-safety". The mesh auto-answer stops sending AI-written answers.

## 9. Trust shown on screen

- Each step: source badge, tap to open the manual page. Each topic: approved by / date, and
  "what this does not cover".

## 10. Practice

- Drill mode for any flow (clearly marked PRACTICE; logs not sent), a short quiz, and a go-kit checklist
  tied to the packs.

## Build order

1. Flow format + PC checker + phone flow player (framework), with the CPR metronome and timestamps.
2. Emergency screen + the eight medical flows (draft Fable → review second model → owner approval).
3. Casualty log and report (mesh text + ATAK marker).
4. Topic tree browse + existing cards as pages; AI "find" with code check.
5. Figures pipeline.
6. Other branches, situation awareness from packs, voice control, practice mode.

Each step: tested on the emulator, radio parts with simulated nodes, labelled as such; physical-phone
pass before calling anything field-ready.
