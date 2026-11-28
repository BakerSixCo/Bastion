# BASTION kit builder (PC side). Collects what goes onto a BASTION phone into one
# folder, from official sources, and checks every file against a pinned SHA-256.
# The phone never downloads anything; tools\provision.ps1 copies the kit over USB.
#
#   powershell -File tools\kit-build.ps1 -List                 # show what's available and sizes
#   powershell -File tools\kit-build.ps1                       # choose interactively
#   powershell -File tools\kit-build.ps1 -Only model-gemma4-e2b,ref-wikimed
#   powershell -File tools\kit-build.ps1 -All -Cache D:\Downloads
#
# -Cache: a folder that may already hold some of the files (e.g. earlier downloads).
#         A cached file is used only if its SHA-256 matches; nothing is trusted by name.
param(
    [string]$Manifest = (Join-Path $PSScriptRoot 'kit-manifest.json'),
    [string]$Out = (Join-Path $PSScriptRoot 'kit'),
    [string]$Cache = '',
    [string[]]$Only = @(),
    [switch]$All,
    [switch]$List,
    [switch]$NoDownload
)
$ErrorActionPreference = 'Stop'
# "-Only a,b,c" arrives as one string when run with powershell -File.
$Only = @($Only | ForEach-Object { $_ -split '[ ,]+' } | Where-Object { $_ })

function Size([long]$b) {
    if ($b -le 0) { return '?' }
    if ($b -ge 1GB) { return ('{0:N1} GB' -f ($b / 1e9)) }
    if ($b -ge 1MB) { return ('{0:N0} MB' -f ($b / 1e6)) }
    return ('{0:N0} KB' -f ($b / 1e3))
}
function Sha([string]$path) { (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant() }

$m = Get-Content -Raw -LiteralPath $Manifest | ConvertFrom-Json
New-Item -ItemType Directory -Force -Path $Out | Out-Null

# Where each item stands right now (hash checks are slow, so only size is compared here).
function State($it) {
    $inKit = Join-Path $Out $it.file
    if (Test-Path -LiteralPath $inKit) { return 'in kit' }
    if ($Cache -and (Test-Path -LiteralPath (Join-Path $Cache $it.file))) { return 'in cache' }
    if (-not $it.url -or -not $it.sha256) { return 'not pinned - add the file yourself' }
    return 'to download'
}

Write-Host "BASTION kit list (version $($m.version))"
Write-Host ''
$i = 0
foreach ($it in $m.items) {
    $i++
    '{0,2}. {1,-62} {2,8}  [{3}]' -f $i, $it.title, (Size $it.bytes), (State $it) | Write-Host
    Write-Host "      id: $($it.id)   licence: $($it.licence)"
}
Write-Host ''
if ($List) { return }

# --- choose ---------------------------------------------------------------------------
if ($All) { $chosen = @($m.items) }
elseif ($Only.Count -gt 0) {
    $chosen = @($m.items | Where-Object { $Only -contains $_.id })
    $unknown = $Only | Where-Object { $_ -notin $m.items.id }
    if ($unknown) { throw "Unknown item id(s): $($unknown -join ', ')" }
}
else {
    $def = @(); $n = 0; foreach ($it in $m.items) { $n++; if ($it.default) { $def += $n } }
    $answer = Read-Host "Numbers to include, separated by spaces (Enter = the usual set: $($def -join ' '))"
    $nums = if ($answer.Trim()) { $answer -split '[ ,]+' | Where-Object { $_ } | ForEach-Object { [int]$_ } } else { $def }
    $chosen = @($nums | ForEach-Object { $m.items[$_ - 1] })
}
if ($chosen.Count -eq 0) { Write-Host 'Nothing chosen.'; return }

# --- size check -----------------------------------------------------------------------
$need = ($chosen | Where-Object { (State $_) -ne 'in kit' } | Measure-Object -Property bytes -Sum).Sum
$drive = (Get-Item -LiteralPath $Out).PSDrive
Write-Host ("Chosen: {0} item(s). Still to add to the kit: {1}. Free on {2}: {3}." -f `
    $chosen.Count, (Size $need), $drive.Name, (Size $drive.Free))
if ($need -gt $drive.Free) { throw 'Not enough free space for this kit. Choose fewer items or another -Out folder.' }

# --- collect and verify ---------------------------------------------------------------
$ok = @(); $problems = @()
foreach ($it in $chosen) {
    $dest = Join-Path $Out $it.file
    Write-Host ''
    Write-Host "== $($it.title)"
    try {
        if (-not (Test-Path -LiteralPath $dest)) {
            $cached = if ($Cache) { Join-Path $Cache $it.file } else { '' }
            if ($cached -and (Test-Path -LiteralPath $cached)) {
                Write-Host "   found in cache, checking it..."
                if ($it.sha256 -and (Sha $cached) -ne $it.sha256) { throw "the cached copy does not match the pinned SHA-256 - not used" }
                Copy-Item -LiteralPath $cached -Destination $dest
            }
            elseif (-not $it.url -or -not $it.sha256) {
                throw "not pinned yet. Download it yourself from the official source and save it as $dest"
            }
            elseif ($NoDownload) { throw 'needs downloading, and -NoDownload was given' }
            else {
                Write-Host "   downloading $(Size $it.bytes) from $(([uri]$it.url).Host) ..."
                # curl resumes an interrupted download (-C -) and follows redirects (-L).
                & curl.exe -L --fail --retry 3 -C - -o "$dest.part" $it.url
                if ($LASTEXITCODE -ne 0) { throw "download failed (curl exit $LASTEXITCODE)" }
                Move-Item -LiteralPath "$dest.part" -Destination $dest -Force
            }
        }
        $h = Sha $dest
        if (-not $it.sha256) {
            Write-Host "   NOT VERIFIED (no pinned SHA-256). This file's SHA-256 is:"
            Write-Host "   $h"
            $problems += "$($it.id): present but unverified"
        }
        elseif ($h -ne $it.sha256) {
            Remove-Item -LiteralPath $dest -Force
            throw "SHA-256 mismatch - the file was removed. Expected $($it.sha256), got $h"
        }
        else { Write-Host '   verified.'; $ok += $it.id }
    }
    catch { Write-Host "   PROBLEM: $($_.Exception.Message)"; $problems += "$($it.id): $($_.Exception.Message)" }
}

Write-Host ''
Write-Host "Kit folder: $Out"
Write-Host "Verified: $($ok.Count)   Problems: $($problems.Count)"
$problems | ForEach-Object { Write-Host "  - $_" }
if ($ok.Count -gt 0) { Write-Host 'Next: connect the phone and run tools\provision.ps1' }
