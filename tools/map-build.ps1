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
    # Build the satellite-imagery companion of a pack instead of the pack itself: same area and
    # zoom levels, imagery only, named <name>-sat. BASTION shows it as the "Satellite" layer.
    [switch] $Satellite,
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
if ($Satellite -and $Name) { $Name = "$Name-sat" }
if ($Name -notmatch '^[a-z0-9][a-z0-9-]{0,30}$') { throw 'Give -Name: lowercase letters, digits and dashes only.' }

$kmW = ($East - $West) * 111.32 * [math]::Cos(($South + $North) / 2 * [math]::PI / 180)
$kmH = ($North - $South) * 111.32
$areaKm2 = $kmW * $kmH
if ($kind -eq 'local' -and $areaKm2 -gt 20000) { $kind = 'state' }   # a big box is treated like a state

# Detail by kind of pack. Elevation cells: zoom 13 is about 14 m, 12 about 27 m, 11 about 55 m.
# A state carries 55 m elevation: at 27 m it was half the pack (1 GB of Idaho's 2.1 GB). Local packs keep 14 m.
$defaults = @{ local = @(9, 15, 13); state = @(8, 13, 11); overview = @(4, 9, 0) }[$kind]
if ($MinZoom -eq 0) { $MinZoom = $defaults[0] }
if ($MaxZoom -eq 0) { $MaxZoom = $defaults[1] }
if ($ElevZoom -eq 0) { $ElevZoom = $defaults[2] }
# Public land stops one level short of the map in a state pack: its top level was 140 MB per state for slightly sharper edges.
$LandMax = [math]::Min($(if ($kind -eq 'state') { 12 } else { 13 }), $MaxZoom)
$withLayers = $kind -ne 'overview' -and -not $Satellite
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
if ($Satellite) {
    FetchTiles 'sat' 'https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile' 'jpg' $MinZoom $MaxZoom $false
} else {
FetchTiles 'topo' 'https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile' 'jpg' $MinZoom $MaxZoom $false
FetchTiles 'land' 'https://gis.blm.gov/arcgis/rest/services/lands/BLM_Natl_SMA_Cached_without_PriUnk/MapServer/tile' 'png' $MinZoom $LandMax $false
if ($ElevZoom -gt 0) { FetchTiles 'elev' 'https://s3.amazonaws.com/elevation-tiles-prod/terrarium' 'png' $ElevZoom $ElevZoom $true }
}
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
        # curl writes to stderr when a request fails; that must count as a failed cell, not stop the build.
        $keep = $ErrorActionPreference; $ErrorActionPreference = 'SilentlyContinue'
        & curl.exe --silent --fail --retry 4 --retry-all-errors --max-time 180 -o $tmp "$url/query?$q" 2>$null
        $code = $LASTEXITCODE; $ErrorActionPreference = $keep
        if ($code -ne 0) { return $null }
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

