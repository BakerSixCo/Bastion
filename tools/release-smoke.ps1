# Offline R8 release smoke against one already-running Android target. Run with PowerShell.
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$AndroidSdk,
    [string]$Jdk,
    [string]$EvidenceDirectory,
    [string]$ZimQuery = 'hypothermia',
    [switch]$SkipUncachedAuxiliaryTasks,
    [ValidateRange(30, 3600)][int]$AnswerTimeoutSeconds = 600
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repository = Split-Path -Parent $PSScriptRoot
$android = Join-Path $repository 'android'
$package = 'com.bastion.terminal'
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 10)
$fixtureMarker = "Q$runId"
$remote = "/data/local/tmp/bastion-release-smoke-$runId"
$deviceDownloads = "/sdcard/Download/bastion-release-smoke-$runId"
$dumpPath = "$remote/window.xml"
$fixture = Join-Path $android 'app/src/androidTest/assets/qa/field-pdf.pdf'
$fixturePhrase = 'heliograph three times every minute'
$script:TargetLabel = 'Android target not selected'
$script:Checks = [ordered]@{ ZIM = 'NOT RUN'; PDF = 'NOT RUN'; AI = 'NOT RUN' }
$script:RemoteReady = $false
$script:HadFailure = $false
$script:RemoteFiles = [Collections.Generic.List[string]]::new()
$script:RemoteFiles.Add($dumpPath)

function Set-Check([string]$Check, [string]$Status, [string]$Reason) {
    $script:Checks[$Check] = $Status
    if ($Status -eq 'FAIL') { $script:HadFailure = $true }
    Write-Host "[$script:TargetLabel] $Check $Status - $Reason"
}

function Invoke-Adb {
    # Keep native ADB flags positional, including -p, -r, and -f.
    $lines = & $script:AdbPath -s $script:DeviceSerial @args 2>&1
    $status = $LASTEXITCODE
    $text = ($lines | ForEach-Object { $_.ToString() }) -join "`n"
    if ($status -ne 0) { throw "adb $($args[0]) failed ($status): $text" }
    return $text.Trim()
}

function Resolve-Sdk {
    $candidates = @($AndroidSdk, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    $properties = Join-Path $android 'local.properties'
    if (Test-Path -LiteralPath $properties) {
        $sdkLine = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($sdkLine) { $candidates += $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\') }
    }
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA 'Android/Sdk') }
    foreach ($path in $candidates) {
        if ($path -and (Test-Path -LiteralPath (Join-Path $path 'platform-tools/adb.exe'))) {
            return (Resolve-Path -LiteralPath $path).Path
        }
    }
    throw 'ADB was not found. Supply -AndroidSdk or configure android/local.properties.'
}

function Resolve-Jdk {
    $installed = @(Get-ChildItem -LiteralPath 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -ExpandProperty FullName)
    foreach ($path in @($Jdk, $env:JAVA_HOME) + $installed) {
        if (-not $path) { continue }
        $java = Join-Path $path 'bin/java.exe'
        if (-not (Test-Path -LiteralPath $java)) { continue }
        $version = (& $java -version 2>&1) -join ' '
        if ($LASTEXITCODE -eq 0 -and $version -match 'version "(1[7-9]|2[0-4])\.') { return $path }
    }
    throw 'A local JDK 17-24 is required. Supply -Jdk, for example the installed Temurin 21.'
}

