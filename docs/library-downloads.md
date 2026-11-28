# Library downloads: what to fetch, and what needs permission

Researched 2026-11-24 from the publishers' own sites (read only; the owner approves each
download). Companion to `docs/library-plan.md`. Sizes are "unknown" where no page states them.

## A. Free to include: government works (public domain) or open licences

| # | Document (edition) | Publisher | Official source | Size | Replaces / note |
|---|---|---|---|---|---|
| A1 | ATP 3-50.21 *Survival* (18 Sep 2018) | US Army | armypubs.army.mil, PUB_ID 1005316 (`ARN12086_ATP 3-50x21 FINAL WEB 2.pdf`) | unknown | Newer than FM 21-76 (1992); keep FM 21-76 only until this is in |
| A2 | ATP 4-02.11 *Casualty Response, TCCC, and First Aid* (23 Mar 2026) | US Army | armypubs.army.mil `ARN46159-ATP_4-02.11-000-WEB-1.pdf` | unknown | Supersedes TC 4-02.1 (2016) |
| A3 | TC 3-25.26 *Map Reading and Land Navigation* (15 Nov 2013) | US Army | armypubs.army.mil `ARN43276-TC_3-25.26-000-WEB-0.pdf` | unknown | Supersedes FM 3-25.26 |
| A4 | *Nuclear War Survival Skills*, Kearny, ORNL-5037 (1979) | Oak Ridge National Lab / DOE | osti.gov/servlets/purl/814213 | unknown | Only the 1979 ORNL text; later OISM editions are copyrighted |
| A5 | *Are You Ready?* P-2064 (Sep 2020) | FEMA / Ready.gov | ready.gov/sites/default/files/2021-11/are-you-ready-guide.pdf | ~4 MB | |
| A6 | *Planning Guidance for Response to a Nuclear Detonation*, 3rd ed. (May 2022) | FEMA (interagency) | fema.gov `fema_nuc-detonation-planning-guide.pdf` | unknown | Supersedes 2nd ed. (2010) |
| A7 | *Nuclear Detonation Response Guidance: First 72 Hours* (2023) | FEMA | fema.gov `fema_oet-72-hour-nuclear-detonation-response-guidance.pdf` | unknown | |
| A8 | *Emergency Disinfection of Drinking Water* (web page, Feb 2026) | US EPA | epa.gov/ground-water-and-drinking-water/emergency-disinfection-drinking-water | page | Saved as text |
| A9 | *Make Water Safe During an Emergency*, *Use Safe Water During an Emergency* (2024) | CDC | cdc.gov/water-emergency/… (two PDFs) | unknown | |
| A10 | KI guidance (2001) and KI Q&A (2002) | FDA | fda.gov/media/72510/download, fda.gov/media/72515/download | 40 KB + ? | |
| A11 | WHO Model List of Essential Medicines, 24th (2025) | WHO | who.int/publications/i/item/B09474 | 1.3 MB | CC BY-NC-SA 3.0 IGO: keep the licence notice with it |
| A12 | Peace Corps ICE technical manuals (chosen titles) | US Peace Corps | files.peacecorps.gov/documents/… | per file | Check each file: some reprint third-party material "with permission" |

ZIMs (Kiwix catalog, openZIM-packaged):

| # | File | Size | Tier | Note |
|---|---|---|---|---|
| Z1 | `ready.gov_en_all_2026-10.zim` | 2.06 GB | Official | FEMA Ready.gov |
| Z2 | `usda-2015_en_2025-04.zim` | 19.8 MB | Official | Same USDA canning guide we have; skip unless replacing the PDF |
| Z3 | `medlineplus.gov_en_all_2025-01.zim` | 1.94 GB | Official | Already in the kit (not on the emulator: space) |
| Z4 | `zimgit-water_en_2024-08.zim` | 21 MB | Reference until vetted | PDF bundle; BASTION extracts its PDFs into the library |
| Z5 | `zimgit-medicine_en_2024-08.zim` | 70 MB | Reference until vetted | PDF bundle, licences per book unknown |
| Z6 | `zimgit-food-preparation_en_2025-04.zim` | 98 MB | Reference until vetted | PDF bundle |
| Z7 | `zimgit-knots_en_2024-08.zim` | 28 MB | Reference until vetted | PDF bundle |

## B. Needs the publisher's permission first (not freely redistributable)

| Document | Publisher | Terms found | Contact |
|---|---|---|---|
| *Where There Is No Doctor*, *…No Dentist*, *A Book for Midwives* | Hesperian | Free print copying for non-commercial use; **any digital distribution needs written permission** | permissions@hesperian.org |
| *The Sphere Handbook* (4th ed., 2018) | Sphere Association | Free to download for education; **posting/reuse needs permission** | info@spherestandards.org |
| *International Medical Guide for Ships* (3rd ed.) | WHO | Pre-2016 WHO book, likely "all rights reserved" (not confirmed) | WHO permissions |
| *Psychological First Aid: Guide for Field Workers* (2011) | WHO / partners | Licence not confirmed | WHO permissions |
| TCCC Guidelines (2024) | Joint Trauma System | Sign-in only since Dec 2023; reuse terms not readable | JTS |

Note: *Where There Is No Doctor* is already in the library through the post-disaster bundle.
Whether that copy may stay depends on Hesperian's answer.

## B2. Free substitutes for section B (researched 2026-11-24)

US government titles are public domain; WHO titles marked CC BY-NC-SA 3.0 IGO need the licence
notice kept with the file (fine for a free, non-commercial app).

