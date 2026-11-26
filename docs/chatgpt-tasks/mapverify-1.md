# Task mapverify-1: an offline `.bmap` pack validator (branch `chatgpt/mapverify-1`)

**Runnable now, independent.** PC-only, no device. Start from Claude's branch:

```powershell
git fetch origin
git switch -c chatgpt/mapverify-1 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md` ("Offline map"). No network. Read-only on every pack (never write to a `.bmap`).

## Why

Map packs (`.bmap`, one area per file; built by `tools/map-build.ps1`, roads cut by `tools/RoadGraph.java`) are drawn on the phone and routed across with `map/RoadPackRouting.kt`. Cross-state routing relies on one hard invariant: **packs on the same grid join only at junctions with the same OSM id and identical stored coordinates.** A corrupt pack, a missing layer, or an id that sits at two different coordinates in two packs would misroute or fail silently. There is no validator. Build one that checks a pack — and a set of packs together — offline on the PC, so every built or copied pack can be verified before it ships.

## Build

A tool `tools/map-verify.*` — match how the repo already runs such tools (a PowerShell script, or a small Java program run the way `tools/RoadGraph.java` is; say which and why in the report). Learn the `.bmap` container and layer format by **reading** `android/app/src/main/java/com/bastion/terminal/map/MapPack.kt` (and `RoadGraph.kt`, `Dem.kt`, `Primitive.kt`, `Geo.kt` as needed) — do not change them. Given one or more `.bmap` paths it must:

1. **Container/structure:** parse the header and layer directory; confirm every declared layer is present and readable; report the pack's area name, bbox, and the layers it carries (`-Overview` vs `-State XX` vs local, per `CLAUDE.md`).
2. **Layers:** elevation windows (`MapPack.dem`) read without going out of range; line-layer cells (`MapPack.cell`) parse; road cells `layers/streets/x/y.bin` parse; report counts (road nodes, edges, line features, elevation tiles).
3. **Road graph:** every street cell parses; junction/node ids are internally consistent (an id referenced by an edge exists); report any dangling reference.
4. **Cross-pack junction consistency (the key check):** across the packs given, any junction OSM id that appears in two packs on the same grid must have **identical stored coordinates**; list every id that appears in two packs with differing coordinates (that is the invariant `RoadPackRouting` depends on).
5. Print a per-pack summary and a cross-pack report; exit non-zero if any structural error or junction mismatch is found.

## Measure it

Run it on the real packs in Claude's checkout `C:\Users\ccie5\StudioProjects\Bastion\tools\maps` (state packs `state-*-roads.bmap`, `usa-overview.bmap`, `roadtest.bmap`). The known-good packs should pass with **no false errors** (explain any you cannot resolve). Add an automated test if you can build a tiny pack in-test from the format; otherwise document the exact manual run and paste its output. These managed assets are read-only.

## Files you may change

`tools/map-verify.*` (new), any new test for it, your report. Nothing under `android/` (you only read the format there). No pack is modified.

## Done when

The tool runs on the state packs and reports structure + per-pack counts + cross-pack junction consistency, passes clean on the known-good packs (or explains each flag), and `docs/chatgpt-tasks/mapverify-1-report.md` shows the output and how it was run.
