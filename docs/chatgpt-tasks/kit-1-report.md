# Kit-1 report

Branch `chatgpt/kit-1`, based on `origin/claude/hello-e9r4rq` at `49125b5`. Source files were read from Claude's `tools/kit/docs`. The worktree's ignored `tools/kit/out` is the staging location for generated PDFs and their provenance.

## Dependency

Installed `pypdf` 7.2.0 for the system Python 3.12 user account. The wheel is BSD-3-Clause and pip checked SHA-256 `5edb9b033dd55cecead9303370e6854c1a3b979669bde1c46024cde0228405b1` against the pinned download URL. [PyPI release and wheel checksum](https://pypi.org/project/pypdf/7.2.0/#files)

The command used `python -m pip --isolated --disable-pip-version-check install --user --no-deps --no-index` with the [pinned wheel](https://files.pythonhosted.org/packages/9d/b5/36b52010264f40f35fcd469f62586c0d88753c16682f2e156c42347f884b/pypdf-7.2.0-py3-none-any.whl) and a `#sha256` URL fragment. No kit reference downloads were run.

## Manifest and verification

`tools/kit/sources.json` records 81 known documents from the live root and `reference/` folder, including six browser copies. Entries preserve the documented URL or acquisition method, publisher, edition and licence when available, source tier, and expected SHA-256. A null field marks missing edition, licence, or URL evidence. The schema checks the document structure and its source-specific fields. `kit.py` provides streaming verification, HTTPS-only fetch with same-host redirects and hash checking, and ordered one-based PDF trims keyed to a pinned original.

`python -I tools/kit/kit.py verify C:\Users\ccie5\StudioProjects\Bastion\tools\kit\docs` checked 98 files on this PC. It found 81 verified, 17 unknown, zero missing, zero mismatched, zero unverified and zero unreadable. The unknown inventory causes exit 1. The following files have provenance gaps.

- `ATAK-CIV-Icon-Glossary-v5.5-2024.pdf`
- `Field-Guide-Forest-Plants-Northern-Idaho-RMRS-GTR-118.pdf`
- `Idaho-Big-Game-Seasons-Rules-2026.pdf`
- `Idaho-Fishing-Seasons-Rules-2025-2027.pdf`
- `Idaho-Migratory-Game-Bird-2026-2027.pdf`
- `Idaho-Moose-Sheep-Goat-2025-2026.pdf`
- `Idaho-Upland-Game-Turkey-Furbearer-2026-2027.pdf`
- `NOAA-Weather-Radio-Stations-US.md`
- `Special-Forest-Products-Pacific-Northwest-PNW-GTR-513.pdf`
- `Special-Operations-Forces-Medical-Handbook-2001.pdf`
- `USDA-Complete-Guide-to-Home-Canning-2015.pdf`
- `Wild-Edible-Mushrooms-Blue-Mountains-PNW-GTR-393.pdf`

The other five unknowns are four operational Army references (including the requested ATP source) and a biological casualties handbook. They were excluded from the actionable manifest. The verification command prints the local inventory.

## Trim page maps

All ranges are inclusive. Printed labels come from the source page content rather than the PDF reader's position count.

| Source and content | Printed pages | PDF pages | First extracted line | Last extracted line |
| --- | --- | --- | --- | --- |
| NIFOG reference and frequency tables | 1–120 | 8–127 | `1` | `users)` |
| NIFOG satellite phone reference | 139–143 | 146–150 | `139` | `92 Commissioning Tests` |
| NIFOG time and phonetics | 181–183 | 188–190 | `181` | `Z Zulu Z Zebra` |
| AUXFOG safety | 1-1–1-4 | 13–16 | `AUXFOG 1-1` | `create` |
| AUXFOG go kit | 3-5–3-7 | 23–25 | `AUXFOG 3-5` | `Make one last walk-through and review` |
| AUXFOG band plan and frequency references | C-1–C-18 | 45–62 | `AUXFOG C-1` | `AUXFOG C-18` |
| AUXFOG antennas | D-1–D-9 | 93–101 | `AUXFOG D-1` | `NOTES:` |
| AUXFOG connectors | E-1–E-4 | 103–106 | `AUXFOG E-1` | `NOTES:` |
| AUXFOG cables | F-1–F-2 | 107–108 | `AUXFOG F-1` | `NOTES:` |
| AUXFOG coded squelch | G-1–G-4 | 109–112 | `AUXFOG G-1` | `NOTES:` |
| AUXFOG ICS forms | I-13–I-21 | 129–137 | `AUXFOG I-13` | `I.5.5 ICS Form 217A` |
| AUXFOG glossary | L-1–L-4 | 145–148 | `AUXFOG L-1` | `NOTES:` |

Whitespace and the go-kit line's bullet glyph are normalized. One go-kit page also begins the mobilization section. The page trim retains that shared page in full. NIFOG's cover identifies version 2.02 even though the embedded PDF title still says 2.01.

The ATP trim was withheld because the requested material includes tactical electromagnetic countermeasures and other operational content. The two CISA trims cover the routine radio references.

## Validation

- **Tool and schema, tested on PC.** `python -I tools/kit/test_kit.py -v` passed all six offline tests. They cover manifest validation, modified/missing/unknown files, fetch preflight and same-host redirects, matching and mismatching hash pins, and exact trim pages and provenance. The download tests used in-memory responses.
- **PDF content, tested on PC.** `python -I tools/kit/kit.py --manifest <temporary selection manifest> trim <id> --out tools/kit/out --kit-dir <source kit>` wrote `CISA-NIFOG-v2.02-kit-trim.pdf` (128 pages) and `CISA-AUXFOG-v1.1-kit-trim.pdf` (57 pages), each with a `.provenance.txt`. Reopened both PDFs and matched the decoded page content, media box, crop box and rotation across all 185 retained pages. Poppler rendered 24 range boundary pages and their originals pixel-for-pixel identically at a 480 px long edge. A visual review confirmed the preserved layout at the range boundaries.
- **Reproduction, tested on PC.** `python -I tools/kit/kit.py trim cisa-nifog --out <fresh output folder> --kit-dir <source kit>` and the same command for `cisa-auxfog` reproduced the staged PDFs and sidecars byte for byte using the branch manifest. The NIFOG output SHA-256 is `16ca74c3e4b66e8dbd7841b7107ba892d62ee499c34894b47e7209fb83bcdc32`. The AUXFOG output SHA-256 is `c25c1cf9cf7ab03f1a5dcf811ae4ddb52fa754770c8b95b30ec6ea129e5d4ec0`.
