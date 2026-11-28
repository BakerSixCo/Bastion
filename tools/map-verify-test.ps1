# Run with PowerShell and the same JDK used by tools/map-verify.java.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

Add-Type -AssemblyName System.IO.Compression.FileSystem
$java = (Get-Command -Name java -CommandType Application | Select-Object -First 1).Source
$verifier = Join-Path $PSScriptRoot 'map-verify.java'
$fixtureName = 'bastion-map-verify-test-' + [Guid]::NewGuid().ToString('N')
$fixtureDir = [IO.Directory]::CreateDirectory((Join-Path ([IO.Path]::GetTempPath()) $fixtureName))
$fixturePaths = [Collections.Generic.List[string]]::new()
$junctionId = 7300000001L

function New-FixturePath([string]$Name) {
    $path = Join-Path $fixtureDir.FullName "$Name.bmap"
    $fixturePaths.Add($path)
    return $path
}

function Write-BigEndianInt([IO.BinaryWriter]$Writer, [int]$Value) {
    $Writer.Write([Net.IPAddress]::HostToNetworkOrder($Value))
}

function Write-BigEndianLong([IO.BinaryWriter]$Writer, [long]$Value) {
    $Writer.Write([Net.IPAddress]::HostToNetworkOrder($Value))
}

function New-RoadCell(
    [long]$EndpointId, [int]$JunctionLat = 1000000, [int]$ToNodeIndex = 1,
    [int]$Version = 4, [long]$EdgeId = 17
) {
    $stream = [IO.MemoryStream]::new()
    $writer = [IO.BinaryWriter]::new($stream)
    try {
        $writer.Write([Text.Encoding]::ASCII.GetBytes("BRG$Version"))
        Write-BigEndianInt $writer 0
        Write-BigEndianInt $writer 2
        Write-BigEndianLong $writer $junctionId
        Write-BigEndianInt $writer $JunctionLat
        Write-BigEndianInt $writer 1000000
        Write-BigEndianLong $writer $EndpointId
        Write-BigEndianInt $writer 1000000
        Write-BigEndianInt $writer 1010000
        Write-BigEndianInt $writer 1
        Write-BigEndianLong $writer $EdgeId
        Write-BigEndianInt $writer 0
        Write-BigEndianInt $writer $ToNodeIndex
        $writer.Write([byte[]]@(0, 0, 30)) # class, direction, speed
        if ($Version -ge 2) { $writer.Write([byte]0) } # flags
        Write-BigEndianInt $writer -1 # unnamed road
        Write-BigEndianInt $writer ([BitConverter]::ToInt32([BitConverter]::GetBytes([single]1112), 0))
        $writer.Write([short]0) # no intermediate shape points
        if ($Version -ge 2) { Write-BigEndianInt $writer 0 } # no node turn restrictions
        if ($Version -ge 4) { Write-BigEndianInt $writer 0 } # no via-way turn restrictions
        return ,$stream.ToArray()
    } finally {
        $writer.Dispose()
    }
}

function Write-Pack(
    [string]$Name,
    [Collections.IDictionary]$Entries,
    [object[]]$Layers = @(),
    [switch]$Elevation
) {
    # Both nodes and the line fit in cell (257, 254) at grid 9 and (514, 509) at grid 10.
    $header = [ordered]@{
        format = 2
        name = $Name
        kind = 'local'
        bbox = @(0.98, 0.98, 1.03, 1.02)
        built = '2026-11-27'
        layers = @($Layers)
        credits = 'Synthetic test fixture'
    }
    if ($Elevation) {
        $header.elev = @{ zoom = 9; ext = 'png'; encoding = 'terrarium' }
    }
    $path = New-FixturePath $Name
    $archive = [IO.Compression.ZipFile]::Open($path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $Entries['pack.json'] = $header | ConvertTo-Json -Depth 6 -Compress
        foreach ($item in $Entries.GetEnumerator()) {
            $value = $item.Value
            [byte[]]$bytes = if ($value -is [string]) { [Text.Encoding]::UTF8.GetBytes($value) } else { $value }
            $entry = $archive.CreateEntry($item.Key).Open()
            try {
                $entry.Write($bytes, 0, $bytes.Length)
            } finally {
                $entry.Dispose()
            }
        }
    } finally {
        $archive.Dispose()
    }
    return $path
}

function Assert-Verification(
    [string]$Scenario,
    [string[]]$Packs,
    [string[]]$Diagnostics,
    [switch]$ShouldFail
) {
    # Capture stderr too: a failed verification is an expected native exit in several cases.
    $ErrorActionPreference = 'Continue'
    $output = & $java $verifier @Packs 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $message = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    if (($code -ne 0) -ne $ShouldFail.IsPresent) {
        throw "Unexpected exit code $code for $Scenario.`n$message"
    }
    foreach ($diagnostic in $Diagnostics) {
        if ($message -notmatch $diagnostic) {
            throw "Expected '$diagnostic' for $Scenario.`n$message"
        }
    }
    Write-Host "PASS $Scenario"
}

