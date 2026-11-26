# BASTION knowledge library plan

Owner decisions, 2026-11-24. The AI does not learn this material: it searches the library and
reads passages when asked, so the library *is* its knowledge. A bad source means bad answers.

## Source tiers

| Tier | What | Used for AI answers |
|---|---|---|
| **Own** | Documents the owner imported, wrote, or put in the kit's docs folder | Yes, as truth |
| **Official** | Government, military, public-health and standards bodies (CDC, NIH MedlinePlus, NHS, US Army/USAF, USDA, USFS, NOAA, FEMA, EPA, WHO, state agencies) | Yes, as truth |
| **Reference** | Reputable but volunteer-edited or mixed: WikiMed (Wikipedia medicine), iFixit, Appropedia, Energypedia, mixed document collections | Only when no Own/Official passage covers the question |
| **Forum** | Q&A and discussion sites (all Stack Exchange ZIMs), blogs | **Never** |
| **Unrated** | A reference BASTION cannot classify | **Never**, until classified |

Rules:
- When Own/Official passages cover the question's subject, Reference passages are not used.
- The same text in two sources is kept once, from the higher tier.
- Overlapping editions: keep the newest official one (ATP 3-50.21 replaces FM 21-76).
- Classification lives in `ai/SourceTier.kt` (by ZIM file name, and by provenance for library
  documents). A new reference is Unrated until it is added there.

## Audit of what we have (2026-11-24)

| Source | Tier |
|---|---|
| FM 21-76 Army Survival Manual, SOF Medical Handbook, USDA Home Canning, USFS field guides, Idaho Fish & Game regulations, NOAA weather radio list | Own (kit docs folder); all government |
| CDC (`wwwnc.cdc.gov`), MedlinePlus, NHS medicines, military medicine (`irp.fas.org`) | Official |
| WikiMed, iFixit, Appropedia, Energypedia | Reference |
| Post-disaster collection (`zimgit-post-disaster`) and its PDFs | Reference: mixed. Contains solid material (US military, HHS, Hesperian) and documents of unclear origin ("Berkeley, California", "Personal Wilderness EMS", "Survival and Austere", `uta4394.tmp`, a commercial wound-closure manual). Vet document by document; promote the good ones into the docs folder. |
| Stack Exchange: Outdoors, Mechanics, Gardening, Ham, DIY, Cooking, Woodworking, Sustainability | Forum: not used for answers |

FM 21-76 (1992) is superseded by ATP 3-50.21 (2018): replace it when the new one is in the kit.

## Doomsday library, by survival priority

Only US-government works (public domain) or material published free to share. *(confirm)* =
check the current edition, title or licence before adding.

1. **Minutes: trauma, airway.** TC 4-02.1 First Aid (Army); TCCC guidelines (CoTCCC); SOF
   Medical Handbook (have); Ranger Medic Handbook *(confirm free to share)*.
2. **Hours: exposure, shelter.** ATP 3-50.21 Survival (2018); Sphere Handbook (shelter); CDC/NWS
   cold and heat illness.
3. **Days: water.** EPA Emergency Disinfection of Drinking Water; CDC water treatment; WHO
   household water treatment in emergencies *(confirm title)*; Sphere (WASH).
4. **Weeks: food.** USDA Complete Guide to Home Canning (have) and NCHFP; survival-manual plant
   and trapping chapters; USFS regional plant/mushroom guides (have some); state hunting and
   fishing regulations (have Idaho).
5. **Months: medicine with no doctor.** Hesperian: Where There Is No Doctor, Where There Is No
   Dentist, A Book for Midwives; WHO International Medical Guide for Ships; WHO Model List of
   Essential Medicines; MedlinePlus drugs (kit); NHS medicines (have); WHO Psychological First Aid.
6. **Disease and sanitation.** CDC Yellow Book (have); Sphere hygiene and latrines; HHS pandemic
   plan (have).
7. **Nuclear, chemical, biological.** Nuclear War Survival Skills (Kearny, ORNL, public domain);
   FEMA nuclear detonation response planning *(confirm title)*; CDC radiation emergencies; FDA
   potassium iodide guidance.
8. **Navigation, communications.** TC 3-25.26 Map Reading and Land Navigation; NOAA list (have);
   FCC Part 97 (amateur) and Part 95 (GMRS/FRS) (have); Meshtastic documentation (have, pending
   tier). See "Radio frequencies and operation" below.
9. **Rebuilding.** Peace Corps technical manuals (water systems, gardening, small livestock); FAO
   manuals *(confirm licence)*; USDA Extension bulletins; Army carpentry, electrical and engine
   manuals *(confirm current numbers)*; DOE/NREL small-solar guides.
10. **General reference (Reference tier).** WikiMed, iFixit.

