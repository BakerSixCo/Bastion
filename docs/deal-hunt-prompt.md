# BASTION Deal-Hunt Prompt — paste everything below into ChatGPT

---

You are a procurement researcher with web access. Find me the best current prices
on the parts list below. I am a military/veteran buyer with access to **GovX** and
**ExpertVoice**, plus retail military discounts (Home Depot, Lowe's ~10%).

## Your rules

1. For every line item return: **product name, brand, exact SKU/model number,
   direct product-page URL, current price, and the channel** (GovX / ExpertVoice /
   eBay sold-comp / Amazon / manufacturer / big-box + mil discount).
2. Verify the page is live and the item in stock **today**. No dead links, no
   "was available" listings. If you cannot verify a SKU, say so — do not invent one.
3. Where I list a candidate SKU marked *(verify)*, confirm or correct it.
4. For each item, check in this order: (a) GovX and ExpertVoice for the brand,
   (b) manufacturer direct with mil/vet discount, (c) eBay **sold** comps for the
   used-OK items only, (d) regular retail. Report the best two channels so I can
   compare.
5. **Never suggest used** for: batteries, fuses/fuse blocks/disconnect/wire/
   connectors/glands, the RTL-SDR (clone-riddled channel — direct from rtl-sdr.com
   or its authorized Amazon store only), the 2TB backup SSD, foam/gaskets, or the
   folding solar panel.
6. **Used/refurb is preferred** for: mini PCs (find me TWO identical units),
   Pelican-class case, Victron MPPT and SmartShunt (RV-teardown pulls; warn me off
   suspiciously cheap "new" Victron — that segment has counterfeits), test
   equipment, monitor/keyboard/hub, laminator.
7. Victron gear at "new" prices must be from an authorized dealer (Victron
   publishes a dealer list — check it).
8. Brands historically on GovX or ExpertVoice worth checking for this list:
   Pelican, Midland, Garmin, Leatherman, Goal Zero, BioLite, Klein Tools,
   Dometic. Programs rotate — verify current membership, don't assume.
9. Output one row per item in a table, grouped by my section numbers, with a
   final total for best-price-per-item. Flag any line where you couldn't meet the
   target price within 20%.
10. Do not substitute a cheaper item that misses a MUST spec. Substitutions that
    meet all MUST specs are welcome — flag them clearly.

## Parts list

### Section 1 — Power (buy NEW unless marked)

| Item | Qty | MUST specs | Candidate SKU | Target |
|---|---|---|---|---|
| LiFePO4 battery 12V 50Ah | 2 | Bluetooth BMS; low-temp charge cutoff OR self-heating; Grade A cells; ≥5yr warranty. Brands: LiTime, Redodo, Weize, Epoch | — | $110–130 ea |
| MRBF terminal fuse holder | 2 | Blue Sea, stud-mount on battery post | 5191 *(verify)* | $15 ea |
| MRBF fuse 60A | 2+2 spare | Blue Sea MRBF series | *(verify)* | $10 ea |
| Battery monitor shunt | 1 | Victron SmartShunt 500A/50mV, Bluetooth | SHU050150050 *(verify)* | new $115 / used $75 |
| MPPT controller | 1 | Victron SmartSolar 100/20 (fits 200W@12V, Voc margin) | SCC110020160R *(verify)* | new $105 / used $70 |
| Battery temp sense | 1 | Victron Smart Battery Sense | SBS050100200 *(verify)* | $30 |
| Folding solar panel 200W | 1 | IP65+, MC4 output, <25 lb. NEW ONLY | — | $170–250 |
| Battery disconnect switch | 1 | Blue Sea m-Series, 300A continuous | 6006 *(verify)* | $22 |
| Fuse block 12-circuit + neg bus | 1 | Blue Sea ST-Blade w/ negative bus | 5026 *(verify)* | $45 |
| Buck-boost converter 12V | 2 | 10–15V in → 12V regulated out, ≥2× device draw, ≥90% eff | — | $20 ea |
| Boost cable for Starlink Mini | 1 | 12V in → 24V+ out, 100W, 5.5×2.5mm barrel, ≥3m | — | $30 |
| AC LiFePO4 charger 12V 10A+ | 1 | 14.6V LiFePO4 profile | — | $50 |
| 12V USB charging panel | 1 | ≥4 ports incl. USB-C PD | — | $25 |
| Wire: 10 AWG (25ft pair), 12 AWG (10ft), 16 AWG (50ft) | — | Marine-grade tinned copper | — | $70 |
| Anderson Powerpole 15/45 kit | 1 | Genuine Anderson, 25+ pairs w/ contacts | — | $30 |
| Powerpole panel mount, inline 30A fuse holder, ring terminal kit (M10 + 5/16"), adhesive heat-shrink, MC4 extension pair, DC barrel pigtails, dielectric grease, threadlocker | — | — | — | $120 |

### Section 2 — Compute & network (USED PREFERRED where marked)

| Item | Qty | MUST specs | Target |
|---|---|---|---|
| Mini PC — **used/refurb, buy 2 identical** | 2 | x86, 16GB RAM, NVMe 500GB+, fanless or near-silent, DC barrel input, idles <10W (N100/N95/N305 class ideal) | $90–130 ea |
| Router GL.iNet | 1 | OpenWrt, AP+WISP modes, 12V input (Slate AX / Flint class) | $60–90 |
| USB GPS dongle | 1 | u-blox 7/8 chipset, gpsd-compatible | $20 |
| RTL-SDR — **NEW, direct only** | 1 | RTL-SDR Blog V4, w/ dipole antenna kit | $55 kit |
| Powered USB hub | 1 | 12V-powerable, ≥4 ports — used OK | $15 |
| 2TB SSD — **NEW** | 1 | Name-brand external or NVMe+enclosure | $110–130 |
| USB keyboard (wired), short HDMI cable — used OK | 1 | — | $15 |
| 7" HDMI field monitor | 1 | **12V barrel input** required — used OK | $40 |
| RJ45 bulkhead + 10m outdoor ethernet (optional) | 1 | IP67 bulkhead | $25 |

### Section 3 — Radio (check GovX/ExpertVoice: Midland lives here)

| Item | Qty | MUST specs | Target |
|---|---|---|---|
| GMRS handheld | 2 | CHIRP-programmable, NOAA RX, repeater-capable (Baofeng UV-9G / Wouxun KG-805G class) | $35–60 ea |
| Mesh node | 1 | Seeed Wio Tracker L1 (or current Meshtastic/Meshcore board) | $40 |
| GMRS mag-mount antenna | 1 | UHF 462–467MHz tuned, SMA — used OK | $25 |
| SMA bulkheads, RF adapter kit (SMA/RP-SMA/u.FL), programming cable | — | — | $55 |

### Section 4 — Case & mounting (USED case preferred; check GovX for Pelican)

| Item | Qty | MUST specs | Target |
|---|---|---|---|
| Hard case | 1 | Pelican 1600/1610/1620 or equal, intact gasket/hinges/latches — **used/local preferred, inspect first** | used $80–120 |
| Closed-cell foam sheets, marine cable glands (MC4 + M20 assortment), stainless M3–M5 fastener kit, standoffs, VHB + velcro, battery straps, zip ties + mounts, loom, grommets, marine sealant | — | — | $180 |

### Section 5 — Resurrection & docs

| Item | Qty | MUST specs | Target |
|---|---|---|---|
| Faraday bag | 1 | Certified shielding, fits SSD + mini PC (Mission Darkness / Faraday Defense class) | $30 |
| Paper topo maps (my region) + baseplate compass | 1 | — | $45 |
| Laminator + pouches — used/thrift OK | 1 | — | $15 |

### Section 6 — Tools (USED preferred — "used Fluke beats new no-name")

| Item | Qty | MUST specs | Target |
|---|---|---|---|
| Ratcheting crimper set | 1 | Powerpole + ring terminal dies | $40 |
| Multimeter ×2 | 2 | Used Fluke 87/115/117 class, or new Klein (ExpertVoice) | $40–60 ea |
| Electric carving knife (foam), label maker, field spares (fuse assortment, tape) | — | — | $70 |

## Final output I want from you

1. The full table with SKU + URL + price + channel per line.
2. Grand total at best price, vs. my target total of **~$2,500**.
3. Top 5 biggest savings you found and which channel delivered each.
4. Any line where GovX/ExpertVoice beat the used market — those are rare and I
   want to know.

---

*End of paste. (Repo note: targets assume ~$350 used-market savings per
docs/build-sheet.md § Used-market sourcing; specs trace to the build sheet and
are the source of truth if this file and the sheet ever disagree.)*
