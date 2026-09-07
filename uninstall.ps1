param(
    [string]$LumiAppPath,
    [string]$DesktopPath = [Environment]::GetFolderPath("Desktop"),
    [switch]$SkipProcessCheck,
    [switch]$KeepLegacyData
)

$ErrorActionPreference = "Stop"
if (Test-Path -LiteralPath variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $true
}

$LegacyRoot = Join-Path $env:LOCALAPPDATA "LumiToGPT"
$AddonRoot = Join-Path $env:LOCALAPPDATA "LumiChatAddon"
$RunId = Get-Date -Format "yyyyMMdd-HHmmss-fff"
$LogRoot = Join-Path $AddonRoot "logs"
$LogPath = Join-Path $LogRoot "legacy-uninstall-$RunId.log"
$TranscriptStarted = $false
$ExitCode = 0

function Resolve-LumiApp([string]$Candidate) {
    if ([String]::IsNullOrWhiteSpace($Candidate)) { return $null }
    try {
        $full = [IO.Path]::GetFullPath($Candidate.Trim().Trim('"'))
        foreach ($path in @($full, [IO.Path]::Combine($full, "app"))) {
            if ([IO.File]::Exists([IO.Path]::Combine($path, "Shimeji-ee.jar"))) { return $path }
        }
    }
    catch { return $null }
    return $null
}

function Find-LumiApp {
    foreach ($candidate in @($LumiAppPath, $env:LUMI_APP_DIR)) {
        $resolved = Resolve-LumiApp $candidate
        if ($resolved) { return $resolved }
    }
    $settingsPath = Join-Path $LegacyRoot "settings.json"
    if (Test-Path -LiteralPath $settingsPath -PathType Leaf) {
        try {
            $settings = Get-Content -LiteralPath $settingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
            $resolved = Resolve-LumiApp ([string]$settings.lumi_app_dir)
            if ($resolved) { return $resolved }
        }
        catch { }
    }
    $steamRoots = [Collections.Generic.List[string]]::new()
    foreach ($registryPath in @(
        "HKCU:\Software\Valve\Steam",
        "HKLM:\SOFTWARE\WOW6432Node\Valve\Steam",
        "HKLM:\SOFTWARE\Valve\Steam"
    )) {
        $steam = Get-ItemProperty -LiteralPath $registryPath -ErrorAction SilentlyContinue
        foreach ($name in @("SteamPath", "InstallPath")) {
            if ($steam -and $steam.$name) { $steamRoots.Add([string]$steam.$name) | Out-Null }
        }
    }
    foreach ($root in @($steamRoots)) {
        $vdf = [IO.Path]::Combine($root, "steamapps", "libraryfolders.vdf")
        if (-not [IO.File]::Exists($vdf)) { continue }
        foreach ($match in [regex]::Matches([IO.File]::ReadAllText($vdf), '"path"\s+"(?<path>[^"]+)"')) {
            $steamRoots.Add($match.Groups["path"].Value.Replace("\\", "\")) | Out-Null
        }
    }
    foreach ($root in @($steamRoots | Sort-Object -Unique)) {
        $resolved = Resolve-LumiApp ([IO.Path]::Combine($root, "steamapps", "common", "Little LUMI", "app"))
        if ($resolved) { return $resolved }
    }
    return $null
}

function Get-PatchMarker([string]$JarPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $archive.GetEntry("META-INF/lumi-to-gpt-patch.properties")
        if (-not $entry) { return $null }
        $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8)
        try { return $reader.ReadToEnd() }
        finally { $reader.Dispose() }
    }
    finally { $archive.Dispose() }
}

function Get-Sha256([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace("-", "") }
    finally {
        $algorithm.Dispose()
        $stream.Dispose()
    }
}

