<#
Sets up a freshly reset Windows PC for BASTION development from the folder pack.ps1 filled.
Run on the NEW PC, in PowerShell (not as administrator):

    C:\bastion-move\setup.ps1

Use the same Windows user name as the old PC (ccie5): the emulator phones and Claude's
memory have that path saved inside them.
It installs programs with winget (Windows asks you to approve each), moves the files into
place, and prints what is left for you. Safe to run again: finished steps are skipped.
#>
$ErrorActionPreference = 'Stop'
$src = $PSScriptRoot

if ($env:USERNAME -ne 'ccie5') {
    Write-Warning "This PC's user is '$env:USERNAME'; the old one was 'ccie5'. The emulator phones and Claude's memory expect C:\Users\ccie5."
    if ((Read-Host 'Continue anyway? (y/n)') -ne 'y') { return }
}

function Install-App($id) {
    if (winget list --id $id -e 2>$null | Select-String -SimpleMatch $id) { Write-Host "already installed: $id"; return }
    Write-Host "installing $id"
    winget install --id $id -e --accept-source-agreements --accept-package-agreements
}

# 1. Programs. Git for Windows also provides Git Bash, perl and curl, which the project scripts use.
Write-Host '--- Programs'
Install-App 'Git.Git'
Install-App 'EclipseAdoptium.Temurin.21.JDK'
Install-App 'Google.AndroidStudio'
Install-App 'Anthropic.Claude'

# 2. Files into place. They are moved, not copied, so the PC never holds two full copies.
Write-Host '--- Files'
function Move-Into($what, $from, $to) {
    if (-not (Test-Path $from)) { Write-Host "$what`: already in place"; return }
    Write-Host "$what -> $to"
    robocopy $from $to /E /MOVE /R:2 /W:5 /NFL /NDL /NP /NJH /NJS /MT:8 | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "moving $what failed (robocopy code $LASTEXITCODE). Run setup.ps1 again to continue." }
}
Move-Into 'Project' "$src\Bastion" "$env:USERPROFILE\StudioProjects\Bastion"
Move-Into 'Emulator phones' "$src\dot-android" "$env:USERPROFILE\.android"
Move-Into 'Android SDK' "$src\Sdk" "$env:LOCALAPPDATA\Android\Sdk"
Move-Into 'Claude memory and history' "$src\dot-claude" "$env:USERPROFILE\.claude"

# 3. Java for the builds: point JAVA_HOME at the installed Temurin 21.
Write-Host '--- Java'
$jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk) {
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk.FullName, 'User')
    Write-Host "JAVA_HOME = $($jdk.FullName)"
} else {
    Write-Warning 'Temurin 21 not found yet: run setup.ps1 again after the installs finish.'
}

# 4. The simulated radios (WSL) and the emulator's speed-up feature. Both need administrator rights once.
Write-Host '--- Simulated radios'
$needAdmin = $false
$haveWsl = $false
try { wsl --status *> $null; $haveWsl = ($LASTEXITCODE -eq 0) } catch { }
if (-not $haveWsl) {
    $needAdmin = $true
} else {
    $distros = (wsl -l -q) -replace "`0", ''
    if ($distros -contains 'Ubuntu') {
        Write-Host 'WSL Ubuntu already present'
        Remove-Item "$src\ubuntu.tar" -Force -ErrorAction SilentlyContinue
    } elseif (Test-Path "$src\ubuntu.tar") {
        Write-Host 'importing the simulated radios'
        New-Item -ItemType Directory -Force "$env:USERPROFILE\wsl\Ubuntu" | Out-Null
        wsl --import Ubuntu "$env:USERPROFILE\wsl\Ubuntu" "$src\ubuntu.tar"
        if ($LASTEXITCODE -eq 0) { Remove-Item "$src\ubuntu.tar" -Force } else { Write-Warning 'Import failed. The radios can be rebuilt later; tell Claude.' }
    } else {
        Write-Host 'No radio export in the pack: Claude can rebuild the radios from tools\meshsim.'
    }
}

Write-Host ''
if ($needAdmin) {
    Write-Host 'ONE ADMINISTRATOR STEP: open PowerShell AS ADMINISTRATOR, run these two lines, restart the PC, then run setup.ps1 again:'
    Write-Host '  wsl --install --no-distribution'
    Write-Host '  Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All -NoRestart'
    Write-Host ''
}
Write-Host 'Left for you (sign-ins and approvals are yours):'
Write-Host '  1. Open Android Studio once, accept its licences, then open the folder StudioProjects\Bastion\android.'
Write-Host '  2. Open Claude, sign in, and open the folder StudioProjects\Bastion. Tell Claude "we moved to the new PC".'
Write-Host '  3. The first git push will ask you to sign in to GitHub.'
Write-Host '  4. When all is working, turn sharing off (PowerShell as administrator):  Remove-SmbShare -Name bastion-move -Force'
$global:LASTEXITCODE = 0
