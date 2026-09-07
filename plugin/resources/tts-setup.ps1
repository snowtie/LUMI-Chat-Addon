param(
    [Parameter(Mandatory = $true)][string]$LumiAppPath,
    [Parameter(Mandatory = $true)][string]$DataRoot,
    [Parameter(Mandatory = $true)][string]$RuntimeManifest
)

$ErrorActionPreference = "Stop"
if (Test-Path -LiteralPath variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $true
}

$WeightsUrl = "https://github.com/snowtie/LUMI-Chat-Addon/releases/download/v1.1.0/GPT_weights_v2.7z"
$WeightsSha256 = "4A0FF7071C3D0D4C56A48016D8BC66CA5C8C626D599C0E71300F0DE3AFA14E79"
$RunId = Get-Date -Format "yyyyMMdd-HHmmss-fff"
$LogRoot = Join-Path $DataRoot "logs"
$LogPath = Join-Path $LogRoot "tts-setup-$RunId.log"
$TranscriptStarted = $false
$ExitCode = 0

function Save-Download([string]$Url, [string]$Destination) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
    $partial = "$Destination.part"
    if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
    try {
        $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
        if ($curl) {
            & $curl.Source -L --fail --progress-bar -o $partial $Url
            if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
        }
        else {
            Invoke-WebRequest -Uri $Url -OutFile $partial -UseBasicParsing
        }
        Move-Item -LiteralPath $partial -Destination $Destination -Force
    }
    catch {
        if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
        throw
    }
}

function Assert-SafeArchive([string]$ArchivePath) {
    $entries = & tar.exe -tf $ArchivePath
    if ($LASTEXITCODE -ne 0) { throw "Cannot read archive: $ArchivePath" }
    foreach ($entry in $entries) {
        $normalized = $entry.Replace('\', '/')
        if ($normalized.StartsWith('/') -or $normalized -match '^[A-Za-z]:' -or
            $normalized.Split('/') -contains '..') {
            throw "Unsafe archive path: $entry"
        }
    }
}

function Expand-SafeArchive([string]$ArchivePath, [string]$Destination) {
    Assert-SafeArchive $ArchivePath
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    & tar.exe -xf $ArchivePath -C $Destination
    if ($LASTEXITCODE -ne 0) { throw "Archive extraction failed: $ArchivePath" }
}

function Find-Runtime([string]$Root) {
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) { return $null }
    $candidates = @($Root) + @(
        Get-ChildItem -LiteralPath $Root -Directory -ErrorAction SilentlyContinue |
            ForEach-Object FullName
    )
    foreach ($candidate in $candidates) {
        if ((Test-Path -LiteralPath (Join-Path $candidate "api_v2.py") -PathType Leaf) -and
            (Test-Path -LiteralPath (Join-Path $candidate "runtime\python.exe") -PathType Leaf)) {
            return $candidate
        }
    }
    return $null
}

function Get-ComputeCapability {
    $command = Get-Command nvidia-smi.exe -ErrorAction SilentlyContinue
    if (-not $command) { return $null }
    try {
        $values = @(& $command.Source --query-gpu=compute_cap --format=csv,noheader,nounits 2>$null)
        if ($LASTEXITCODE -ne 0) { return $null }
        $parsed = @()
        foreach ($value in $values) {
            $number = 0.0
            if ([double]::TryParse(
                ([string]$value).Trim(),
                [Globalization.NumberStyles]::Float,
                [Globalization.CultureInfo]::InvariantCulture,
                [ref]$number
            )) { $parsed += $number }
        }
        if ($parsed.Count -gt 0) { return ($parsed | Measure-Object -Maximum).Maximum }
    }
    catch { return $null }
    return $null
}

function Select-Runtime($Manifest, [Nullable[double]]$ComputeCapability) {
    $candidates = @($Manifest.runtimes | Where-Object {
        $minimumMatches = $null -eq $_.min_compute_capability -or
            ($null -ne $ComputeCapability -and [double]$ComputeCapability -ge [double]$_.min_compute_capability)
        $maximumMatches = $null -eq $_.max_compute_capability -or
            ($null -ne $ComputeCapability -and [double]$ComputeCapability -le [double]$_.max_compute_capability)
        $minimumMatches -and $maximumMatches
    } | Sort-Object -Property @{ Expression = { [int]$_.priority }; Descending = $true })
    if ($candidates.Count -gt 0) { return $candidates[0] }
    return @($Manifest.runtimes | Where-Object { $_.default })[0]
}

