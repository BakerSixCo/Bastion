# BASTION — KISS Build

**Revision date:** 30 July 2026
**Status:** current lead variant. The DIY 12V build ([build-sheet.md](build-sheet.md),
[wiring-diagram.svg](wiring-diagram.svg)) remains in the repo as the documented
full-spec upgrade path, not a competing plan.
**Editor's note:** this revision was produced in an external ChatGPT session; its
"supersedes" references to an S-tier/EcoFlow BOM refer to that session's drafts, not
files in this repo. Engineering review notes from the repo maintainer are in §8.

---

## 0. The design rule

**Two pieces. Both one-hand carry. The case does not hold the power.**

Every previous revision violated this. The DIY version put a 12V bus in the case. The S-tier version put a 27.6 lb power station in the case and needed a wheeled 64 lb crate to hold it. Both drifted away from your own stated goals: *frugal but functional over full-spec*, *minimal moving parts*, and *sell the finished kits*.

This version has one computer, one power source, no 12V bus, and no crimping.

| Module | What | Weight |
|---|---|---|
| **A — Terminal** | Pelican 1500 holding compute, radio, network, docs | ~20 lb loaded |
| **B — Plant** | Jackery Explorer 300 v2 + panel (**already owned**) | ~8 lb + panel |

### Fit note — measure before you buy the case

The Pelican 1500's interior depth is **6.12 in**. The Jackery will not fit inside it, and that's the point — it has its own folding handle and rides separately.

⚠️ I could not get a clean verified dimension for the **v2** specifically. Published sources repeatedly quote the *original* Explorer 300 (9.1 × 5.2 × 7.8 in, 7.1 lb, 293Wh, 500 cycles) when describing the v2 (288Wh, 8.1 lb, 4,000+ cycles, "19% smaller"). **Measure yours** before ordering anything sized around it.

---

## 1. The BOM

| # | Item | Pick | Price | Verified |
|---|---|---|---|---|
| 1 | Power plant | **Jackery Explorer 300 v2** — 288Wh LiFePO4, 300W (600W surge), 4,000+ cycles, ≤0.01s UPS, 2× AC, 100W USB-C, 15W USB-C, USB-A | **$0 — owned** | ✅ specs |
| 2 | Solar | **Jackery SolarSaga 100W** — the unit's input ceiling is 100W; anything larger is wasted | ~$200 | ⚠️ est. |
| 3 | Compute | **MeLE Quieter DL** — N150, 16GB, 512GB NVMe, **fanless**, dual 2.5GbE, unlocked BIOS, ~6W idle / <12W load | ~$250 | ⚠️ price est., specs ✅ |
| 4 | Redundancy | Spare 512GB NVMe, cloned, stored in the Faraday bag | ~$40 | ⚠️ est. |
| 5 | Network | **GL.iNet Beryl AX (GL-MT3000)** — OpenWrt + LuCI, AP/WISP/repeater, USB tethering, 12V in, fan only under load | ~$79 | ✅ |
| 6 | Storage | 2TB external SSD — **NEW only** | ~$150 | ⚠️ est. |
| 7 | SDR | **RTL-SDR Blog V4 + dipole kit** — buy from rtl-sdr.com or its official Amazon store **only** | ~$55 | ⚠️ est. |
| 8 | Mesh | **Seeed Wio Tracker L1 Pro** — nRF52840 + SX1262, **GPS on board**, OLED, solar. **915 MHz US variant** | ~$45 | ✅ |
| 9 | GMRS ×2 | **Wouxun KG-805G** — 5W, repeater-capable, CHIRP, IP55, NOAA | $160 | ✅ |
| 10 | Antenna | GMRS mag-mount, 462–467 MHz, SMA | ~$30 | ⚠️ est. |
| 11 | Case | **Pelican 1500 with foam** — interior 16.75 × 11.18 × 6.12 in, 7.13 lb empty, IP67, lifetime warranty | ~$169 | ✅ |
| 12 | EMP | **Mission Darkness Non-Window Faraday Bag (laptop size)** — TitanRF, certified MIL-STD-188-125 + IEEE 299-2006. Holds spare NVMe, SSD, and a handheld | ~$55 | ⚠️ price est., cert ✅ |
| 13 | Analog fallback | USGS 7.5' quads for your AO + baseplate compass | ~$70 | ⚠️ est. |
| 14 | Consumables | Barrel adapters, short HDMI, USB cables, VHB, velcro, straps, foam, zip ties | ~$100 | ⚠️ est. |

