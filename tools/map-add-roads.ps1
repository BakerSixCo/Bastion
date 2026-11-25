<#
Adds (or refreshes) the driving-road network in an existing map pack without downloading its map tiles again.

  .\map-add-roads.ps1 -Pack maps\state-id.bmap            # then copy the pack to the phone again
  .\map-add-roads.ps1 -Pack maps\state-id.bmap -Push

Roads come from OpenStreetMap (Overpass, one request per grid cell, kept in <pack>.osm\streets so a
rerun only asks for missing cells) and are cut into per-cell files by RoadGraph.java (needs Java 17+).
The pack is updated in place; the old road files in it are replaced.
#>
param(
    [Parameter(Mandatory = $true)] [string] $Pack,
    [switch] $Push,
    [switch] $Sd
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$Pack = (Resolve-Path $Pack).Path
$base = [IO.Path]::Combine([IO.Path]::GetDirectoryName($Pack), [IO.Path]::GetFileNameWithoutExtension($Pack))

# --- the pack's box and grid ---
$zip = [IO.Compression.ZipFile]::OpenRead($Pack)
$entry = $zip.Entries | Where-Object { $_.FullName -eq 'pack.json' }
$reader = New-Object IO.StreamReader($entry.Open()); $json = $reader.ReadToEnd(); $reader.Close(); $zip.Dispose()
$meta = $json | ConvertFrom-Json
$West = [double]$meta.bbox[0]; $South = [double]$meta.bbox[1]; $East = [double]$meta.bbox[2]; $North = [double]$meta.bbox[3]
$GridZoom = 9

function TileX([double]$lon, [int]$z) { [int][math]::Floor(($lon + 180) / 360 * [math]::Pow(2, $z)) }
function TileY([double]$lat, [int]$z) {
    $r = $lat * [math]::PI / 180
    [int][math]::Floor((1 - [math]::Log([math]::Tan($r) + 1 / [math]::Cos($r)) / [math]::PI) / 2 * [math]::Pow(2, $z))
}
function TileLon([int]$x, [int]$z) { $x / [math]::Pow(2, $z) * 360 - 180 }
function TileLat([int]$y, [int]$z) { $t = [math]::PI * (1 - 2 * $y / [math]::Pow(2, $z)); [math]::Atan([math]::Sinh($t)) * 180 / [math]::PI }

function Overpass([string]$q, [string]$dest) {
    if ((Test-Path $dest) -and (Get-Item $dest).Length -gt 0) { return $true }
    New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
    $qf = "$dest.query"; [IO.File]::WriteAllText($qf, $q)
    $wait = 20
    for ($try = 0; $try -lt 8; $try++) {
        $keep = $ErrorActionPreference; $ErrorActionPreference = 'SilentlyContinue'
        & curl.exe --silent --fail --max-time 400 -A 'map-pack-builder/1.0' --data-urlencode "data@$qf" -o "$dest.part" 'https://overpass-api.de/api/interpreter' 2>$null
        $code = $LASTEXITCODE; $ErrorActionPreference = $keep
        if ($code -eq 0 -and (Test-Path "$dest.part")) {
            $head = (Get-Content "$dest.part" -TotalCount 40) -join "`n"
            if ($head.TrimStart().StartsWith('<') -and $head -notmatch 'runtime error') { Move-Item -Force "$dest.part" $dest; Remove-Item $qf; return $true }
        }
        Write-Host "  OpenStreetMap server busy; trying again in $wait s ..."
        Start-Sleep -Seconds $wait; $wait = [math]::Min($wait * 2, 600)
    }
    return $false
}

# --- download, one grid cell at a time ---
$x0 = TileX $West $GridZoom; $x1 = TileX $East $GridZoom; $y0 = TileY $North $GridZoom; $y1 = TileY $South $GridZoom
$rawDir = "$base.osm\streets"
$cells = ($x1 - $x0 + 1) * ($y1 - $y0 + 1); $done = 0; $failed = 0
$inv = [Globalization.CultureInfo]::InvariantCulture
$body = 'way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|track|road|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"]["service"!~"^(parking_aisle|drive-through|emergency_access)$"]["area"!="yes"];out body geom qt;rel(bw)["type"="restriction"];out body qt;'
$start = Get-Date
for ($cx = $x0; $cx -le $x1; $cx++) { for ($cy = $y0; $cy -le $y1; $cy++) {
    $done++
    $w = [math]::Max($West, (TileLon $cx $GridZoom)); $e = [math]::Min($East, (TileLon ($cx + 1) $GridZoom))
    $n = [math]::Min($North, (TileLat $cy $GridZoom)); $s = [math]::Max($South, (TileLat ($cy + 1) $GridZoom))
    $q = '[out:xml][timeout:300][bbox:' + [string]::Format($inv, '{0},{1},{2},{3}', $s, $w, $n, $e) + '];' + $body
    if (-not (Overpass $q (Join-Path $rawDir "$cx-$cy.xml"))) { $failed++ }
    Write-Host ("  Roads: cell {0} of {1} ({2:N0} min so far)" -f $done, $cells, ((Get-Date) - $start).TotalMinutes)
} }
if ($failed) { Write-Warning "$failed of $cells cells could not be downloaded; run the same command again to fetch them. Building with what is here." }

# --- cut at junctions ---
$java = (Get-Command java -ErrorAction SilentlyContinue).Source
if (-not $java) { $jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-*' | Sort-Object Name -Descending | Select-Object -First 1; $java = Join-Path $jdk.FullName 'bin\java.exe' }
$graphDir = "$base.roads"
if (Test-Path $graphDir) { Remove-Item -Recurse -Force $graphDir }
$report = & $java -Xmx8g (Join-Path $PSScriptRoot 'RoadGraph.java') $rawDir $graphDir $GridZoom $x0 $y0 $x1 $y1
if ($LASTEXITCODE -ne 0) { throw 'RoadGraph.java failed' }
Write-Host "Driving roads: $report"

# --- into the pack ---
$files = Get-ChildItem $graphDir -Recurse -File -Filter '*.bin'
$zip = [IO.Compression.ZipFile]::Open($Pack, 'Update')
# Zips made on Windows may use "\" between folders.
@($zip.Entries | Where-Object { $_.FullName.Replace('\', '/') -like 'layers/streets/*' }) | ForEach-Object { $_.Delete() }
foreach ($f in $files) {
    $rel = 'layers/streets/' + $f.Directory.Name + '/' + $f.Name
    [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.FullName, $rel, [IO.Compression.CompressionLevel]::NoCompression)
}
$layers = @($meta.layers | Where-Object { $_.id -ne 'streets' })
$layers += [pscustomobject]@{ id = 'streets'; title = 'Driving roads'; kind = 'roadgraph'; grid = $GridZoom; count = $files.Count; complete = ($failed -eq 0) }
$meta.layers = $layers
if ($meta.credits -notmatch 'driving roads') { $meta.credits = $meta.credits + ' Driving roads: (c) OpenStreetMap contributors, ODbL.' }
($zip.Entries | Where-Object { $_.FullName -eq 'pack.json' }).Delete()
$pj = $zip.CreateEntry('pack.json')
$wr = New-Object IO.StreamWriter($pj.Open(), (New-Object Text.UTF8Encoding $false)); $wr.Write(($meta | ConvertTo-Json -Depth 6)); $wr.Close()
$zip.Dispose()
Write-Host ("Pack updated: {0} ({1:N1} MB, {2} road cells). Took {3:N0} min." -f $Pack, ((Get-Item $Pack).Length / 1MB), $files.Count, ((Get-Date) - $start).TotalMinutes)

if ($Push) {
    $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    $dir = '/sdcard/Android/media/com.bastion.terminal/maps'
    if ($Sd) {
        $card = (& $adb shell 'ls /storage' | Where-Object { $_ -match '^[0-9A-F]{4}-[0-9A-F]{4}$' } | Select-Object -First 1)
        if (-not $card) { throw 'No SD card found on the phone.' }
        $dir = "/storage/$card/Android/media/com.bastion.terminal/maps"
    }
    & $adb shell "mkdir -p $dir" | Out-Null
    & $adb push $Pack "$dir/$([IO.Path]::GetFileName($Pack))" | Select-Object -Last 1
}
