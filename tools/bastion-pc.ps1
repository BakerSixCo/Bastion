# BASTION on the PC: starts the simulated mesh (two Meshtastic nodes in WSL),
# the Android emulator, and BASTION. Safe to run again — it only starts what
# isn't running. Double-click bastion-pc.cmd, or:  powershell -File tools\bastion-pc.ps1
#
# First-time setup (once):  wsl -d Ubuntu -u root -- bash tools/meshsim/install.sh
param(
    [string]$Avd = 'Medium_Phone_API_37.0',
    [switch]$NoMesh
)
$ErrorActionPreference = 'Stop'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emu = Join-Path $sdk 'emulator\emulator.exe'

if (-not $NoMesh) {
    Write-Host 'Mesh simulator (WSL)...'
    $state = (wsl -d Ubuntu -u root -- systemctl is-active meshsim-a meshsim-b 2>$null) -join ' '
    if ($state -ne 'active active') {
        wsl -d Ubuntu -u root -- systemctl start meshsim-a meshsim-b meshsim-a-fwd meshsim-b-fwd
        Start-Sleep 5
    }
    # Keep the WSL instance from idling out while the session is in use.
    if (-not (Get-CimInstance Win32_Process -Filter "Name='wsl.exe'" | Where-Object { $_.CommandLine -match 'bastion-keepalive' })) {
        Start-Process wsl -ArgumentList '-d','Ubuntu','--','bash','-c','exec -a bastion-keepalive sleep infinity' -WindowStyle Hidden
    }
    $ok = (Test-NetConnection -ComputerName 127.0.0.1 -Port 4403 -WarningAction SilentlyContinue).TcpTestSucceeded
    Write-Host ("  Sim A localhost:4403  " + $(if ($ok) { 'up' } else { 'NOT reachable - run tools/meshsim/install.sh in WSL' }))
    Write-Host '  Sim B localhost:4404  (second node; send from it with tools\meshsim\send.cmd "text")'
}

Write-Host 'Emulator...'
$devices = (& $adb devices) -join "`n"
if ($devices -notmatch 'emulator-\d+\s+device') {
    # Software graphics: ATAK's map crashes on the host-GPU translator ("No config chosen", 2026-11-22).
    Start-Process -FilePath $emu -ArgumentList '-avd', $Avd, '-no-snapshot-save', '-no-boot-anim', '-gpu', 'swiftshader_indirect'
    & $adb wait-for-device
}
for ($i = 0; $i -lt 120; $i++) {
    if ("$(& $adb shell getprop sys.boot_completed)".Trim() -eq '1') { break }
    Start-Sleep 2
}

$pkg = (& $adb shell pm list packages com.bastion.terminal) -join ''
if ($pkg -notmatch 'com.bastion.terminal') {
    Write-Host '  BASTION is not installed on the emulator. Build and install it:'
    Write-Host '    cd android; .\gradlew :app:installDebug'
} else {
    & $adb shell am start -n com.bastion.terminal/.MainActivity | Out-Null
    Write-Host 'BASTION is open on the emulator.'
}
if (-not $NoMesh) {
    Write-Host 'In the Meshtastic app: Connection > Network > Add device manually > 10.0.2.2 (port 4403).'
}
