# Kit retrieval measurement

**Emulator evaluation blocked by storage.** `emulator-5554` had 1,055,801,344 bytes free before transfer, 144,198,656 bytes below the 1.2 GB floor even using decimal GB. This branch contains the source-checked question set and the compiled instrumentation harness.

## Verification

- **Kit integrity, tested on PC.** On base `28cfda2`, `python -I tools/kit/kit.py verify C:/Users/ccie5/StudioProjects/Bastion/tools/kit/docs` returned 0 missing, 137 verified, 0 mismatched, 8 unknown, 0 unverified, 0 unreadable. The exit code was 1 for the documented unknowns. The 46 kit-4/5 transfers are covered by the manifest. The eight unpinned files listed in the ledger remain owner-gated, including the tactical and biological manuals.
- **Storage, tested on emulator.** `adb -s emulator-5554 shell df -k /data /sdcard` reported 1,031,056 KiB available on both mounts. The boot check returned 1, and the instrumentation scan was clear. The host kit snapshot totals 720,893,987 bytes before any extracted-text or index writes. Transfer stopped at preflight, so there is no post-transfer reading.
- **Question corpus, tested on PC.** `tools/model-eval/kit-questions.json` contains 44 answerable cases across 11 topics and eight unanswerable cases with no expected source or phrase. All 44 anchors were found in their 17 manifest-pinned source files using direct UTF-8 reads and offline `pypdf` extraction with whitespace normalization. The checks verified unique IDs and questions, four cases per topic, and manifest filename identity. Android extraction and retrieval quality remain unmeasured.
- **Compile and package, tested on PC.** Temurin 21 and the offline Gradle wrapper assembled the debug app and the final instrumentation APK. The JVM suite recorded 259 passes, two real-map skips because `BASTION_MAPS` was unset, and zero failures or errors.
- **App launch, import, and retrieval, not tested on emulator.** Space stopped the kit transfer before APK installation, app startup import, Home Rescan, or instrumentation. There are no observed import rejections, chunk counts, recorded tiers, top-three misses, or results for the eight negative questions. `RetrievalEvalTest` 13/13 and `ZimRetrievalEvalTest` 14/14 are required baselines and remain unverified for this run. The existing ZIM test assumes WikiMed is the only open archive.

## Instrumentation behavior

`KitRetrievalEvalTest` loads `kit-questions.json` from the app media folder and scores the five passages returned by the production Ask retriever. A hit needs the exact expected indexed filename and a case-insensitive, whitespace-normalized phrase in the same passage. The log includes the first three distinct source filenames on a miss, returned filenames for every unanswerable case, and totals by topic. The test reads library metadata and bulk import receipts for per-file state, chunk count, and recorded provenance tier. Retrieval and source policy are unchanged.

The app imports its docs folder on startup. Home → Rescan restores references. Once a target has sufficient space for the approved test corpus and its index, allow startup import to finish and use the existing in-place install and instrument workflow. The two baseline classes can be invoked together with the new test. They emit `RetrievalEval`, `ZimEval`, and `KitRetrievalEval` log lines.

```powershell
# From android/ with the local Temurin 21 JAVA_HOME.
.\gradlew.bat --offline --no-daemon '-Pandroid.builder.sdkDownload=false' :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 push ../tools/model-eval/kit-questions.json /sdcard/Android/media/com.bastion.terminal/kit-questions.json
$classes = 'com.bastion.terminal.ai.KitRetrievalEvalTest,com.bastion.terminal.data.RetrievalEvalTest,com.bastion.terminal.ai.ZimRetrievalEvalTest'
adb -s emulator-5554 shell am instrument -w -e class $classes com.bastion.terminal.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 logcat -d -s KitRetrievalEval:I RetrievalEval:I ZimEval:I
```

## Per-file preflight ledger

