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
# Build the kit folder first with tools\kit-build.ps1 (downloads from official sources and
# verifies every file). Add -Sd to put reference files on the phone's SD card.
param(
    [string]$Serial = '',
    [string]$Apk = '',
    [string]$Kit = (Join-Path $PSScriptRoot 'kit'),
    # Put reference (.zim) files on the phone's SD card instead of its internal storage.
    [switch]$Sd
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
$owners = (A shell dpm list-owners) -join ' '
if ($owners -match [regex]::Escape("$pkg/")) {
    Write-Host '  already the device manager.'
} else {
    $r = (A shell dpm set-device-owner "$pkg/.device.BastionAdmin") -join ' '
    if ($r -notmatch 'Success') { Write-Host "  $r"; throw 'Android refused the device-manager role. See the message above.' }
    Write-Host '  done.'
}

# Mesh inbox: notification access cannot be granted by a device manager, but it can over USB.
A shell cmd notification allow_listener "$pkg/$pkg.mesh.MeshInboxService" | Out-Null
Write-Host 'Mesh inbox access granted.'

if (Test-Path $Kit) {
    $media = "/sdcard/Android/media/$pkg"
    $zimDir = "$media/zim"
    if ($Sd) {
        # A removable card shows up under /storage as XXXX-XXXX.
        $card = (A shell ls /storage) | ForEach-Object { "$_".Trim() } | Where-Object { $_ -match '^[0-9A-F]{4}-[0-9A-F]{4}$' } | Select-Object -First 1
        if (-not $card) { throw 'No SD card found in the phone. Insert one, or run without -Sd.' }
        $zimDir = "/storage/$card/Android/media/$pkg/zim"
        Write-Host "Reference files go to the SD card ($card)."
    }
    A shell mkdir -p "$media/kit" "$zimDir" | Out-Null
    foreach ($f in Get-ChildItem $Kit -File) {
        switch -Regex ($f.Extension) {
            '\.apk$'      { Write-Host "Kit app: $($f.Name)";   A push $f.FullName "$media/kit/$($f.Name)" | Select-Object -Last 1 }
            '\.zim$'      { Write-Host "Reference: $($f.Name)"; A push $f.FullName "$zimDir/$($f.Name)" | Select-Object -Last 1 }
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