# --- places and military areas (OpenStreetMap) ---
# One Overpass request each for the whole pack. The raw answers are kept beside the pack
# (<name>.osm) so a rebuild does not ask again; delete that folder to refresh them.
function Overpass([string]$body, [string]$dest, [double[]]$box = @($South, $West, $North, $East), [string]$fmt = 'json') {
    if ((Test-Path $dest) -and (Get-Item $dest).Length -gt 0) { return $true }
    $inv = [Globalization.CultureInfo]::InvariantCulture
    $q = "[out:$fmt][timeout:300][bbox:" + [string]::Format($inv, '{0},{1},{2},{3}', $box[0], $box[1], $box[2], $box[3]) + '];' + $body
    New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
    $qf = "$dest.query"; [IO.File]::WriteAllText($qf, $q)
    $wait = 20
    for ($try = 0; $try -lt 6; $try++) {
        $keep = $ErrorActionPreference; $ErrorActionPreference = 'SilentlyContinue'
        & curl.exe --silent --fail --max-time 400 -A 'map-pack-builder/1.0' --data-urlencode "data@$qf" -o "$dest.part" 'https://overpass-api.de/api/interpreter' 2>$null
        $code = $LASTEXITCODE; $ErrorActionPreference = $keep
        if ($code -eq 0 -and (Test-Path "$dest.part") -and ([IO.File]::ReadAllText("$dest.part")).TrimStart().StartsWith($(if ($fmt -eq 'xml') { '<' } else { '{' }))) {
            Move-Item -Force "$dest.part" $dest; Remove-Item $qf; return $true
        }
        Write-Host "  OpenStreetMap server busy; trying again in $wait s ..."
        Start-Sleep -Seconds $wait; $wait *= 2
    }
    return $false
}
function ReadJson([string]$path) {
    Add-Type -AssemblyName System.Web.Extensions
    $ser = New-Object System.Web.Script.Serialization.JavaScriptSerializer
    $ser.MaxJsonLength = [int]::MaxValue; $ser.RecursionLimit = 200
    $ser.DeserializeObject([IO.File]::ReadAllText($path, [Text.Encoding]::UTF8))
}
function Js([string]$s) {
    if (-not $s) { return '' }
    $s = [regex]::Replace($s, '[\x00-\x1f]', ' ')
    $s.Replace('\', '\\').Replace('"', '\"')
}
function Num([double]$v) { $v.ToString('0.#####', [Globalization.CultureInfo]::InvariantCulture) }

if ($withLayers) {
    $osm = Join-Path $OutDir "$Name.osm"
    $utf8 = New-Object Text.UTF8Encoding $false

    # Places people need: fuel, food, medical, emergency services, water, repair, camps.
    $cats = @{
        'amenity=fuel' = 'Fuel'; 'shop=gas' = 'Fuel'
        'shop=supermarket' = 'Groceries'; 'shop=general' = 'Groceries'; 'shop=convenience' = 'Convenience store'
        'amenity=pharmacy' = 'Pharmacy'; 'amenity=hospital' = 'Hospital'; 'amenity=clinic' = 'Clinic'; 'amenity=doctors' = 'Clinic'
        'amenity=veterinary' = 'Vet'
        'amenity=fire_station' = 'Fire station'; 'amenity=police' = 'Police'; 'emergency=ambulance_station' = 'Ambulance'
        'amenity=ranger_station' = 'Ranger station'
        'amenity=drinking_water' = 'Water'; 'amenity=water_point' = 'Water'
        'shop=hardware' = 'Hardware'; 'shop=doityourself' = 'Hardware'; 'shop=farm' = 'Hardware'
        'shop=outdoor' = 'Outdoor gear'; 'shop=car_repair' = 'Vehicle repair'; 'shop=tyres' = 'Vehicle repair'
        'tourism=camp_site' = 'Campground'
    }
    $nameless = @('Fuel', 'Water', 'Fire station', 'Police', 'Hospital', 'Ambulance', 'Ranger station')   # worth showing even without a name
    $raw = Join-Path $osm 'places.json'
    $body = '(nwr["amenity"~"^(fuel|pharmacy|hospital|clinic|doctors|veterinary|fire_station|police|ranger_station|drinking_water|water_point)$"];' +
            'nwr["shop"~"^(supermarket|convenience|general|hardware|doityourself|outdoor|farm|car_repair|tyres|gas)$"];' +
            'nwr["tourism"="camp_site"];nwr["emergency"="ambulance_station"];);out center tags qt;'
    if (-not (Overpass $body $raw)) { Write-Warning 'Layer Places could not be downloaded; the pack is built without it. Run the same command again later.' }
    else {
        $cells = @{}; $count = 0
        foreach ($el in (ReadJson $raw)['elements']) {
            $t = $el['tags']; if (-not $t) { continue }
            $cat = $null
            foreach ($k in 'amenity', 'shop', 'tourism', 'emergency') { if ($t.ContainsKey($k) -and $cats.ContainsKey("$k=$($t[$k])")) { $cat = $cats["$k=$($t[$k])"]; break } }
            if (-not $cat) { continue }
            if ($t.ContainsKey('access') -and $t['access'] -in 'private', 'no') { continue }
            $nm = $null; foreach ($k in 'name', 'brand', 'operator') { if ($t.ContainsKey($k) -and $t[$k]) { $nm = [string]$t[$k]; break } }
            if (-not $nm -and $cat -notin $nameless) { continue }
            if ($el.ContainsKey('lat')) { $la = [double]$el['lat']; $lo = [double]$el['lon'] }
            elseif ($el.ContainsKey('center')) { $la = [double]$el['center']['lat']; $lo = [double]$el['center']['lon'] }
            else { continue }
            if ($la -lt $South -or $la -gt $North -or $lo -lt $West -or $lo -gt $East) { continue }
            $addr = (@(("$($t['addr:housenumber']) $($t['addr:street'])").Trim(), [string]$t['addr:city']) | Where-Object { $_ }) -join ', '
            $phone = if ($t.ContainsKey('phone')) { [string]$t['phone'] } elseif ($t.ContainsKey('contact:phone')) { [string]$t['contact:phone'] } else { '' }
            $props = '"name":"' + (Js $nm) + '","cat":"' + $cat + '"'
            if ($addr) { $props += ',"addr":"' + (Js $addr) + '"' }
            if ($phone) { $props += ',"phone":"' + (Js $phone) + '"' }
            if ($t.ContainsKey('opening_hours')) { $props += ',"hours":"' + (Js ([string]$t['opening_hours'])) + '"' }
            $key = "$(TileX $lo $GridZoom)/$(TileY $la $GridZoom)"
            if (-not $cells.ContainsKey($key)) { $cells[$key] = New-Object System.Collections.Generic.List[string] }
            $cells[$key].Add('{"type":"Feature","properties":{' + $props + '},"geometry":{"type":"Point","coordinates":[' + (Num $lo) + ',' + (Num $la) + ']}}')
            $count++
        }
        $dir = Join-Path $work 'layers/places'
        if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
        foreach ($key in $cells.Keys) {
            $dest = Join-Path $dir "$key.geojson"
            New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
            [IO.File]::WriteAllText($dest, '{"type":"FeatureCollection","features":[' + ($cells[$key] -join ',') + ']}', $utf8)
        }
        Write-Host ("Layer {0,-14} {1,7:N0} features" -f 'Places', $count)
        if ($count) { $built += @{ id = 'places'; title = 'Places'; kind = 'point'; grid = $GridZoom; count = $count } }
    }

    # Military areas: named outlines, one file.
    $raw = Join-Path $osm 'military.json'
    $body = '(wr["landuse"="military"]["name"];wr["military"~"^(base|airfield|naval_base|barracks|range|training_area|danger_area)$"]["name"];);out tags geom qt;'
    if (-not (Overpass $body $raw)) { Write-Warning 'Layer Military areas could not be downloaded; the pack is built without it. Run the same command again later.' }
    else {
        $feats = New-Object System.Collections.Generic.List[string]
        foreach ($el in (ReadJson $raw)['elements']) {
            $rings = @()
            if ($el['type'] -eq 'way' -and $el.ContainsKey('geometry')) { $rings = @(, $el['geometry']) }
            elseif ($el['type'] -eq 'relation') { $rings = @($el['members'] | Where-Object { $_ -and $_['type'] -eq 'way' -and $_['role'] -ne 'inner' -and $_.ContainsKey('geometry') } | ForEach-Object { , $_['geometry'] }) }
            $lines = @()
            foreach ($r in $rings) {
                $pts = @($r | Where-Object { $_ } | ForEach-Object { '[' + (Num ([double]$_['lon'])) + ',' + (Num ([double]$_['lat'])) + ']' })
                if ($pts.Count -ge 2) { $lines += '[' + ($pts -join ',') + ']' }
            }
            if ($lines.Count) { $feats.Add('{"type":"Feature","properties":{"name":"' + (Js ([string]$el['tags']['name'])) + '"},"geometry":{"type":"MultiLineString","coordinates":[' + ($lines -join ',') + ']}}') }
        }
        $dest = Join-Path $work 'layers/military.geojson'
        New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
        if ($feats.Count) { [IO.File]::WriteAllText($dest, '{"type":"FeatureCollection","features":[' + ($feats -join ',') + ']}', $utf8) }
        elseif (Test-Path $dest) { Remove-Item $dest }
        Write-Host ("Layer {0,-14} {1,7:N0} features" -f 'Military areas', $feats.Count)
        if ($feats.Count) { $built += @{ id = 'military'; title = 'Military areas'; kind = 'line'; file = 'layers/military.geojson'; count = $feats.Count } }
    }

    # Road network for driving routes (OpenStreetMap): one Overpass request per grid cell, kept in
    # <name>.osm/streets so a rebuild does not ask again. tools/RoadGraph.java (needs Java 17+) cuts
    # the roads at junctions and writes one binary file per cell; the phone never loads it whole.
    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) { $jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1; if ($jdk) { $java = Join-Path $jdk.FullName 'bin\java.exe' } } else { $java = $java.Source }
    if (-not $java) { Write-Warning 'No Java found: the pack is built without driving routes. Install a JDK 17+ and run again.' }
    else {
        $g = TileRange $GridZoom
        $rawDir = Join-Path $osm 'streets'
        $cells = ($g[2] - $g[0] + 1) * ($g[3] - $g[1] + 1); $done = 0; $failed = 0
        $body = 'way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|track|road|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"]["service"!~"^(parking_aisle|drive-through|emergency_access)$"]["area"!="yes"];out body geom qt;rel(bw)["type"="restriction"];out body qt;'
        for ($cx = $g[0]; $cx -le $g[2]; $cx++) { for ($cy = $g[1]; $cy -le $g[3]; $cy++) {
            $done++
            $w = [math]::Max($West, (TileLon $cx $GridZoom)); $e = [math]::Min($East, (TileLon ($cx + 1) $GridZoom))
            $n = [math]::Min($North, (TileLat $cy $GridZoom)); $s = [math]::Max($South, (TileLat ($cy + 1) $GridZoom))
            $dest = Join-Path $rawDir "$cx-$cy.xml"
            if (-not (Overpass $body $dest @($s, $w, $n, $e) 'xml')) { $failed++; continue }
            # Overpass reports a timeout inside an otherwise valid answer: drop it so the next run asks again.
            $head = Get-Content $dest -TotalCount 40 -ErrorAction SilentlyContinue
            if (($head -join "`n") -match 'runtime error') { Remove-Item $dest; $failed++; continue }
            if ($cells -gt 20 -and $done % 20 -eq 0) { Write-Host ("  Roads: cell {0} of {1}" -f $done, $cells) }
        } }
        if ($failed) { Write-Warning "Driving roads: $failed of $cells cells could not be downloaded. Run the same command again to fetch them." }
        $graphDir = Join-Path $work 'layers/streets'
        if (Test-Path $graphDir) { Remove-Item -Recurse -Force $graphDir }
        $src = Join-Path $PSScriptRoot 'RoadGraph.java'
        $report = & $java -Xmx6g $src $rawDir $graphDir $GridZoom $g[0] $g[1] $g[2] $g[3]
        if ($LASTEXITCODE -ne 0) { Write-Warning 'Building the road network failed; the pack is built without driving routes.' }
        else {
            $nCells = @(Get-ChildItem $graphDir -Recurse -File -Filter '*.bin' -ErrorAction SilentlyContinue).Count
            Write-Host ("Layer {0,-14} {1}" -f 'Driving roads', $report)
            if ($nCells) { $built += @{ id = 'streets'; title = 'Driving roads'; kind = 'roadgraph'; grid = $GridZoom; count = $nCells; complete = ($failed -eq 0) } }
        }
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
    credits = 'Map: USGS The National Map. Public land: BLM Surface Management Agency. Roads, trails, wilderness: USDA Forest Service. Streams: USGS NHD. Elevation: USGS 3DEP via AWS Terrain Tiles. Places, military areas and driving roads: (c) OpenStreetMap contributors, ODbL.'
}
if (HasFiles 'topo') { $pack.topo = [ordered]@{ minZoom = $MinZoom; maxZoom = $MaxZoom; ext = 'jpg' } }
if (HasFiles 'land') { $pack.land = [ordered]@{ minZoom = $MinZoom; maxZoom = $LandMax; ext = 'png' } }
if (HasFiles 'sat')  { $pack.sat = [ordered]@{ minZoom = $MinZoom; maxZoom = $MaxZoom; ext = 'jpg' }; $pack.credits = 'Imagery: USDA and USGS The National Map, Orthoimagery.' }
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
