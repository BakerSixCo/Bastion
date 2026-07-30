# BASTION Build Guide

### Battery-backed, Autonomous Survival Terminal for Information & Off-grid Networking

**Cradle-to-grave instruction set — v1.0**

A rugged, solar-recharged, DC-native, fanless off-grid information and communications station. No orientation restrictions, no moving parts, cold-capable, family-operable.

**Build time:** 2–3 weekends. **Skill floor:** comfortable with a crimper, a multimeter, and a Linux installer. **Budget:** ~$2,050–2,400 plus consumables.

---

## Phase 0 — Planning & Procurement (Week 0)

### 0.1 Licensing (start first — it takes days, not hours)

1. Apply for a **GMRS license** via the FCC Universal Licensing System (fcc.gov → ULS). No exam. $35, valid 10 years, covers your entire immediate family. You need an FCC Registration Number (FRN) first — the ULS walks you through it.
2. Record your call sign in the documentation pack (Phase 5). FCC rules require identifying with it periodically during use.
3. Optional but recommended: one household member studies for the ham Technician exam later. Not a blocker for this build.

### 0.2 Order everything

Order the [full build sheet](build-sheet.md) in one pass — batteries and the power station class of items have the longest shipping times. Settle the open decisions at the foot of that sheet first; each one gates a part number. While waiting:

### 0.3 Verify on arrival (do not skip)

For each item as it arrives:

1. Photograph the item and serial number. Keep a build log folder.
2. **Batteries:** check open-circuit voltage with a multimeter. A healthy LiFePO4 12V ships at ~13.0–13.3V. Below 12.0V on arrival = return it. Connect each to the Bluetooth app (if equipped) and confirm BMS reports sane cell voltages.
3. **Mini PC:** power it on bare, on a desk, before anything else. Confirm it POSTs.
4. **SDR:** plug into any computer, confirm it enumerates (shows up as a USB device).
5. Anything DOA gets returned now, not discovered in Phase 4.

### 0.4 Download everything while you still have internet

Create a `/bastion-sources/` folder on your 2TB backup SSD and pull:

- **Kiwix ZIM files** (kiwix.org/en/downloads): full English Wikipedia (~100GB+ with images), WikiMed, Wikivoyage, iFixit, Post-disaster crisis library, Project Gutenberg. Verify checksums.
- **Offline maps:** OpenStreetMap extracts for your region + country (Geofabrik.de), plus a routing/nav app package (Organic Maps or OsmAnd data files).
- **AI models** (via Ollama later, but note your picks): a 7–8B instruct model quantized to 4-bit is the sweet spot for an N100 (e.g., Llama-class or Qwen-class 7B). Also pull a small 1–3B model as a fast fallback.
- **OS installers:** Ubuntu Server LTS (or Debian) ISO for the mini PC, plus a live-USB rescue image.
- **Software installers/packages** for offline reinstall: Ollama, Open WebUI, Kiwix-serve, SDR++ / GQRX, gpsd, CHIRP, Meshtastic/Meshcore clients.
- **All product manuals as PDFs** — battery, MPPT, shunt, radios, mini PC. These go in the knowledge base too.

**Date-stamp this folder.** This is your data staleness manifest baseline.

A Starlink Mini in the kit makes future refreshes easier — it changes nothing here.
Download everything anyway: the uplink is a luxury that can vanish (power budget,
subscription, constellation, weather), and the local copy is the plan.

---

## Phase 1 — Bench Bring-Up: Software First (Weekend 1)

Build and prove the entire electronics stack on a desk with wall power **before** any 12V wiring exists. Debugging software while also debugging your own wiring is how projects die.

### 1.1 Mini PC preparation

1. Enter BIOS. Set three things:
   - **Restore AC Power Loss → Power On.** Critical: the station must self-boot when power returns, with no keyboard attached. This single setting is what makes the box family-operable after an outage.
   - Disable Secure Boot (simplifies Linux + driver life).
   - Set boot order to internal NVMe first.