Every listed file was skipped before transfer. Indexed chunk counts and recorded tiers are unobserved for all rows. The directory tier is the application’s policy for that destination. The Meshtastic guide is under `reference/`. The two CISA trims are listed separately from the full host originals.

| Host filename | Bytes | Host verification | Directory tier | Emulator result |
|---|---:|---|---|---|
| `AFRRI-Medical-Management-Radiological-Casualties-5th-2022.pdf` | 1,166,336 | Verified | OWN | Skipped at preflight |
| `ATAK-CIV-Icon-Glossary-v5.5-2024.pdf` | 829,652 | Verified | OWN | Skipped at preflight |
| `ATAK-CIV-User-Guide-v5.5-2025.pdf` | 4,198,740 | Verified | OWN | Skipped at preflight |
| `Army-ATP-3-50.21-Survival-2018.pdf` | 23,772,309 | Owner-gated unknown | OWN | Skipped at preflight |
| `Army-ATP-4-02.11-Casualty-Response-TCCC-First-Aid-2026.pdf` | 12,665,561 | Verified | OWN | Skipped at preflight |
| `Army-ATP-4-02.83-Nuclear-Radiological-Casualties-2014.pdf` | 1,880,347 | Verified | OWN | Skipped at preflight |
| `Army-ATP-4-02.85-Chemical-Casualties-2016.pdf` | 3,688,306 | Verified | OWN | Skipped at preflight |
| `Army-ATP-4-25.12-Unit-Field-Sanitation-Teams-2014-C2-2025.pdf` | 5,049,825 | Verified | OWN | Skipped at preflight |
| `Army-ATP-6-02.53-Tactical-Radios-and-Retransmission-2025.pdf` | 6,063,028 | Owner-gated unknown | OWN | Skipped at preflight |
| `Army-FM-3-25.26-Map-Reading-and-Land-Navigation-2001.pdf` | 23,583,528 | Verified | OWN | Skipped at preflight |
| `Army-FM-7-22-Holistic-Health-and-Fitness-2020.pdf` | 4,282,557 | Verified | OWN | Skipped at preflight |
| `Army-TB-MED-507-Heat-Stress-Control-2022.pdf` | 2,596,231 | Verified | OWN | Skipped at preflight |
| `Army-TB-MED-508-Cold-Weather-Injuries-2026.pdf` | 7,202,752 | Verified | OWN | Skipped at preflight |
| `Army-TB-MED-577-Field-Water-Supplies-2010.pdf` | 1,890,594 | Verified | OWN | Skipped at preflight |
| `Army-TB-MED-593-Field-Waste-Management-2006.pdf` | 996,456 | Verified | OWN | Skipped at preflight |
| `Army-TC-21-3-Cold-Weather-Operations-Survival-2025.pdf` | 8,355,749 | Owner-gated unknown | OWN | Skipped at preflight |
| `Army-TC-3-21.76-Ranger-Handbook-2025.pdf` | 37,297,223 | Owner-gated unknown | OWN | Skipped at preflight |
| `Army-TC-4-02.3-Field-Hygiene-and-Sanitation-2015.pdf` | 362,339 | Verified | OWN | Skipped at preflight |
| `CDC-Make-Water-Safe-During-an-Emergency-2024.pdf` | 730,926 | Verified | OWN | Skipped at preflight |
| `CDC-Use-Safe-Water-During-an-Emergency-2024.pdf` | 141,092 | Verified | OWN | Skipped at preflight |
| `CISA-AUXFOG-Auxiliary-Communications-Field-Operations-Guide-v1.1-2016.pdf` | 3,427,518 | Verified | OWN | Skipped at preflight |
| `CISA-NIFOG-National-Interoperability-Field-Operations-Guide-v2.02-2025.pdf` | 6,735,362 | Verified | OWN | Skipped at preflight |
| `DOE-Consumer-Guide-Residential-Renewable-Energy-2022.pdf` | 1,220,875 | Verified | OWN | Skipped at preflight |
| `EPA-Burn-Wise-Best-Wood-Burning-Practices-Historic.md` | 4,221 | Verified | OWN | Skipped at preflight |
| `EPA-Burn-Wise-Tips-2010-Historic.pdf` | 65,282 | Verified | OWN | Skipped at preflight |
| `EPA-Drinking-Water-from-Household-Wells-EPA816-K-02-003-2002.pdf` | 1,684,285 | Verified | OWN | Skipped at preflight |
| `EPA-Emergency-Disinfection-of-Drinking-Water-2026.md` | 6,789 | Verified | OWN | Skipped at preflight |
| `FCC-47-CFR-Part-95-Personal-Radio-Services-2026.md` | 107,670 | Verified | OWN | Skipped at preflight |
| `FCC-47-CFR-Part-97-Amateur-Radio-Service-2026.md` | 131,293 | Verified | OWN | Skipped at preflight |
| `FCC-Consumer-Guide-Personal-Radio-Services.md` | 6,307 | Verified | OWN | Skipped at preflight |
| `FCC-Personal-Radio-Services-Channels-2026.md` | 18,000 | Verified | OWN | Skipped at preflight |
| `FDA-Insulin-Storage-Switching-Emergency.md` | 11,190 | Verified | OWN | Skipped at preflight |
| `FDA-Potassium-Iodide-Guidance-2001.pdf` | 40,894 | Verified | OWN | Skipped at preflight |
| `FDA-Potassium-Iodide-QA-2002.pdf` | 164,973 | Verified | OWN | Skipped at preflight |
| `FDA-Safe-Drug-Use-After-Natural-Disaster.md` | 4,293 | Verified | OWN | Skipped at preflight |
| `FEMA-Are-You-Ready-P-2064-2020.pdf` | 4,233,522 | Verified | OWN | Skipped at preflight |
| `FEMA-National-Incident-Management-System-Third-Edition-2017.pdf` | 3,476,202 | Verified | OWN | Skipped at preflight |
| `FEMA-Nuclear-Detonation-First-72-Hours-2023.pdf` | 5,894,390 | Verified | OWN | Skipped at preflight |
| `FEMA-Nuclear-Detonation-Planning-Guidance-3rd-2022.pdf` | 16,470,095 | Verified | OWN | Skipped at preflight |
| `FEMA-ORNL-6404-Simplified-Wood-Gas-Generator-1989.pdf` | 13,775,773 | Verified | OWN | Skipped at preflight |
| `Field-Guide-Forest-Plants-Northern-Idaho-RMRS-GTR-118.pdf` | 60,461,757 | Verified | OWN | Skipped at preflight |
| `GPO-Constitution-Annotated-2022.pdf` | 14,034,445 | Verified | OWN | Skipped at preflight |
| `GPO-Constitution-Annotated-2024-Supplement.pdf` | 5,642,051 | Verified | OWN | Skipped at preflight |
| `HHS-CHEMM-SALT-Triage-2026.md` | 5,485 | Verified | OWN | Skipped at preflight |
| `HHS-CHEMM-START-Triage-2026.md` | 3,346 | Verified | OWN | Skipped at preflight |
| `HHS-Ships-Medicine-Chest-Medical-Aid-at-Sea-2003.pdf` | 2,999,399 | Verified | OWN | Skipped at preflight |
| `Idaho-Big-Game-Seasons-Rules-2026.pdf` | 5,936,258 | Verified | OWN | Skipped at preflight |
| `Idaho-Fishing-Seasons-Rules-2025-2027.pdf` | 7,727,536 | Verified | OWN | Skipped at preflight |
| `Idaho-Legislature-Constitution.md` | 188,224 | Verified | OWN | Skipped at preflight |
| `Idaho-Migratory-Game-Bird-2026-2027.pdf` | 4,868,296 | Verified | OWN | Skipped at preflight |
| `Idaho-Moose-Sheep-Goat-2025-2026.pdf` | 4,471,647 | Verified | OWN | Skipped at preflight |
| `Idaho-Upland-Game-Turkey-Furbearer-2026-2027.pdf` | 8,460,526 | Owner-gated unknown | OWN | Skipped at preflight |
| `JTS-CPG-Airway_Management_in_Trauma_28_Jan_2026_ID39.pdf` | 2,006,311 | Verified | OWN | Skipped at preflight |
| `JTS-CPG-Altitude_Emergencies_Prehospital_Environment_05_Mar_2024_ID95_v1.2.pdf` | 3,337,681 | Verified | OWN | Skipped at preflight |
| `JTS-CPG-Analgesia_and_Sedation_Management_during_PFC_11_May_2017_ID61.pdf` | 2,627,536 | Verified | OWN | Skipped at preflight |
| `JTS-CPG-Burn_Management_PFC_13_Jan_2017_ID57.pdf` | 3,236,201 | Verified | OWN | Skipped at preflight |
| `JTS-CPG-Crush_Syndrome_PFC_28_Dec_2016_ID58.pdf` | 1,395,939 | Verified | OWN | Skipped at preflight |
| `JTS-CPG-Drowning_Management_17_Mar_2025_ID64_v1.1.pdf` | 2,042,139 | Verified | OWN | Skipped at preflight |
| `JTS-CPG-Eye_Trauma_Initial_Care_01_Jun_2021_ID03.pdf` | 2,878,235 | Verified | OWN | Skipped at preflight |
| `JTS-CPG-Frostbite_and_Immersion_Foot_Care_26_Jan_2017_ID_59.pdf` | 293,219 | Verified | OWN | Skipped at preflight |
| `JTS-CPG-Hypothermia_Prevention_Treatment_07_Jun_2023_ID23.pdf` | 1,355,521 | Verified | OWN | Skipped at preflight |
| `JTS-CPG-Infection_Prevention_in_Combat_Injuries_03_Nov_2026_ID24.pdf` | 1,980,132 | Verified | OWN | Skipped at preflight |
| `JTS-CPG-Nursing_Interventions_PCC_08_July_2025_ID70_v1.1.pdf` | 2,559,079 | Verified | OWN | Skipped at preflight |
| `JTS-CPG-Ocular_Injuries_Vision-Threatening_Conditions_PFC_01_Dec_2017_ID66.pdf` | 4,915,674 | Verified | OWN | Skipped at preflight |
| `JTS-CPG-Orthopaedic_Trauma_Extremity_Fractures_ID56_22_Jun_2026.pdf` | 3,948,349 | Verified | OWN | Skipped at preflight |
| `JTS-CPG-Prolonged_Casualty_Care_Guidelines_21_Dec_2021_ID91v1.1.pdf` | 1,642,638 | Verified | OWN | Skipped at preflight |
| `JTS-CPG-Sepsis_Management_PFC_28_Oct_2020_ID83.pdf` | 1,202,904 | Verified | OWN | Skipped at preflight |
| `JTS-CPG-Snakebite_Envenomation_CPG_ID81_26_Apr_2026_v1.1.pdf` | 14,141,242 | Verified | OWN | Skipped at preflight |
| `JTS-CPG-Spider_and_Scorpion_Envenomation_21_Jul_2026_ID84_v1.pdf` | 1,222,524 | Verified | OWN | Skipped at preflight |
| `JTS-CPG-Traumatic_Brain_Injury_PFC_06_Dec_2017_ID63.pdf` | 1,297,380 | Verified | OWN | Skipped at preflight |
| `JTS-CPG-Wound_Management_PFC_24_Jul_2017_ID62.pdf` | 2,681,476 | Verified | OWN | Skipped at preflight |
| `NARA-Articles-of-Confederation-1777.md` | 21,233 | Verified | OWN | Skipped at preflight |
| `NARA-Bill-of-Rights-1791.md` | 3,450 | Verified | OWN | Skipped at preflight |
| `NARA-Declaration-of-Independence-1776.md` | 9,610 | Verified | OWN | Skipped at preflight |
| `NARA-US-Constitution-1787.md` | 27,744 | Verified | OWN | Skipped at preflight |
| `NARA-US-Constitution-Amendments-11-27.md` | 18,255 | Verified | OWN | Skipped at preflight |
| `NCHFP-Drying.md` | 1,264 | Verified | OWN | Skipped at preflight |
| `NCHFP-Fermenting.md` | 1,195 | Verified | OWN | Skipped at preflight |
| `NCVEC-Technician-Question-Pool-2026-2030-Answers.md` | 63,066 | Verified | OWN | Skipped at preflight |
| `NCVEC-Technician-Question-Pool-2026-2030-Diagrams.pdf` | 278,003 | Verified | OWN | Skipped at preflight |
| `NIH-NCCIH-Chinese-Medicine-and-Herbs-at-a-Glance-2026.md` | 526,545 | Verified | OWN | Skipped at preflight |
| `NIST-SP-811-Guide-to-SI-2008.pdf` | 2,027,518 | Verified | OWN | Skipped at preflight |
| `NOAA-NWS-Cloudwise-Cloud-Chart.pdf` | 9,051,873 | Verified | OWN | Skipped at preflight |
| `NOAA-Weather-Radio-Stations-US.md` | 862,692 | Owner-gated unknown | OWN | Skipped at preflight |
| `NWCG-Basic-Land-Navigation-PMS-475-2016.pdf` | 30,650,584 | Verified | OWN | Skipped at preflight |
| `NWCG-IRPG-Incident-Response-Pocket-Guide-PMS-461-2025.pdf` | 3,045,707 | Verified | OWN | Skipped at preflight |
| `NWS-NOAA-Weather-Radio-SAME-Codes-2026.md` | 97,924 | Verified | OWN | Skipped at preflight |
| `Navy-NEETS-Module-01-Matter-Energy-and-Direct-Current-NAVEDTRA-14173A.pdf` | 6,988,744 | Verified | OWN | Skipped at preflight |
| `Navy-NEETS-Module-02-Alternating-Current-and-Transformers-NAVEDTRA-14174A.pdf` | 7,730,192 | Verified | OWN | Skipped at preflight |
| `Navy-NEETS-Module-03-Circuit-Protection-Control-and-Measurement-NAVEDTRA-14175A.pdf` | 6,302,168 | Verified | OWN | Skipped at preflight |
| `Navy-NEETS-Module-04-Conductors-Wiring-Soldering-and-Schematics-NAVEDTRA-14176A.pdf` | 3,989,839 | Verified | OWN | Skipped at preflight |
| `Navy-NEETS-Module-07-Solid-State-Devices-and-Power-Supplies-NAVEDTRA-14179A.pdf` | 6,938,505 | Verified | OWN | Skipped at preflight |
| `Navy-NEETS-Module-10-Wave-Propagation-Transmission-Lines-and-Antennas-NAVEDTRA-14182A.pdf` | 7,138,987 | Verified | OWN | Skipped at preflight |
| `Navy-NEETS-Module-16-Test-Equipment-Multimeters-NAVEDTRA-14188A.pdf` | 7,844,341 | Verified | OWN | Skipped at preflight |
| `Navy-NEETS-Module-17-Radio-Frequency-Communications-Principles-NAVEDTRA-14189A.pdf` | 5,272,853 | Verified | OWN | Skipped at preflight |
| `Navy-NEETS-Module-19-Technicians-Handbook-Safety-and-Tools-NAVEDTRA-14191.pdf` | 3,725,275 | Verified | OWN | Skipped at preflight |
| `Navy-NEETS-Module-20-Master-Glossary-NAVEDTRA-14192.pdf` | 558,651 | Verified | OWN | Skipped at preflight |
| `Navy-NEETS-Module-21-Test-Methods-and-Practices-Troubleshooting-NAVEDTRA-14193A.pdf` | 5,703,152 | Verified | OWN | Skipped at preflight |
| `ORNL-5037-Nuclear-War-Survival-Skills-1979.pdf` | 34,721,298 | Verified | OWN | Skipped at preflight |
| `PNNL-PV-Battery-Storage-for-Power-Outages-2021.md` | 10,697 | Verified | OWN | Skipped at preflight |
| `Peace-Corps-R0025-Intensive-Vegetable-Gardening-2014.pdf` | 4,629,096 | Verified | OWN | Skipped at preflight |
| `Peace-Corps-T0020-Improved-Food-Drying-and-Storage-1984.pdf` | 1,071,888 | Verified | OWN | Skipped at preflight |
| `PeaceCorps-Backyard-Rabbit-Production-R0041-2014.pdf` | 10,273,187 | Verified | OWN | Skipped at preflight |
| `PeaceCorps-Practical-Poultry-Raising-M0011-2015.pdf` | 8,053,695 | Verified | OWN | Skipped at preflight |
| `Special-Forest-Products-Pacific-Northwest-PNW-GTR-513.pdf` | 19,128,907 | Verified | OWN | Skipped at preflight |
| `Special-Operations-Forces-Medical-Handbook-2001.pdf` | 39,435,808 | Owner-gated unknown | OWN | Skipped at preflight |
| `UF-IFAS-ENH1383-Seed-Saving-2024.pdf` | 4,876,494 | Verified | OWN | Skipped at preflight |
| `USAMRIID-Medical-Management-Biological-Casualties-10th-2025.pdf` | 4,482,834 | Owner-gated unknown | OWN | Skipped at preflight |
| `USCG-Boaters-Guide-Federal-Requirements-2023.pdf` | 2,545,583 | Verified | OWN | Skipped at preflight |
| `USCG-Emergency-Radio-Procedures-Placard.pdf` | 28,063 | Verified | OWN | Skipped at preflight |
| `USCG-NAVCEN-Marine-VHF-Channels-2026.md` | 15,138 | Verified | OWN | Skipped at preflight |
| `USCourts-Federal-Rules-of-Civil-Procedure-2025.pdf` | 415,707 | Verified | OWN | Skipped at preflight |
| `USCourts-Federal-Rules-of-Criminal-Procedure-2025.pdf` | 281,843 | Verified | OWN | Skipped at preflight |
| `USCourts-Federal-Rules-of-Evidence-2025.pdf` | 165,892 | Verified | OWN | Skipped at preflight |
| `USDA-Complete-Guide-to-Home-Canning-2015.pdf` | 17,320,124 | Verified | OWN | Skipped at preflight |
| `USDA-FB2265-Pork-Slaughtering-Cutting-Preserving-Cooking-on-the-Farm-1981.txt` | 73,709 | Verified | OWN | Skipped at preflight |
| `USDA-FSIS-Meat-Food-Safety-2026.md` | 28,796 | Verified | OWN | Skipped at preflight |
| `USDA-Historic-FB1775-Homemade-Bread-Cake-Pastry-1942.pdf` | 1,444,192 | Verified | OWN | Skipped at preflight |
| `USDA-Historic-FB2152-Slaughtering-Cutting-Processing-Lamb-Mutton-1967.pdf` | 11,767,869 | Verified | OWN | Skipped at preflight |
| `USDA-Historic-FB876-Making-Butter-on-the-Farm-1924.pdf` | 1,024,811 | Verified | OWN | Skipped at preflight |
| `USDA-Historic-Slaughtering-Cutting-Processing-Beef-on-the-Farm-1973.pdf` | 3,763,475 | Verified | OWN | Skipped at preflight |
| `USFS-One-Moving-Part-2020-Chapter-11a.pdf` | 8,493,128 | Verified | OWN | Skipped at preflight |
| `USFS-One-Moving-Part-2020-Part0000_RearCovers.pdf` | 4,074,983 | Verified | OWN | Skipped at preflight |
| `USFS-One-Moving-Part-2020-Part01_Intro.pdf` | 348,245 | Verified | OWN | Skipped at preflight |
| `USFS-One-Moving-Part-2020-Part02-FS-Relevance-Safety.pdf` | 357,655 | Verified | OWN | Skipped at preflight |
| `USFS-One-Moving-Part-2020-Part04_Anatomy.pdf` | 664,643 | Verified | OWN | Skipped at preflight |
| `USFS-One-Moving-Part-2020-Part05_Types.pdf` | 6,357,893 | Verified | OWN | Skipped at preflight |
| `USFS-One-Moving-Part-2020-Part06_SelectingAnAx.pdf` | 4,365,589 | Verified | OWN | Skipped at preflight |
| `USFS-One-Moving-Part-2020-Part07_Files.pdf` | 2,439,015 | Verified | OWN | Skipped at preflight |
| `USFS-One-Moving-Part-2020-Part08_Sharpening.pdf` | 6,309,243 | Verified | OWN | Skipped at preflight |
| `USFS-One-Moving-Part-2020-Part09_ReplacingHandles.pdf` | 2,837,148 | Verified | OWN | Skipped at preflight |
| `USFS-One-Moving-Part-2020-Part10_HangingAnAx.pdf` | 3,846,589 | Verified | OWN | Skipped at preflight |
| `USFS-One-Moving-Part-2020-Part12_MaintainingAnAx.pdf` | 1,767,432 | Verified | OWN | Skipped at preflight |
| `USFS-One-Moving-Part-2020-Part14_Conclusion+Resources-new.pdf` | 1,677,330 | Verified | OWN | Skipped at preflight |
| `USFS-One-Moving-Part-2020-Part15_Appendixes.pdf` | 3,815,317 | Verified | OWN | Skipped at preflight |
| `USGS-Topographic-Map-Symbols.pdf` | 2,197,818 | Verified | OWN | Skipped at preflight |
| `USGS-US-Topo-Map-Symbols.pdf` | 936,022 | Verified | OWN | Skipped at preflight |
| `United-Nations-Universal-Declaration-of-Human-Rights.pdf` | 98,029 | Verified | OWN | Skipped at preflight |
| `WHO-ICRC-Basic-Emergency-Care-2018.pdf` | 4,257,552 | Verified | OWN | Skipped at preflight |
| `WHO-Managing-Complications-Pregnancy-Childbirth-2nd-2017.pdf` | 5,110,597 | Verified | OWN | Skipped at preflight |
| `WHO-Model-List-Essential-Medicines-24th-2025.pdf` | 1,364,687 | Verified | OWN | Skipped at preflight |
| `Wild-Edible-Mushrooms-Blue-Mountains-PNW-GTR-393.pdf` | 977,504 | Verified | OWN | Skipped at preflight |
| `army-tm-3-34-46-electrical-systems.pdf` | 9,615,844 | Verified | OWN | Skipped at preflight |
| `cpsc-5123-portable-generator-hazards.pdf` | 111,498 | Verified | OWN | Skipped at preflight |
| `reference/Meshtastic-User-Guide-2026-11.md` | 636,115 | Verified | REFERENCE | Skipped at preflight |
| `CISA-AUXFOG-v1.1-kit-trim.pdf` | 1,789,629 | Hash matches kit-1 report | OWN | Skipped at preflight. The trim is in the Codex worktree’s local kit output. |
| `CISA-NIFOG-v2.02-kit-trim.pdf` | 2,269,535 | Hash matches kit-1 report | OWN | Skipped at preflight. The trim is in the Codex worktree’s local kit output. |
