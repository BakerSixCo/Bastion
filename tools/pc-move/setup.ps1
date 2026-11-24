<#
Sets up a freshly reset Windows PC for BASTION development from the folder pack.ps1 made.
Run on the NEW PC, in PowerShell, from the external drive:   .\setup.ps1
Use the same Windows user name as the old PC (ccie5): saved paths inside the emulator
and Claude's memory depend on it.
It installs programs with winget (Windows asks you to approve each), copies the files
back, and prints what is left for you. Run it again after the restart it asks for:
finished steps are skipped.
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
Install-App 'Git.Git'
Install-App 'EclipseAdoptium.Temurin.21.JDK'
Install-App 'Google.AndroidStudio'
Install-App 'Anthropic.Claude'

# 2. Files back to where they were.
function Copy-Back($from, $to) {
    if (-not (Test-Path $from)) { Write-Host "skip (not in the pack): $from"; return }
    Write-Host "copying to $to"
    robocopy $from $to /E /R:1 /W:1 /NFL /NDL /NP /MT:8 | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "copy failed: $to" }
}
Copy-Back "$src\Bastion" "$env:USERPROFILE\StudioProjects\Bastion"
Copy-Back "$src\dot-android" "$env:USERPROFILE\.android"
Copy-Back "$src\Sdk" "$env:LOCALAPPDATA\Android\Sdk"
Copy-Back "$src\dot-claude" "$env:USERPROFILE\.claude"

# 3. Java for the builds: point JAVA_HOME at the installed Temurin 21.
$jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk) {
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk.FullName, 'User')
    Write-Host "JAVA_HOME = $($jdk.FullName)"
} else {
    Write-Warning 'Temurin 21 not found yet: run this script again after the installs finish.'
}

# 4. The simulated radios (WSL).
$haveWsl = $false
try { wsl --status *> $null; $haveWsl = ($LASTEXITCODE -eq 0) } catch { }
if (-not $haveWsl) {
    Write-Host ''
    Write-Host 'WSL is not installed. Open PowerShell AS ADMINISTRATOR, run these two lines, restart, then run setup.ps1 again:'
    Write-Host '  wsl --install --no-distribution'
    Write-Host '  Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All -NoRestart'
} else {
    $distros = (wsl -l -q) -replace "`0", ''
    if ($distros -contains 'Ubuntu') {
        Write-Host 'WSL Ubuntu already present'
    } elseif (Test-Path "$src\ubuntu.tar") {
        Write-Host 'importing the simulated radios'
        New-Item -ItemType Directory -Force "$env:USERPROFILE\wsl\Ubuntu" | Out-Null
        wsl --import Ubuntu "$env:USERPROFILE\wsl\Ubuntu" "$src\ubuntu.tar"
    }
}

Write-Host ''
Write-Host 'Left for you (sign-ins and approvals are yours):'
Write-Host '  1. Open Android Studio once, accept its licences, and open the folder StudioProjects\Bastion\android.'
Write-Host '  2. Open Claude, sign in, and open the folder StudioProjects\Bastion.'
Write-Host '  3. The first git push will ask you to sign in to GitHub.'
Write-Host '  4. Reaching the simulated radios from your iPhone needs the network forwarding redone as administrator: ask Claude for the commands once it is running here.'