Kept out: forums, blogs, anything whose origin cannot be named.

## Radio frequencies and operation (owner request 2026-11-26)

Goal: frequency lists for every radio service a kit user may hold (NOAA weather, FRS, GMRS, MURS,
CB, marine VHF, amateur, aviation/SAR emergency) and how to operate them "at a common level".
Downloaded 2026-11-26 into `tools/kit/docs/` (managed assets, never committed); SHA-256 recorded
in `docs/status.md`.

| File | Source | What it gives |
|---|---|---|
| `CISA-NIFOG-...-v2.02-2025.pdf` | CISA (DHS), Jan 2025 | National interoperability, SAR, aviation, marine, amateur emergency/calling frequencies; CTCSS/DCS tone tables; line-of-sight formulas; phonetic alphabet; satellite phone dialing. One embedded figure (RJ-45 pinout) is third-party copyright; the rest is a federal work, published free by CISA. |
| `CISA-AUXFOG-...-v1.1-2016.pdf` | DHS OEC, June 2016 | Volunteer emergency comms: safety, go kit, amateur band plan and emergency/calling frequencies, MURS/CB/marine tables, **field-expedient antennas**, coded squelch, GMRS/FRS, ICS forms 205/213/214. |
| `FCC-Personal-Radio-Services-Channels-2026.md` | FCC service pages (GMRS, FRS, MURS, CB, amateur) | Channel tables, licence rules, ranges, dual FRS/GMRS radios, CB channel 9. |
| `FCC-47-CFR-Part-95-...-2026.md` | eCFR, current 2026-11-24 | The binding rules: Subparts A, B (FRS), D (CB), E (GMRS), J (MURS), K (personal locator beacons). |
| `FCC-47-CFR-Part-97-...-2026.md` | eCFR, current 2026-11-24 | Amateur rules, incl. § 97.301 bands by licence class and § 97.403 safety of life and protection of property. |
| `USCG-NAVCEN-Marine-VHF-Channels-2026.md` | USCG Navigation Center | U.S. marine channel table, WX channels, **voice MAYDAY procedure**, **DSC distress and how to cancel a false alert** ("DO NOT power off the radio before cancelling"), calling a ship, channel 9/16 rules. |
| `NWS-NOAA-Weather-Radio-SAME-Codes-2026.md` | National Weather Service | How to program a SAME weather radio, all event codes, 3,295 county codes. |
| `NWCG-IRPG-...-PMS-461-2025.pdf` | NWCG (federal interagency), Jan 2025 | Field radio practice in incidents: size-up and medical incident reports, helicopter landing zones, radio safety (UXO, lightning, no patient names on air). |
| `tools/kit/reference-pending/Meshtastic-Documentation-2026-11.md` | Meshtastic docs, GPL-3.0, commit 8210e9d | Node setup, channels, LoRa regions, antennas, device buttons, Android app. **Community docs: Reference tier.** Not in the docs folder yet, because every kit document is currently Own tier; needs a `SourceTier` rule for it (with a `SourceTierTest`) first. |

Not obtained, and why:
- ATP 6-02.53 *Techniques for Tactical Radios and Retransmission* (2025) and TC 3-21.76 *Ranger
  Handbook* (2025): Army Publishing lists no public PDF (CAC sign-in). The public 2017 Ranger
  Handbook is superseded; not used.
- FCC "Personal Radio Services" consumer PDF and the USCG *Emergency Radio Procedures* PDF: the
  servers refuse non-browser downloads; the same content is covered by the FCC pages and NAVCEN text above.
- Repeater directories (RepeaterBook, ARRL): not free to share. Local repeaters are owner data (a note).
- Device manuals (a specific GMRS handheld, the Wio Tracker L1 Pro, a weather radio): copyrighted by the
  maker. The owner may import the manuals for radios they own as their own documents.

Cards to draft (Fable, after the 2026-11-30 reset; Opus review; owner approval), each quoting these
files verbatim: calling for help by radio (CB 9, marine 16 MAYDAY, GMRS/FRS, amateur emergency
frequencies); marine MAYDAY wording; cancelling a false DSC alert; programming a weather radio; FRS
vs GMRS (licence, which channels need one); MURS channels; using a handheld at a common level (listen
before talk, range and height, what CTCSS/DCS tones are: NIFOG/AUXFOG coded squelch; the common
claim that "privacy codes" give no privacy is in none of these files, so a card may say it only
once a source does); phonetic alphabet; field-expedient antenna (AUXFOG Appendix D). Radio-short versions only
where the card's own lines fit (`radio` field).

## Getting them

BASTION never downloads (it never connects to the internet). The PC kit tool fetches files,
the owner approves each download, and they reach the phone's `docs/` or `zim/` folder over USB.