2. Install **Ubuntu Server LTS** (no desktop — saves RAM and watts). Hostname: `bastion`. Static IP: `192.168.8.10` (matches router config below).
3. Enable SSH. All later admin happens from your laptop/phone.

### 1.2 Core services

Install and configure, in this order, testing each before the next:

1. **Ollama** — install, then `ollama pull` your chosen 7–8B model and the small fallback. Test: `ollama run <model>` answers a question.
2. **Open WebUI** — the browser chat interface to Ollama. Run as a service (Docker or native). Test from another device's browser: `http://192.168.8.10:3000` (or your chosen port). This is the family's AI interface — if it isn't reachable by browser, it doesn't exist.
3. **Kiwix-serve** — point it at your ZIM library directory, run as a service on port 8080. Test: browse Wikipedia from your phone with WiFi only, ethernet unplugged.
4. **gpsd** — plug in the USB GPS dongle, confirm `cgps` shows a fix (near a window).
5. **SDR stack** — install SDR++ (or GQRX + rtl_433). Plug in the RTL-SDR, confirm you can hear a local FM broadcast station. That's your known-good signal test forever.
6. **Set the RTC expectation:** the mini PC has a coin-cell RTC — confirm date survives a full unplug. If your unit doesn't hold time, add a battery-backed USB RTC or configure gpsd time sync (GPS becomes your clock source off-grid — set this up regardless, it's free accuracy).

### 1.3 Router configuration