function Build-Release([string]$Sdk) {
    $properties = Get-Content -LiteralPath (Join-Path $android 'gradle/wrapper/gradle-wrapper.properties') -Raw
    foreach ($entry in @('distributionBase=GRADLE_USER_HOME', 'distributionPath=wrapper/dists',
                         'zipStoreBase=GRADLE_USER_HOME', 'zipStorePath=wrapper/dists')) {
        if ($properties -notmatch ('(?m)^' + [regex]::Escape($entry) + '\r?$')) {
            throw 'The Gradle wrapper cache layout has changed; offline build stopped.'
        }
    }
    if ($properties -notmatch '(?m)^distributionUrl=(\S+)\r?$') { throw 'Missing Gradle wrapper URL.' }
    $url = $Matches[1].Replace('\:', ':')
    if ($url -notmatch '/gradle-(?<version>[0-9.]+)-(?<type>bin|all)\.zip$') { throw 'Unknown Gradle wrapper archive.' }
    $version = $Matches.version
    $archive = "gradle-$version-$($Matches.type)"
    # Match Gradle's URL-derived cache key. The wrapper runs before --offline applies.
    $md5 = [Security.Cryptography.MD5]::Create()
    try { $digest = $md5.ComputeHash([Text.Encoding]::UTF8.GetBytes($url)) } finally { $md5.Dispose() }
    [Array]::Reverse($digest)
    $value = [Numerics.BigInteger]::new([byte[]]($digest + 0))
    $key = ''
    do {
        $digit = [Numerics.BigInteger]::Zero
        $value = [Numerics.BigInteger]::DivRem($value, 36, [ref]$digit)
        $key = [string]'0123456789abcdefghijklmnopqrstuvwxyz'[[int]$digit] + $key
    } while ($value -gt 0)
    $gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    $cache = Join-Path $gradleHome "wrapper/dists/$archive/$key"
    if (-not (Test-Path -LiteralPath (Join-Path $cache "$archive.zip.ok")) -or
        -not (Test-Path -LiteralPath (Join-Path $cache "gradle-$version/bin/gradle.bat"))) {
        throw "Gradle $archive is not installed in the exact local wrapper cache; offline build stopped."
    }

    $javaHome, $sdkHome = $env:JAVA_HOME, $env:ANDROID_HOME
    $env:JAVA_HOME = Resolve-Jdk
    $env:ANDROID_HOME = $Sdk
    Write-Host "[$script:TargetLabel] BUILD Java $env:JAVA_HOME, Gradle $version, offline"
    $arguments = @('--offline', '--no-daemon', '-Pandroid.builder.sdkDownload=false', ':app:assembleRelease')
    if ($SkipUncachedAuxiliaryTasks) {
        $arguments += @('-x', ':app:lintVitalRelease',
            '-x', ':app:produceReleaseComposeMapping', '-x', ':app:mergeReleaseComposeMapping')
        Write-Host "[$script:TargetLabel] BUILD vital lint and Compose group mapping excluded for this run"
    }
    Push-Location $android
    try {
        & .\gradlew.bat @arguments | ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
    } finally {
        Pop-Location
        $env:JAVA_HOME, $env:ANDROID_HOME = $javaHome, $sdkHome
    }
}

function Get-Tree {
    # A failed dump can leave the previous XML on the device. Only read after a new dump.
    $message = Invoke-Adb shell uiautomator dump $dumpPath
    if ($message -notmatch '(?i)dumped to' -or $message -match '(?i)error:') { throw "UI dump failed: $message" }
    $tree = [xml](Invoke-Adb shell cat $dumpPath)
    return ,$tree
}

function Find-Node([xml]$Tree, [string]$Pattern, [string]$PackageName, [string]$ClassName) {
    foreach ($node in $Tree.SelectNodes('//node')) {
        if ($node.GetAttribute('enabled') -eq 'false') { continue }
        if ($PackageName -and $node.GetAttribute('package') -ne $PackageName) { continue }
        if ($ClassName -and $node.GetAttribute('class') -ne $ClassName) { continue }
        if ($node.GetAttribute('text') -match $Pattern -or $node.GetAttribute('content-desc') -match $Pattern) {
            if ($node.GetAttribute('bounds') -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$' -and
                [int]$Matches[3] -gt [int]$Matches[1] -and [int]$Matches[4] -gt [int]$Matches[2]) {
                return $node
            }
        }
    }
    return $null
}

function Swipe-Up {
    $x = [int]($script:Width * 0.52)
    [void](Invoke-Adb shell input swipe $x ([int]($script:Height * 0.72)) $x ([int]($script:Height * 0.29)) 410)
}

function Wait-Node([string]$Pattern, [string]$PackageName = $package, [int]$Attempts = 8, [switch]$Scroll, [string]$ClassName) {
    $lastError = ''
    for ($i = 0; $i -lt $Attempts; $i++) {
        try {
            $tree = Get-Tree
            $node = Find-Node $tree $Pattern $PackageName $ClassName
            if ($null -ne $node) { return $node }
        } catch { $lastError = $_.Exception.Message }
        if ($Scroll) { Swipe-Up } else { Start-Sleep -Milliseconds 350 }
    }
    throw "UI element '$Pattern' was not found. $lastError"
}

