# BASTION PDF figure extractor

Run these PowerShell commands from the worktree root with Java 21. The tool
reads the kit PDFs in place and writes an offline contact sheet, a JSON index,
and PNGs under the ignored output directory.

**Dependency:** Apache PDFBox 3.0.7, Apache License 2.0. Place the JAR at
`tools/figures/out/pdfbox-app-3.0.7.jar`. Use the SHA-256 below to verify it.
[Maven Central artifact](https://repo.maven.apache.org/maven2/org/apache/pdfbox/pdfbox-app/3.0.7/pdfbox-app-3.0.7.jar)

`bf70b90aca964bda6f1438d7b87d6f99cfaa9912ba6fcebc0541d9d90ee5ef54`

```powershell
$kit = 'C:/Users/ccie5/StudioProjects/Bastion/tools/kit/docs'
$out = 'tools/figures/out'
$jar = "$out/pdfbox-app-3.0.7.jar"
$classes = "$out/classes"
$cp = "$classes;$jar"
New-Item -ItemType Directory -Force $classes | Out-Null
javac -Xlint:all -encoding UTF-8 -cp $jar -d $classes tools/figures/Figures.java tools/figures/FiguresTest.java
java '-Djava.awt.headless=true' "-Dpdfbox.fontcache=$out" -cp $cp FiguresTest "$out/test"
java '-Djava.awt.headless=true' "-Dpdfbox.fontcache=$out" -Xmx2g -cp $cp Figures $kit $out
```

For complete kit coverage, use an existing Python runtime with `pypdfium2` and
Pillow, such as the Codex bundled workspace runtime. Set `$python` to its
executable and run this extraction command. When PDFBox reports an incomplete
render, PDFium exports the affected page at 200 dpi with low confidence. The
Java-only invocation lists affected pages on stderr and omits their incomplete
renders.

```powershell
java '-Djava.awt.headless=true' "-Dpdfbox.fontcache=$out" -Xmx2g -cp $cp Figures $kit $out $python tools/figures/render-page.py
```

Open `index.html` locally. Select a thumbnail to inspect the full-resolution
PNG. `pdfPage` counts from 1. `printedPage` is the visible page label when
the text layer yields a single plausible header or footer label. `docTitle`
comes from a checked cover title where available, then PDF metadata, then the
filename stem for missing or placeholder metadata.

The extractor finds graphical regions in a graphics-only preview, then exports
them from the complete rendering so original figure labels and compositing are
preserved. When the layout supports a unique caption-to-region match, the index
marks the crop `high`. Ambiguous captioned figures get a `full-page` image
with `low` confidence. Uncaptioned candidates also have low confidence. The
caption parser uses the text layer and preserves detected caption line breaks.
Scans, atypical caption styles, and densely composed pages can yield missed or
grouped figures. Check every selected figure against the source PDF.

IDs depend on the filename, PDF page, and candidate order on that page. Runs
with the same inputs, renderers, and installed fonts produce the same indexes
and PNGs. Use a fresh output directory after changing the input set to avoid
retaining obsolete PNGs from an earlier run.