function Stop-LegacyProcesses {
    $legacyPrefix = [IO.Path]::GetFullPath($LegacyRoot).TrimEnd('\') + '\'
    $processes = @(
        Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
            Where-Object {
                $_.ExecutablePath -and
                [IO.Path]::GetFullPath($_.ExecutablePath).StartsWith(
                    $legacyPrefix,
                    [StringComparison]::OrdinalIgnoreCase
                ) -and
                ($_.Name -eq "lumi-to-gpt.exe" -or $_.Name -eq "python.exe")
            }
    )
    foreach ($process in $processes) {
        Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        $remaining = @($processes | Where-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue })
        if ($remaining.Count -gt 0) { Start-Sleep -Milliseconds 100 }
    } while ($remaining.Count -gt 0 -and [DateTime]::UtcNow -lt $deadline)
    if ($remaining.Count -gt 0) { throw "A legacy LUMI to GPT process could not be stopped." }
}

function Move-MergedDirectory([string]$Source, [string]$Destination) {
    if (-not (Test-Path -LiteralPath $Source -PathType Container)) { return }
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    foreach ($item in @(Get-ChildItem -LiteralPath $Source -Force)) {
        $target = Join-Path $Destination $item.Name
        if ($item.PSIsContainer) {
            Move-MergedDirectory $item.FullName $target
            continue
        }
        if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
            Move-Item -LiteralPath $item.FullName -Destination $target
            continue
        }
        if ((Get-Sha256 $item.FullName) -eq (Get-Sha256 $target)) {
            Remove-Item -LiteralPath $item.FullName -Force
            continue
        }
        $suffix = 1
        do {
            $conflict = "$target.legacy-$suffix"
            $suffix++
        } while (Test-Path -LiteralPath $conflict)
        Move-Item -LiteralPath $item.FullName -Destination $conflict
    }
    if (@(Get-ChildItem -LiteralPath $Source -Force).Count -eq 0) {
        Remove-Item -LiteralPath $Source -Force
    }
}

function Restore-LumiJar([string]$AppPath) {
    if (-not $AppPath) {
        Write-Host "Little LUMI was not found automatically."
        $manual = Read-Host "Paste the Little LUMI or app folder path (leave blank if its JAR was never patched)"
        if ($manual) { $AppPath = Resolve-LumiApp $manual }
    }
    if (-not $AppPath) {
        Write-Host "Little LUMI JAR restoration was skipped."
        return
    }
    if (-not $SkipProcessCheck -and (Get-Process -Name "LittleLumiModel" -ErrorAction SilentlyContinue)) {
        throw "Close Little LUMI completely before uninstalling."
    }
    $jar = Join-Path $AppPath "Shimeji-ee.jar"
    $backup = "$jar.lumi-to-gpt.bak"
    $marker = Get-PatchMarker $jar
    if (-not $marker) {
        Write-Host "Little LUMI JAR is already clean."
        if (Test-Path -LiteralPath $backup -PathType Leaf) {
            Remove-Item -LiteralPath $backup -Force
            Write-Host "Removed the obsolete legacy JAR backup."
        }
        return
    }
    if (-not (Test-Path -LiteralPath $backup -PathType Leaf)) {
        throw "The patched JAR was found, but its original backup is missing. Use Steam Verify Integrity instead."
    }
    $match = [regex]::Match($marker, '(?im)^baseJarSha256=(?<hash>[0-9a-f]{64})\s*$')
    if (-not $match.Success) { throw "The patch marker does not contain a valid original JAR hash." }
    $expected = $match.Groups["hash"].Value
    $actual = Get-Sha256 $backup
    if (-not [String]::Equals($expected, $actual, [StringComparison]::OrdinalIgnoreCase)) {
        throw "The original JAR backup hash does not match. Use Steam Verify Integrity instead."
    }
    if (Get-PatchMarker $backup) { throw "The JAR backup is also patched. Use Steam Verify Integrity instead." }
    $temporary = "$jar.restore.part"
    $rollback = "$jar.uninstall-rollback"
    if (Test-Path -LiteralPath $rollback) { Remove-Item -LiteralPath $rollback -Force }
    Copy-Item -LiteralPath $backup -Destination $temporary -Force
    [IO.File]::Replace($temporary, $jar, $rollback, $true)
    if (Get-PatchMarker $jar) { throw "JAR restoration verification failed." }
    Remove-Item -LiteralPath $rollback -Force
    Remove-Item -LiteralPath $backup -Force
    Write-Host "Restored the original Little LUMI JAR."
}

