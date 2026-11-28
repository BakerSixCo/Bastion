# Task apk-pdfbox-audit-1: audit PdfBox CMap/AFM assets in the APK (branch `chatgpt/apk-pdfbox-audit-1`)

**Runnable now, independent. PC only (no emulator)** — good to pick when the emulator is busy with a Claude/owner
eval. Start from Claude's branch:

```powershell
git fetch origin
git switch -c chatgpt/apk-pdfbox-audit-1 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md`. No network. Do not touch the owner/Claude-only zones (card/flow text,
`ai/CheckedAnswer.kt`, `ai/QueryPlan.kt`, Meshtastic device pages, route math). **This task is measure-and-propose
only — it does NOT remove any asset.** Shrinking the APK is a follow-up the owner/Claude decide from your numbers.

## Why

PdfBox-Android `com.tom-roush:pdfbox-android:2.0.27.0` ([version pinned in `CLAUDE.md`]) ships a large `assets/`
tree inside its `.aar` — CMap tables (CID/CJK + encoding maps), AFM font metrics, and the Adobe glyph list — which
are merged into the APK. BASTION uses PdfBox for **text extraction only**: `PDFBoxResourceLoader.init(app)` then
`PDFTextStripper` ([`data/LibraryStore.kt:144-147`](../../android/app/src/main/java/com/bastion/terminal/data/LibraryStore.kt)).
It never renders glyphs or embeds fonts, so some of those assets may be dead weight in a ~36 MB release APK. But
CMap/encoding assets are load-bearing for extracting text from PDFs with CID-keyed or CJK fonts and some encodings,
so **this is an audit, not a delete**: we need the exact sizes and a provably-safe recommendation before anything is
excluded. Removing a needed CMap would silently drop text from some manuals (a retrieval/coverage regression), which
is exactly the kind of quiet gap the project avoids.

## Do

1. **Inventory.** Resolve the `pdfbox-android` `.aar` from the Gradle cache, unzip it, and list its `assets/` tree
   with per-file and per-directory sizes (CMap directories, `*.afm`, `glyphlist.txt`/`additional.txt`, etc.). Also
   report how much of the **merged** APK those assets account for: build the release APK
   (`cd android; ./gradlew :app:assembleRelease`), unzip the x86_64 or arm64 APK, and size the pdfbox `assets/` inside
   it (R8/packaging may already compress them). Give a table: asset group → raw bytes → bytes in APK.
2. **Classify, conservatively.** For each asset group, state whether text extraction can need it:
   - **Keep (needed):** anything PdfBox touches during `PDFTextStripper` for CID/CJK/encoded PDFs — the CMap tree and
     the glyph lists are presumed needed unless you can prove otherwise.
   - **Candidate to drop (prove it):** groups used only for rendering/embedding (e.g. AFM metrics for the 14 standard
     fonts) that a text-only path does not read. Cite the PdfBox code path or a reference, not a guess.
3. **Prove the safety bar with a test, don't loosen it.** Add a small JVM unit test under `tools/` **or**
   `android/app/src/test/` that runs `PDFTextStripper` over a few committed sample PDFs **including one CID/CJK or
   non-Latin-encoded PDF** and asserts non-empty text, so a future exclusion that breaks encoded-PDF extraction is
   caught. Use tiny, clearly-labeled sample PDFs (public-domain / generated); do not add large binaries. If a
   JVM-only PdfBox run is impractical, say so and instead specify the exact `RetrievalEvalTest` / `ZimRetrievalEvalTest`
   cases that would catch it on-device (they must stay 13/13 and 14/14).
4. **Recommend.** One short section: the realistic APK saving (bytes), the exact Gradle mechanism a removal would use
   (`androidResources { ignoreAssetsPatterns }` / packaging exclude), the precise patterns, and the residual risk.
   Mark it "proposal — not applied."

## Files you may change

A new test file (`tools/…` or `android/app/src/test/…`) and its tiny sample PDFs; your report. **Do not** edit
`build.gradle.kts` to exclude assets in this task, and nothing in the owner/Claude-only zones. No app behaviour change.

## Done when

`docs/chatgpt-tasks/apk-pdfbox-audit-1-report.md` is committed with: the asset inventory table (raw + in-APK sizes),
the keep/candidate-to-drop classification with cited reasons, the new extraction test (green, named in the report with
how to run it, and proving an encoded/CJK PDF still extracts), and a conservative APK-saving recommendation marked
"proposal — not applied." `:app:assembleRelease` still builds. No asset removed, no eval loosened.
