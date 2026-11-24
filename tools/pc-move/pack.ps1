<#
Packs everything BASTION development needs that is not on GitHub onto an external drive.
Run on the OLD PC, in PowerShell:   .\tools\pc-move\pack.ps1 -Dest E:\bastion-move
Needs about 75 GB free on the drive. Close Android Studio and the emulator first.
Nothing on this PC is changed or deleted.
#>
param([Parameter(Mandatory = $true)][string]$Dest)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (Get-Process qemu-system-x86_64, studio64 -ErrorAction SilentlyContinue) { throw 'Close the emulator and Android Studio first, then run this again.' }
New-Item -ItemType Directory -Force $Dest | Out-Null

function Copy-Tree($from, $to, $skipDirs = @(), $skipFiles = @()) {
    if (-not (Test-Path $from)) { Write-Host "skip (not found): $from"; return }
    Write-Host "copying $from"
    $a = @($from, $to, '/E', '/R:1', '/W:1', '/NFL', '/NDL', '/NP', '/MT:8')
    if ($skipDirs) { $a += '/XD'; $a += $skipDirs }
    if ($skipFiles) { $a += '/XF'; $a += $skipFiles }
    robocopy @a | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "copy failed: $from (robocopy code $LASTEXITCODE)" }
}

# 1. The project folder: code, reference kit (31 GB), map packs (7 GB). Build output is left out.
Copy-Tree $repo "$Dest\Bastion" @('build', '.gradle', '.kotlin')
# 2. The emulator phones with everything set up on them (23 GB) and the app signing key.
Copy-Tree "$env:USERPROFILE\.android" "$Dest\dot-android"
# 3. The Android SDK (5 GB): saves downloading it again.
Copy-Tree "$env:LOCALAPPDATA\Android\Sdk" "$Dest\Sdk"
# 4. Claude's memory, session history and the scheduled phone-deal watch.
#    The sign-in file is left out: sign in again on the new PC.
Copy-Tree "$env:USERPROFILE\.claude" "$Dest\dot-claude" @() @('.credentials.json')
# 5. The simulated radios (WSL Ubuntu), as one file.
wsl --shutdown
Write-Host 'exporting WSL Ubuntu (simulated radios)'
wsl --export Ubuntu "$Dest\ubuntu.tar"

Copy-Item "$PSScriptRoot\setup.ps1" "$Dest\setup.ps1" -Force
Push-Location $repo
$ahead = git log --oneline '@{u}..' 2>$null
Pop-Location
if ($ahead) { Write-Warning 'There are commits not pushed to GitHub. They are in the copied folder, so nothing is lost.' }
$gb = [math]::Round(((Get-ChildItem $Dest -Recurse -File | Measure-Object Length -Sum).Sum / 1GB), 1)
Write-Host "Done: $gb GB in $Dest. On the new PC run setup.ps1 from that folder."
