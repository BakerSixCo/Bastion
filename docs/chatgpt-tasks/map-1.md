# Task: driving routes across state packs (new branch `chatgpt/map-1`)

Start from Claude's branch (radio-3 is separate and not needed here):

```powershell
git fetch origin
git switch -c chatgpt/map-1 origin/claude/hello-e9r4rq
```

Same rules: `AGENTS.md`, `CLAUDE.md` ("Offline map": routes are computed, never AI; every route screen says
what it does not know; never load a pack whole; `map/` has no network code). You write code and tests.

## Where it stands (measured by Claude, 2026-11-25, PC JVM in a 256 MB heap)

Seven state road packs are built in `C:\Users\ccie5\StudioProjects\Bastion\tools\maps\`
(`state-{id,mt,nv,or,ut,wa,wy}-roads.bmap`, 0.8-1.7 GB each, never committed). `StatePackRouteTest`
routes 8 in-state trips in 0.8-3.3 s each (`BASTION_MAPS=<that folder>`, optional `BASTION_PACKS=id,wa`,
`-PunitTestHeap=256m`). Routing is per pack: `MapStore.drive` → `roadPacks(from, to)` keeps only packs
that contain **both** ends, then `PackChoice.drive`. So Boise → Spokane, Salt Lake City → Boise or
Portland → Seattle via a state line returns nothing when no single pack covers both ends.

## Goal

1. **Cross-pack driving.** When no single pack holds a route between the two ends, route across the road
   packs on the phone that together connect them (adjacent states, at most what the trip needs).
   - Find out first how the packs meet at a border (do their grids align? do they overlap? are border
     junction nodes identical by position? `CLAUDE.md` says road ids differ between packs). Report it.
   - Join networks at matching border junctions by position (state the tolerance and why), never by
     straight-line jumps. A route must never cross a gap the road data does not show.
   - Memory: still within a 256 MB heap, reading cells only as the search reaches them (no whole pack).
   - Blocked roads (stored as positions) must still apply in both packs.
   - The route says which packs it used; if a needed neighbouring pack is missing, say that plainly
     ("No route: the Washington road pack is not on this phone"), never a partial or invented route.
   - `reducedCoverage` must be true if any pack used is incomplete.
2. **Snapping without a full scan.** `RoadGraph.nearby` scans every loaded edge for each snap. Give it a
   per-cell (or finer) index of loaded edges so snapping costs are local. Same results as before: prove it
   with a test comparing old and new snaps on the road test fixtures.

## Measure it (required)

- Extend `StatePackRouteTest` with cross-state trips and expected hour ranges you justify from the
  distance (for example Boise → Spokane, Salt Lake City → Boise, Portland → Seattle via I-5 is in-state, so
  pick Portland → Boise, Reno → Boise, Missoula → Spokane, Cheyenne → Salt Lake City). Report per trip:
  miles, hours, seconds to compute, peak heap, packs used.
- In-state trips must not get slower than today's numbers by more than 10%, and their routes must not
  change (compare total metres to within 0.1%).
- Run with `-PunitTestHeap=256m`. A test that needs the packs is skipped (not failed) without
  `BASTION_MAPS`.
- Unit tests for the join on small synthetic two-pack fixtures (no real packs needed): joined border,
  missing neighbour, a border road that exists in only one pack, a blocked road at the border.

## Files you may change

`map/RoadGraph.kt`, `map/MapPack.kt` (`MapStore.drive`, `PackChoice`, pack lookup), `map/Primitive.kt`,
new files in `map/`, their tests under `android/app/src/test/`, and your report. If the route screen
(`ui/RouteSession.kt` or the map UI) needs to show the packs used or the missing-pack message, make the
smallest change and list it. Nothing else; list anything else that should change (for example in
`tools/map-build.ps1` or `tools/RoadGraph.java`), do not change it.

## Done when

`./gradlew :app:testDebugUnitTest :app:assembleDebug` (JDK 21) pass, the state-pack test with
`BASTION_MAPS` and `-PunitTestHeap=256m` passes including the new cross-state trips, and
`docs/chatgpt-tasks/map-1-report.md` (border findings, the join design, all measurements, what was tested
how: JVM / emulator / not run) is committed on `chatgpt/map-1`. Label every result as in `CLAUDE.md`.