try {
    $streets = @{ id = 'streets'; kind = 'roadgraph'; grid = 9; count = 1; complete = $true }
    $otherGrid = @{ id = 'streets'; kind = 'roadgraph'; grid = 10; count = 1; complete = $true }
    $trails = @{ id = 'trails'; kind = 'line'; grid = 9; count = 1 }
    $streetCell = 'layers/streets/257/254.bin'
    $trailCell = 'layers/trails/257/254.geojson'
    $demCell = 'elev/9/257/254.png'
    $line = '{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"Fixture trail"},"geometry":{"type":"LineString","coordinates":[[1.0,1.0],[1.01,1.0]]}}]}'
    # Indexed PNGs containing only Terrarium RGB (128, 0, 0), in 256x256 and 1x1.
    $tile = [Convert]::FromBase64String('iVBORw0KGgoAAAANSUhEUgAAAQAAAAEAAQMAAABmvDolAAAAA1BMVEWAAABGTyZaAAAAH0lEQVR42u3BAQ0AAADCoPdPbQ43oAAAAAAAAAAAvg0hAAABYOSdlwAAAABJRU5ErkJggg==')
    $shortTile = [Convert]::FromBase64String('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABAQMAAAAl21bKAAAAA1BMVEWAAABGTyZaAAAACklEQVR42mNgAAAAAgAB5Sfe/AAAAABJRU5ErkJggg==')

    $base = Write-Pack 'same-grid-base' @{
        $streetCell = New-RoadCell 7300000002L
        $trailCell = $line
        $demCell = $tile
    } @($streets, $trails) -Elevation
    $match = Write-Pack 'same-grid-match' @{ $streetCell = New-RoadCell 7300000003L } @($streets)
    $mismatch = Write-Pack 'same-grid-mismatch' @{ $streetCell = New-RoadCell 7300000004L -JunctionLat 1000001 } @($streets)
    $differentGrid = Write-Pack 'different-grid' @{
        'layers/streets/514/509.bin' = New-RoadCell 7300000005L -JunctionLat 1000001
    } @($otherGrid)
    $badNode = Write-Pack 'bad-node-reference' @{ $streetCell = New-RoadCell 7300000002L -ToNodeIndex 2 } @($streets)
    $missingLayer = Write-Pack 'incomplete-layer-declaration' @{ $streetCell = New-RoadCell 7300000002L } @($streets, $trails)
    $badLine = Write-Pack 'bad-line-cell' @{ $trailCell = '{"type":"FeatureCollection","features":[' } @($trails)
    $badDem = Write-Pack 'bad-dem-tile' @{ $demCell = $shortTile } -Elevation
    $undeclared = Write-Pack 'undeclared-street-cell' @{ $streetCell = New-RoadCell 7300000002L }
    $noncanonical = Write-Pack 'noncanonical-cell-path' @{
        'layers/streets/0257/254.bin' = New-RoadCell 7300000002L
    } @($streets)
    $twoCells = @{ id = 'streets'; kind = 'roadgraph'; grid = 9; count = 2 }
    $badLocal = Write-Pack 'within-pack-mismatch' @{
        $streetCell = New-RoadCell 7300000002L
        'layers/streets/258/254.bin' = New-RoadCell 7300000003L -JunctionLat 1000001
    } @($twoCells)
    $legacy = Write-Pack 'legacy-single-pack' @{
        $streetCell = New-RoadCell 7300000002L -Version 1 -EdgeId -17
    } @($streets)
    $badZip = New-FixturePath 'invalid-container'
    [IO.File]::WriteAllText($badZip, 'This fixture is deliberately not a ZIP archive.')

    Assert-Verification 'matching junction on the same grid' @($base, $match) @('same-grid-base', 'same-grid-match', 'shared junctions=1')
    Assert-Verification 'moved junction on the same grid' @($base, $mismatch) @('junction', "$junctionId", 'same-grid-mismatch') -ShouldFail
    Assert-Verification 'moved junction on a different grid' @($base, $differentGrid) @('different-grid')
    Assert-Verification 'dangling road node index' @($badNode) @('bad-node-reference', 'node index') -ShouldFail
    Assert-Verification 'missing declared layer' @($missingLayer) @('missing', 'trails') -ShouldFail
    Assert-Verification 'invalid line cell' @($badLine) @([regex]::Escape($trailCell)) -ShouldFail
    Assert-Verification 'wrong-sized elevation tile' @($badDem) @([regex]::Escape($demCell)) -ShouldFail
    Assert-Verification 'broken ZIP container' @($badZip) @('invalid-container', 'zip') -ShouldFail
    Assert-Verification 'undeclared street cell' @($undeclared) @('missing enabled streets declaration') -ShouldFail
    Assert-Verification 'noncanonical road cell path' @($noncanonical) @('0257/254.bin', 'invalid streets cell path') -ShouldFail
    Assert-Verification 'conflicting coordinates within one pack' @($badLocal) @("$junctionId", 'within-pack-mismatch', 'internal mismatches=1') -ShouldFail
    Assert-Verification 'legacy road IDs in a standalone pack' @($legacy) @('roadEdges=1')
    Assert-Verification 'legacy road IDs in a joined search' @($base, $legacy) @('joined road id', '-17') -ShouldFail
} finally {
    # Only the registered fixture files are deleted. Directory deletion is nonrecursive.
    foreach ($path in $fixturePaths) {
        if (Test-Path -LiteralPath $path -PathType Leaf) { Remove-Item -LiteralPath $path -Force }
    }
    [IO.Directory]::Delete($fixtureDir.FullName, $false)
}
