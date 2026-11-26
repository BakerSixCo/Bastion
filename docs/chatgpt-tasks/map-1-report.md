# map-1 report

Branch `chatgpt/map-1`, based on `origin/claude/hello-e9r4rq` at `b1c882d`.

## Border findings

Tested by reading the seven existing road ZIPs and sampling 12 shared road cells across the 11 overlapping pack pairs. All seven use the same Web Mercator zoom-9 road grid. Their rectangular metadata footprints overlap. Shared road cell counts were ID–WA 8, ID–OR 11, ID–UT 11, ID–NV 10, ID–MT 23, ID–WY 13, NV–OR 9, MT–WY 17, NV–UT 10, OR–WA 15, and UT–WY 12.

- All 2,524 sampled shared OSM endpoint IDs agreed at the exact signed microdegree latitude and longitude. The tolerance is zero stored coordinate units, with matching OSM ID also required. In the sample, 16 pairs of distinct junction IDs within 10 m had no explicit edge between them. The closest was 5.553 m apart at Ontario.
- The 2,278 matching sampled edge geometries had different pack-local ordinals. The ID–WA sample also reused 1,933 numeric ordinals on different geometry. All 106 matching sampled one-way edges agreed on direction.
- The sample had 743 pairs of roads that shared their first geometry segment out of a junction and had different far endpoints. Turn restrictions varied by pack. For example, the ID and WA samples had one and eight restrictions respectively at shared in-cell junctions. Equal road IDs, nearby coordinates, and tile adjacency are insufficient to join roads.

## Implementation

- `map/RoadPackRouting.kt` finds candidate packs on the same grid using their ZIP cell inventory, including neighboring cells. It tries the trip corridor and short metadata paths before opening a wider connected set. `map/MapPack.kt` tries the single-pack route first and serializes route and pack access so a cross-pack search can release the existing cached graphs before opening another graph. The search decodes only cells it reaches.
- `map/RoadGraph.kt` keeps pack-local edge IDs and their turn rules in separate namespaces. Graphs share a node only when its OSM ID and stored coordinates agree. Matching geometry can share an edge while retaining the IDs referenced by restrictions. Conflicting directions can close a shared edge. Differently split edges are treated conservatively at restricted approaches. The graph never inserts a straight-line link at a boundary.
- Blocks resolve against every candidate pack at the saved position. Roads through cells intersecting the 50 m radius are loaded before measuring their exact distance to the block, including a long side road whose endpoints are outside the snap detail area.
- `map/RoadSnapIndex.kt` indexes loaded segment geometry bounds. It preserves original edge order and uses the original segment projection on local candidates. `RoadGraphSnapTest` compares indexed hits with brute-force projection, including curves and ties. `CrossPackDriveTest` covers exact joins, adjacent cells, missing neighbors, nearby disconnected roads, relative distance, incomplete coverage, restrictions on duplicate geometry, and blocks at the border and along a long side road.
- `ui/RouteSession.kt` and `ui/MapScreen.kt` show packs used, incomplete coverage, and the known missing pack or generic connection failure. Route conditions and access limits remain visible.

## Tests on the PC JVM with real road packs

Temurin JDK 21, 256 MiB test heap, seven packs from the read-only `C:\Users\ccie5\StudioProjects\Bastion\tools\maps` directory. Each trip opens a cold route graph. Compute time includes pack reads and alternative searches. Peak is total JVM used heap sampled every 10 ms, so it can miss shorter spikes. Cell counts include cells read through the on-demand source, measured as distinct entries per pack.

| Cross-state trip | Miles | Route hours | Expected hours | Compute s | Peak MiB | Packs used | Cells read |
| --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| Boise → Spokane | 367.33 | 6.611 | 4.5–8.5 | 2.127 | 203.9 | ID, WA | 99 + 25 |
| Salt Lake City → Boise | 339.94 | 4.444 | 4.0–6.5 | 3.879 | 252.2 | UT, ID | 89 + 71 |
| Portland → Boise | 430.49 | 6.422 | 5.0–8.5 | 3.826 | 252.3 | OR, ID | 103 + 22 |
| Reno → Boise | 421.60 | 6.157 | 5.0–9.0 | 1.583 | 193.9 | NV, OR, ID | 88 + 28 + 33 |
| Missoula → Spokane | 197.58 | 2.778 | 2.3–4.5 | 2.000 | 226.4 | MT, ID, WA | 73 + 44 + 22 |
| Cheyenne → Salt Lake City | 439.44 | 5.870 | 5.0–8.5 | 2.280 | 248.2 | WY, UT | 116 + 37 |

