param(
    [string]$SdkJar,
    [string]$LumiChatJar
)

$ErrorActionPreference = "Stop"
if (Test-Path -LiteralPath variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $true
}

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$TauriRoot = Join-Path $ProjectRoot "src-tauri"
$ReleaseRoot = Join-Path $ProjectRoot "release"
$Version = "1.1.2"
$HelperAsset = "lumi-chat-addon-helper-v$Version-windows-x64.exe"
$WorkshopArchive = "LUMI-Chat-Addon-v$Version-workshop.zip"
$UninstallerArchive = "LUMI-to-GPT-Legacy-Uninstaller-v$Version.zip"
$VoiceWeightsSha256 = "4a0ff7071c3d0d4c56a48016d8bc66ca5c8c626d599c0e71300f0de3afa14e79"

$resolvedProject = [IO.Path]::GetFullPath($ProjectRoot).TrimEnd('\') + '\'
$resolvedRelease = [IO.Path]::GetFullPath($ReleaseRoot)
if (-not $resolvedRelease.StartsWith($resolvedProject, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Release directory is outside the project: $resolvedRelease"
}

Push-Location $TauriRoot
try {
    cargo tauri build --no-bundle
    if ($LASTEXITCODE -ne 0) { throw "Rust/Tauri build failed." }
}
finally { Pop-Location }

if (Test-Path -LiteralPath $ReleaseRoot) {
    Remove-Item -LiteralPath $ReleaseRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $ReleaseRoot | Out-Null

$builtHelper = Join-Path $TauriRoot "target\release\lumi-chat-addon-helper.exe"
if (-not (Test-Path -LiteralPath $builtHelper -PathType Leaf)) {
    throw "Built helper executable was not found: $builtHelper"
}
$helperPath = Join-Path $ReleaseRoot $HelperAsset
Copy-Item -LiteralPath $builtHelper -Destination $helperPath

& (Join-Path $ProjectRoot "plugin\build.ps1") `
    -SdkJar $SdkJar `
    -LumiChatJar $LumiChatJar `
    -OutputRoot (Join-Path $ReleaseRoot "workshop-content")
if ($LASTEXITCODE -ne 0) { throw "Plugin build failed." }

$workshopRoot = Join-Path $ReleaseRoot "workshop-content"
Copy-Item -LiteralPath (Join-Path $ProjectRoot "workshop\README.txt") -Destination $workshopRoot
Copy-Item -LiteralPath (Join-Path $ProjectRoot "LICENSE") -Destination $workshopRoot
Copy-Item -LiteralPath (Join-Path $ProjectRoot "NOTICE.txt") -Destination $workshopRoot
Copy-Item -LiteralPath (Join-Path $ProjectRoot "VOICE_MODEL_NOTICE.txt") -Destination $workshopRoot
$pluginJar = Join-Path $workshopRoot "plugins\lumi.chat.addon.jar"
if (-not (Test-Path -LiteralPath $pluginJar -PathType Leaf)) { throw "Plugin JAR was not built." }

$previewPath = Join-Path $ReleaseRoot "workshop-preview.png"
$workshopPreviewPath = Join-Path $workshopRoot "workshop-preview.png"
$logoPath = Join-Path $ProjectRoot "ui\lumi-chat-addon.png"
Add-Type -AssemblyName System.Drawing
$sourceImage = [Drawing.Image]::FromFile($logoPath)
$bitmap = [Drawing.Bitmap]::new(512, 512)
$graphics = [Drawing.Graphics]::FromImage($bitmap)
try {
    $graphics.CompositingQuality = [Drawing.Drawing2D.CompositingQuality]::HighQuality
    $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.DrawImage($sourceImage, 0, 0, 512, 512)
    $bitmap.Save($previewPath, [Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Save($workshopPreviewPath, [Drawing.Imaging.ImageFormat]::Png)
}
finally {
    $graphics.Dispose()
    $bitmap.Dispose()
    $sourceImage.Dispose()
}

$workshopZip = Join-Path $ReleaseRoot $WorkshopArchive
Compress-Archive -Path (Join-Path $workshopRoot "*") -DestinationPath $workshopZip -CompressionLevel Optimal

$uninstallerRoot = Join-Path $ReleaseRoot "legacy-uninstaller"
New-Item -ItemType Directory -Force -Path $uninstallerRoot | Out-Null
foreach ($name in @("uninstall.ps1", "UNINSTALL.cmd", "README.md", "LICENSE", "NOTICE.txt")) {
    Copy-Item -LiteralPath (Join-Path $ProjectRoot $name) -Destination $uninstallerRoot
}
$uninstallerZip = Join-Path $ReleaseRoot $UninstallerArchive
Compress-Archive -Path (Join-Path $uninstallerRoot "*") -DestinationPath $uninstallerZip -CompressionLevel Optimal

Copy-Item -LiteralPath (Join-Path $ProjectRoot "workshop\description.txt") -Destination (Join-Path $ReleaseRoot "workshop-description.txt")
Copy-Item -LiteralPath (Join-Path $ProjectRoot "README.md") -Destination $ReleaseRoot
Copy-Item -LiteralPath (Join-Path $ProjectRoot "RELEASE_NOTES.md") -Destination $ReleaseRoot
Copy-Item -LiteralPath (Join-Path $ProjectRoot "LICENSE") -Destination $ReleaseRoot
Copy-Item -LiteralPath (Join-Path $ProjectRoot "NOTICE.txt") -Destination $ReleaseRoot
Copy-Item -LiteralPath (Join-Path $ProjectRoot "VOICE_MODEL_NOTICE.txt") -Destination $ReleaseRoot

$checksumTargets = @($helperPath, $pluginJar, $workshopZip, $uninstallerZip)
$checksumLines = foreach ($target in $checksumTargets) {
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash.ToLowerInvariant()
    "$hash  $([IO.Path]::GetFileName($target))"
}
$checksumLines += "$VoiceWeightsSha256  GPT_weights_v2.7z"
[IO.File]::WriteAllLines(
    (Join-Path $ReleaseRoot "SHA256SUMS.txt"),
    $checksumLines,
    [Text.UTF8Encoding]::new($false)
)

Write-Host "Build complete: $ReleaseRoot"
Write-Host "Workshop package: $WorkshopArchive"
Write-Host "Legacy uninstaller: $UninstallerArchive"
