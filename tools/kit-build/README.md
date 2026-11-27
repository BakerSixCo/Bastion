# Kit build scripts

Scripts that turned official sources into the kit documents in `tools/kit/docs/` (the kit itself is a
managed asset and is not in git; hashes are in `docs/status.md`, sources and decisions in
`docs/library-plan.md`). Run with `python -I` from a quarantine folder, never inside the kit.

| Script | Made | Input |
|---|---|---|
| `ecfr2md.py` | `FCC-47-CFR-Part-95-...md`, `FCC-47-CFR-Part-97-...md` | eCFR "full" XML (`https://www.ecfr.gov/api/versioner/v1/full/<date>/title-47.xml?part=95`, needs `--compressed`) |
| `ncvec2md.py` | `NCVEC-Technician-Question-Pool-2026-2030-Answers.md` | `pdftotext` of the NCVEC pool PDF; keeps only each question's keyed answer |
| `meshdocs.py`, `meshtrim.py` | `reference/Meshtastic-User-Guide-2026-11.md` | sparse clone of `github.com/meshtastic/meshtastic` (`docs/**/*.md*`), then the owner's trim |
| `nccih.py` | `NIH-NCCIH-Chinese-Medicine-and-Herbs-at-a-Glance-2026.md` | the NCCIH Herbs at a Glance index page |
| `bodyhash.py` | check | whitespace-normalised SHA-256 of each `<!-- body -->` section, to compare a browser-copied page with the live page |

Pages that refuse command-line downloads (fcc.gov, navcen.uscg.gov, fsis.usda.gov, ready.gov) were read in
the in-app browser and copied as text; `bodyhash.py` proves each copy matches the live page.

## Converter tests

Run all six offline suites from the repository root:

```powershell
Get-ChildItem tools/kit-build/test_*.py | ForEach-Object {
    python -I $_.FullName
    if ($LASTEXITCODE -ne 0) { throw "Converter tests failed in $($_.Name)" }
}
```

Each suite can also run directly with `python -I tools/kit-build/test_<name>.py`. The tests use
small inputs and exact UTF-8/LF goldens in `testdata/`. They rerun the converters in temporary
directories. The NCCIH suite serves its page fixtures through a mocked HTTP response.
`bodyhash.py` emits UTF-8/LF even when stdout uses a different platform encoding. Its digests
fold whitespace and preserve quote glyphs.
