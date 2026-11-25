# Figure extractor report

The Java 21 tool reads the kit PDFs from the sibling BASTION checkout and
produces an offline contact sheet, JSON index, and 200 dpi PNGs. The index
records the filename, title, 1-based PDF page, printed page where detectable,
text-layer caption, PNG dimensions, SHA-256, licence, method, and confidence.
The source PDFs were read in place. The 2018 Army cover's document-handling
notice was treated as source text.

## Files

- `tools/figures/Figures.java` renders, detects graphic regions, associates
  captions, and writes the indexes and PNGs. It renders the visible page so
  vector art, raster art, and overlaid figure labels can be exported together.
- `tools/figures/FiguresTest.java` creates and exercises a ten-page fixture.
- `tools/figures/render-page.py` uses the existing workspace PDFium runtime
  to preserve complete pages when PDFBox cannot render them reliably.
- `tools/figures/README.md` gives the compile, test, and extraction commands.
- `tools/figures/.gitignore` excludes `tools/figures/out/`, including
  `index.json`, `index.html`, `images/`, and `thumbs/`.

## Verification

| Check | Result |
| --- | --- |
| Compile | Tested on Windows with Temurin 21.0.13. `javac -Xlint:all -encoding UTF-8` succeeded with no diagnostics. |
| Synthetic run | Tested. `FiguresTest` passed with ten candidates on ten fixture pages. It exercises caption parsing and line breaks, licence mapping, title handling, vector crops above and below captions, independently captioned panels on the same row, ambiguous and missing-art fallback, uncaptioned infographics, blank/TOC handling, printed pages, PNG dimensions and hashes, and identical output on a second run. |
| Corpus run | Tested twice on Windows. Each run processed all 55 PDFs and 6,521 pages and produced 2,779 candidates. The indexes list 610 high-confidence crops, 1,396 low-confidence crops, and 773 low-confidence full-page renders. Each run used PDFium for 80 pages. |
| Corpus correctness | Tested. The verifier matched SHA-256 across all 5,560 output files from the two runs. It checked all index fields, source filenames and PDF page bounds, figure and thumbnail links, PNG dimensions and resolution, and the indexed PNG digests. The three visually reviewed PNGs match the delivered files by SHA-256. |
| General visual review | The source PDF page and exported crop were visually compared for the cases below. Other figures remain candidates for human review. |

Commands from the worktree root, with `$jdk` set to the Temurin 21 `bin`
directory and `$python` set to the bundled workspace Python executable.

```powershell
$out = 'tools/figures/out'
$jar = "$out/pdfbox-app-3.0.7.jar"
$classes = "$out/classes"
$cp = "$classes;$jar"
$kit = 'C:/Users/ccie5/StudioProjects/Bastion/tools/kit/docs'
& "$jdk/javac.exe" -Xlint:all -encoding UTF-8 -cp $jar -d $classes tools/figures/Figures.java tools/figures/FiguresTest.java
& "$jdk/java.exe" '-Djava.awt.headless=true' "-Dpdfbox.fontcache=$out" -cp $cp FiguresTest "$out/final-layout-test"
& "$jdk/java.exe" '-Djava.awt.headless=true' "-Dpdfbox.fontcache=$out" -Xmx2g -cp $cp Figures $kit "$out/handoff" $python tools/figures/render-page.py
& "$jdk/java.exe" '-Djava.awt.headless=true' "-Dpdfbox.fontcache=$out" -Xmx2g -cp $cp Figures $kit "$out/handoff-repeat" $python tools/figures/render-page.py
& $python "$out/verify-handoff.py"
git diff --cached --check
git check-ignore tools/figures/out/index.json tools/figures/out/images/example.png
```

The corpus check script and logs are local verification artifacts under
`tools/figures/out/`. The contact sheet was checked by parsing its HTML and
validating the referenced thumbnails and full PNGs.

## Visual source checks

| Source | PDF page | Printed page | Visual result |
| --- | ---: | --- | --- |
| `Army-TC-21-3-Cold-Weather-Operations-Survival-2025.pdf` | 121 | 113 | **Tested by eye.** Figure C-1, “Ground-to-air markings,” matches the full two-column symbol chart, including labels and borders. Index ID `ab1b8b7720883488-p0121-01`. |
| `Army-ATP-4-02.11-Casualty-Response-TCCC-First-Aid-2026.pdf` | 62 | 48 | **Tested by eye.** Figure 4-5, “Windlass and Ratchet Tourniquets,” matches the illustration in “Application of Tourniquets.” The continuation on PDF pages 63–64 contains application text and no additional tourniquet illustration. Index ID `8269e5b3e4b4f490-p0062-01`. |
| `Army-ATP-3-50.21-Survival-2018.pdf` | 143 | 6-9 | **Tested by eye.** Figure 6-6, “A-frame shelters,” retains all four panels and their internal labels. Index ID `6b7283ff48873cdd-p0143-01`. |

I also visually checked the separation of the first two side-by-side pulse
figures in the casualty manual on PDF page 60 and confirmed the alternate
renderer visibly retained the scanned illustration in the Forest Service
mushroom manual on PDF page 12. The three requested crops are indexed as
`rendered-region` with `high` confidence.

I also checked the full-page capture of the CDC water infographic on PDF page
1 against its source. The index marks it `full-page` with `low` confidence.

## Dependencies and limits

- Downloaded Apache PDFBox `pdfbox-app` 3.0.7 under Apache License 2.0.
  SHA-256 `bf70b90aca964bda6f1438d7b87d6f99cfaa9912ba6fcebc0541d9d90ee5ef54`.
  [Maven Central JAR](https://repo.maven.apache.org/maven2/org/apache/pdfbox/pdfbox-app/3.0.7/pdfbox-app-3.0.7.jar)
- Used the existing Codex workspace bundle 26.1123.11515 for Python,
  pypdfium2/PDFium 153.0.7999.0 and Pillow. PDFium handled reported partial
  streams and JPEG 2000 or other unsupported graphics through full-page
  fallback. PDFium can preserve the available contents of a damaged stream.
  Source damage may still affect the result.
- The parser uses the text layer; scanned captions and printed page labels
  can be visible in the PNG with empty or null index fields. Decorative art,
  page furniture, and multi-panel compositions can produce grouped or extra
  candidates. Full-corpus figure recall and crop accuracy were not measured.
- Licence values follow the named publishers in the brief. Ownership of
  individual credited illustrations, photos, and third-party material
  reproduced within a government manual remains an open review item.
- The Android app was outside the extraction task. App compile, launch, and
  device correctness were not tested.
