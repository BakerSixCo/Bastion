# Task route-perf-1: cut cross-state routing peak memory (branch `chatgpt/route-perf-1`)

**Runnable now, independent of the kit chain.** No hardware needed (JVM unit tests). Start from Claude's branch:

```powershell
git fetch origin
git switch -c chatgpt/route-perf-1 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md` (especially the "Offline map" section). No network. No behaviour change: routes and alternatives must stay identical.

## Why

Cross-state driving routing (`map/RoadPackRouting.kt`, `map/RoadSnapIndex.kt`, `map/RoadGraph.kt`, merged 2026-11-26) peaks around **255 MiB of a 256 MiB** budget in `StatePackRouteTest` (`CLAUDE.md`: "peaks reach ~255 MiB of 256, so treat new memory use in routing as a regression"). The device uses `largeHeap`, but a pack far larger than memory must never be loaded whole, and that headroom is thin. Give routing real headroom **without changing a single route**.

## Do

1. **Measure first (baseline).** Add a peak-heap probe to `StatePackRouteTest` (test-only code): before each trip force GC and settle, then sample `Runtime.getRuntime().totalMemory() - freeMemory()` on a background sampler thread during the route, and record the peak per trip. Print a table: trip → peak MiB. Run with the real state packs and report the baseline. (Run from Claude's checkout: `BASTION_MAPS` → `C:\Users\ccie5\StudioProjects\Bastion\tools\maps`; `-PunitTestHeap=256m`. The `.bmap` packs are managed assets you **read**, never copy or change.)
2. **Find the hotspots.** Read the three files. Likely costs: boxed keys/values in hot maps (use primitive-keyed structures — e.g. `HashMap<Long,…>` → a long-keyed open-addressing map or parallel arrays), whole-cell arrays retained after a cell is done, coordinates stored twice, per-node object churn in the search frontier, the snap index holding more than it needs. Name each hotspot with evidence (what retains it, rough bytes).
3. **Reduce, proving no behaviour change.** Implement the reductions. These must stay green and **identical in output**:
   - `StatePackRouteTest` (8 in-state + 6 cross-state trips),
   - `CrossPackDriveTest`, `RoadGraphSnapTest` (snap results must match exactly), `RoadGraphTest`.
   Add a check (in the test, temporarily is fine, keep if cheap) that the chosen route's node/coordinate sequence and the up-to-3 alternatives are unchanged from a captured baseline for two representative trips.
4. **Report** before/after peak per trip, the measurement method, each change and why it is behaviour-neutral, and the net MiB saved.

## Files you may change

`android/app/src/main/java/com/bastion/terminal/map/RoadPackRouting.kt`, `RoadSnapIndex.kt`, `RoadGraph.kt` (and private helpers they own); the routing tests under `android/app/src/test/java/com/bastion/terminal/map/` (to add the probe and the route-identity check only — do not weaken existing assertions); your report. **Not** `MapPack.kt`'s public API, `Dem.kt`, `TerrainRoute.kt`, or any pack file.

## Done when

All routing tests pass at `-PunitTestHeap=256m` with the real packs; the report shows before/after peak per trip with the method; two representative routes (and their alternatives) are shown unchanged; `docs/chatgpt-tasks/route-perf-1-report.md` is committed. Do not lower the heap to pass — measure the real peak.
