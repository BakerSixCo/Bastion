<#
Copies everything BASTION development needs from this PC straight to the new PC over the
home network. Run on the OLD PC, in PowerShell, from the project folder:

    .\tools\pc-move\pack.ps1 -Dest \\NEWPC\bastion-move

NEWPC is the new PC's name (it prints it when you run the share commands there).
Close the emulator first. Nothing on this PC is changed or deleted.
Safe to run again: files already copied are skipped, so a dropped connection costs nothing.

    -ListOnly   shows what would be copied and how much, without copying anything.
#>
param(
    [Parameter(Mandatory = $true)][string]$Dest,
    [switch]$ListOnly
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

if (-not $ListOnly -and (Get-Process qemu-system-x86_64 -ErrorAction SilentlyContinue)) {
    throw 'The emulator is running. Close it, then run this again.'
}

# Reach the share. Windows asks for the new PC's user name and password (the account password, not the PIN).
if ($Dest.StartsWith('\\') -and -not (Test-Path $Dest)) {
    Write-Host "Connecting to $Dest. Enter the new PC's user name and password when asked."
    net use $Dest /persistent:no
    if (-not (Test-Path $Dest)) { throw "Cannot reach $Dest. Check both PCs are on the same network and the share commands were run on the new PC." }
}
if (-not $ListOnly) { New-Item -ItemType Directory -Force $Dest | Out-Null }

$total = 0L
function Copy-Tree($what, $from, $to, $skipDirs = @(), $skipFiles = @()) {
    if (-not (Test-Path $from)) { Write-Host "skip (not found): $from"; return }
    $a = @($from, $to, '/E', '/R:2', '/W:5', '/NFL', '/NDL', '/NP', '/NJH', '/BYTES', '/MT:8')
    if ($ListOnly) { $a += '/L' }
    if ($skipDirs) { $a += '/XD'; $a += $skipDirs }
    if ($skipFiles) { $a += '/XF'; $a += $skipFiles }
    Write-Host ("{0,-46} ..." -f $what) -NoNewline
    $out = robocopy @a
    if ($LASTEXITCODE -ge 8) { Write-Host ' FAILED'; throw "copy failed: $from (robocopy code $LASTEXITCODE). Run the script again to continue." }
    # The summary's "Bytes" line: total, copied, skipped ...
    $line = $out | Where-Object { $_ -match '^\s*Bytes\s*:' } | Select-Object -Last 1
    $n = if ($line) { [regex]::Matches($line, '\d+') | ForEach-Object { [long]$_.Value } } else { @(0, 0) }
    $script:total += $n[0]
    Write-Host (" {0,6:N1} GB, {1:N1} GB {2}" -f ($n[0] / 1GB), ($n[1] / 1GB), $(if ($ListOnly) { 'to copy' } else { 'copied now' }))
}

# 1. The project: code, reference files and models (31 GB), map packs (7 GB). Build output is left out.
Copy-Tree 'Project, reference files, map packs' $repo "$Dest\Bastion" @('build', '.gradle', '.kotlin')
# 2. The emulator phones with everything set up on them (23 GB) and the app signing key.
Copy-Tree 'Emulator phones and signing key' "$env:USERPROFILE\.android" "$Dest\dot-android" @() @('*.lock')
# 3. The Android SDK (5 GB): saves downloading it again.
Copy-Tree 'Android SDK' "$env:LOCALAPPDATA\Android\Sdk" "$Dest\Sdk"
# 4. Claude's memory, session history and the scheduled phone-deal watch.
#    The sign-in file is left out: sign in again on the new PC.
Copy-Tree 'Claude memory and history' "$env:USERPROFILE\.claude" "$Dest\dot-claude" @() @('.credentials.json')

# 5. The simulated radios (WSL Ubuntu): exported to one file here first, then copied.
$tar = Join-Path $env:TEMP 'bastion-ubuntu.tar'
if ($ListOnly) {
    Write-Host ("{0,-46} ...    about 3 GB (exported, then copied)" -f 'Simulated radios (WSL Ubuntu)')
} elseif (Test-Path "$Dest\ubuntu.tar") {
    Write-Host 'Simulated radios: already copied'
} else {
    try {
        wsl --shutdown
        Write-Host 'Simulated radios: exporting WSL Ubuntu ...'
        wsl --export Ubuntu $tar
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $tar)) { throw 'export failed' }
        Copy-Item $tar "$Dest\ubuntu.tar" -Force
        Write-Host ("Simulated radios: {0:N1} GB copied" -f ((Get-Item $tar).Length / 1GB))
    } catch {
        Write-Warning "The simulated radios were not copied ($($_.Exception.Message)). Everything else is fine: they can be rebuilt on the new PC."
    } finally {
        Remove-Item $tar -Force -ErrorAction SilentlyContinue
    }
}

if (-not $ListOnly) {
    Copy-Item "$PSScriptRoot\setup.ps1" "$Dest\setup.ps1" -Force
    Push-Location $repo
    $ahead = git log --oneline '@{u}..' 2>$null
    Pop-Location
    if ($ahead) { Write-Host 'Note: some commits are not pushed to GitHub yet. They are in the copied folder, so nothing is lost.' }
}
Write-Host ''
Write-Host ("Total: {0:N1} GB." -f ($total / 1GB))
if ($ListOnly) { Write-Host 'Nothing was copied (list only).' }
else { Write-Host 'Done. On the new PC, open PowerShell and run:  powershell -ExecutionPolicy Bypass -File C:\bastion-move\setup.ps1' }
$global:LASTEXITCODE = 0
