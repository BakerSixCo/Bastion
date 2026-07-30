# BASTION Build Sheet

Bill of materials for the [Build Guide](build-guide.md), v1.0. Order this in one pass
per §0.2 — batteries and the power-station class of items have the longest lead times.

## How to read this

Every line is traced to the phase that requires it. The **Source** column tells you how
much to trust the spec:

| Mark | Meaning |
|---|---|
| **§X.Y** | Specified in the guide at that section. The spec is the guide's, not a guess. |
| **inferred** | The build needs it, but the guide never names a part or size. The spec here is a starting point — replace it with your actual choice before ordering. |

**Prices are rough street-price ballparks for sizing the order, not quotes.** Replace
each with the real number as you shop. The guide's ~$2,050–2,400 band covers the core
build only; tools and consumables are the "plus consumables" and are listed separately.

Fill in the two right-hand columns as parts arrive — this doubles as the §0.3 receiving
log.

---

## 1. Power system (Phase 2)

| Qty | Item | Spec | Source | Est. | Actual part / notes | ✓ recv |
|---|---|---|---|---|---|---|
| 2 | LiFePO4 battery | 12V 50Ah, internal BMS, Bluetooth app | §0.3, §2.1.3 | $260 | | |
| 2 | MRBF terminal fuse holder + fuse | 60A, post-mount | §2.1.1 | $60 | | |
| 1 | Battery shunt | Victron SmartShunt 500A | §2.1.2, §2.2.3 | $120 | | |
| 1 | MPPT solar charge controller | Victron, VictronConnect-capable; sized to panel Voc | §2.1.7 | $110 | | |
| 1 | MPPT temperature sensor | For the 5°C low-temp charge cutoff | §2.1.7 | $30 | | |
| 1 | Solar panel | Folding, IP65, MC4 leads, rides outside the case | §2.1.7, §3.2.4 | $200 | ⚠ wattage never specified | |
| 1 | Battery disconnect switch | In the positive main | §2.1.4 | $25 | | |
| 1 | Fuse block | Blue Sea, ≥6 circuits, with negative bus | §2.1.5 | $45 | | |
| 1 | Blade fuse assortment | 5–10A range for 16 AWG branches | §2.1.5 | $15 | | |
| 2 | Buck converter | Regulated 12V out, adjustable — one for mini PC, one for router | §2.1.6 | $40 | ⚠ current rating to match your devices | |
| 1 | AC charger | LiFePO4 profile (14.6V), for monthly top-off | §6 | $60 | | |
| 1 | 12V USB charging panel | ≥4 ports, feeds phones + radio chargers | §2.1.5, §4.1 | $30 | | |
| — | Wire, 10 AWG | Battery mains and parallel pairs — **cut in equal lengths** | §2.1.3 | $40 | | |
| — | Wire, 16 AWG | Branch circuits from the fuse block | §2.1.5 | $20 | | |
| — | Wire, 12 AWG | MPPT battery feed to the bus | §2.1.7 | $15 | inferred — sized to MPPT output | |
| 1 | Inline fuse holder + 30A fuse | MPPT charge feed ("its own fused connection") | §2.1.7, diagram | $10 | | |
| 1 | MC4 extension cable pair | Pre-terminated; cut in half = pigtails for the bulkhead glands, no MC4 crimp tool needed | §2.1.7, §3.2.2 | $18 | inferred | |
| 2 | DC input pigtails | Barrel plugs matching the mini PC and router jacks (check sizes — commonly 5.5×2.5 / 5.5×2.1 mm) | §2.1.6 | $12 | inferred — the forgotten part | |
| 1 | Powerpole panel-mount housing | For the external power port bulkhead | §3.2.2 | $12 | inferred | |
| — | Anderson Powerpole set | Connectors + contacts, bulk | §2.1.8 | $30 | | |
| — | Ring terminals + heat-shrink | Adhesive-lined shrink; ring studs to match battery posts, shunt (M10), MRBF (5/16") | §2.1.8 | $30 | ⚠ verify stud sizes against actual parts | |
| 1 | Dielectric grease + blue threadlocker | Terminals and fastener retention in the vibration path | §2.1.8 | $15 | inferred | |
| | | | | **~$1,250** | | |

## 2. Compute & network (Phase 1)

| Qty | Item | Spec | Source | Est. | Actual part / notes | ✓ recv |
|---|---|---|---|---|---|---|
| 1 | Mini PC | Intel N100 class, fanless, internal NVMe, DC input | §0.4, §1.1 | $200 | ⚠ RAM/storage never specified — 16GB / 512GB suits a 7–8B model | |
| 1 | Router | GL.iNet, AP mode capable | §1.3.1 | $70 | ⚠ model never specified | |
| 1 | USB GPS dongle | gpsd-compatible | §1.2.4 | $25 | | |
| 1 | RTL-SDR dongle | RTL-SDR Blog v4 or equivalent | §0.3.4, §1.2.5 | $40 | | |
| 1 | Powered USB hub | Rides on the removable electronics deck | §3.1 | $25 | ⚠ port count never specified | |
| 1 | External SSD | 2TB — holds `/bastion-sources/` and the Clonezilla image | §0.4, §4.3 | $130 | | |
| 1 | Cold-spare compute | Raspberry Pi 5 **or** a spare NVMe drive | §4.3.3 | $120 | Lives in the Faraday bag, disconnected | |
| 1 | USB stick | ≥16GB, for the live-USB rescue image | §0.4 | $10 | inferred | |
| 1 | USB RTC module | **Only if** the mini PC won't hold time unplugged | §1.2.6 | $15 | Contingency — test first, buy second | |
| 1 | Compact wired USB keyboard | Console access when SSH is unreachable; lives in the TOOLS pocket | §1.1, playbook | $15 | inferred — no wireless, no batteries to die | |
| 1 | HDMI cable, short | Pairs with the keyboard for console recovery | §1.1 | $10 | inferred | |
| 1 | Portable HDMI field monitor | 7", **12V-powerable** — runs off the SPARE circuit, so console recovery needs no working TV | playbook | $50 | inferred, optional-but-recommended for grid-down recovery | |
| | | | | **~$710** | | |

**No permanent display, by design.** The family interface is their own phones over
BASTION WiFi; admin is SSH; Ubuntu Server needs no mouse ever. The keyboard, cable,
and field monitor exist for exactly one scenario: the box is headless and the network
path to it is down (router dead, bad update, hung boot). That is the failure the
playbook can't currently reach — console access is the resurrection path.

## 3. Radio & antennas (Phase 1.4, Phase 3.2)

| Qty | Item | Spec | Source | Est. | Actual part / notes | ✓ recv |
|---|---|---|---|---|---|---|
| 2 | GMRS handheld | CHIRP-programmable, NOAA weather receive | §1.4.1 | $80 | ⚠ model never specified | |
| 1 | Mesh node | Seeed Wio Tracker, Meshcore/Meshtastic | §1.4.2 | $40 | | |
| 1 | Programming cable | For the handhelds, if not included | §1.4.1 | $20 | inferred | |
| 1 | SDR antenna set | Wideband — the guide's known-good test is local FM | §1.2.5, §7 playbook | $30 | Often bundled with the RTL-SDR kit | |
| 1 | GMRS antenna | Roll-up or mag-mount, external, for the SMA bulkhead | §3.2.2, §3.2.3 | $35 | inferred — "antenna elements" in the lid | |
| — | SMA bulkhead connectors + pigtails | One per feedline through the case wall | §3.2.2 | $25 | ⚠ count depends on how many radios you bulkhead | |
| 1 | RF adapter kit | SMA / RP-SMA / u.FL — SDR dongles, GMRS radios, and mesh boards rarely share a connector | §3.2.2 | $15 | inferred — verify each device's connector before ordering pigtails | |
| | | | | **~$245** | | |

## 4. Case & fabrication (Phase 3)

| Qty | Item | Spec | Source | Est. | Actual part / notes | ✓ recv |
|---|---|---|---|---|---|---|
| 1 | Case | IP67, hard-sided, lid deep enough for flat documents | §3.2, §3.3 | $180 | ⚠ model/size never specified — size it around the battery cradle | |
| — | Foam | Closed-cell, enough for a full profiled layout + channels | §3.1, §3.2.1 | $50 | | |
| — | Cable glands | Marine-grade, sealed: solar MC4 pair, one Powerpole port, antenna feedlines | §3.2.2 | $30 | | |
| 1 | Deck plate | Board/plate carrying mini PC, router, SDR, hub, bucks as one unit | §3.1 | $20 | inferred — material never specified | |
| 1 | Battery cradle | Fixes battery orientation, mass low and centered | §3.1 | $20 | inferred — the guide requires it, doesn't spec it | |
| 1 | Panel sleeve + straps | Panel rides outside the case | §3.2.4 | $25 | | |
| 1 | Stainless fastener assortment | M3–M5 machine screws, nyloc nuts, washers | §3.1 | $25 | inferred | |
| 1 | Standoff kit | Nylon/brass M3, for boards and bucks on the deck | §3.1 | $12 | inferred | |
| — | VHB tape + industrial velcro | Mounting for devices with no screw holes (most consumer mini PCs, routers, hubs); velcro where field swap matters | §3.1 | $25 | inferred | |
| 2 | Battery hold-down straps | Cinch strap or bracket over the foam cradle — foam alone won't hold 6 kg in a drop | §3.1 | $20 | inferred | |
| — | Zip ties + adhesive/screw-down mounts | Wire management in the cut channels | §3.1 | $15 | inferred | |
| — | Wire loom / braided sleeve | Abrasion protection where runs cross the deck edge | §3.1 | $15 | inferred | |
| — | Grommets / edge trim | Any hole a wire passes through inside the case | §3.1 | $8 | inferred | |
| 1 | Marine sealant | Backing the cable glands, keeps the IP rating honest | §3.2.2 | $10 | inferred | |
| | | | | **~$455** | | |

**Mounting approach** (the guide implies it, stated here so the hardware makes sense):
the case wall is only penetrated by the glands — nothing else gets drilled. The deck
plate seats in its foam pocket (§3.1); devices mount to the *deck* with standoffs,
VHB, or velcro, and the deck lifts out as one unit with Powerpole tails. Batteries sit
in the foam cradle with straps over the top. Everything else lives in profiled foam.

## 5. Resurrection kit & documentation (Phases 4.3, 5)

| Qty | Item | Spec | Source | Est. | Actual part / notes | ✓ recv |
|---|---|---|---|---|---|---|
| 1 | Faraday bag | Fits the 2TB SSD + cold-spare compute | §4.3.3 | $30 | Stays disconnected, always | |
| — | Paper topo maps | Your region | §5.4 | $30 | | |
| 1 | Baseplate compass | | §5.4 | $15 | | |
| 1 | Laminator + pouches | For the lid cards and wiring diagram | §5 | $35 | inferred | |
| | | | | **~$110** | | |

## 6. Tools & consumables

Excluded from the guide's ~$2,050–2,400 band ("plus consumables"). Skip the lines you
already own — the guide's skill floor assumes a crimper and a multimeter.

| Qty | Item | Spec | Source | Est. | Actual part / notes | ✓ recv |
|---|---|---|---|---|---|---|
| 1 | Ratcheting crimper | For Powerpoles and ring terminals | Skill floor, §2.1.8 | $50 | | |
| 1 | Multimeter | Also lives in the field spares bag | §0.3.2, §3.2.5 | $40 | Buy two, or the bench loses its meter to the case | |
| 1 | Foam cutter | Electric carving knife or hot wire — **not** snap blades | §3.2.1 | $25 | | |
| 1 | Label maker | Both ends of every wire get labeled | §3.1 | $25 | inferred | |
| 1 | Field spares bag | Blade fuse assortment, 2 Powerpole sets, 1 buck converter, tape, multimeter | §3.2.5 | $60 | Second buck + fuses are duplicates of §1 lines | |
| | | | | **~$200** | | |

## 7. Fees & licensing (Phase 0.1)

| Item | Cost | Source | Notes |
|---|---|---|---|
| GMRS license (FCC ULS) | $35 | §0.1.1 | 10 years, whole immediate family, no exam. **Start this first.** |
| FCC Registration Number (FRN) | $0 | §0.1.1 | Prerequisite to the license application |

---

## Budget reconciliation

| Section | Est. |
|---|---|
| 1. Power system | ~$1,250 |
| 2. Compute & network | ~$710 |
| 3. Radio & antennas | ~$245 |
| 4. Case & fabrication | ~$455 |
| 5. Resurrection kit & documentation | ~$110 |
| 7. Fees | $35 |
| **Core build** | **~$2,805** |
| 6. Tools & consumables | ~$200 |
| **All-in** | **~$3,005** |

The core build now runs ~$400 over the guide's ~$2,050–2,400 band. The overage is
almost entirely mounting hardware, wiring consumables, and console-recovery gear
(~$325 across sections 1, 2, and 4) that the guide's budget never itemized — the
parts that stall assembly on a Saturday, or strand a headless box when the network
is down. The band is achievable by trimming the swing items instead: a smaller
panel, a used case, promoting an old drive as the cold spare, skipping the field
monitor if a TV will always be reachable. Don't trim the consumables — they're the
cheapest lines on the sheet and the most expensive to be missing.

## Decisions the guide leaves open

Settle these before you order — each one gates a part number:

1. **Solar panel wattage.** Drives the MPPT model (Voc must clear its input rating) and
   the sleeve size. §6 wants FULL mode (~25–60W) net-positive under sun, which argues
   for 100W minimum and 200W if you want margin in winter or under cloud.
2. **Case size.** Follow from the battery cradle outward: two 50Ah cells low and
   centered, deck above, lid deep enough for laminated flats.
3. **Mini PC RAM.** A 4-bit 7–8B model plus Open WebUI and Kiwix-serve wants 16GB.
4. **Cold spare: Pi 5 or spare drive.** A spare NVMe restores the exact Clonezilla image
   into the same box — cheaper, but useless if the failure is the mini PC itself. A Pi 5
   survives that, at the cost of a second software stack to maintain.
5. **Antenna count through the wall.** Each bulkheaded feedline is another gland and
   another SMA. The SDR needs one; whether GMRS and mesh get external antennas or run on
   their stock whips is an operating-posture call as much as a parts call.
6. **Battery self-heating.** §2.1.7 branches on this: non-self-heating packs require the
   MPPT temp sensor and the 5°C cutoff. Self-heating packs cost more and drop that line.
