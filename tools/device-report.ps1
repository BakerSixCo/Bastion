[CmdletBinding()]
param([string] $AdbPath)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-Field($Object, [string] $Name) {
    if ($null -eq $Object) { return }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -ne $property) { $property.Value }
}

function Format-MarkdownCell($Value) {
    $text = ([string]$Value -replace '[\x00-\x1f\x7f]+', ' ').Trim()
    if (!$text) { return 'not reported' }
    $text = $text.Replace('&', '&amp;').Replace('<', '&lt;').Replace('>', '&gt;')
    return $text.Replace('|', '&#124;').Replace('[', '&#91;').Replace(']', '&#93;').Replace('`', '&#96;')
}

function Format-Storage($Bytes) {
    if ($null -eq $Bytes -or $Bytes -isnot [ValueType] -or $Bytes -is [bool] -or $Bytes -lt 0) {
        return 'not reported'
    }
    return ($Bytes / 1e9).ToString('0.00', [Globalization.CultureInfo]::InvariantCulture) + ' GB'
}

if (!$AdbPath) {
    $command = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) {
        $AdbPath = $command.Source
    } else {
        $sdkRoots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
        if ($env:LOCALAPPDATA) { $sdkRoots += Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
        foreach ($sdkRoot in $sdkRoots) {
            if (!$sdkRoot) { continue }
            $candidate = Join-Path $sdkRoot 'platform-tools/adb.exe'
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { $AdbPath = $candidate; break }
        }
    }
}
if (!$AdbPath) { throw 'adb was not found. Use -AdbPath or put Android SDK platform-tools on PATH.' }
$adb = (Get-Command $AdbPath -CommandType Application | Select-Object -First 1).Source

$devices = & $adb devices 2>$null
if ($LASTEXITCODE -ne 0) { throw 'adb could not list devices.' }
$serials = @($devices | ForEach-Object {
    if ($_ -match '^(\S+)\s+device\s*$') { $Matches[1] }
})
if ($serials.Count -ne 1 -or $serials[0] -match ':|\._adb-tls-|\.local$') {
    throw 'Connect exactly one authorized USB device or local emulator.'
}

$remote = '/sdcard/Android/media/com.bastion.terminal/device-check/device-check.json'
$temp = [IO.Path]::GetTempFileName()
try {
    & $adb -s $serials[0] pull $remote $temp *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Could not pull the report. Open the BASTION device check and save the report first.' }
    $report = Get-Content -LiteralPath $temp -Raw -Encoding UTF8 | ConvertFrom-Json
} finally {
    [IO.File]::Delete($temp)
}

$facts = Get-Field $report 'facts'
$verdict = Get-Field $report 'verdict'
$reportedLabel = Get-Field $verdict 'summary'
$tier = Get-Field $verdict 'tier'
$isEmulator = Get-Field $facts 'emulator'
if ((Get-Field $report 'schemaVersion') -ne 1) { throw 'Unsupported device check report version.' }
if ($isEmulator -isnot [bool] -or $reportedLabel -isnot [string] -or $tier -notin 'Expected', 'Not supported') {
    throw 'The file is missing device check facts or a recognized verdict.'
}
$label = if ($isEmulator) {
    'emulator ' + [char]0x2014 + ' not a phone'
} else {
    $reportedLabel
}

$device = @((Get-Field $facts 'maker'), (Get-Field $facts 'model')) -join ' '
$version = Get-Field $facts 'androidVersion'
$sdk = Get-Field $facts 'sdk'
$android = if ($null -ne $sdk -and $version) { "$version ($sdk)" } else { $version }
$ram = Format-Storage (Get-Field $facts 'totalRamBytes')
$advertisedRam = Get-Field $facts 'advertisedRamBytes'
if ($null -ne $advertisedRam -and $advertisedRam -ne (Get-Field $facts 'totalRamBytes')) {
    $ram += ' (' + (Format-Storage $advertisedRam) + ' advertised)'
}
$storage = $ram + ' / ' + (Format-Storage (Get-Field $facts 'phoneFreeBytes'))
$sdField = $facts.PSObject.Properties['sdFreeBytes']
$sd = if ((Get-Field $facts 'knownNoSdSlot') -eq $true) {
    'no SD slot'
} elseif ($null -eq $sdField -or $null -eq $sdField.Value) {
    'not reported'
} else {
    $volumes = @($sdField.Value)
    if (!$volumes.Count) {
        'no removable card detected (slot status unknown)'
    } else {
        $volumesText = for ($index = 0; $index -lt $volumes.Count; $index++) {
            'volume ' + ($index + 1) + ': ' + (Format-Storage $volumes[$index])
        }
        $volumesText -join ', '
    }
}
$evidence = if ($isEmulator) { 'Tested on emulator (device facts)' } else { 'Device facts read on phone' }
$cells = @($device, $android, $storage, $sd, $label, $evidence) | ForEach-Object { Format-MarkdownCell $_ }
'| ' + ($cells -join ' | ') + ' |'
