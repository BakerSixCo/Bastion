<#
.SYNOPSIS
  Builds an offline map pack (.bmap) for BASTION on this PC.

.DESCRIPTION
  BASTION never goes online, so maps are prepared here and copied to the phone.
  One pack = one area, in one file. The phone shows every pack it holds as one
  seamless map, so the country is covered by stacking three kinds:

    -Overview        the whole lower 48 at low detail (find your way between states)
    -State ID        one state at medium detail (zoom 13: roads, contours, names)
    -Center / -Bbox  a local area at full detail (zoom 15: readable on foot)

  Inside a pack:
    pack.json                 name, bounds, zoom levels, layers, credits
    topo/z/x/y.jpg            USGS topographic map tiles
    land/z/x/y.png            public land (who manages it), see-through overlay
    elev/z/x/y.png            elevation (steepness, shading and walking routes are computed from it on the phone)
    layers/<layer>/x/y.geojson  Forest roads, motor trails, trails (and streams), cut into grid cells
    layers/wilderness.geojson

  Sources (all US government / public): USGS The National Map, BLM Surface
  Management Agency, USDA Forest Service (MVUM, trails, wilderness), USGS NHD,
  and USGS 3DEP elevation as AWS Terrain Tiles. United States only.

  A stopped build can simply be run again: everything already downloaded is kept.

.EXAMPLE
  .\map-build.ps1 -Name sawtooths -Bbox -115.10,44.06,-114.85,44.24
.EXAMPLE
  .\map-build.ps1 -Name home -Center 43.62,-116.20 -RadiusKm 15 -Push
.EXAMPLE
  .\map-build.ps1 -State ID -Estimate
.EXAMPLE
  .\map-build.ps1 -Overview -Push -Sd