function Find-PreferredFile([string]$Root, [string]$PreferredName, [string]$Filter) {
    $files = @(Get-ChildItem -LiteralPath $Root -Recurse -File -Filter $Filter -ErrorAction SilentlyContinue)
    $preferred = $files | Where-Object Name -eq $PreferredName | Select-Object -First 1
    if ($preferred) { return $preferred.FullName }
    if ($files.Count -eq 1) { return $files[0].FullName }
    throw "Cannot select $Filter under $Root"
}

function Find-ReferenceVoice([string]$LumiApp) {
    $indexes = @(
        Get-ChildItem -LiteralPath (Join-Path $LumiApp "voice\Lumi") -Recurse -File -Filter "index.tsv" -ErrorAction SilentlyContinue
    )
    foreach ($index in $indexes) {
        $rows = @(Get-Content -LiteralPath $index.FullName -Encoding UTF8 | Where-Object { $_ -and -not $_.StartsWith('#') })
        $preferredRows = @($rows | Where-Object { $_.StartsWith("0001f2f71d2f937f`t") }) + $rows
        foreach ($row in $preferredRows) {
            $columns = $row.Split("`t", 5)
            if ($columns.Count -lt 5 -or -not $columns[4].Trim()) { continue }
            $audioPath = Join-Path $index.DirectoryName $columns[3]
            if (Test-Path -LiteralPath $audioPath -PathType Leaf) {
                return @{ Audio = $audioPath; Text = $columns[4].Trim() }
            }
        }
    }
    throw "LUMI Voice Pack reference audio was not found. Install the LUMI Voice Pack first."
}

