# Route performance report

The worst sampled cross-state peak fell from 255.8 to 250.4 MiB at the same 256 MiB heap limit. Across the six cross-state trips, the median saving was 41.7 MiB. The Portland to Boise run still used 250.4 MiB, leaving 5.6 MiB of sampled headroom. All 14 trips returned the same ordered route fingerprints, including every returned alternative.

## Before and after

**Tested on the PC JVM.** Baseline application code was from `d7c7fc0`. Both timed runs used Temurin 21, Gradle `-PunitTestHeap=256m`, and the same seven read-only state road packs at `C:\Users\ccie5\StudioProjects\Bastion\tools\maps`. Before each trip, the test requested GC and waited 75 ms. A background thread sampled `totalMemory() - freeMemory()` every 10 ms. Peak values include JVM heap unrelated to the search and can miss allocations between samples. Wall time and peaks vary with GC and system load.

| Trip | Before MiB | After MiB | Saved MiB |
| --- | ---: | ---: | ---: |
| Boise → Idaho Falls | 144.4 | 104.4 | 40.0 |
| Boise → Coeur d’Alene | 188.6 | 135.4 | 53.2 |
| Missoula → Billings | 118.0 | 99.9 | 18.1 |
| Reno → Las Vegas | 225.4 | 148.6 | 76.8 |
| Portland → Bend | 223.9 | 156.5 | 67.4 |
| Salt Lake City → St George | 231.7 | 148.2 | 83.5 |
| Seattle → Spokane | 253.8 | 223.6 | 30.2 |
| Cheyenne → Jackson | 112.7 | 87.5 | 25.2 |
| Boise → Spokane | 255.8 | 179.2 | 76.6 |
| Salt Lake City → Boise | 255.3 | 241.6 | 13.7 |
| Portland → Boise | 253.5 | 250.4 | 3.1 |
| Reno → Boise | 206.0 | 182.4 | 23.6 |
| Missoula → Spokane | 232.2 | 172.4 | 59.8 |
| Cheyenne → Salt Lake City | 250.9 | 162.7 | 88.2 |

The measured compute time decreased on 12 trips. Boise to Idaho Falls took 4.243 s after the changes, up from 3.649 s in the baseline. Boise to Spokane took 5.903 s, up from 5.755 s. These are single-run observations.

## Where the allocations went

- **Junction adjacency.** Per-node lists retained an estimated 60 bytes per node before larger junctions grew, based on compressed HotSpot references and object alignment. Paged heads and tails use about 8 bytes per allocated node slot. Successors use up to 8 bytes per allocated edge, plus page slack. Appending in the original publication order preserves equal-cost tie outcomes and the one-entry self-loop rule.
- **Cell decoding.** The former parser allocated a raw-edge object, list entry, and integer shape array for roads later rejected by hierarchy or deduplication. A skipped record now avoids roughly 56 bytes for the object, the list reference, and roughly `16 + 8 × shape points` bytes for its array on this JVM layout. The parser consumes and bounds-checks every shape and validates the turn table before publishing a cell in file order. Zero-point shapes share one immutable-in-practice empty array.
- **Search state.** The old primitive hash table used 13 bytes per capacity slot and stored every directed edge and turn-history key. A populated ordinary-state page needs 4 bytes per directed edge index. Sparse via-way states keep the full primitive key. Cost, key and predecessor arrays are reused across alternatives, and the search assigns slots and heap priorities in the same order.
- **Snap and turn lookups.** The temporary cross-pack graph indexes new road shapes only when a snap query needs them. This avoids up to four bucket references per deferred edge while retaining publication order when backfilling. Primitive lookup keys and direct scans remove boxed lookup keys and temporary turn-filter lists. Cross-pack prohibition precedence and single-pack only-turn precedence remain distinct.

`RoadGraph.kt` contains the implementation. `StatePackRouteTest.kt` adds the settle interval, ordered route fingerprints and the snapshot-pinned checks. The pack snapshot hashes sorted ZIP record names, sizes and CRCs for the road cells and pack metadata.

## Verification

- **Compile and package tested on PC.** From `android`, `.gradlew.bat --offline --no-daemon '-Pandroid.builder.sdkDownload=false' '-PunitTestHeap=256m' :app:testDebugUnitTest :app:assembleDebug` succeeded.
- **Correctness tested on PC JVM.** The full command passed all 261 tests with no skips. The four routing suites had also passed together. The real-pack baseline used the same options and `:app:testDebugUnitTest --tests '*StatePackRouteTest*'`.
- **Ordered route geometry tested on PC JVM.** An exact comparison matched all 14 trip fingerprints, each covering the ordered alternatives, public road and coordinate sequence, distance and duration. The committed snapshot checks exercised Boise → Idaho Falls and Boise → Spokane, each with three routes. They compare IEEE-754 bits of every output coordinate and are conditional on the matching local road-pack snapshots.
- **Directed OSM junctions tested on PC JVM.** Temporarily instrumented the baseline and optimized route builders. With `BASTION_PACKS=id,wa` and the same targeted Gradle command, both runs passed. Each trace hashed the ordered, directed edges, both OSM node IDs, and exact microdegree coordinates, including the destination edge. All six traces matched across Boise → Idaho Falls and Boise → Spokane. The temporary probe was removed and both production and test files were restored byte for byte.
- **App launch and device performance untested.** The validation ran on the PC JVM. The emulator and phones were unused.
