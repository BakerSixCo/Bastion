# Map verification report

The validator passed all nine requested packs with zero errors. Eight packs carry grid-9 roads. Among them, 19,401 distinct OSM junction IDs occurred in more than one pack and all had identical stored coordinates.

## Implementation

`tools/map-verify.java` uses the same Java source-file launcher as `tools/RoadGraph.java`. It requires Java 17 or newer and uses only the JDK. It opens packs read-only, checks ZIP entry CRCs, parses the format-1/2 header, validates the declared layers, and emits a summary for each area. Physical ZIP entry names are slash-normalized like Android’s reader. Manifest file references must match the in-app paths.

The validator checks the world-grid coordinates of cell and tile names, GeoJSON geometry and source feature counts, BRG1 through BRG4 road records, dangling endpoint indexes, and the 48-bit local road IDs used by joined searches. Individual turn tables are parsed completely. Turn references may legally cross cell boundaries and their pack-wide referential integrity is outside this check. Road node and edge counts are cell records, including expected replicas. Unique nodes are counted separately by pack. Line features count line parts and polygon rings consumed by the app.

Elevation tiles fully decode, must be 256×256 with Terrarium RGB channels, and must fit the declared bbox and Android window indexing. The checker validates a clamped window around each tile using wide integer arithmetic. Ordinary raster tiles receive a signature, image header and full ZIP CRC check. Alternate raster encodings fully decode before acceptance. Full pixel decoding of ordinary raster tiles was out of scope.

Road node IDs and exact microdegree positions are written to temporary partitions. Only one road cell or sorted junction partition is processed at a time. The checker reports each conflicting ID within a pack and across same-grid packs with the relevant paths and coordinates. Input packs are never extracted or changed.

## How it ran

**Compile and run tested on the PC.** Java 21 ran the source launcher in Java 17 mode with a 256 MiB heap. The final real-pack command took 151.15 seconds and exited 0.

```powershell
$maps = 'C:\Users\ccie5\StudioProjects\Bastion\tools\maps'
$statePacks = @(Get-ChildItem -LiteralPath $maps -Filter 'state-*-roads.bmap' -File |
    Sort-Object Name | ForEach-Object FullName)
$mapPacks = $statePacks + (Join-Path $maps 'usa-overview.bmap') + (Join-Path $maps 'roadtest.bmap')
java -Xmx256m --source 17 tools/map-verify.java @mapPacks
```

**Correctness tested on the real packs.** Counts below come from the final command.

| Pack | Kind | Road cells | Road nodes | Unique nodes | Road edges | Vector features | Line features | Elevation tiles |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| state-id | state | 106 | 495,530 | 487,817 | 623,539 | 42,660 | 35,917 | 2,220 |
| state-mt | state | 177 | 465,051 | 455,116 | 572,408 | 32,741 | 28,252 | 2,870 |
| state-nv | state | 101 | 556,319 | 549,196 | 731,649 | 30,914 | 23,817 | 1,908 |
| state-or | state | 106 | 990,189 | 978,992 | 1,196,716 | 52,031 | 40,603 | 1,692 |
| state-ut | state | 84 | 628,663 | 622,389 | 792,075 | 22,480 | 15,806 | 1,170 |
| state-wa | state | 88 | 1,424,035 | 1,412,994 | 1,717,086 | 32,475 | 10,810 | 1,395 |
| state-wy | state | 111 | 344,258 | 336,988 | 423,358 | 12,071 | 10,514 | 1,428 |
| usa-overview | overview | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| roadtest | local | 2 | 9,228 | 9,120 | 12,320 | 401 | 206 | 12 |

```text
CROSS-PACK road packs by grid={9=8}, shared junctions=19401, junction mismatches=0, internal mismatches=0
RESULT OK, 0 error(s)
```

The known-good exceptions were compatible with Android’s reader. The 11,196 ordinary raster tiles with mixed encodings decoded successfully, including 13 in the overview. Sparse raster coverage is allowed, and the local test pack contains one valid empty, undeclared wilderness file. The overview declares imagery only.

**Synthetic checks tested on the PC.** `pwsh -NoProfile -File tools/map-verify-test.ps1` passed all 13 scenarios. The fixtures cover matching and shifted junctions, grid isolation, conflicts within one pack, legacy and joined road IDs, dangling endpoints, missing and undeclared layers, noncanonical paths, broken GeoJSON, wrong-size elevation, and a broken ZIP. The fixtures are generated and removed in the system temporary directory.

**App launch and device behaviour untested.** All validation ran on the PC.
