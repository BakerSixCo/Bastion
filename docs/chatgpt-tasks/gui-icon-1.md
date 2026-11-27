# Task gui-icon-1: add an adaptive launcher icon (branch `chatgpt/gui-icon-1`)

**Runnable now, independent.** Emulator to see the launcher. Start from Claude's branch:

```powershell
git fetch origin
git switch -c chatgpt/gui-icon-1 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md`. No network. Do not touch the owner/Claude-only zones. Vector only — no raster PNGs (keeps the APK flat).

## Why

The app ships with **no launcher icon**: `res/` has only `values/` + `xml/` (no `mipmap`/`drawable`), and `AndroidManifest.xml:41-48` sets no `android:icon`/`roundIcon`, so Android shows the stock placeholder icon. A real adaptive icon is a basic polish gap.

## Do

1. Add a vector adaptive icon using the existing theme palette (`ui/Theme.kt:9`, dark design — green `#8BC34A` foreground on dark `#0E120F` background):
   - `res/drawable/ic_launcher_foreground.xml` — a clean, simple BASTION mark (a minimal first pass, e.g. a shield/beacon glyph or a bold "B"; the owner can restyle later — keep it legible at small sizes and within the adaptive-icon safe zone).
   - `res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` with `<background>`, `<foreground>` and a `<monochrome>` layer (themed-icon support, API 33+).
   - A solid `res/drawable/ic_launcher_background.xml` (or a color resource).
2. Set `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher_round"` on `<application>` in `AndroidManifest.xml`.
3. Verify on the emulator: the launcher shows the icon; `aapt dump badging` lists it; the themed/monochrome slot is populated on API 33+.

## Files you may change

`android/app/src/main/res/drawable/`, `res/mipmap-anydpi-v26/`, `res/values/` (a color), `AndroidManifest.xml`; your report. Nothing else. Do not add raster `mipmap-*dpi` PNGs.

## Done when

The emulator launcher shows the BASTION icon (screenshot), `aapt dump badging` lists the icon, the monochrome layer is present; `:app:assembleDebug` builds; `docs/chatgpt-tasks/gui-icon-1-report.md` is committed. Flag in the report that the mark is a first pass for the owner to refine.