function Tap-Node([System.Xml.XmlElement]$Node) {
    $bounds = [regex]::Matches($Node.GetAttribute('bounds'), '\d+')
    if ($bounds.Count -ne 4) { throw "Malformed element bounds: $($Node.GetAttribute('bounds'))" }
    $x = [int](([int]$bounds[0].Value + [int]$bounds[2].Value) / 2)
    $y = [int](([int]$bounds[1].Value + [int]$bounds[3].Value) / 2)
    [void](Invoke-Adb shell input tap $x $y)
}

function Set-Input([string]$Value) {
    if ($Value -notmatch '^[A-Za-z0-9. -]+$') { throw 'Text entry requires a plain ASCII value.' }
    $expected = '^' + [regex]::Escape($Value) + '$'
    $editClass = 'android.widget.EditText'
    for ($i = 0; $i -lt 3; $i++) {
        # Library dismisses its import message on the first edit and may recreate this field.
        Tap-Node (Wait-Node '.*' -ClassName $editClass)
        Start-Sleep -Milliseconds 550
        [void](Invoke-Adb shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_A)
        [void](Invoke-Adb shell input keyevent KEYCODE_DEL)
        [void](Invoke-Adb shell input text ($Value.Replace(' ', '%s')))
        try {
            $node = Find-Node (Get-Tree) $expected $package $editClass
            if ($null -ne $node) { return }
        } catch {}
    }
    throw "The active text field did not contain '$Value' after input."
}

function Hide-Keyboard {
    if ((Invoke-Adb shell dumpsys input_method) -match '\b(?:mInputShown|mShowRequested)=true\b') {
        [void](Invoke-Adb shell input keyevent KEYCODE_BACK)
    }
}

function Save-Screen([string]$Name) {
    $path = "$remote/$Name.png"
    $local = Join-Path $script:OutputPath "$Name.png"
    $script:RemoteFiles.Add($path)
    [void](Invoke-Adb shell screencap -p $path)
    [void](Invoke-Adb pull $path $local)
    Write-Host "[$script:TargetLabel] screenshot $local"
    return $local
}

function Begin-Log([string]$Phase) {
    $mark = "$runId-$Phase"
    [void](Invoke-Adb shell log -t BastionReleaseSmoke $mark)
    return $mark
}

function Read-Log([string]$Marker) {
    $log = Invoke-Adb logcat -d -v brief -s 'BastionReleaseSmoke:I' 'BastionZim:I' 'BastionAI:I' 'AndroidRuntime:E'
    $pos = $log.LastIndexOf($Marker, [StringComparison]::Ordinal)
    if ($pos -lt 0) { throw "The logcat marker for $Marker is missing." }
    return $log.Substring($pos + $Marker.Length)
}

function Get-Preflight {
    # Shared media is a confirmation of presence. App state also covers other volumes
    # and the app-private fallback directories.
    $zimPath = "/sdcard/Android/media/$package/zim"
    $media = Invoke-Adb shell "if [ -d '$zimPath' ]; then ls -1 '$zimPath'; fi"
    $zimFiles = @($media -split "`n" | Where-Object { $_ -match '(?i)\.zim$' })
    for ($i = 0; $i -lt 12; $i++) {
        $tree = Get-Tree
        $intro = Find-Node $tree 'No offline reference \(ZIM\)|Offline reference searched too:|Offline reference unavailable:' $package
        if ($null -eq $intro) { continue }
        $text = $intro.GetAttribute('text')
        if ($text -match 'No offline reference' -and ($zimFiles.Count -gt 0 -or $i -lt 2)) { continue }
        $zim = if ($text -match 'Offline reference searched too:') { 'ready' }
               elseif ($text -match 'No offline reference') { 'absent' }
               else { 'failed' }
        $model = if ($text -match 'Local AI is not installed\.') { 'absent' }
                 elseif ($text -match 'Local AI failed:') { 'failed' }
                 elseif ($text -match 'A model is being imported') { 'importing' }
                 else { 'present' }
        return [pscustomobject]@{ Zim = $zim; Model = $model; Text = $text }
    }
    throw 'The Ask page did not resolve the ZIM/model preconditions.'
}

