# PdfBox assets in the release APK

The 112 PdfBox assets occupy **1,618,605 compressed payload bytes** in the current x86_64 release, 4.29% of the APK. Keep the CMaps, AFMs, glyph lists, bidi data, and fallback font. The evidence supports one follow-up candidate, `Scripts.txt`, at 39,158 compressed payload bytes.

## Build and measurement

- **Release compile and packaging, tested on PC.** Base `28cfda2`, Temurin 21.0.13, Gradle 8.14.3. The default offline `:app:assembleRelease` reached release Kotlin compilation and failed in `:app:lintVitalAnalyzeRelease` because `com.android.tools.lint:lint-gradle:31.7.3` is uncached. The offline retry below executed R8 and assembled both release APKs in 5m 15s. The two Compose mapping tasks were also excluded because that producer dependency is absent from this cache.
- **Asset identity and sizing, tested on PC.** Used the cached `com.tom-roush:pdfbox-android:2.0.27.0` AAR, 3,254,019 bytes, SHA-256 `30277f879cfd571db2a137582c95516a0d4ea6778e945519bc58ca93d57d88c7`. Joined each regular AAR `assets/` entry to its exact path in both new APKs with Python `zipfile`, compared the inflated bytes directly, then summed `ZipInfo.file_size` and APK `ZipInfo.compress_size`. All 112 matched byte for byte and had identical compressed sizes between splits. Payload sizes exclude ZIP headers, alignment, and signing.
- **APK identity, tested on PC.** `app-x86_64-release.apk` is 37,738,783 bytes, SHA-256 `25ce315234912eeffa88678232b17caf24b80bb8d23613ad6c180e0fd004b0e4`. `app-arm64-v8a-release.apk` is 36,639,452 bytes, SHA-256 `9ac30ca4be34a82240a42252a0844175e4a9388c1805002d6f9507492a0e49ca`. Both passed `apksigner` v2 verification with the Android Debug certificate. App launch and Android extraction were not tested in this PC-only audit.

```powershell
# From android/ with the local Temurin 21 JAVA_HOME and SDK.
.\gradlew.bat --offline --no-daemon '-Pandroid.builder.sdkDownload=false' :app:assembleRelease `
  -x :app:lintVitalRelease -x :app:produceReleaseComposeMapping -x :app:mergeReleaseComposeMapping
