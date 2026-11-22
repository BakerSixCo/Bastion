# BASTION setup tool (PC side). Makes BASTION the device manager on a dedicated,
# factory-reset Android phone (or a clean emulator) and installs the kit over USB.
# The phone never goes online for this.
#
#   powershell -File tools\provision.ps1                 # the only connected device
#   powershell -File tools\provision.ps1 -Serial emulator-5556
#
# Before running:
#   - Factory-reset the phone and finish Android's welcome screens WITHOUT adding any
#     account (no Google sign-in). Android refuses the device-manager role otherwise.
#   - Enable USB debugging (Settings > About > tap Build number 7 times > Developer options).
#   - Put companion app .apk files in tools\kit\ (Meshtastic, ATAK-CIV, Organic Maps, Kiwix),
#     plus optionally a .litertlm model and .zim files. BASTION installs the apps itself.
#
# This version installs from files you already have. Downloading the kit automatically at
# pinned, verified versions is the next step (see docs/build-sheet.md).
param(
    [string]$Serial = '',
    [string]$Apk = '',
    [string]$Kit = (Join-Path $PSScriptRoot 'kit')
)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$pkg = 'com.bastion.terminal'
function A { if ($Serial) { & $adb -s $Serial @args } else { & $adb @args } }

$abi = ("$(A shell getprop ro.product.cpu.abi)").Trim()
if (-not $abi) { throw 'No device found. Connect the phone with USB debugging on.' }
if (-not $Apk) {
    $Apk = Join-Path $PSScriptRoot "..\android\app\build\outputs\apk\debug\app-$abi-debug.apk"
}
if (-not (Test-Path $Apk)) { throw "BASTION APK not found: $Apk  (build it: cd android; .\gradlew :app:assembleDebug)" }
Write-Host "Device ABI: $abi"

# Android only grants the device-manager role when the phone has no accounts.
$accounts = (A shell dumpsys account) -join "`n"
if ($accounts -match 'Accounts:\s*([1-9]\d*)') {
    throw "This device has $($Matches[1]) account(s). Factory-reset it and set it up without signing in, then run this again."
}

Write-Host 'Installing BASTION...'
A install -r $Apk | Select-Object -Last 1

Write-Host 'Making BASTION the device manager...'
$r = (A shell dpm set-device-owner "$pkg/.device.BastionAdmin") -join ' '
Write-Host "  $r"
if ($r -notmatch 'Success') {
    if ($r -match 'already') { Write-Host '  (already set, continuing)' } else { throw 'Android refused the device-manager role. See the message above.' }
}

# Mesh inbox: notification access cannot be granted by a device manager, but it can over USB.
A shell cmd notification allow_listener "$pkg/$pkg.mesh.MeshInboxService" | Out-Null
Write-Host 'Mesh inbox access granted.'

if (Test-Path $Kit) {
    $media = "/sdcard/Android/media/$pkg"
    A shell mkdir -p "$media/kit" "$media/zim" | Out-Null
    foreach ($f in Get-ChildItem $Kit -File) {
        switch -Regex ($f.Extension) {
            '\.apk$'      { Write-Host "Kit app: $($f.Name)";   A push $f.FullName "$media/kit/$($f.Name)" | Select-Object -Last 1 }
            '\.zim$'      { Write-Host "Reference: $($f.Name)"; A push $f.FullName "$media/zim/$($f.Name)" | Select-Object -Last 1 }
            '\.litertlm$' { Write-Host "AI model: $($f.Name) -> Download (import it in BASTION > Home > Local AI)"
                            A push $f.FullName "/sdcard/Download/$($f.Name)" | Select-Object -Last 1 }
        }
    }
} else {
    Write-Host "No kit folder at $Kit - skipping companion apps."
}

A shell am start -n "$pkg/.MainActivity" | Out-Null
Write-Host ''
Write-Host 'Done. On the phone: BASTION > Home > Device manager > "Install kit apps", then "Apply setup".'