| # | Replaces | Document (edition) | Publisher | Official source | Licence |
|---|---|---|---|---|---|
| F1 | WTND, ships guide | *The Ship's Medicine Chest and Medical Aid at Sea* (2003) | HHS / Public Health Service | purl.fdlp.gov/GPO/gpo20582 | Public domain (dated 2003) |
| F2 | WTND (emergencies) | *Basic Emergency Care: approach to the acutely ill and injured* (2018) | WHO + ICRC | who.int/publications/i/item/9789241513081 (4.1 MB) | CC BY-NC-SA 3.0 IGO |
| F3 | WTND (trauma) | *Emergency War Surgery*, 5th US revision (2018) | Borden Institute, US Army | bookstore.gpo.gov/node/19752 (free PDF) | Public domain |
| F4 | WTND (austere care) | JTS Clinical Practice Guidelines: Prolonged Casualty Care, Sepsis/TBI/Analgesia in prolonged field care, Wound care & splints (2025), Altitude emergencies (2024) | Joint Trauma System (DoD) | jts.health.mil/index.cfm/PI_CPGs/cpgs | DoD work, public domain likely (no statement on page) |
| F5 | Book for Midwives | *Managing Complications in Pregnancy and Childbirth*, 2nd ed. (2017) | WHO | who.int/publications/i/item/9789241565493 (4.9 MB) | CC BY-NC-SA 3.0 IGO |
| F6 | Book for Midwives (lay) | *Emergency Childbirth* (US Civil Defense + PHS, mid-1900s) | US government | Project Gutenberg #26923 (no official host) | Public domain, content old |
| F7 | Sphere (sanitation) | ATP 4-25.12 *Unit Field Sanitation Teams* (2014, Change 2 2025) | US Army | armypubs PUB_ID 104111 | Public domain |
| F8 | Sphere (hygiene) | TC 4-02.3 *Field Hygiene and Sanitation* (2015) | US Army | armypubs PUB_ID 105251 | Public domain |
| F9 | Sphere (water) | TB MED 577 *Sanitary Control and Surveillance of Field Water Supplies* (2010) | US Army | armypubs PUB_ID 86297 | Public domain |
| F10 | Sphere (waste) | TB MED 593 *Guidelines for Field Waste Management* (2006) | US Army | armypubs PUB_ID 83563 | Public domain |
| F11 | Sphere (heat) | TB MED 507 *Heat Stress Control and Heat Casualty Management* (2022) | US Army | armypubs PUB_ID 1024722 | Public domain |
| F12 | Sphere (cold) | TB MED 508 *Prevention and Management of Cold-Weather Injuries* (2026) | US Army | armypubs PUB_ID 1032535 | Public domain |
| F13 | Sphere (cold) | TC 21-3 *Individual Operations and Survival in Cold-Weather Areas* (2025) | US Army | armypubs PUB_ID 1032109 | Public domain |
| F14 | PFA | SAMHSA Disaster Technical Assistance Center survivor tip sheets | SAMHSA (HHS) | samhsa.gov/technical-assistance/dtac/disaster-survivors | Public domain (check each sheet) |
| F15 | PFA | FM 7-22 *Holistic Health and Fitness* (2020) | US Army | armypubs PUB_ID 1020968 | Public domain |
| F16 | TCCC | ATP 4-02.11 (2026), already A2: care under fire, tactical field care, evacuation | US Army | see A2 | Public domain |
| F17 | CBRN | *Medical Management of Chemical Casualties Handbook*, 5th ed. (2014) | USAMRICD / Borden | ccc.apgea.army.mil (URL not confirmed) | Public domain |
| F18 | CBRN | *Medical Management of Biological Casualties* ("Blue Book"), 10th ed. (2025) | USAMRIID | usamriid.health.mil/assets/docs/training/Blue_Book_10th_Edition.pdf | Public domain |
| F19 | CBRN | *Medical Management of Radiological Casualties*, 5th ed. (2022) | AFRRI / USU | afrri.usuhs.edu/node/133734 | Public domain |
| F20 | CBRN | ATP 4-02.83 / 4-02.84 / 4-02.85 (nuclear, biological, chemical casualties) | US Army (multi-service) | armypubs PUB_ID 104161, 1008190, 1001926 | Public domain |

Still open (no confirmed free source):
- **Dentistry:** no lay emergency-dental guide confirmed free. Candidates: WHO AFRO *Promoting Oral
  Health in Africa* (2016, licence not stated), Army TC 8-20-1 *Dental Specialist* (public domain,
  but a dental-assistant manual).
- **A full psychological-first-aid protocol:** the US PFA Field Operations Guide is marked
  "all rights reserved"; SAMHSA sheets and FM 7-22 cover parts.
- **Written TCCC algorithm:** only behind the Deployed Medicine sign-in; ATP 4-02.11 covers
  TCCC care but may not reprint the charts.
- **A humanitarian standard like Sphere:** none open; the Army sanitation set (F7–F13) covers the
  field part.

Already in the library and now in doubt: the **SOF Medical Handbook (2001)** release status could
not be confirmed. F3/F4 cover the same ground; review whether to keep it.

## C. Excluded

- Ranger Medic Handbook: sold commercially, restricted to Ranger medics.
- *So Easy to Preserve* (NCHFP/UGA): copyrighted, sold.
- Nuclear War Survival Skills, OISM 1987+ editions: copyrighted additions.
- YouTube-channel ZIMs (prepper channels), Stack Exchange ZIMs: forums or opinion.
- WikEM: not on Kiwix.

## Space

The emulator has ~0.9 GB free, so the 2 GB ZIMs are for the phone (SD card) only; all of A plus
Z4–Z7 is a few hundred MB.