### **Total: ~$1,403**

Against the S-tier version at $3,050: **$1,647 cut, 54% off.**
Against your original $2,500 target: **$1,100 under.**

With military pricing applied (Pelican via GovX/ExpertVoice, Wouxun via myGMRS on GovX, SSD via Samsung's ID.me program), realistic landed cost is **~$1,250–1,300**.

---

## 2. Power budget — the honest version

| Mode | Load | Runtime on 288Wh |
|---|---|---|
| **Idle** — mini PC + router + mesh node | ~12W | **~24 hrs** |
| **Working** — above + SDR active + SSD reads | ~22W | **~13 hrs** |

### Solar balance with a 100W panel

Realistic harvest: 100W × 4.5 peak hrs × 0.8 derate ≈ **360Wh/day** (good Idaho summer sun)

| Duty cycle | Daily draw | Balance |
|---|---|---|
| Idle 24/7 | 288Wh | **+72Wh surplus** ✅ |
| Working 24/7 | 528Wh | **−168Wh deficit** ❌ |

**The operating doctrine falls out of this:** idle is indefinitely sustainable. Continuous SDR scanning and heavy compute are not. Run the radio and the AI in bursts, idle between. Winter and overcast shift both rows against you.

That is a real constraint, not a footnote. If your mission profile is "run continuously off-grid for weeks," this build does not do it and you need the DELTA back.

*(See §8.1 and §8.2 — these figures omit AI inference and conversion losses; the
corrected planning numbers are ~21 hrs idle and ~8 hrs working-with-AI.)*

---

## 3. What got cut, and why

| Cut | Reason | Saved |
|---|---|---|
| EcoFlow DELTA 3 Plus + its 220W panel | You own a power station | $948 |
| Second mini PC | A cloned spare NVMe recovers ~90% of the redundancy for $40 | $210 |
| Entire tools section (crimper, 2× multimeter, label maker) | The crimper existed for the 12V bus that no longer exists. Multimeters are shop capex, not kit BOM | $385 |
| 7" field monitor | You already decided the interface is a phone over WiFi. Keep a short HDMI cable for recovery only | $50 |
| USB GPS dongle | The Wio Tracker L1 Pro has GPS on board | $30 |
| Powered USB hub | The MeLE has the ports | $30 |
| Pelican 1620 → 1500 | The 1620 was sized around the DELTA. No DELTA, no crate | $150 |
| Laminator | Nice-to-have, not a system | $35 |
| Router upgrade (Beryl 7 / Slate 7 → Beryl AX) | WiFi 7 buys nothing on a single-user offline mesh, and the AX's fan runs less | $61 |

**Also gone from the original DIY list:** LiFePO4 cells, Victron MPPT/SmartShunt/Battery Sense, Blue Sea MRBF/disconnect/fuse block, buck-boost converters, AC charger, marine wire, Anderson kit. Roughly **$700 of parts and every crimp in the build.**

---

## 4. What you're giving up

State these plainly before you commit:

1. **Runtime falls from ~27 hrs to ~13 hrs working / ~24 hrs idle.**
2. **Solar is break-even at idle, negative under load.** Duty-cycle discipline is now a requirement, not a preference.
3. **300W AC ceiling with no expansion path.** The Jackery takes no expansion batteries.
4. **One computer.** A cloned NVMe covers drive failure and corruption. It does not cover the mini PC itself dying.
5. **No wheels, no trolley handle.** At ~28 lb across two pieces you don't need them.

---

## 5. Upgrade path — deliberately preserved

Nothing here is a dead end. If the build proves out and runtime becomes the binding complaint:

- **+$599** — EcoFlow DELTA 3 Plus drops in as the plant. The Jackery demotes to a detached scout module. No redesign, no rewiring.
- **+$250** — second identical MeLE restores the two-PC redundancy doctrine.
- **+$169** — larger case if the payload grows.

That optionality is the entire argument for buy-and-assemble. Take it.

---

## 6. Sequencing

1. **Measure the Jackery.** Confirm it clears whatever you plan to store it with.
2. **Verify GovX/ExpertVoice pricing on the Pelican 1500** before ordering the case. ExpertVoice codes are single-use per brand and expire in ~30 days — don't pull it early.
3. **Buy the mini PC and router first.** Get the software stack running on a desk before anything goes in foam. This is where the project actually lives or dies.
4. **Case and foam last.** Cut foam around hardware you've already validated, not hardware you hope works.
5. **Check whether Jackery has a military program** on the SolarSaga — I did not verify this one either way.

---

## 7. Verification status

**Prices confirmed live:** Wouxun KG-805G ($79.99 sale, myGMRS), Wio Tracker L1 Pro ($42.90–48), Pelican 1500 ($169–181 retail), GL.iNet Beryl AX (~$79).

**Specs confirmed:** Jackery Explorer 300 v2 capacity/output/cycles/UPS/100W solar input cap, Pelican 1500 interior and empty weight, MeLE Quieter DL power draw and I/O, Wouxun repeater + CHIRP capability, Mission Darkness shielding certifications.

**Marked ⚠️ est.:** commodity items where price moves weekly. Confirm at checkout, not from this sheet.

**Explicitly unverified:** Jackery Explorer 300 v2 physical dimensions (sources conflate v1 and v2 — measure yours), and whether Jackery runs a military discount program.

---

## 8. Engineering review notes (repo maintainer)

Filed on intake, 30 Jul 2026. Four substantive findings, three flags. None kill the
architecture — the two-piece design is right — but the power numbers need the
corrections below before anyone plans around them.

### 8.1 The AI workload is missing from the power budget

§2's "Working — 22W" covers SDR + SSD but not LLM inference, which is the
centerpiece service. An N150 running a 4-bit 7–8B model adds 15–25W of package
power during generation. **Working-with-AI is ~30–38W → roughly 8 hours**, not 13.
The duty-cycle doctrine stands; the number the family plans around must include
the AI.

### 8.2 The AC inverter is a hidden 6–10W parasite — the idle claim depends on avoiding it

If the mini PC runs from its wall brick through the Jackery's AC outlets, inverter
overhead alone nearly doubles the 12W idle figure. The entire idle budget assumes
**DC-path powering: mini PC from the 100W USB-C PD port** (PD trigger cable to
barrel if the MeLE lacks native PD input — Quieter-series units are historically
12V-barrel; verify before purchase), router from the 15W USB-C. AC ports stay off
in normal operation. Also apply ~90% DC conversion efficiency to all runtime math:
**~21 hrs idle**, not 24; solar break-even at idle is correspondingly thinner, and
winter erases it.

### 8.3 The owner's Starlink Mini is absent from this BOM

It exists, it's integrated in the DIY-variant docs, and it contends for the same
100W USB-C port the mini PC wants. Add: **100W PD trigger-to-barrel cable
(20V, 5.5×2.5mm, ~$15)** for the dish. Doctrine: UPLINK windows only; FULL +
UPLINK (~50–75W combined) on a 288Wh plant is a solar-hours-only activity, and the
mini PC moves to AC (accepting §8.2's penalty) or powers down during dish windows.

### 8.4 Keep one multimeter in the kit

Cutting the crimper and shop tools is right. Cutting *every* meter is not — the
failure playbook's first step in half its rows is "measure the voltage." One $25
compact DMM rides in the case. +$25 to §1.

### Flags (verify before ordering)

- **"Explorer 300 v2" naming:** the quoted specs (288Wh, 100W USB-C, LiFePO4)
  exactly match the **Explorer 300 Plus**. Confirm which unit is actually owned;
  don't size foam or cables from a model name the sources themselves conflate.
- **"MeLE Quieter DL":** model name unverified at intake. Confirm it exists as
  specced (N150/16GB/512GB/fanless/dual 2.5GbE) or substitute the current
  Quieter-series equivalent meeting the same MUSTs.
- **Printed-parts library impact:** deck tray, cable clips, and fuse organizer
  remain useful; the MC4 spanner and gland plugs are obsolete in this variant
  (SolarSaga uses Jackery's DC connector, and the 1500 gets no bulkhead
  penetrations unless antennas demand one).

### What survives from the DIY-variant docs unchanged (see §9 for the Starlink change)

The Family Test, the bench-first sequencing (§6.3 here matches guide Phase 1), the
operating-mode discipline, the offline-first Starlink doctrine, quarterly drills,
the resurrection-kit pattern, and the used-market sourcing rules all carry over.
The [build guide](build-guide.md)'s software stack (Phases 1, 4, 5, 6, 7) applies
to this variant as written; only Phase 2 (12V power build) and Phase 3's bulkhead
work are superseded.

---

## 9. Three-module doctrine & EMP protection (owner decision, 30 Jul 2026)

### 9.1 The fallback paradox, stated plainly

**If the internet is up and reachable, the terminal is mostly moot — online
services win.** That is not a flaw; it is the mission statement read backwards.
The terminal exists for exactly the condition where the uplink doesn't. Therefore
the uplink is not integrated into the build — it is a **peer module in its own
case** that happens to travel with the others.

| Module | Case | Role |
|---|---|---|
| **A — Terminal** | Pelican 1500 | The offline core. Mission case: internet unreachable |
| **B — Plant** | Jackery's own case | Power for whichever module needs it |
| **C — Uplink** | Starlink kit's own case | Luxury layer. When it works, A idles — that's success, not waste |

Integration between modules is deliberately thin: one PD trigger cable so the
Plant can feed the Uplink, one WISP profile on the router so the Terminal can use
the Uplink as WAN when it happens to exist. No shared enclosure, no shared power
budget, no dependency in either direction. §8.3's port-contention concern
dissolves: the dish is not in the Terminal's power budget at all.

### 9.2 EMP: nest bags, don't line cases

The B and C cases do not get conductive lining. A Faraday enclosure requires
electrical continuity across every gap, and a hinged, gasketed lid opened
hundreds of times over the kit's life will not keep a taped fabric seam
conductive. The reliable pattern:

> **Bag shields, case armors.** A certified roll-top Faraday bag *inside* the
> existing case. The case keeps doing crush and water; the bag does RF; the
> roll-top is the only seam and it's engineered for that job.

Additions to the BOM (Mission Darkness / Faraday Defense class, certified
non-window):

| # | Item | Contents | Est. |
|---|---|---|---|
| 15 | Faraday bag, dish-size (laptop/XL) | Starlink Mini **plus its power cable and the PD trigger cable** — a shielded dish with a fried PSU is still dead | ~$60 |
| 16 | Faraday bag, box-style for the plant | Jackery **plus its AC charger and car cable**; interior ≥ measured unit + 2" each dimension | ~$80 |

(BOM #12, the laptop bag for spare NVMe / SSD / a handheld, is unchanged.)
Revised total: **~$1,545** (~$1,570 with §8.4's multimeter), still ~$950 under
the original target.

### 9.3 Rules that come with the bags

1. **No grounding required.** A Faraday enclosure works ungrounded — persistent
   myth, ignore it.
2. **Test quarterly, because you can.** Seal a phone inside and call it; tune an
   FM radio to a strong station and close the roll around it. If the phone rings
   or the radio keeps playing, the bag failed. This testability is the whole
   argument for bags over lining — a lined case can never be proven.
3. **Readiness state: in bag, in case, charged.** Quarterly top-off means opening
   the plant bag; it reseals in ten seconds. A bag left open "for convenience"
   is a bag that isn't there.
4. **Cables and chargers live in the bag with their device.** The kit's weakest
   EMP link is whichever unshielded accessory the shielded device can't run
   without.

### 9.4 The honest limit

Shielding the dish hedges **ground-level EMP** (HEMP, localized events). It does
nothing for **constellation loss** — a severe solar storm or orbital conflict
degrades the satellites themselves, and a pristine terminal wakes to an empty
sky. That asymmetry is why C is the luxury layer and A is the plan: the failure
modes that kill the uplink from above are invisible to any preparation on the
ground.