function Set-Property([string]$Path, [string]$Key, [string]$Value) {
    $lines = if (Test-Path -LiteralPath $Path) { @(Get-Content -LiteralPath $Path -Encoding UTF8) } else { @() }
    $escaped = $Value.Replace('\', '\\').Replace(':', '\:').Replace('=', '\=')
    $found = $false
    for ($index = 0; $index -lt $lines.Count; $index++) {
        if ($lines[$index] -match ('^' + [regex]::Escape($Key) + '=')) {
            $lines[$index] = "$Key=$escaped"
            $found = $true
        }
    }
    if (-not $found) { $lines += "$Key=$escaped" }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    [IO.File]::WriteAllLines($Path, $lines, [Text.UTF8Encoding]::new($false))
}

try {
    New-Item -ItemType Directory -Force -Path $LogRoot | Out-Null
    Start-Transcript -LiteralPath $LogPath -Force | Out-Null
    $TranscriptStarted = $true
    Write-Host "LUMI Chat Addon GPT-SoVITS setup"
    Write-Host "Keep this window open until the completion message appears."

    if (-not (Test-Path -LiteralPath (Join-Path $LumiAppPath "Shimeji-ee.jar") -PathType Leaf)) {
        throw "Little LUMI app folder is invalid: $LumiAppPath"
    }
    $manifest = Get-Content -LiteralPath $RuntimeManifest -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.schema_version -ne 1 -or -not $manifest.runtimes) {
        throw "Runtime manifest is invalid: $RuntimeManifest"
    }
    $computeCapability = Get-ComputeCapability
    $selected = Select-Runtime $manifest $computeCapability
    Write-Host "Selected runtime: $($selected.label)"

    $runtimeRoot = Join-Path $DataRoot (Join-Path "gpt-sovits\runtimes" ([string]$selected.id))
    $runtime = Find-Runtime $runtimeRoot
    if (-not $runtime) {
        $archive = Join-Path $DataRoot (Join-Path "downloads" ([string]$selected.archive_name))
        if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
            Write-Host "Downloading the official GPT-SoVITS package. This is about $($selected.download_size_gb)GB."
            Save-Download ([string]$selected.url) $archive
        }
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash -ne ([string]$selected.sha256).ToUpperInvariant()) {
            throw "GPT-SoVITS SHA-256 mismatch: $archive"
        }
        Write-Host "Extracting GPT-SoVITS. Do not close this window."
        Expand-SafeArchive $archive $runtimeRoot
        $runtime = Find-Runtime $runtimeRoot
    }
    if (-not $runtime) { throw "GPT-SoVITS runtime was not found after extraction." }

    $selection = [ordered]@{
        schema_version = 1
        manifest_revision = [string]$manifest.revision
        runtime_id = [string]$selected.id
        runtime_root = [IO.Path]::GetFullPath($runtime)
        compute_capability = $computeCapability
    } | ConvertTo-Json
    [IO.File]::WriteAllText(
        (Join-Path $DataRoot "gpt-sovits-runtime-selection.json"),
        $selection,
        [Text.UTF8Encoding]::new($false)
    )

    $modelRoot = Join-Path $DataRoot "models\LUMI-v2"
    $gptWeight = Get-ChildItem -LiteralPath $modelRoot -Recurse -File -Filter "LUMI-e10.ckpt" -ErrorAction SilentlyContinue | Select-Object -First 1
    $sovitsWeight = Get-ChildItem -LiteralPath $modelRoot -Recurse -File -Filter "LUMI_e8_s880.pth" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $gptWeight -or -not $sovitsWeight) {
        $weightsArchive = Join-Path $DataRoot "downloads\GPT_weights_v2.7z"
        if (-not (Test-Path -LiteralPath $weightsArchive -PathType Leaf)) {
            Write-Host "Downloading the authorized LUMI voice weights. This is about 420MB."
            Save-Download $WeightsUrl $weightsArchive
        }
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath $weightsArchive).Hash -ne $WeightsSha256) {
            throw "LUMI voice weights SHA-256 mismatch: $weightsArchive"
        }
        Write-Host "Installing LUMI voice weights."
        Expand-SafeArchive $weightsArchive $modelRoot
    }
    $gptWeight = Find-PreferredFile $modelRoot "LUMI-e10.ckpt" "*.ckpt"
    $sovitsWeight = Find-PreferredFile $modelRoot "LUMI_e8_s880.pth" "*.pth"
    $reference = Find-ReferenceVoice $LumiAppPath

    $aiSettings = Join-Path $LumiAppPath "plugindata\lumi.ai\ai.properties"
    Set-Property $aiSettings "tts.enabled" "true"
    Set-Property $aiSettings "tts.provider" "gpt_sovits"
    Set-Property $aiSettings "tts.gpt_sovits.base" "http://127.0.0.1:9880"
    Set-Property $aiSettings "tts.gpt_sovits.runtime" ([IO.Path]::GetFullPath($runtime))
    Set-Property $aiSettings "tts.gpt_sovits.gpt_weights" $gptWeight
    Set-Property $aiSettings "tts.gpt_sovits.sovits_weights" $sovitsWeight
    Set-Property $aiSettings "tts.gpt_sovits.reference_audio" $reference.Audio
    Set-Property $aiSettings "tts.gpt_sovits.reference_text" $reference.Text
    Set-Property $aiSettings "tts.gpt_sovits.text_language" "ko"
    Set-Property $aiSettings "tts.gpt_sovits.prompt_language" "ko"
    Set-Property $aiSettings "tts.gpt_sovits.power_mode" "balanced"
    Set-Property $aiSettings "tts.gpt_sovits.device_mode" "auto"
    Set-Property $aiSettings "tts.gpt_sovits.speed" "1.0"

    Write-Host ""
    Write-Host "GPT-SoVITS setup complete. Restart Little LUMI." -ForegroundColor Green
    Write-Host "Log: $LogPath"
}
catch {
    Write-Host ""
    Write-Host "GPT-SoVITS setup failed: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Log: $LogPath"
    $ExitCode = 1
}
finally {
    if ($TranscriptStarted) { Stop-Transcript | Out-Null }
}

Read-Host "Press Enter to close"
exit $ExitCode
