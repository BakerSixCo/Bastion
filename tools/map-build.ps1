<#
.SYNOPSIS
  Builds an offline map pack (.bmap) for BASTION on this PC.

.DESCRIPTION
  BASTION never goes online, so maps are prepared here and copied to the phone.
  One pack = one area, in one file:
    pack.json            name, bounds, zoom levels, layers, credits
    topo/z/x/y.jpg       USGS topographic map tiles
    land/z/x/y.png       public land (who manages it), see-through overlay tiles
    elev/z/x/y.png       elevation tiles (steepness, shade and terrain routes are computed from these on the phone)
    layers/*.geojson     Forest roads, motor trails, trails, wilderness, streams

  Sources (all US government / public): USGS The National Map, BLM Surface
  Management Agency, USDA Forest Service (MVUM, trails, wilderness), USGS NHD,
  and USGS 3DEP elevation as AWS Terrain Tiles. US coverage only.

.EXAMPLE
  .\map-build.ps1 -Name sawtooths -Bbox -115.10,44.06,-114.85,44.24
.EXAMPLE
  .\map-build.ps1 -Name home -Center 43.62,-116.20 -RadiusKm 15 -MaxZoom 16 -Push
#>
param(
    [Parameter(Mandatory)] [string] $Name,
    # West,South,East,North in degrees.
    [string[]] $Bbox,
    # Or: a centre "lat,lon" and a radius.
    [string[]] $Center,
    [double] $RadiusKm = 10,
    [int] $MinZoom = 9,
    # 15 is readable on foot; 16 is the sharpest USGS offers and about 4x the size.
    [int] $MaxZoom = 15,
    # Where packs are written; default is the "maps" folder next to this script.
    [string] $OutDir,
    # Only print what would be downloaded and how big it is likely to be.
    [switch] $Estimate,
    # Copy the finished pack to the connected phone (add -Sd for its SD card).
    [switch] $Push,
    [switch] $Sd
)
$ErrorActionPreference = 'Stop'
# Accept both  -Bbox a,b,c,d  and  -Bbox "a,b,c,d"  (the second is what -File passes).
function Numbers($v) { if ($v) { @(($v -join ',') -split '[,\s]+' | Where-Object { $_ } | ForEach-Object { [double]::Parse($_, [Globalization.CultureInfo]::InvariantCulture) }) } }
[double[]] $bb = Numbers $Bbox; [double[]] $ctr = Numbers $Center
if (-not $OutDir) { $OutDir = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'maps' }
Add-Type -AssemblyName System.IO.Compression.FileSystem

if ($ctr) {
    $dLat = $RadiusKm / 111.32
    $dLon = $RadiusKm / (111.32 * [math]::Cos($ctr[0] * [math]::PI / 180))
    $bb = @(($ctr[1] - $dLon), ($ctr[0] - $dLat), ($ctr[1] + $dLon), ($ctr[0] + $dLat))
}
if (-not $bb -or $bb.Count -ne 4) { throw 'Give -Bbox west,south,east,north or -Center lat,lon [-RadiusKm n].' }
$W, $S, $E, $N = $bb
if ($W -ge $E -or $S -ge $N) { throw 'Bbox must be west,south,east,north.' }
if ($Name -notmatch '^[a-z0-9][a-z0-9-]{0,30}$') { throw 'Name: lowercase letters, digits and dashes only.' }
$ElevZoom = 13
$LandMax = [math]::Min(13, $MaxZoom)

function TileX([double]$lon, [int]$z) { [int][math]::Floor(($lon + 180) / 360 * [math]::Pow(2, $z)) }
function TileY([double]$lat, [int]$z) {
    $r = $lat * [math]::PI / 180
    [int][math]::Floor((1 - [math]::Log([math]::Tan($r) + 1 / [math]::Cos($r)) / [math]::PI) / 2 * [math]::Pow(2, $z))
}
function Tiles([int]$z) {
    $x0 = TileX $W $z; $x1 = TileX $E $z; $y0 = TileY $N $z; $y1 = TileY $S $z
    for ($x = $x0; $x -le $x1; $x++) { for ($y = $y0; $y -le $y1; $y++) { [pscustomobject]@{ z = $z; x = $x; y = $y } } }
}

$jobs = New-Object System.Collections.Generic.List[object]
foreach ($z in $MinZoom..$MaxZoom) { foreach ($t in Tiles $z) {
    $jobs.Add(@{ url = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/$($t.z)/$($t.y)/$($t.x)"; out = "topo/$($t.z)/$($t.x)/$($t.y).jpg" }) } }
$topoCount = $jobs.Count
foreach ($z in $MinZoom..$LandMax) { foreach ($t in Tiles $z) {
    $jobs.Add(@{ url = "https://gis.blm.gov/arcgis/rest/services/lands/BLM_Natl_SMA_Cached_without_PriUnk/MapServer/tile/$($t.z)/$($t.y)/$($t.x)"; out = "land/$($t.z)/$($t.x)/$($t.y).png" }) } }
$landCount = $jobs.Count - $topoCount
foreach ($t in Tiles $ElevZoom) {
    $jobs.Add(@{ url = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$($t.z)/$($t.x)/$($t.y).png"; out = "elev/$($t.z)/$($t.x)/$($t.y).png" }) }
$elevCount = $jobs.Count - $topoCount - $landCount
$estMb = [math]::Round(($topoCount * 22 + $landCount * 12 + $elevCount * 125) / 1024 + 2)
$kmW = [math]::Round(($E - $W) * 111.32 * [math]::Cos(($S + $N) / 2 * [math]::PI / 180))
$kmH = [math]::Round(($N - $S) * 111.32)
Write-Host "Area '$Name': about $kmW x $kmH km, zoom $MinZoom-$MaxZoom."
Write-Host "Tiles: $topoCount topo, $landCount public land, $elevCount elevation. Roughly $estMb MB."
if ($Estimate) { return }

$work = Join-Path $OutDir "$Name.work"
New-Item -ItemType Directory -Force $work | Out-Null

# --- tiles: one curl run, several at a time, skipping what is already there ---
$cfg = Join-Path $work 'tiles.curl'
$lines = New-Object System.Collections.Generic.List[string]
foreach ($j in $jobs) {
    $dest = Join-Path $work $j.out
    if ((Test-Path $dest) -and (Get-Item $dest).Length -gt 0) { continue }
    New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
    $lines.Add('url = "' + $j.url + '"')
    $lines.Add('output = "' + ($dest -replace '\\', '/') + '"')
}
if ($lines.Count) {
    [IO.File]::WriteAllLines($cfg, $lines)
    Write-Host "Downloading $($lines.Count / 2) tiles ..."
    & curl.exe --silent --show-error --fail --location --parallel --parallel-max 8 --retry 3 --retry-delay 2 --connect-timeout 20 --max-time 120 --config $cfg
    if ($LASTEXITCODE -ne 0) { Write-Warning "Some tiles failed (curl exit $LASTEXITCODE). Run the same command again to fetch the missing ones." }
}
# A tile outside a service's coverage can come back empty; an empty file is not a tile.
Get-ChildItem $work -Recurse -File | Where-Object { $_.Length -eq 0 } | Remove-Item

# --- vector layers ---
$layers = @(
    @{ id = 'roads';      title = 'Forest roads'; kind = 'line'; url = 'https://apps.fs.usda.gov/arcx/rest/services/EDW/EDW_MVUM_01/MapServer/1';
       fields = 'name,id,mvum_symbol_name,seasonal,surfacetype,operationalmaintlevel,passengervehicle,passengervehicle_datesopen,highclearancevehicle,highclearancevehicle_datesopen' },
    @{ id = 'mototrails'; title = 'Motor trails'; kind = 'line'; url = 'https://apps.fs.usda.gov/arcx/rest/services/EDW/EDW_MVUM_01/MapServer/2';
       fields = 'name,id,mvum_symbol_name,seasonal,atv,atv_datesopen,motorcycle,motorcycle_datesopen' },
    @{ id = 'trails';     title = 'Trails';       kind = 'line'; url = 'https://apps.fs.usda.gov/arcx/rest/services/EDW/EDW_TrailNFSPublish_01/MapServer/0';
       fields = 'trail_name,trail_no,trail_type,trail_class,typical_trail_grade,trail_surface' },
    @{ id = 'wilderness'; title = 'Wilderness';   kind = 'area'; url = 'https://apps.fs.usda.gov/arcx/rest/services/EDW/EDW_Wilderness_01/MapServer/0';
       fields = 'wildernessname'; simplify = 0.0003 },
    @{ id = 'water';      title = 'Streams';      kind = 'line'; url = 'https://hydro.nationalmap.gov/arcgis/rest/services/nhd/MapServer/6';
       fields = 'gnis_name,fcode' }
)
New-Item -ItemType Directory -Force (Join-Path $work 'layers') | Out-Null
$built = @()
foreach ($l in $layers) {
    $dest = Join-Path $work "layers/$($l.id).geojson"
    $features = New-Object System.Collections.Generic.List[string]
    $offset = 0; $ok = $true
    while ($true) {
        $q = "where=1%3D1&geometry=$W,$S,$E,$N&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects" +
             "&outFields=$($l.fields)&outSR=4326&geometryPrecision=5&f=geojson&resultOffset=$offset&resultRecordCount=1000"
        if ($l.simplify) { $q += "&maxAllowableOffset=$($l.simplify)" }
        $tmp = Join-Path $work "layers/$($l.id).$offset.part"
        & curl.exe --silent --show-error --fail --retry 3 --max-time 180 -o $tmp "$($l.url)/query?$q"
        if ($LASTEXITCODE -ne 0) { Write-Warning "Layer '$($l.title)' could not be downloaded; the pack is built without it."; $ok = $false; break }
        $text = [IO.File]::ReadAllText($tmp); Remove-Item $tmp
        $m = [regex]::Match($text, '"features"\s*:\s*\[(.*)\]\s*[,}]', 'Singleline')
        if (-not $m.Success) { Write-Warning "Layer '$($l.title)': unexpected answer; skipped."; $ok = $false; break }
        if ($m.Groups[1].Value.Trim()) { $features.Add($m.Groups[1].Value) }
        $got = ([regex]::Matches($text, '"type"\s*:\s*"Feature"')).Count
        if ($got -lt 1000 -and $text -notmatch '"exceededTransferLimit"\s*:\s*true') { break }
        if ($got -eq 0 -or $offset -ge 20000) { break }
        $offset += $got
    }
    if (-not $ok) { continue }
    [IO.File]::WriteAllText($dest, '{"type":"FeatureCollection","features":[' + ($features -join ',') + ']}')
    $count = ([regex]::Matches([IO.File]::ReadAllText($dest), '"type"\s*:\s*"Feature"')).Count
    Write-Host ("Layer {0,-14} {1,5} features, {2:N0} KB" -f $l.title, $count, ((Get-Item $dest).Length / 1KB))
    $built += @{ id = $l.id; title = $l.title; kind = $l.kind; file = "layers/$($l.id).geojson"; count = $count }
}

# --- pack.json ---
function HasFiles([string]$d) {
    $p = Join-Path $work $d
    (Test-Path $p) -and (@(Get-ChildItem $p -Recurse -File).Count -gt 0)
}
$pack = [ordered]@{
    format  = 1
    name    = $Name
    bbox    = @($W, $S, $E, $N)
    built   = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd')
    layers  = $built
    credits = 'Map: USGS The National Map. Public land: BLM Surface Management Agency. Roads, trails, wilderness: USDA Forest Service. Streams: USGS NHD. Elevation: USGS 3DEP via AWS Terrain Tiles.'
}
if (HasFiles 'topo') { $pack.topo = [ordered]@{ minZoom = $MinZoom; maxZoom = $MaxZoom; ext = 'jpg' } }
if (HasFiles 'land') { $pack.land = [ordered]@{ minZoom = $MinZoom; maxZoom = $LandMax; ext = 'png' } }
if (HasFiles 'elev') { $pack.elev = [ordered]@{ zoom = $ElevZoom; ext = 'png'; encoding = 'terrarium' } }
$json = $pack | ConvertTo-Json -Depth 6
[IO.File]::WriteAllText((Join-Path $work 'pack.json'), $json, (New-Object Text.UTF8Encoding $false))
Remove-Item $cfg -ErrorAction SilentlyContinue

# --- one file ---
$file = Join-Path $OutDir "$Name.bmap"
if (Test-Path $file) { Remove-Item $file }
[IO.Compression.ZipFile]::CreateFromDirectory($work, $file, [IO.Compression.CompressionLevel]::NoCompression, $false)
$mb = [math]::Round((Get-Item $file).Length / 1MB, 1)
Write-Host "Pack: $file ($mb MB). The downloaded pieces stay in $work so a rebuild is quick; delete that folder to save space."

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
