# Bastion

**B**attery-backed **A**utonomous **S**urvival **T**erminal for **I**nformation & **O**ff-grid **N**etworking.

A rugged, solar-recharged, DC-native, fanless off-grid information and communications
station: local AI, offline Wikipedia, SDR receive, GPS, GMRS radio and mesh text — all
running off a 100Ah LiFePO4 bank in a sealed case, with no moving parts and no
orientation restrictions.

## Documentation

- [Build Guide](docs/build-guide.md) — cradle-to-grave instruction set, v1.0.
  Procurement and licensing (Phase 0), bench bring-up (Phase 1), power system
  (Phase 2), case fabrication (Phase 3), commissioning (Phase 4), the lid
  documentation pack (Phase 5), operations doctrine (Phase 6), and maintenance
  through end-of-life (Phase 7), plus a failure playbook.
- [Build Sheet](docs/build-sheet.md) — bill of materials, every line traced to the
  phase that requires it, with inferred specs flagged as such. Doubles as the
  receiving log for §0.3.

## At a glance

| | |
|---|---|
| Build time | 2–3 weekends |
| Skill floor | Comfortable with a crimper, a multimeter, and a Linux installer |
| Budget | ~$2,050–2,400 plus consumables |
| Idle draw | ~15–30W (more under AI load) |
| Operating modes | LISTEN (~10–15W) · FULL (~25–60W+) · SILENT (receive-only) |

## Acceptance gates

The build isn't done when it powers on. It's done when it passes both gates:

1. **24-hour acceptance test** — full station on battery + solar only, all services up.
2. **The Family Test** — someone who didn't build it goes from closed case to asking
   the AI a question and making a radio call in 15 minutes, using only the lid card.
   If they can't, the documentation gets fixed, not the person.

*Build once. Drill quarterly. Refresh semi-annually. The wiring is the easy part — the discipline is the product.*
