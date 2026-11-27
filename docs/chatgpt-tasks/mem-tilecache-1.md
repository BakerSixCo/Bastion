# Task mem-tilecache-1: make the map tile bitmap cache size-aware (branch `chatgpt/mem-tilecache-1`)

**Runnable now, independent.** Emulator for the `meminfo` check. Start from Claude's branch:

```powershell
git fetch origin
git switch -c chatgpt/mem-tilecache-1 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md` (especially "Offline map"). No network. Do not touch the owner/Claude-only zones, the route math (`RoadGraph`/`RoadPackRouting`/`TerrainRoute`), or any pack file. No visual regression.

## Why

`ui/MapScreen.kt:183` `private val images = LruCache<String, ImageBitmap>(200)` caps by **count**, not bytes. Each tile is 256×256 ARGB_8888 = 256 KB (base rasters decoded at `:228`, synthesized slope/shade/sun overlays `Bitmap.createBitmap(..., ARGB_8888)` at `:225`), so 200 entries ≈ up to **51 MB** of bitmaps held regardless of device RAM (the app already uses `android:largeHeap`, `AndroidManifest.xml:45`). The elevation cache `LruCache<Long, FloatArray>(40)` (`map/MapPack.kt:163`, ~256 KB each ≈ 10 MB) has the same shape.

## Do

1. Replace the count-based `LruCache` for `images` with one that overrides `sizeOf(key, value)` to return the bitmap's byte size, and set the cache budget from the device: e.g. `ActivityManager.memoryClass` → ~1/8 of heap, clamped to a sane floor/ceiling. Evict by bytes.
2. Optionally give the `MapPack.kt:163` elevation `LruCache` the same byte-aware treatment (FloatArray size × 4).
3. Verify no tiles stop rendering and panning still feels the same.

## Files you may change

`android/app/src/main/java/com/bastion/terminal/ui/MapScreen.kt` (the `MapData` cache), and optionally `map/MapPack.kt` (tile/elevation cache only — not its public API or route code); your report. (Note: `mem-rgb565-1`, if staged, edits the same `MapScreen.kt` decode path — do them together or sequence them. `route-perf-1` is in different `map/` files.)

## Done when

Panning/zooming across many tiles holds graphics/native heap at the byte budget instead of growing with tile count (`adb shell dumpsys meminfo com.bastion.terminal` before/after, shown in the report); tiles still render correctly; `MapPackTest` and the map unit tests stay green; `:app:assembleDebug` builds; `docs/chatgpt-tasks/mem-tilecache-1-report.md` is committed.
