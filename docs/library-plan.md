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
   FCC Part 97 (amateur) and Part 95 (GMRS/FRS); Meshtastic documentation.
9. **Rebuilding.** Peace Corps technical manuals (water systems, gardening, small livestock); FAO
   manuals *(confirm licence)*; USDA Extension bulletins; Army carpentry, electrical and engine
   manuals *(confirm current numbers)*; DOE/NREL small-solar guides.
10. **General reference (Reference tier).** WikiMed, iFixit.

Kept out: forums, blogs, anything whose origin cannot be named.

## Getting them

BASTION never downloads (it never connects to the internet). The PC kit tool fetches files,
the owner approves each download, and they reach the phone's `docs/` or `zim/` folder over USB.