#>
param(
    # Pack name (lowercase letters, digits, dashes). Not needed with -State or -Overview.
    [string] $Name,
    # West,South,East,North in degrees.
    [string[]] $Bbox,
    # Or: a centre "lat,lon" and a radius.
    [string[]] $Center,
    [double] $RadiusKm = 10,
    # Or: a whole state by its two-letter code.
    [string] $State,
    # Or: the lower 48 states at low detail.
    [switch] $Overview,
    # Zoom levels. Left at 0 they are chosen from the size of the area.
    [int] $MinZoom = 0,
    [int] $MaxZoom = 0,
    [int] $ElevZoom = 0,
    # Streams are included for local packs and left out of state packs (large, and the map already shows water).
    [switch] $Streams,
    [switch] $NoStreams,
    # Where packs are written; default is the "maps" folder next to this script.
    [string] $OutDir,
    # Only print what would be downloaded and how big it is likely to be.
    [switch] $Estimate,
    # Copy the finished pack to the connected phone (add -Sd for its SD card).
    [switch] $Push,
    [switch] $Sd
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (-not $OutDir) { $OutDir = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'maps' }

# Approximate boxes around each state (they overlap their neighbours a little).
$States = @{
    AL='-88.5,30.1,-84.9,35.1'; AK='-170.0,51.0,-129.9,71.5'; AZ='-114.9,31.3,-109.0,37.1'; AR='-94.7,33.0,-89.6,36.6'
    CA='-124.5,32.5,-114.1,42.1'; CO='-109.1,36.9,-102.0,41.1'; CT='-73.8,40.9,-71.7,42.1'; DE='-75.8,38.4,-75.0,39.9'
    FL='-87.7,24.4,-79.9,31.1'; GA='-85.7,30.3,-80.8,35.1'; HI='-160.3,18.9,-154.8,22.3'; ID='-117.3,41.9,-111.0,49.1'
    IL='-91.6,36.9,-87.4,42.6'; IN='-88.1,37.7,-84.7,41.8'; IA='-96.7,40.3,-90.1,43.6'; KS='-102.1,36.9,-94.5,40.1'
    KY='-89.6,36.4,-81.9,39.2'; LA='-94.1,28.9,-88.8,33.1'; ME='-71.1,43.0,-66.9,47.5'; MD='-79.5,37.8,-75.0,39.8'
    MA='-73.6,41.2,-69.9,42.9'; MI='-90.5,41.6,-82.3,48.3'; MN='-97.3,43.4,-89.4,49.4'; MS='-91.7,30.1,-88.0,35.1'
    MO='-95.8,35.9,-89.0,40.7'; MT='-116.1,44.3,-104.0,49.1'; NE='-104.1,39.9,-95.3,43.1'; NV='-120.1,35.0,-114.0,42.1'
    NH='-72.6,42.6,-70.6,45.4'; NJ='-75.6,38.9,-73.8,41.4'; NM='-109.1,31.3,-103.0,37.1'; NY='-79.8,40.4,-71.8,45.1'
    NC='-84.4,33.8,-75.4,36.6'; ND='-104.1,45.9,-96.5,49.1'; OH='-84.9,38.4,-80.5,42.4'; OK='-103.1,33.6,-94.4,37.1'
    OR='-124.6,41.9,-116.4,46.3'; PA='-80.6,39.7,-74.6,42.3'; RI='-71.9,41.1,-71.1,42.1'; SC='-83.4,32.0,-78.5,35.3'
    SD='-104.1,42.4,-96.4,46.0'; TN='-90.4,34.9,-81.6,36.7'; TX='-106.7,25.8,-93.5,36.6'; UT='-114.1,36.9,-109.0,42.1'
    VT='-73.5,42.7,-71.4,45.1'; VA='-83.7,36.5,-75.2,39.5'; WA='-124.8,45.5,-116.9,49.1'; WV='-82.7,37.2,-77.7,40.7'
    WI='-92.9,42.4,-86.8,47.1'; WY='-111.1,40.9,-104.0,45.1'
}

# Accept both  -Bbox a,b,c,d  and  -Bbox "a,b,c,d"  (the second is what -File passes).
function Numbers($v) { if ($v) { @(($v -join ',') -split '[,\s]+' | Where-Object { $_ } | ForEach-Object { [double]::Parse($_, [Globalization.CultureInfo]::InvariantCulture) }) } }
[double[]] $bb = Numbers $Bbox
[double[]] $ctr = Numbers $Center
$kind = 'local'
if ($Overview) {
    $kind = 'overview'; $bb = @(-125.0, 24.4, -66.9, 49.4); if (-not $Name) { $Name = 'usa-overview' }
} elseif ($State) {
    $code = $State.ToUpper()
    if (-not $States.ContainsKey($code)) { throw "Unknown state '$State'. Use a two-letter code such as ID." }
    $kind = 'state'; $bb = Numbers $States[$code]; if (-not $Name) { $Name = 'state-' + $code.ToLower() }
} elseif ($ctr) {
    $dLat = $RadiusKm / 111.32
    $dLon = $RadiusKm / (111.32 * [math]::Cos($ctr[0] * [math]::PI / 180))
    $bb = @(($ctr[1] - $dLon), ($ctr[0] - $dLat), ($ctr[1] + $dLon), ($ctr[0] + $dLat))
}
if (-not $bb -or $bb.Count -ne 4) { throw 'Give -Bbox west,south,east,north, or -Center lat,lon [-RadiusKm n], or -State XX, or -Overview.' }
$West, $South, $East, $North = $bb
if ($West -ge $East -or $South -ge $North) { throw 'Bbox must be west,south,east,north.' }
if ($Name -notmatch '^[a-z0-9][a-z0-9-]{0,30}$') { throw 'Give -Name: lowercase letters, digits and dashes only.' }

$kmW = ($East - $West) * 111.32 * [math]::Cos(($South + $North) / 2 * [math]::PI / 180)
$kmH = ($North - $South) * 111.32
$areaKm2 = $kmW * $kmH
if ($kind -eq 'local' -and $areaKm2 -gt 20000) { $kind = 'state' }   # a big box is treated like a state

# Detail by kind of pack. Elevation cells: zoom 13 is about 14 m, 12 about 27 m, 9 about 220 m.
$defaults = @{ local = @(9, 15, 13); state = @(8, 13, 12); overview = @(4, 9, 0) }[$kind]
if ($MinZoom -eq 0) { $MinZoom = $defaults[0] }
if ($MaxZoom -eq 0) { $MaxZoom = $defaults[1] }
if ($ElevZoom -eq 0) { $ElevZoom = $defaults[2] }
$LandMax = [math]::Min(13, $MaxZoom)
$withLayers = $kind -ne 'overview'
$withStreams = $withLayers -and -not $NoStreams -and ($Streams -or $kind -eq 'local')
$GridZoom = 9   # line layers are cut into cells of this zoom (about 55 km) so the phone reads only what is on screen

function TileX([double]$lon, [int]$z) { [int][math]::Floor(($lon + 180) / 360 * [math]::Pow(2, $z)) }
function TileY([double]$lat, [int]$z) {
    $r = $lat * [math]::PI / 180
    [int][math]::Floor((1 - [math]::Log([math]::Tan($r) + 1 / [math]::Cos($r)) / [math]::PI) / 2 * [math]::Pow(2, $z))
}
function TileLon([int]$x, [int]$z) { $x / [math]::Pow(2, $z) * 360 - 180 }
function TileLat([int]$y, [int]$z) {
    $t = [math]::PI * (1 - 2 * $y / [math]::Pow(2, $z))
    [math]::Atan([math]::Sinh($t)) * 180 / [math]::PI
}
function TileRange([int]$z) { @((TileX $West $z), (TileY $North $z), (TileX $East $z), (TileY $South $z)) }
function TileCount([int]$z) { $r = TileRange $z; [long]($r[2] - $r[0] + 1) * ($r[3] - $r[1] + 1) }

$topoCount = 0L; foreach ($z in $MinZoom..$MaxZoom) { $topoCount += TileCount $z }
$landCount = 0L; foreach ($z in $MinZoom..$LandMax) { $landCount += TileCount $z }
$elevCount = if ($ElevZoom -gt 0) { TileCount $ElevZoom } else { 0L }
# Averages measured on the Sawtooths pack: topo 22 KB, land 12 KB, elevation 125 KB a tile (flat country is smaller).
$estGb = ($topoCount * 22 + $landCount * 12 + $elevCount * 125) / 1MB
Write-Host ("Pack '{0}' ({1}): about {2:N0} x {3:N0} km, map zoom {4}-{5}, elevation zoom {6}." -f $Name, $kind, $kmW, $kmH, $MinZoom, $MaxZoom, $(if ($ElevZoom) { $ElevZoom } else { 'none' }))
Write-Host ("Tiles: {0:N0} topo, {1:N0} public land, {2:N0} elevation. Roughly {3:N1} GB, and about {4:N0} minutes of downloading at 60 tiles a second." -f $topoCount, $landCount, $elevCount, $estGb, (($topoCount + $landCount + $elevCount) / 60 / 60))
if ($Estimate) { return }

$work = Join-Path $OutDir "$Name.work"
New-Item -ItemType Directory -Force $work | Out-Null
$free = (Get-PSDrive (Split-Path -Qualifier (Resolve-Path $work))[0]).Free / 1GB
if ($free -lt $estGb * 2.2 + 1) { throw ("Not enough free disk space: this needs about {0:N0} GB (the pieces plus the finished pack), {1:N0} GB is free." -f ($estGb * 2.2 + 1), $free) }

# --- tiles: curl, several at a time, in batches, skipping what is already there ---
function FetchTiles([string]$what, [string]$urlBase, [string]$ext, [int]$z0, [int]$z1, [bool]$zxy) {
    foreach ($z in $z0..$z1) {
        $r = TileRange $z
        $lines = New-Object System.Collections.Generic.List[string]
        $made = @{}
        for ($x = $r[0]; $x -le $r[2]; $x++) {
            $dir = Join-Path $work "$what/$z/$x"
            $have = @{}
            if (Test-Path $dir) { foreach ($f in [IO.Directory]::GetFiles($dir)) { if ((New-Object IO.FileInfo $f).Length -gt 0) { $have[[IO.Path]::GetFileName($f)] = 1 } } }
            for ($y = $r[1]; $y -le $r[3]; $y++) {
                if ($have.ContainsKey("$y.$ext")) { continue }
                if (-not $made.ContainsKey($dir)) { [IO.Directory]::CreateDirectory($dir) | Out-Null; $made[$dir] = 1 }
                $url = if ($zxy) { "$urlBase/$z/$x/$y.$ext" } else { "$urlBase/$z/$y/$x" }
                $lines.Add('url = "' + $url + '"')
                $lines.Add('output = "' + (($dir -replace '\\', '/') + "/$y.$ext") + '"')
            }
        }
        if (-not $lines.Count) { continue }
        Write-Host ("  {0} zoom {1}: {2:N0} tiles ..." -f $what, $z, ($lines.Count / 2))
        $cfg = Join-Path $work 'tiles.curl'
        # Batches keep one failure from stopping a long run, and keep progress visible.
        for ($i = 0; $i -lt $lines.Count; $i += 20000) {
            [IO.File]::WriteAllLines($cfg, $lines.GetRange($i, [math]::Min(20000, $lines.Count - $i)))
            RunCurl $cfg
        }
        Remove-Item $cfg -ErrorAction SilentlyContinue
    }
}
$script:tileProblems = 0
# curl has been seen to finish its downloads and then never exit (twice on 2026-11-22), which would hang a
# long build. It is watched: if it does no work for a minute it is stopped; missing tiles are picked up by
# the next run because finished ones are skipped.
$CurlExe = Join-Path (Join-Path $env:SystemRoot 'System32') 'curl.exe'
if (-not (Test-Path $CurlExe)) { $CurlExe = 'curl.exe' }
function RunCurl([string]$config) {
    $p = Start-Process -FilePath $CurlExe -NoNewWindow -PassThru -ArgumentList @('--silent', '--fail', '--location', '--parallel', '--parallel-max', '6', '--retry', '2', '--retry-delay', '2', '--connect-timeout', '20', '--max-time', '90', '--config', ('"' + $config + '"'))
    $last = [TimeSpan]::Zero; $idle = 0
    while (-not $p.WaitForExit(5000)) {
        $p.Refresh()
        $cpu = $p.TotalProcessorTime
        if ($cpu -eq $last) { $idle += 5 } else { $idle = 0; $last = $cpu }
        if ($idle -ge 60) { try { $p.Kill() } catch { }; $script:stalls++; break }
    }
}
$script:stalls = 0
Write-Host 'Downloading tiles (already downloaded ones are skipped) ...'
FetchTiles 'topo' 'https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile' 'jpg' $MinZoom $MaxZoom $false
FetchTiles 'land' 'https://gis.blm.gov/arcgis/rest/services/lands/BLM_Natl_SMA_Cached_without_PriUnk/MapServer/tile' 'png' $MinZoom $LandMax $false
if ($ElevZoom -gt 0) { FetchTiles 'elev' 'https://s3.amazonaws.com/elevation-tiles-prod/terrarium' 'png' $ElevZoom $ElevZoom $true }
# A tile outside a service's coverage can come back empty; an empty file is not a tile.
Get-ChildItem $work -Recurse -File | Where-Object { $_.Length -eq 0 } | Remove-Item
if ($script:stalls) { Write-Warning "The downloader stalled $($script:stalls) time(s) and was restarted. Run the same command again to fetch anything it missed." }

# --- line and area layers ---
function Query([string]$url, [string]$fields, [double]$w, [double]$s, [double]$e, [double]$n, [string]$extra) {
    # Returns the features of one box as a list of JSON fragments, or $null if the service failed.
    $inv = [Globalization.CultureInfo]::InvariantCulture
    $box = [string]::Format($inv, '{0},{1},{2},{3}', $w, $s, $e, $n)
    $out = New-Object System.Collections.Generic.List[string]
    $offset = 0
    while ($true) {
        $q = "where=1%3D1&geometry=$box&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects" +
             "&outFields=$fields&outSR=4326&geometryPrecision=5&f=geojson&resultOffset=$offset&resultRecordCount=1000$extra"
        $tmp = Join-Path $work 'query.part'
        & curl.exe --silent --show-error --fail --retry 3 --max-time 180 -o $tmp "$url/query?$q" 2>$null
        if ($LASTEXITCODE -ne 0) { return $null }
        $text = [IO.File]::ReadAllText($tmp); Remove-Item $tmp
        $m = [regex]::Match($text, '"features"\s*:\s*\[(.*)\]\s*[,}]', 'Singleline')
        if (-not $m.Success) { return $null }
        if ($m.Groups[1].Value.Trim()) { $out.Add($m.Groups[1].Value) }
        $got = ([regex]::Matches($text, '"type"\s*:\s*"Feature"')).Count
        if ($got -lt 1000 -and $text -notmatch '"exceededTransferLimit"\s*:\s*true') { break }
        if ($got -eq 0 -or $offset -ge 50000) { break }
        $offset += $got
    }
    return ,$out
}

$built = @()
if ($withLayers) {
    $lineLayers = @(
        @{ id = 'roads';      title = 'Forest roads'; url = 'https://apps.fs.usda.gov/arcx/rest/services/EDW/EDW_MVUM_01/MapServer/1';
           fields = 'name,id,mvum_symbol_name,seasonal,surfacetype,operationalmaintlevel,passengervehicle,passengervehicle_datesopen,highclearancevehicle,highclearancevehicle_datesopen' },
        @{ id = 'mototrails'; title = 'Motor trails'; url = 'https://apps.fs.usda.gov/arcx/rest/services/EDW/EDW_MVUM_01/MapServer/2';
           fields = 'name,id,mvum_symbol_name,seasonal,atv,atv_datesopen,motorcycle,motorcycle_datesopen' },
        @{ id = 'trails';     title = 'Trails';       url = 'https://apps.fs.usda.gov/arcx/rest/services/EDW/EDW_TrailNFSPublish_01/MapServer/0';
           fields = 'trail_name,trail_no,trail_type,trail_class,typical_trail_grade,trail_surface' }
    )
    if ($withStreams) { $lineLayers += @{ id = 'water'; title = 'Streams'; url = 'https://hydro.nationalmap.gov/arcgis/rest/services/nhd/MapServer/6'; fields = 'gnis_name,fcode' } }
    $g = TileRange $GridZoom
    $cellsTotal = ($g[2] - $g[0] + 1) * ($g[3] - $g[1] + 1)
    foreach ($l in $lineLayers) {
        $count = 0; $failed = 0; $done = 0
        for ($cx = $g[0]; $cx -le $g[2]; $cx++) { for ($cy = $g[1]; $cy -le $g[3]; $cy++) {
            $done++
            $dest = Join-Path $work "layers/$($l.id)/$cx/$cy.geojson"
            $mark = Join-Path $OutDir "$Name.empty/$($l.id)/$cx-$cy"   # remembered outside the pack: this cell has nothing, do not ask again
            if (Test-Path $mark) { continue }
            if (-not (Test-Path $dest)) {
                # The cell, clipped to the pack's own box.
                $w = [math]::Max($West, (TileLon $cx $GridZoom)); $e = [math]::Min($East, (TileLon ($cx + 1) $GridZoom))
                $n = [math]::Min($North, (TileLat $cy $GridZoom)); $s = [math]::Max($South, (TileLat ($cy + 1) $GridZoom))
                $parts = Query $l.url $l.fields $w $s $e $n ''
                if ($null -eq $parts) { $failed++; continue }
                New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
                if ($parts.Count -eq 0) { New-Item -ItemType Directory -Force (Split-Path $mark) | Out-Null; [IO.File]::WriteAllText($mark, ''); continue }
                [IO.File]::WriteAllText($dest, '{"type":"FeatureCollection","features":[' + ($parts -join ',') + ']}')
                if ($cellsTotal -gt 20 -and $done % 20 -eq 0) { Write-Host ("  {0}: cell {1} of {2}" -f $l.title, $done, $cellsTotal) }
            }
            $count += ([regex]::Matches([IO.File]::ReadAllText($dest), '"type"\s*:\s*"Feature"')).Count
        } }
        if ($failed) { Write-Warning "Layer '$($l.title)': $failed of $cellsTotal cells could not be downloaded. Run the same command again to fetch them." }
        Write-Host ("Layer {0,-14} {1,7:N0} features" -f $l.title, $count)
        if ($count) { $built += @{ id = $l.id; title = $l.title; kind = 'line'; grid = $GridZoom; count = $count } }
    }
    # Wilderness: a few large outlines, kept as one simplified file.
    $dest = Join-Path $work 'layers/wilderness.geojson'
    $parts = Query 'https://apps.fs.usda.gov/arcx/rest/services/EDW/EDW_Wilderness_01/MapServer/0' 'wildernessname' $West $South $East $North '&maxAllowableOffset=0.0003'
    if ($null -eq $parts) { Write-Warning 'Layer Wilderness could not be downloaded; the pack is built without it.' }
    else {
        New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
        [IO.File]::WriteAllText($dest, '{"type":"FeatureCollection","features":[' + ($parts -join ',') + ']}')
        $count = ([regex]::Matches([IO.File]::ReadAllText($dest), '"type"\s*:\s*"Feature"')).Count
        Write-Host ("Layer {0,-14} {1,7:N0} features" -f 'Wilderness', $count)
        if ($count) { $built += @{ id = 'wilderness'; title = 'Wilderness'; kind = 'area'; file = 'layers/wilderness.geojson'; count = $count } }
    }
}

# --- pack.json ---
function HasFiles([string]$d) {
    $p = Join-Path $work $d
    (Test-Path $p) -and ($null -ne (Get-ChildItem $p -Recurse -File | Select-Object -First 1))
}
$pack = [ordered]@{
    format  = 2
    name    = $Name
    kind    = $kind
    bbox    = @($West, $South, $East, $North)
    built   = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd')
    layers  = $built
    credits = 'Map: USGS The National Map. Public land: BLM Surface Management Agency. Roads, trails, wilderness: USDA Forest Service. Streams: USGS NHD. Elevation: USGS 3DEP via AWS Terrain Tiles.'
}
if (HasFiles 'topo') { $pack.topo = [ordered]@{ minZoom = $MinZoom; maxZoom = $MaxZoom; ext = 'jpg' } }
if (HasFiles 'land') { $pack.land = [ordered]@{ minZoom = $MinZoom; maxZoom = $LandMax; ext = 'png' } }
if (HasFiles 'elev') { $pack.elev = [ordered]@{ zoom = $ElevZoom; ext = 'png'; encoding = 'terrarium' } }
[IO.File]::WriteAllText((Join-Path $work 'pack.json'), ($pack | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding $false))

# --- one file ---
$file = Join-Path $OutDir "$Name.bmap"
if (Test-Path $file) { Remove-Item $file }
Write-Host 'Packing into one file ...'
[IO.Compression.ZipFile]::CreateFromDirectory($work, $file, [IO.Compression.CompressionLevel]::NoCompression, $false)
Write-Host ("Pack: {0} ({1:N1} MB). The downloaded pieces stay in {2} so a rebuild is quick; delete that folder to save space." -f $file, ((Get-Item $file).Length / 1MB), $work)

if ($Push) {
    $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    $pkg = 'com.bastion.terminal'
    $dir = "/sdcard/Android/media/$pkg/maps"
    if ($Sd) {
        $card = (& $adb shell 'ls /storage' | Where-Object { $_ -match '^[0-9A-F]{4}-[0-9A-F]{4}$' } | Select-Object -First 1)
        if (-not $card) { throw 'No SD card found on the phone.' }
        $dir = "/storage/$card/Android/media/$pkg/maps"
    }
    & $adb shell "mkdir -p $dir" | Out-Null
    & $adb push $file "$dir/$Name.bmap" | Select-Object -Last 1
    Write-Host 'On the phone: open BASTION > Map.'
}