function Move-LegacyData {
    if (-not (Test-Path -LiteralPath $LegacyRoot -PathType Container)) { return }
    New-Item -ItemType Directory -Force -Path $AddonRoot | Out-Null
    foreach ($name in @("gpt-sovits", "models", "downloads")) {
        $source = Join-Path $LegacyRoot $name
        $target = Join-Path $AddonRoot $name
        Move-MergedDirectory $source $target
    }
    $selection = Join-Path $LegacyRoot "gpt-sovits-runtime-selection.json"
    $targetSelection = Join-Path $AddonRoot "gpt-sovits-runtime-selection.json"
    if ((Test-Path -LiteralPath $selection -PathType Leaf) -and -not (Test-Path -LiteralPath $targetSelection)) {
        Move-Item -LiteralPath $selection -Destination $targetSelection
    }
    elseif (Test-Path -LiteralPath $selection -PathType Leaf) {
        if ((Get-Sha256 $selection) -eq (Get-Sha256 $targetSelection)) {
            Remove-Item -LiteralPath $selection -Force
        }
        else {
            Move-Item -LiteralPath $selection -Destination (Join-Path $AddonRoot "gpt-sovits-runtime-selection.legacy.json") -Force
        }
    }
    $codex = Join-Path $LegacyRoot "app\codex-app-server.exe"
    $runtime = Join-Path $AddonRoot "runtime"
    if ((Test-Path -LiteralPath $codex -PathType Leaf) -and -not (Test-Path -LiteralPath (Join-Path $runtime "codex-app-server.exe"))) {
        New-Item -ItemType Directory -Force -Path $runtime | Out-Null
        Copy-Item -LiteralPath $codex -Destination (Join-Path $runtime "codex-app-server.exe")
    }
    $settings = Join-Path $LegacyRoot "settings.json"
    $targetSettings = Join-Path $AddonRoot "settings.json"
    if ((Test-Path -LiteralPath $settings -PathType Leaf) -and -not (Test-Path -LiteralPath $targetSettings)) {
        $text = [IO.File]::ReadAllText($settings).Replace($LegacyRoot.Replace('\', '\\'), $AddonRoot.Replace('\', '\\'))
        [IO.File]::WriteAllText($targetSettings, $text, [Text.UTF8Encoding]::new($false))
    }
}

try {
    New-Item -ItemType Directory -Force -Path $LogRoot | Out-Null
    Start-Transcript -LiteralPath $LogPath -Force | Out-Null
    $TranscriptStarted = $true
    Write-Host "LUMI to GPT legacy uninstaller"
    Stop-LegacyProcesses
    Restore-LumiJar (Find-LumiApp)
    if (-not $KeepLegacyData) {
        Move-LegacyData
        if (Test-Path -LiteralPath $LegacyRoot -PathType Container) {
            Remove-Item -LiteralPath $LegacyRoot -Recurse -Force
        }
        $shortcut = Join-Path $DesktopPath "LUMI to GPT.lnk"
        if (Test-Path -LiteralPath $shortcut -PathType Leaf) { Remove-Item -LiteralPath $shortcut -Force }
    }
    Write-Host "Legacy LUMI to GPT was removed successfully." -ForegroundColor Green
    Write-Host "Preserved runtime data: $AddonRoot"
}
catch {
    $ExitCode = 1
    Write-Host "Uninstall failed: $($_.Exception.Message)" -ForegroundColor Red
}
finally {
    if ($TranscriptStarted) { Stop-Transcript | Out-Null }
}

Write-Host "Log: $LogPath"
Read-Host "Press Enter to close"
exit $ExitCode