function Invoke-ZimCheck([string]$State) {
    if ($State -eq 'absent') {
        [void](Save-Screen 'zim-skipped')
        Set-Check ZIM SKIP 'no ZIM present'
        return
    }
    if ($State -ne 'ready') { throw 'The app could not open its offline reference.' }
    Set-Input $ZimQuery
    Hide-Keyboard
    $search = Wait-Node '^Search library \+ reference$'
    $marker = Begin-Log 'zim'
    Tap-Node $search
    for ($i = 0; $i -lt 30; $i++) {
        $line = (Read-Log $marker) -split "`n" | Where-Object {
            $_ -match 'BastionZim.*query=\[.*\].*zim=.*\.zim results=[1-9][0-9]*\s*$'
        } | Select-Object -First 1
        if ($line) {
            [void](Wait-Node '^Sources \(' -Attempts 7 -Scroll)
            [void](Save-Screen 'zim-search')
            Set-Check ZIM PASS ($line.Trim())
            return
        }
        Start-Sleep -Seconds 1
    }
    throw 'The current query produced no positive BastionZim search result in logcat.'
}

function Stage-Fixture {
    $name = "release-smoke-$runId.pdf"
    $local = Join-Path $script:OutputPath $name
    $encoding = [Text.Encoding]::GetEncoding(28591)
    $pdf = $encoding.GetString([IO.File]::ReadAllBytes($fixture))
    $originalMarker = 'QA PDF TEST'
    if (-not $pdf.Contains($originalMarker)) { throw 'The PDF fixture search marker is missing.' }
    # A same-length marker makes this run's PDF searchable without shifting PDF offsets.
    [IO.File]::WriteAllBytes($local, $encoding.GetBytes($pdf.Replace($originalMarker, $fixtureMarker)))
    [void](Invoke-Adb shell mkdir -p $deviceDownloads)
    $script:DownloadsReady = $true
    $path = "$deviceDownloads/$name"
    $script:RemoteFiles.Add($path)
    [void](Invoke-Adb push $local $path)
    return $name
}

function Select-Pdf([string]$Name) {
    $leaf = '^' + [regex]::Escape($Name) + '$'
    $folder = '^bastion-release-smoke-' + $runId + '$'
    $roots = Wait-Node 'Show roots|^Open from$' '' -Attempts 10
    Tap-Node $roots
    # The local storage provider exposes adb-pushed PDFs before Downloads indexes them.
    Tap-Node (Wait-Node $script:StorageRoot '' -Attempts 6)
    Tap-Node (Wait-Node '^Download$' '' -Attempts 7 -Scroll)
    Tap-Node (Wait-Node $folder '' -Attempts 7 -Scroll)
    Tap-Node (Wait-Node $leaf '' -Attempts 6)
}

function Invoke-PdfCheck {
    $name = Stage-Fixture
    $title = [IO.Path]::GetFileNameWithoutExtension($name)
    Tap-Node (Wait-Node '^Library$')
    Tap-Node (Wait-Node '^Import document$')
    Select-Pdf $name
    [void](Wait-Node ('Imported .*' + [regex]::Escape($title)) -Attempts 15)
    Set-Input $fixtureMarker
    Hide-Keyboard
    Tap-Node (Wait-Node ('^' + [regex]::Escape($title) + '$') -Attempts 9 -Scroll)
    [void](Wait-Node 'PDF text layer extracted' -Attempts 8)
    [void](Wait-Node ([regex]::Escape($fixtureMarker)) -Attempts 8)
    [void](Wait-Node ([regex]::Escape($fixturePhrase)) -Attempts 8)
    [void](Save-Screen 'pdf-extracted')
    Set-Check PDF PASS "Reader shows PDF text layer extracted and the expected fixture phrase ($title)."
}