```

## Directory inventory and classification

Paths below are relative to `assets/com/tom_roush/`. The call paths are from `javap -p -c` on the pinned AAR's `classes.jar`. The cached Gradle runtime JAR has all 902 class files byte-identical to that JAR. BASTION calls `PDFBoxResourceLoader.init`, `PDDocument.load` and `PDFTextStripper` in `LibraryStore.kt`.

| Asset directory | Files | Raw bytes | APK payload bytes | Classification and evidence |
|---|---:|---:|---:|---|
| `fontbox/resources/cmap/` | 92 | 3,304,434 | 1,209,581 | Keep. `CMapParser.getExternalCMap` resolves named encodings and `usecmap` dependencies. `PDType0Font.readEncoding` and `fetchCMapUCS2` use the maps to turn CID codes into Unicode. |
| `fontbox/resources/unicode/` | 1 | 166,719 | 39,158 | Follow-up candidate. `OpenTypeScript` loads `Scripts.txt` for `SubstitutingCmapLookup.getGlyphId`. In the pinned bytecode, `TrueTypeFont` starts with GSUB features disabled. The only enable chain comes from the vertical `PDType0Font` creation and embedding constructor. The separate `PDType0Font(COSDictionary)` read constructor does not enable that path. |
| `pdfbox/resources/afm/` | 14 | 642,394 | 124,794 | Keep. `PDFont(COSDictionary)` calls `Standard14Fonts.getAFM`. The unembedded `PDType1Font.readEncodingFromFont` path can construct `Type1Encoding` from AFM, and `PDFStreamEngine.showText` calls `getDisplacement` and `getWidth` for text placement. Removing AFM breaks extraction. |
| `pdfbox/resources/glyphlist/` | 3 | 85,506 | 27,415 | Keep. `GlyphList` initializes `glyphlist.txt` and `zapfdingbats.txt`. `LegacyPDFStreamEngine` eagerly reads `additional.txt` and calls `PDFont.toUnicode` when converting glyph codes. |
| `pdfbox/resources/text/` | 1 | 24,691 | 6,792 | Keep. `PDFTextStripper` reads `BidiMirroring.txt` in its static initializer. The table supplies mirrored characters for bidirectional text. |
| `pdfbox/resources/ttf/` | 1 | 410,712 | 210,865 | Keep. `FontMapperImpl` initializes its last-resort font from `LiberationSans-Regular.ttf`. The `PDType1Font` and CID font read paths use the mapper for unembedded or substituted fonts. |
| **Total** | **112** | **4,634,456** | **1,618,605** | |

## Extraction tests

**JVM extraction, tested on PC.** `tools/pdfbox-text-test.py` runs `PdfBoxExtractionProbe` against the pinned Android library's own classes. It uses the library's classpath asset loading, sets font scanning to `NONE`, and supplies host shims for Android logging and basic geometry. With `--apk` it reads the merged assets from the chosen APK. The probe still runs the AAR classes on the host JVM; Android AssetManager and the minified classes require separate device verification.

The three fixture PDFs were generated for this audit and total 2,704 bytes. One uses unembedded Base 14 Helvetica and WinAnsi for `Café`. The others use the named `90ms-RKSJ-H` and `90ms-RKSJ-V` encodings with Adobe Japan1 CID descendants and no ToUnicode maps. The probe requires exact `日本` in both cases after whitespace normalization. Strict local PDF parsing also opened all three fixtures.

All three passed with the cached AAR assets and with the merged x86_64 APK assets. A temporary omission of `Scripts.txt` passed all three again with either source. Independent omission controls against the AAR assets failed as expected. Missing `Helvetica.afm` caused a font initialization failure. Missing `90ms-RKSJ-H` failed the named CMap lookup. Missing `Adobe-Japan1-UCS2` produced empty text for the Japanese fixture. These controls used only the probe's temporary classpath.

```powershell
# From the repository root with Python 3.9+, the local JDK and Android SDK 35.
$apk = 'android/app/build/outputs/apk/release/app-x86_64-release.apk'
python -I tools/pdfbox-text-test.py
python -I tools/pdfbox-text-test.py --apk $apk
python -I tools/pdfbox-text-test.py --apk $apk --omit-asset assets/com/tom_roush/fontbox/resources/unicode/Scripts.txt
```

## Proposal — not applied

Keep the current package. If a later, owner-approved trim is worthwhile, evaluate **only** `assets/com/tom_roush/fontbox/resources/unicode/Scripts.txt`. The possible saving is **39,158 compressed payload bytes**, about 0.10% of the x86_64 APK. Both measured APKs contain exactly one case-insensitive `Scripts.txt` basename. The pinned AGP 8.7.3 `PatternBasedFileFilter` matches a file's basename, so the narrow literal is `<file>Scripts.txt`. Its configured list replaces the implicit defaults, so retain those defaults when adding the pattern. `packaging.resources.excludes` controls Java resources. This entry is an Android asset.

```kotlin
android {
    androidResources {
        ignoreAssetsPatterns.addAll(listOf(
            "!.svn", "!.git", "!.ds_store", "!*.scc", ".*", "<dir>_*",
            "!CVS", "!thumbs.db", "!picasa.ini", "!*~", "<file>Scripts.txt",
        ))
    }
}
```

Any follow-up needs a before/after APK inventory that confirms the single-path change and device extraction checks on representative embedded-font, multilingual, and vertical-text manuals. The probes cover named horizontal and vertical Japanese CMaps and one WinAnsi font. Other shaping and substitution cases remain untested. The measured AFM saving is 124,794 bytes and the AFM omission control failed, so AFMs are outside the proposal.

## Per-file inventory

| Path relative to `assets/com/tom_roush/` | Raw bytes | APK payload bytes |
|---|---:|---:|
| `fontbox/resources/cmap/83pv-RKSJ-H` | 7,149 | 2,945 |
| `fontbox/resources/cmap/90ms-RKSJ-H` | 6,139 | 2,588 |
| `fontbox/resources/cmap/90ms-RKSJ-V` | 4,298 | 1,753 |
| `fontbox/resources/cmap/90msp-RKSJ-H` | 6,070 | 2,559 |
| `fontbox/resources/cmap/90msp-RKSJ-V` | 4,281 | 1,741 |
| `fontbox/resources/cmap/90pv-RKSJ-H` | 7,884 | 3,216 |
| `fontbox/resources/cmap/90pv-RKSJ-V` | 3,784 | 1,565 |
| `fontbox/resources/cmap/Add-RKSJ-H` | 15,107 | 5,993 |
| `fontbox/resources/cmap/Add-RKSJ-V` | 3,892 | 1,607 |
| `fontbox/resources/cmap/Adobe-CNS1-0` | 3,898 | 1,670 |
| `fontbox/resources/cmap/Adobe-CNS1-1` | 4,071 | 1,757 |
| `fontbox/resources/cmap/Adobe-CNS1-2` | 4,091 | 1,764 |
| `fontbox/resources/cmap/Adobe-CNS1-3` | 4,191 | 1,805 |
| `fontbox/resources/cmap/Adobe-CNS1-4` | 4,211 | 1,812 |
| `fontbox/resources/cmap/Adobe-CNS1-5` | 4,211 | 1,812 |
| `fontbox/resources/cmap/Adobe-CNS1-6` | 4,211 | 1,813 |
| `fontbox/resources/cmap/Adobe-CNS1-UCS2` | 265,146 | 93,868 |
| `fontbox/resources/cmap/Adobe-GB1-0` | 3,361 | 1,464 |
| `fontbox/resources/cmap/Adobe-GB1-1` | 3,515 | 1,537 |
| `fontbox/resources/cmap/Adobe-GB1-2` | 4,469 | 1,919 |
| `fontbox/resources/cmap/Adobe-GB1-3` | 4,468 | 1,917 |
| `fontbox/resources/cmap/Adobe-GB1-4` | 5,020 | 2,128 |
| `fontbox/resources/cmap/Adobe-GB1-5` | 5,120 | 2,169 |
| `fontbox/resources/cmap/Adobe-GB1-UCS2` | 231,913 | 79,951 |
| `fontbox/resources/cmap/Adobe-Japan1-0` | 3,410 | 1,487 |
| `fontbox/resources/cmap/Adobe-Japan1-1` | 3,411 | 1,489 |
| `fontbox/resources/cmap/Adobe-Japan1-2` | 3,449 | 1,503 |
| `fontbox/resources/cmap/Adobe-Japan1-3` | 3,459 | 1,508 |
| `fontbox/resources/cmap/Adobe-Japan1-4` | 3,937 | 1,702 |
| `fontbox/resources/cmap/Adobe-Japan1-5` | 4,317 | 1,854 |
| `fontbox/resources/cmap/Adobe-Japan1-6` | 4,537 | 1,942 |
| `fontbox/resources/cmap/Adobe-Japan1-7` | 4,444 | 1,910 |
| `fontbox/resources/cmap/Adobe-Japan1-UCS2` | 284,124 | 98,471 |
| `fontbox/resources/cmap/Adobe-Japan2-0` | 3,237 | 1,407 |
| `fontbox/resources/cmap/Adobe-Korea1-0` | 3,502 | 1,512 |
| `fontbox/resources/cmap/Adobe-Korea1-1` | 4,158 | 1,795 |
| `fontbox/resources/cmap/Adobe-Korea1-2` | 4,157 | 1,790 |
| `fontbox/resources/cmap/Adobe-Korea1-UCS2` | 146,880 | 52,333 |
| `fontbox/resources/cmap/B5pc-H` | 7,616 | 3,257 |
| `fontbox/resources/cmap/B5pc-V` | 3,005 | 1,267 |
| `fontbox/resources/cmap/CNS-EUC-H` | 12,315 | 4,075 |
| `fontbox/resources/cmap/CNS-EUC-V` | 13,347 | 4,335 |
| `fontbox/resources/cmap/ETen-B5-H` | 7,772 | 3,322 |
| `fontbox/resources/cmap/ETen-B5-V` | 3,043 | 1,285 |
| `fontbox/resources/cmap/ETenms-B5-H` | 2,798 | 1,168 |
| `fontbox/resources/cmap/ETenms-B5-V` | 3,110 | 1,307 |
| `fontbox/resources/cmap/EUC-H` | 5,144 | 2,197 |
| `fontbox/resources/cmap/EUC-V` | 3,292 | 1,378 |
| `fontbox/resources/cmap/Ext-RKSJ-H` | 15,676 | 6,224 |
| `fontbox/resources/cmap/Ext-RKSJ-V` | 3,550 | 1,481 |
| `fontbox/resources/cmap/GB-EUC-H` | 4,496 | 1,975 |
| `fontbox/resources/cmap/GB-EUC-V` | 3,169 | 1,329 |
| `fontbox/resources/cmap/GBK-EUC-H` | 83,171 | 30,117 |
| `fontbox/resources/cmap/GBK-EUC-V` | 3,158 | 1,323 |
| `fontbox/resources/cmap/GBK2K-H` | 90,995 | 33,626 |
| `fontbox/resources/cmap/GBK2K-V` | 3,551 | 1,476 |
| `fontbox/resources/cmap/GBKp-EUC-H` | 83,152 | 30,107 |
| `fontbox/resources/cmap/GBKp-EUC-V` | 3,164 | 1,324 |
| `fontbox/resources/cmap/GBpc-EUC-H` | 4,524 | 1,988 |
| `fontbox/resources/cmap/GBpc-EUC-V` | 3,181 | 1,332 |
| `fontbox/resources/cmap/H` | 5,010 | 2,125 |
| `fontbox/resources/cmap/HKscs-B5-H` | 23,322 | 9,461 |
| `fontbox/resources/cmap/HKscs-B5-V` | 3,030 | 1,275 |
| `fontbox/resources/cmap/Identity-H` | 7,889 | 3,187 |
| `fontbox/resources/cmap/Identity-V` | 2,688 | 1,133 |
| `fontbox/resources/cmap/KSC-EUC-H` | 11,797 | 4,700 |
| `fontbox/resources/cmap/KSC-EUC-V` | 3,105 | 1,305 |
| `fontbox/resources/cmap/KSCms-UHC-H` | 16,009 | 6,354 |
| `fontbox/resources/cmap/KSCms-UHC-HW-H` | 16,005 | 6,352 |
| `fontbox/resources/cmap/KSCms-UHC-HW-V` | 3,118 | 1,302 |
| `fontbox/resources/cmap/KSCms-UHC-V` | 3,119 | 1,309 |
| `fontbox/resources/cmap/KSCpc-EUC-H` | 12,624 | 5,029 |
| `fontbox/resources/cmap/KSCpc-EUC-V` | 3,117 | 1,307 |
| `fontbox/resources/cmap/UniCNS-UCS2-H` | 326,418 | 111,812 |
| `fontbox/resources/cmap/UniCNS-UCS2-V` | 3,040 | 1,276 |
| `fontbox/resources/cmap/UniCNS-UTF16-H` | 252,742 | 94,957 |
| `fontbox/resources/cmap/UniCNS-UTF16-V` | 3,029 | 1,278 |
| `fontbox/resources/cmap/UniGB-UCS2-H` | 274,452 | 95,548 |
| `fontbox/resources/cmap/UniGB-UCS2-V` | 3,236 | 1,357 |
| `fontbox/resources/cmap/UniGB-UTF16-H` | 199,956 | 78,100 |
| `fontbox/resources/cmap/UniGB-UTF16-V` | 3,130 | 1,322 |
| `fontbox/resources/cmap/UniJIS-UCS2-H` | 168,666 | 58,550 |
| `fontbox/resources/cmap/UniJIS-UCS2-HW-H` | 2,889 | 1,207 |
| `fontbox/resources/cmap/UniJIS-UCS2-HW-V` | 6,660 | 2,578 |
| `fontbox/resources/cmap/UniJIS-UCS2-V` | 6,578 | 2,546 |
| `fontbox/resources/cmap/UniJIS-UTF16-H` | 187,728 | 71,278 |
| `fontbox/resources/cmap/UniJIS-UTF16-V` | 5,762 | 2,321 |
| `fontbox/resources/cmap/UniKS-UCS2-H` | 166,078 | 59,463 |
| `fontbox/resources/cmap/UniKS-UCS2-V` | 3,145 | 1,319 |
| `fontbox/resources/cmap/UniKS-UTF16-H` | 122,974 | 49,202 |
| `fontbox/resources/cmap/UniKS-UTF16-V` | 3,099 | 1,306 |
| `fontbox/resources/cmap/V` | 3,268 | 1,373 |
| `fontbox/resources/unicode/Scripts.txt` | 166,719 | 39,158 |
| `pdfbox/resources/afm/Courier-Bold.afm` | 15,675 | 3,986 |
| `pdfbox/resources/afm/Courier-BoldOblique.afm` | 15,741 | 4,104 |
| `pdfbox/resources/afm/Courier-Oblique.afm` | 15,783 | 4,101 |
| `pdfbox/resources/afm/Courier.afm` | 15,677 | 3,977 |
| `pdfbox/resources/afm/Helvetica-Bold.afm` | 72,096 | 13,031 |
| `pdfbox/resources/afm/Helvetica-BoldOblique.afm` | 72,192 | 13,238 |
| `pdfbox/resources/afm/Helvetica-Oblique.afm` | 77,443 | 13,881 |
| `pdfbox/resources/afm/Helvetica.afm` | 77,343 | 13,687 |
| `pdfbox/resources/afm/Symbol.afm` | 9,953 | 3,461 |
| `pdfbox/resources/afm/Times-Bold.afm` | 66,839 | 12,378 |
| `pdfbox/resources/afm/Times-BoldItalic.afm` | 62,026 | 11,764 |
| `pdfbox/resources/afm/Times-Italic.afm` | 68,995 | 12,725 |
| `pdfbox/resources/afm/Times-Roman.afm` | 62,879 | 11,890 |
| `pdfbox/resources/afm/ZapfDingbats.afm` | 9,752 | 2,571 |
| `pdfbox/resources/glyphlist/additional.txt` | 3,472 | 1,234 |
| `pdfbox/resources/glyphlist/glyphlist.txt` | 78,111 | 24,289 |
| `pdfbox/resources/glyphlist/zapfdingbats.txt` | 3,923 | 1,892 |
| `pdfbox/resources/text/BidiMirroring.txt` | 24,691 | 6,792 |
| `pdfbox/resources/ttf/LiberationSans-Regular.ttf` | 410,712 | 210,865 |