The expected ranges use waypoint distances and plausible speeds on each corridor. Straight segments between waypoints total about 304 mi via New Meadows, Lewiston and Pullman, 318 mi via Ogden and Burley, 391 mi via the Columbia Gorge, La Grande and Ontario, 373 mi via Winnemucca, McDermitt and Jordan Valley, 173 mi via Lookout Pass and Coeur d'Alene, and 391 mi via Laramie, Rawlins and Rock Springs, respectively. The ranges allow for road curvature and differences between rural and interstate speeds. Drive times are estimates from the stored road network.

| In-state trip | Metres before and after | Miles | Route hours | Baseline s | Targeted s | Final full-suite s | Final peak MiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Boise → Idaho Falls | 452133.338 | 280.9 | 3.628 | 1.370 | 1.363 | 1.856 | 152.0 |
| Boise → Coeur d'Alene | 609341.989 | 378.6 | 6.826 | 1.995 | 1.523 | 1.662 | 182.8 |
| Missoula → Billings | 551620.208 | 342.8 | 4.553 | 1.787 | 1.041 | 0.891 | 102.8 |
| Reno → Las Vegas | 711410.217 | 442.0 | 6.925 | 3.283 | 1.839 | 1.679 | 222.4 |
| Portland → Bend | 261258.859 | 162.3 | 3.161 | 4.234 | 2.308 | 2.023 | 245.5 |
| Salt Lake City → St George | 486830.690 | 302.5 | 4.052 | 3.120 | 1.831 | 1.685 | 202.8 |
| Seattle → Spokane | 449169.651 | 279.1 | 4.108 | 6.706 | 3.830 | 3.550 | 250.7 |
| Cheyenne → Jackson | 695341.821 | 432.1 | 6.182 | 1.503 | 0.851 | 0.710 | 103.3 |

Baseline times were measured on this PC at the branch base with the same fixtures and heap. All eight distances and primary drive times matched to the printed precision. All eight met the 10% threshold in the targeted run. The first trip was above that threshold in full-suite runs, ranging from 1.741 to 2.522 s. The other seven were within threshold in the final full run. The measurements establish the distance regression result and a targeted speed comparison, with a variable first-trip timing under the full test workload.

## Validation and limits

- **Compile and package, tested on PC.** From `android`, `.\gradlew.bat --offline --no-daemon '-Pandroid.builder.sdkDownload=false' '-PunitTestHeap=256m' :app:testDebugUnitTest :app:assembleDebug` succeeded. The SDK was `C:\Users\ccie5\AppData\Local\Android\Sdk`.
- **Correctness, tested on PC JVM.** The same command with `BASTION_MAPS` set passed all 245 tests, including six cross-state and eight in-state trips. The targeted command used the same options and `:app:testDebugUnitTest --tests '*CrossPackDriveTest*' --tests '*RoadGraphSnapTest*' --tests '*StatePackRouteTest*' :app:assembleDebug`. With `BASTION_MAPS` cleared, `:app:testDebugUnitTest --tests '*StatePackRouteTest*'` succeeded via skips. The differently split via-way guard was code-reviewed and exercised by the suite as a whole, with no dedicated synthetic case.
- **Android UI and hardware, not run.** The map screen compiled into the debug APK. ART heap behavior, interaction, and field accuracy remain unmeasured. Portland → Boise reached 255.1 MiB earlier in the full suite. The heap results cover the tested trips and fixtures.
- Missing pack names use unique endpoint coverage against the seven kit build footprints. Overlapping footprints and missing intermediate packs can only be reported generically. Different road grids do not join. An exact match can reject a real connection if independently produced snapshots move or renumber its junction. A compatible source revision across packs and a stable cross-pack road identity in `tools/RoadGraph.java` would make junction and turn matching easier to validate.

The seven existing packs were read in place. No pack, built APK, or generated measurement file was committed.
