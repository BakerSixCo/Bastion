# Task: figure extractor for the field guide (branch `chatgpt/figures`)

Read `AGENTS.md` and `CLAUDE.md` first. Background: `docs/field-guide-design.md`, section 7 (Pictures).

## Goal

A PC tool that finds the figures (drawings, diagrams, photos) in the kit's PDF manuals and saves each as an
image with exactly where it came from, so Claude and the owner can pick the right figure for each field-guide
step. Nothing here reaches the phone yet; correctness of *where each image came from* matters most.

## Input

- The PDFs in `C:\Users\ccie5\StudioProjects\Bastion\tools\kit\docs\` (read-only; ~55 files; they are not in
  git, so read them from Claude's checkout path).

## Output (in your worktree)

- `tools/figures/` — the tool and its tests. Java 21 is installed
  (`C:\Program Files\Eclipse Adoptium\jdk-21.*`); a single-file Java program or a small Gradle-free project is
  fine. PowerShell or Perl glue is fine.
- `tools/figures/out/` — the images and index; **add a `tools/figures/.gitignore` so `out/` is never committed.**
- `tools/figures/out/index.json`: one entry per figure:
  `id, doc (file name), docTitle, pdfPage (1-based), printedPage (if the page shows one), caption (verbatim,
  e.g. "Figure C-1. Ground-to-air emergency code"), file, widthPx, heightPx, sha256, licence, method, confidence`
  - `licence`: `"US government work (public domain)"` for Army, JTS, CDC, EPA, FDA, FEMA, HHS, USDA, AFRRI,
    USAMRIID, NOAA, ORNL, US Forest Service; `"check"` for WHO and anything else. Never guess beyond that.
  - `method`: `embedded-image` or `rendered-region` or `full-page`; `confidence`: `high` / `low`.
- `tools/figures/out/index.html`: a contact sheet (thumbnail, caption, doc, page) for quick human checking.

## Requirements

1. **Vector drawings count.** Many manual figures are line drawings, not embedded images. Render the figure's
   region of the page (find the caption text position, then the drawing area above or below it). If the region
   cannot be found reliably, render the whole page with `method: full-page`, `confidence: low` — never a wrong
   crop marked high.
2. Captions: take them verbatim from the PDF text layer. A figure with no caption gets `caption: ""` and
   `confidence: low`.
3. Resolution: about 200 dpi, PNG.
4. Deterministic: running twice gives the same files and hashes.
5. Must work on these known cases (check them by eye and say so in the report):
   - `Army-TC-21-3-Cold-Weather-Operations-Survival-2025.pdf`, Appendix C, figure C-1 (ground-to-air signal
     symbols), PDF page 121.
   - `Army-ATP-4-02.11-Casualty-Response-TCCC-First-Aid-2026.pdf`: the tourniquet application figures.
   - `Army-ATP-3-50.21-Survival-2018.pdf`: any shelter or fire-lay figure.
6. Tests: at least caption parsing, licence mapping, and region/fallback logic on one small PDF you create.

## Allowed download

- **Apache PDFBox** (Apache-2.0) from Maven Central, a pinned 3.0.x `pdfbox-app` jar, if you need it.
  Record version, URL and SHA-256 in your report. Nothing else.

## Not in scope

- Choosing which figure goes with which field-guide step (Claude and the owner do that).
- Anything under `android/`, the cards, the map tools.

## Done when

The tool runs over all kit PDFs, `index.json` and `index.html` exist, the three known cases are checked by eye,
tests pass, and `docs/chatgpt-tasks/figures-report.md` is committed on `chatgpt/figures`.