1. Flash/update the GL.iNet router, set it to **Access Point mode** with its own SSID (e.g., `BASTION`) and WPA2 password.
2. Static-lease the mini PC at `192.168.8.10`.
3. Set the router's DNS to resolve friendly names: `ai.bastion` → WebUI port, `wiki.bastion` → Kiwix. If the router supports a captive portal / landing page, make it a single page with two big links: **ASK THE AI** and **LIBRARY**. This page is "dumb mode."
4. Test: phone joins `BASTION` WiFi, both services load, with no internet anywhere in the chain.
5. **Starlink Mini (optional uplink):** the Mini is a modem, never the LAN. Connect it to the router as WAN — wired through an RJ45 bulkhead, or by joining the Mini's WiFi in WISP/repeater mode (no new case penetrations). The GL.iNet stays the only family-facing AP; the SSID, friendly names, and captive portal don't change whether the dish is present or not. Power it from the EXT Powerpole port through the 24V boost cable — never bare 12V bus (it sags below the Mini's happy range under peak draw). Then test the gate that actually matters: **with the dish powered off, every local service still works.** Internet is a bonus, never a dependency.

### 1.4 Radio programming (bench)

1. Install CHIRP on your laptop. Program both GMRS handhelds identically:
   - GMRS simplex channels 1–22, local GMRS repeaters (find them at mygmrs.com while online), and **NOAA weather channels** (receive).
   - Label channels in plain English ("FAMILY 1", "WEATHER", not frequencies).
2. Configure the Wio Tracker (Meshcore/Meshtastic): region setting, channel key shared with any other family nodes, and **decide your GPS broadcast policy now** — position beaconing off or encrypted-channel-only is the OPSEC-sane default; it's a deliberate choice, not a default to inherit.
3. Save both radio images and the mesh config into `/bastion-sources/configs/`. A dead radio should be re-programmable in 5 minutes from this folder.

### 1.5 Bench milestone (gate — do not proceed until all pass)

- [ ] Phone on BASTION WiFi can chat with the AI and browse Wikipedia
- [ ] SDR receives local FM and NOAA weather broadcast
- [ ] GPS has a fix; system clock syncs from it
- [ ] Both handhelds programmed and talk to each other
- [ ] Mesh node pairs with a phone and sends a message
- [ ] Mini PC auto-boots on power restore with nothing attached

---

## Phase 2 — Power System Build (Weekend 2, Day 1)

### 2.0 Safety rules (read twice)

- A 50Ah LiFePO4 can source hundreds of amps into a short. Treat battery terminals like a loaded tool: **remove rings and watches**, insulate one terminal while working the other, never let a wrench bridge the posts.
- Fuses protect **wire**, not devices. Every fuse is sized to the wire downstream of it.
- Work with the disconnect switch OFF and the batteries fused before anything else connects.

### 2.1 Wiring order (this exact sequence)

*Full schematic: [wiring-diagram.svg](wiring-diagram.svg) — print, mark up with your actual wire labels, laminate for the lid.*

```
BATTERY(+) → MRBF fuse (on the post) → Disconnect switch → Fuse block (+ bus)
BATTERY(−) → SmartShunt (battery side) → Shunt (load side) → Fuse block (− bus)
SOLAR MC4 → MPPT PV input        MPPT battery output → its own fused feed to the bus
```

1. **MRBF terminal fuses first.** Bolt the holder directly to each battery's positive post. Nothing connects to a battery positive except through this fuse. Size: 60A for this build (10 AWG mains).
2. **SmartShunt** goes in the **negative** line — ALL current must pass through it or its accounting lies. Nothing else lands on the battery negative post; everything negative lands on the shunt's load side or the bus.
3. **Parallel the two 50Ah batteries** with equal-length 10 AWG pairs (equal length = equal current sharing). Both through their own MRBF fuses.
4. **Disconnect switch** in the positive main between fuse and bus.
5. **Blue Sea fuse block:** one labeled circuit per load — MINI PC, ROUTER, SDR/USB, RADIO CHG, USB PANEL, SPARE. Fuse each at the wire's rating (typically 5–10A blade fuses for 16 AWG branches).
6. **Buck converters:** mini PC and router each get a regulated 12V buck between fuse block and device. Set output with a multimeter BEFORE connecting the device. LiFePO4 bus swings ~10–14.6V; regulated rails make that invisible to the electronics.
7. **MPPT:** PV in from the panel's MC4 leads (through the case bulkhead), battery out through its own fused connection to the bus. **Configure the LiFePO4 charge profile in VictronConnect before first sun:** absorption 14.2–14.4V, float 13.5V, and if your batteries are NOT self-heating, set the MPPT's low-temperature charge cutoff at 5°C using its temp sense — this is your freeze-plating protection.
8. **Every connection is a crimped Anderson Powerpole or a ring terminal with heat-shrink.** No wire nuts, no twist-and-tape, no solder on stranded wire in the vibration path.

### 2.2 Power-up test sequence (loads disconnected)

1. Disconnect switch OFF. Measure bus: 0V. Good.
2. Switch ON. Measure bus: battery voltage (~13.2V). Check EVERY fuse block position reads the same.
3. Pair SmartShunt via Bluetooth, set battery capacity (100Ah for 2×50), zero the SOC at full charge later.
4. Connect loads one circuit at a time, watching shunt current. Record each device's real draw in the build log — these numbers are your future troubleshooting baseline.
5. Full system on: expect roughly 15–30W idle, more under AI load. If anything is wildly off baseline, stop and find out why now.
6. Solar test: panel in the sun, confirm MPPT shows charge current and the shunt shows net-positive. Congratulations — the power plant works.

---

## Phase 3 — Case Fabrication (Weekend 2, Day 2)

### 3.1 Layout principles

- **Batteries lowest and centered** — mass at the bottom regardless of how the case lands. Fixed cradle orientation solves the "bolts-side-down" caveat permanently.
- Every device in a profiled pocket. Cut foam so each item requires slight force to seat. Nothing shares a pocket with something heavier than itself.
- **Electronics deck removable as one unit** (mini PC, router, SDR, hub, bucks on a single board/plate with Powerpole tails). Field service = pull the deck, not disassemble the case.
- Wire runs in cut channels below foam surface. Label BOTH ends of every wire. Photograph everything before the foam goes over it.

### 3.2 Case work

1. Cut foam with an electric carving knife or hot wire — snap blades tear foam.
2. Install bulkhead pass-throughs (sealed cable glands) for: solar MC4 pair, one Powerpole external power port, antenna feedlines (SMA bulkheads). Marine-grade glands keep the IP rating meaningful when closed.
3. Lid: laminated quick-start card, wiring diagram, paper maps, antenna elements. Flat items only.
4. Exterior: panel sleeve strapped to the case (the folded panel never fits inside — it's IP65, it rides outside), and the case gets ID marks that mean something to you and nothing to strangers.
5. TOOLS pocket: field spares bag (blade fuse assortment, 2 spare Powerpole sets, 1 spare buck converter, tape, multimeter).

### 3.3 Thermal rule

**Operate lid-open. Transport lid-closed.** The station is not designed to run sealed — a closed IP67 box is a heat trap. The bulkhead ports exist so short check-ins (charging a phone off the external port) don't require opening; sustained operation does.

---

## Phase 4 — Integration & Commissioning

### 4.1 Full-system acceptance test

Run the complete station for a continuous 24 hours on battery + solar only:

- [ ] AI answers queries from a phone over WiFi throughout
- [ ] Kiwix serves throughout
- [ ] SDR monitors NOAA weather; log at least one complete broadcast cycle
- [ ] GPS fix and time sync maintained
- [ ] SmartShunt SOC math is sane (net Wh in/out roughly balances your load log)
- [ ] Nothing exceeds warm-to-touch with the lid open
- [ ] Radios and 4 phones charged from the USB panel during the run

### 4.2 The Family Test (the actual S-tier gate)

Hand the closed case to your spouse or eldest kid with zero coaching. Using only the lid card, they must within 15 minutes: power the system on, join the WiFi, ask the AI a question, find something in Wikipedia, and make a radio call on FAMILY 1. **If they can't, fix the documentation — not the person — and re-run.**

### 4.3 Backup images (the resurrection kit)

1. Image the mini PC's drive (Clonezilla) to the 2TB SSD.
2. Copy `/bastion-sources/` (installers, ZIMs, configs, radio images, this guide) to the same drive.
3. Store the 2TB SSD + the cold-spare compute (Pi 5 or spare drive) in the Faraday bag. **This bag stays disconnected, always.** It is opened after an event or after a hardware death, not before.

---

## Phase 5 — Documentation Pack (in the lid, laminated)

1. **Quick-start card** (one page): power-on steps with photos, WiFi name/password, the two URLs, radio channel plan in plain English, "if it doesn't work" three-step (check disconnect switch → check shunt app → swap the deck).
2. **Wiring diagram** (one page): [wiring-diagram.svg](wiring-diagram.svg) with your actual wire labels written in.
3. **Manifest**: data sync date, model versions, radio image date, battery install date, GMRS call sign.
4. Paper topo maps of your region + baseplate compass.

---

## Phase 6 — Operations Doctrine

**Readiness state (default, at home):** batteries at 100%, case closed, panel in its sleeve, AC charger tops off monthly. Station lives indoors — conditioned space. It deploys TO a vehicle; it does not live in one (summer cabin heat murders battery calendar life; winter blocks charging on non-heated packs).

**Deployment:** case + panel + the separate med kit travel together. On site: lid open, panel out and angled at the sun, antennas up, system on, family devices join WiFi.

**Operating modes** (power discipline is capability):

- **LISTEN** (~10–15W): SDR + router + Kiwix, AI off. Days of runtime.
- **FULL** (~25–60W+): everything including AI. Default while solar is net-positive.
- **UPLINK** (adds ~25–40W): Starlink Mini live for scheduled windows — data refresh, forecasts, family check-ins — then off. Continuous Starlink roughly halves battery runtime; treat sky time as a rationed resource unless solar is net-positive.
- **SILENT**: all transmitters off (mesh beaconing and Starlink included), receive-only. This is also your low-profile mode — a mesh node broadcasting GPS is a beacon by design, and a dish is a visible, emitting signature; transmit only what the situation earns.

**Comms plan:** radio checks on FAMILY 1 at pre-agreed times, mesh for text/position within the team, SDR watching WEATHER. Rally points are on the paper map, not only in a device.

---

## Phase 7 — Maintenance Through End-of-Life

### Quarterly (calendar reminder — non-negotiable)

1. Cold boot the full station; run the Family Test checklist in 10 minutes.
2. Cycle batteries: discharge to ~40% under real load, recharge to full. Log SOC accuracy drift in the build log.
3. Inspect: connector seating, fuse corrosion, foam wear, gasket condition, gland tightness.

### Semi-annual

1. **Data refresh:** re-download ZIM updates and map extracts, update AI models if a better small model exists, re-sync `/bastion-sources/`, update the manifest date. With the Starlink Mini aboard, this can happen from the driveway over an UPLINK window instead of hauling drives to internet.
2. Re-image the mini PC to the backup SSD (after confirming the current system is healthy — never overwrite your only good image with a broken one; keep two rotating images).
3. Radio check against current local repeater listings; reflash if anything changed.
4. Firmware: MPPT/shunt via VictronConnect, router, mesh node — read changelogs first; if it isn't fixing something you have, skip it. Stability beats currency here.

### Annual

1. Full family drill: deploy from storage to operating in the yard, run 4 hours, everyone rotates through every role.
2. Capacity test: measured full discharge through the shunt. Log Wh delivered vs. rated.
3. Review the threat model and the kit against it. Update the guide.

### Replacement triggers (the "grave")

| Component | Replace when | Expected life |
|---|---|---|
| LiFePO4 batteries | Capacity test < 80% of rating | ~10 yr / 3–4k cycles at this duty |
| Mini PC | Won't hold time, thermal throttling at idle, or the cold spare gets promoted | 5–8 yr |
| Foam/gaskets | Compression set, crumbling | 5–10 yr |
| Radios | Battery swell, PTT failure | swap-not-repair |
| Panel | Output < 70% of commissioning baseline in same-sun test | 10+ yr |

**Decommissioning:** LiFePO4 to a battery recycler (call2recycle.org locates drop-offs) — discharge to ~30% and tape terminals first. Wipe drives (`nvme format` / full overwrite) before any disposal or resale — this box holds your family's locations, frequencies, and plans. Electronics to e-waste. The case outlives everything; it becomes the next build's shell.

### Long-term storage exception

If the station will sit unused 6+ months: store batteries at **50–60% SOC**, disconnect switch OFF, and set a reminder — LiFePO4 stored full ages faster; stored dead, the BMS eventually can't recover it.

---

## Appendix — Failure Playbook

| Symptom | First check | Second | Field fix |
|---|---|---|---|
| Nothing powers on | Disconnect switch | MRBF fuses | Shunt app shows SOC? If 0%, AC-charge or solar-charge before diagnosing further |
| WiFi up, AI down | Is mini PC circuit's fuse good? | SSH in; is Ollama service up? | Swap deck / promote cold spare |
| Wiki up, AI slow | Big model on a hot day | — | Switch WebUI to the small fallback model |
| No solar charge | MC4 seated? Panel shaded? | MPPT app error codes; temp cutoff active? | Warm batteries above 5°C, or AC charge |
| SDR silent | Antenna seated (SMA snug) | Known-good test: local FM broadcast | Swap dongle from Faraday spare |
| One radio dead | Battery seated | Reflash from configs folder | Operate single-radio; spares list next order |
| SOC obviously wrong | A negative wire bypassing the shunt | Re-zero at full charge | Cosmetic until fixed; run by voltage |
| Starlink dead / no internet | Boost cable output at the dish (~24V)? | Obstructions or account status in the app | Run offline — core services don't depend on it |

*Build once. Drill quarterly. Refresh semi-annually. The wiring is the easy part — the discipline is the product.*
