# Bastion

**B**attery-backed **A**utonomous **S**urvival **T**erminal for **I**nformation & **O**ff-grid **N**etworking.

A rugged, solar-recharged, DC-native, fanless off-grid information and communications
station: local AI, offline Wikipedia, SDR receive, GPS, GMRS radio and mesh text — all
running off a 100Ah LiFePO4 bank in a sealed case, with no moving parts and no
orientation restrictions.

## Build variants

- **[KISS Build](docs/kiss-build.md) — current lead variant.** Three peer modules,
  each in its own case, deliberately not integrated: the Pelican 1500 **terminal**
  (offline core — its mission case is "internet unreachable"), the owned Jackery
  **plant**, and the owned Starlink **uplink** (luxury layer; nested Faraday bags
  inside the plant and uplink cases handle EMP — bag shields, case armors). No 12V
  bus, no crimping, ~$1,550. Read §8 (honest power numbers) and §9 (three-module
  doctrine).
- **Full-spec 12V build** — the original architecture below (build sheet, wiring
  diagram, Phase 2). Kept as the documented upgrade path when runtime becomes the
  binding constraint.

## Documentation

- [Build Guide](docs/build-guide.md) — cradle-to-grave instruction set, v1.0.
  Software phases (1, 4–7) apply to both variants; Phase 2's 12V power build is
  full-spec-variant only.
  Procurement and licensing (Phase 0), bench bring-up (Phase 1), power system
  (Phase 2), case fabrication (Phase 3), commissioning (Phase 4), the lid
  documentation pack (Phase 5), operations doctrine (Phase 6), and maintenance
  through end-of-life (Phase 7), plus a failure playbook.
- [Build Sheet](docs/build-sheet.md) — bill of materials, every line traced to the
  phase that requires it, with inferred specs flagged as such. Doubles as the
  receiving log for §0.3.
- [Deployed Illustration](docs/deployed-illustration.svg) — what the finished station
  looks like in operating configuration: lid open, panel out, antennas up, phone on
  BASTION WiFi.
- [Wiring Diagram](docs/wiring-diagram.svg) — one-page 12V DC power schematic
  (batteries → MRBF fuses → disconnect → fuse block, all negatives through the
  shunt, MPPT feed, six branch circuits). Print and laminate for the lid.
- [Printed Parts](hardware/printed-parts/) — parametric OpenSCAD sources (+ preview
  STLs) for the deck tray, battery spacers, Powerpole panel mount, cable clips,
  MC4 spanner, fuse organizer, and gland plugs. Measure, edit one config file,
  re-export.

## At a glance

| | |
|---|---|
| Build time | 2–3 weekends |
| Skill floor | Comfortable with a crimper, a multimeter, and a Linux installer |
| Budget | ~$2,050–2,400 plus consumables |
| Idle draw | ~15–30W (more under AI load) |
| Operating modes | LISTEN (~10–15W) · FULL (~25–60W+) · UPLINK (+~25–40W, Starlink windows) · SILENT (receive-only) |

## Acceptance gates

The build isn't done when it powers on. It's done when it passes both gates:

1. **24-hour acceptance test** — full station on battery + solar only, all services up.
2. **The Family Test** — someone who didn't build it goes from closed case to asking
   the AI a question and making a radio call in 15 minutes, using only the lid card.
   If they can't, the documentation gets fixed, not the person.

*Build once. Drill quarterly. Refresh semi-annually. The wiring is the easy part — the discipline is the product.*
