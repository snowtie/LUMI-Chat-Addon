param(
    [string]$SdkJar,
    [string]$OutputRoot
)

$ErrorActionPreference = "Stop"
if (Test-Path -LiteralPath variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $true
}

$PluginRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $PluginRoot
$OutputRoot = if ($OutputRoot) { [IO.Path]::GetFullPath($OutputRoot) } else {
    Join-Path $ProjectRoot "release\workshop-content"
}
$SdkCandidates = @(
    $SdkJar,
    $env:LUMI_PLUGIN_SDK,
    "D:\Steam\steamapps\common\Little LUMI\app\Shimeji-ee.jar",
    "C:\Program Files (x86)\Steam\steamapps\common\Little LUMI\app\Shimeji-ee.jar"
) | Where-Object { $_ }
$ResolvedSdk = $SdkCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if (-not $ResolvedSdk) {
    throw "Little LUMI Plugin SDK jar was not found. Pass -SdkJar or set LUMI_PLUGIN_SDK."
}

$Jdk = Get-ChildItem -LiteralPath "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-25*-hotspot" -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    Select-Object -First 1
if (-not $Jdk) { throw "JDK 25 is required." }
$Javac = Join-Path $Jdk.FullName "bin\javac.exe"
$Jar = Join-Path $Jdk.FullName "bin\jar.exe"

$BuildRoot = Join-Path $PluginRoot "build"
$Classes = Join-Path $BuildRoot "classes"
if (Test-Path -LiteralPath $BuildRoot) {
    Remove-Item -LiteralPath $BuildRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $Classes | Out-Null
$Sources = @(Get-ChildItem -LiteralPath (Join-Path $PluginRoot "src") -Recurse -File -Filter "*.java" | ForEach-Object FullName)
if ($Sources.Count -eq 0) { throw "Plugin Java sources were not found." }
& $Javac -encoding UTF-8 -source 25 -target 25 -cp $ResolvedSdk -d $Classes @Sources
if ($LASTEXITCODE -ne 0) { throw "Plugin compilation failed." }

$PluginsRoot = Join-Path $OutputRoot "plugins"
New-Item -ItemType Directory -Force -Path $PluginsRoot | Out-Null
$PluginJar = Join-Path $PluginsRoot "lumi.chat.addon.jar"
& $Jar --create --file $PluginJar -C $Classes . -C (Join-Path $PluginRoot "resources") .
if ($LASTEXITCODE -ne 0) { throw "Plugin jar creation failed." }
Write-Host "Plugin build complete: $PluginJar"