function Invoke-AiCheck([string]$State) {
    if ($State -eq 'absent') {
        Set-Check AI SKIP 'no model imported (see preflight screenshot)'
        return
    }
    if ($State -ne 'present') { throw "A model is not ready for generation (state: $State)." }
    if ($script:Checks.PDF -ne 'PASS') { throw 'The fixture passage was not established by the PDF check.' }
    Tap-Node (Wait-Node '^.*Back$' -Attempts 5)
    Tap-Node (Wait-Node '^Ask$')
    Set-Input "What is $fixtureMarker about"
    Hide-Keyboard
    $search = Wait-Node '^Search library(?: \+ reference)?$'
    $retrievalMarker = Begin-Log 'ai-search'
    Tap-Node $search
    $fixtureRetrieved = $false
    for ($i = 0; $i -lt 30; $i++) {
        $log = Read-Log $retrievalMarker
        if ($log -match ('BastionAI.*retrieved .*release-smoke-' + $runId)) {
            $fixtureRetrieved = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $fixtureRetrieved) { throw 'The current Ask search did not retrieve the PDF imported by this run.' }
    $answer = Wait-Node '^Answer from these sources$' -Attempts 11 -Scroll
    $marker = Begin-Log 'ai'
    Tap-Node $answer
    Confirm-AiAnswer $marker
}

function Confirm-AiAnswer([string]$Marker) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt $AnswerTimeoutSeconds) {
        $log = Read-Log $Marker
        if ($log -match 'verified card:') { throw 'The app used a verified card instead of model generation.' }
        if ($log -match 'FATAL EXCEPTION' -and $log -match 'Process: com\.bastion\.terminal,') {
            throw 'Android logged a BASTION fatal exception during generation.'
        }
        # CPU is the last backend in Auto, so its load failure cannot fall back again.
        $cpuFailure = $log -split "`n" | Where-Object { $_ -match 'BastionAI.*backend CPU failed:' } |
            Select-Object -First 1
        if ($cpuFailure) { throw $cpuFailure.Trim() }
        $failure = $null
        $errorPattern = '^Generation failed:|^Model failed to load:|^Not enough free storage to start the model:|' +
            '^Local AI is not available\.|^The library does not contain an answer to that\.'
        try { $failure = Find-Node (Get-Tree) $errorPattern $package } catch {}
        if ($null -ne $failure) { throw $failure.GetAttribute('text') }
        $done = $log -split "`n" | Where-Object { $_ -match 'BastionAI.*done .*noAnswer=' } | Select-Object -Last 1
        if ($done) {
            if ($log -notmatch 'checked answer: stage=both failed=null' -or
                $done -notmatch 'cited=\[[^\]]+\] invalid=\[\].*noAnswer=false withheld=null') {
                throw "The checked answer did not pass: $done"
            }
            [void](Wait-Node '^Local AI answer$' -Attempts 7 -Scroll)
            [void](Wait-Node '(?s)signal mirror.*\[\d+\]|\[\d+\].*signal mirror' -Attempts 6)
            [void](Save-Screen 'ai-answer')
            Set-Check AI PASS $done.Trim()
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "No accepted AI answer was generated within $AnswerTimeoutSeconds seconds."
}

function Invoke-SmokeCheck([string]$Name, [scriptblock]$Action) {
    try {
        & $Action
    } catch {
        Set-Check $Name FAIL $_.Exception.Message
        try { [void](Save-Screen ($Name.ToLowerInvariant() + '-failed')) } catch {
            Write-Host "[$script:TargetLabel] Screenshot unavailable: $($_.Exception.Message)"
        }
    }
}

try {
    $sdk = Resolve-Sdk
    $script:AdbPath = Join-Path $sdk 'platform-tools/adb.exe'
    $devices = & $script:AdbPath devices -l 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Cannot list Android devices: $($devices -join ' ')" }
    $attached = @(foreach ($line in $devices) {
        if ($line -match '^(?<serial>\S+)\s+device(?:\s|$)') { $Matches.serial }
    })
    if ($Serial) {
        if ($Serial -notin $attached) { throw "Selected Android serial '$Serial' is unavailable." }
        $script:DeviceSerial = $Serial
    } elseif ($attached.Count -eq 1) {
        $script:DeviceSerial = $attached[0]
    } else {
        throw 'Exactly one running Android target is required, or supply -Serial.'
    }

    $modelName = Invoke-Adb shell getprop ro.product.model
    $storageNames = @($modelName, (Invoke-Adb shell settings get global device_name),
        'Internal storage', 'Internal shared storage') | Where-Object { $_ -and $_ -ne 'null' }
    $script:StorageRoot = '^(?:' + (($storageNames | ForEach-Object { [regex]::Escape($_) }) -join '|') + ')$'
    $qemu = Invoke-Adb shell getprop ro.kernel.qemu
    $isEmulator = $script:DeviceSerial -match '^emulator-\d+$' -or $qemu -eq '1'
    $script:TargetLabel = if ($isEmulator) { "emulator $script:DeviceSerial ($modelName)" }
                          else { "$modelName ($script:DeviceSerial)" }
    if ((Invoke-Adb shell getprop sys.boot_completed) -ne '1') { throw 'Android is still booting.' }
    if ((Invoke-Adb shell dumpsys activity processes) -match '\bActiveInstrumentation\{') {
        throw 'An instrumentation run is active. The selected device is busy.'
    }
    $abi = Invoke-Adb shell getprop ro.product.cpu.abi
    if ($abi -notin @('arm64-v8a', 'x86_64')) {
        $abi = (Invoke-Adb shell getprop ro.product.cpu.abilist) -split ',' |
            Where-Object { $_ -in @('arm64-v8a', 'x86_64') } | Select-Object -First 1
    }
    if (-not $abi) { throw 'The selected target does not support either release APK ABI.' }
    if (-not (Test-Path -LiteralPath $fixture -PathType Leaf)) { throw "Missing PDF fixture: $fixture" }
    Write-Host "[$script:TargetLabel] PREFLIGHT PDF fixture $fixture"
    Write-Host "[$script:TargetLabel] PREFLIGHT target ABI $abi"

    $base = if ($EvidenceDirectory) { $EvidenceDirectory } else { Join-Path $android 'build/release-smoke' }
    $script:OutputPath = (New-Item -ItemType Directory -Path (Join-Path $base $runId) -Force).FullName
    Build-Release $sdk
    $releasePath = Join-Path $android 'app/build/outputs/apk/release'
    $metadata = Get-Content -LiteralPath (Join-Path $releasePath 'output-metadata.json') -Raw | ConvertFrom-Json
    if ($metadata.variantName -ne 'release' -or $metadata.applicationId -ne $package) { throw 'Unexpected release APK metadata.' }
    $artifacts = @($metadata.elements | Where-Object {
        $_.filters | Where-Object { $_.filterType -eq 'ABI' -and $_.value -eq $abi }
    })
    if ($artifacts.Count -ne 1) { throw "Could not find a unique $abi release APK in the build metadata." }
    $apk = Join-Path $releasePath $artifacts[0].outputFile
    Write-Host "[$script:TargetLabel] INSTALL $apk"
    $installed = Invoke-Adb install -r $apk
    if ($installed -notmatch '\bSuccess\b') { throw "Unexpected install result: $installed" }
    Write-Host "[$script:TargetLabel] INSTALL $installed"

    [void](Invoke-Adb shell mkdir -p $remote)
    $script:RemoteReady = $true
    $dimensions = [regex]::Matches((Invoke-Adb shell wm size), '(\d+)x(\d+)')
    if ($dimensions.Count -eq 0) { throw 'The Android display size could not be determined.' }
    $script:Width = [int]$dimensions[$dimensions.Count - 1].Groups[1].Value
    $script:Height = [int]$dimensions[$dimensions.Count - 1].Groups[2].Value
    $start = Invoke-Adb shell am start -W -n "$package/.MainActivity"
    if ($start -notmatch '(?im)^Status:\s+ok') { throw "BASTION launch did not complete: $start" }
    Write-Host "[$script:TargetLabel] LAUNCH Status: ok"
    $destination = Wait-Node '^Ask$|^(?:Enter (?:your )?)?PIN$' -Attempts 12
    if ($destination.GetAttribute('text') -match 'PIN' -or $destination.GetAttribute('content-desc') -match 'PIN') {
        throw 'BASTION is locked. Open it normally before running the smoke test.'
    }
    Tap-Node $destination
    $preflight = Get-Preflight
    Write-Host "[$script:TargetLabel] PREFLIGHT ZIM=$($preflight.Zim), model=$($preflight.Model)"
    [void](Save-Screen 'preflight')

    Invoke-SmokeCheck 'ZIM' { Invoke-ZimCheck $preflight.Zim }
    Invoke-SmokeCheck 'PDF' { Invoke-PdfCheck }
    Invoke-SmokeCheck 'AI' { Invoke-AiCheck $preflight.Model }
} catch {
    Set-Check SETUP FAIL $_.Exception.Message
    if ($script:RemoteReady) { try { [void](Save-Screen 'setup-failed') } catch {} }
} finally {
    if ($script:RemoteReady) {
        try {
            $cleanup = @('shell', 'rm', '-f') + $script:RemoteFiles.ToArray()
            [void](Invoke-Adb @cleanup)
            [void](Invoke-Adb shell rmdir $remote)
            if ($script:DownloadsReady) { [void](Invoke-Adb shell rmdir $deviceDownloads) }
        } catch {
            Write-Host "[$script:TargetLabel] Scratch cleanup incomplete: $($_.Exception.Message)"
        }
    }
}

$verdict = if ($script:HadFailure) { 'FAIL' } else { 'PASS' }
$summary = ($script:Checks.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ', '
Write-Host "RELEASE SMOKE $verdict [$script:TargetLabel] $summary"
if ($script:HadFailure) { exit 1 }
exit 0
