# Kit-6 report

The fetched base was `d7c7fc0`. The live kit contained 98 files. Its manifest covered 81. This branch adds SHA-256 pins and provenance for nine existing PDFs, taking manifest coverage to 90 of 98. The tactical trim and five operational manuals are outside the completed scope. The file inventory still has eight gaps.

## Sources pinned

All nine files were already present in the live kit. All use `origin: owner-download` and `tier: own`. The exact source URLs and whole-file reuse terms remain unconfirmed and are represented by null fields and flags. This metadata records the existing assets and does not establish redistribution rights. The ATAK digest matches the previously published local hash. The other eight are fingerprints of the current local bytes.

| File | Edition evidence | Provenance |
| --- | --- | --- |
| `ATAK-CIV-Icon-Glossary-v5.5-2024.pdf` | PDF cover dated 2024-11-18 | TAK Product Center. Extracted from the kit’s ATAK-CIV APK 5.5.1.8. [Acquisition record](../library-plan.md), [prior hash](../status.md) |
| `Field-Guide-Forest-Plants-Northern-Idaho-RMRS-GTR-118.pdf` | Printed 2004 Rocky Mountain Research Station issue. Title sheets show February and June. Flagged historic per the publisher’s archive cover. | USDA Forest Service. [Kit acquisition list](../status.md) |
| `Idaho-Big-Game-Seasons-Rules-2026.pdf` | 2026 seasons, July 2026 corrections | Idaho Department of Fish and Game. [Kit acquisition list](../status.md) |
| `Idaho-Fishing-Seasons-Rules-2025-2027.pdf` | Cover identifies the 2026 second edition, with 2025–2027 season coverage | Idaho Department of Fish and Game. [Kit acquisition list](../status.md) |
| `Idaho-Migratory-Game-Bird-2026-2027.pdf` | Cover identifies 2026–2027 seasons | Idaho Department of Fish and Game. [Kit acquisition list](../status.md) |
| `Idaho-Moose-Sheep-Goat-2025-2026.pdf` | 2025–2026 seasons, March 2026 corrections | Idaho Department of Fish and Game. [Kit acquisition list](../status.md) |
| `Special-Forest-Products-Pacific-Northwest-PNW-GTR-513.pdf` | Printed citation dated 2001 | USDA Forest Service, Pacific Northwest Research Station. [Kit acquisition list](../status.md) |
| `USDA-Complete-Guide-to-Home-Canning-2015.pdf` | Cover states revised 2015, Agriculture Information Bulletin No. 539 | USDA National Institute of Food and Agriculture. [Kit acquisition list](../status.md) |
| `Wild-Edible-Mushrooms-Blue-Mountains-PNW-GTR-393.pdf` | Cover dated January 1997. The local copy includes a later digitization notice. | USDA Forest Service, Pacific Northwest Research Station. [Kit acquisition list](../status.md) |

## Gaps

- The four Army operational manuals and biological-casualty handbook were excluded because of mixed tactical and dual-use biological content. The requested tactical-radio trim and its provenance file remain unproduced.
- `Idaho-Upland-Game-Turkey-Furbearer-2026-2027.pdf` combines regulations and operational trapping content. The whole PDF was excluded.
- `NOAA-Weather-Radio-Stations-US.md` states that its National Weather Service data were downloaded on 2026-11-22. Its header and the repository document its source family. The exact source inputs and generation script required by the manifest’s derived-entry schema are unrecorded.
- `Special-Operations-Forces-Medical-Handbook-2001.pdf` is listed in the kit’s acquisition history. Its precise acquisition, edition, redistribution terms and content scope remain unverified.

## Verification

- **Compiled on PC.** The existing Python tool and its tests were syntax-compiled in memory. The only implementation change is the nine new JSON entries.
- **Ran on PC.** `python -I -B tools/kit/test_kit.py -v` passed six tests, including the full updated manifest schema check.
- **Correctness tested on PC.** Compared the first 81 entries field for field with the base manifest. Used the existing `kit.py` verifier in a read-only Python harness with enumeration restricted to the 90 tracked local paths. Exit 0 reported 90 verified and zero missing, mismatched, unknown, unverified or unreadable **within that set**. An independent filename inventory found the eight gaps above. Unfiltered verification and the tactical-radio trim were untested.
- **App launch, import and devices untested.** The kit documents were read in place. No asset was copied, replaced or installed.
