#Requires -Version 3.0
param(
    [string]$Version = "8.10",
    [string]$Proxy = "http://127.0.0.1:10808",
    [switch]$NoProxy
)

<#
    Download and unpack Gradle into <project>\gradle-dist\gradle-<Version>.
    Called by build.bat; safe to run directly as well.

        powershell -NoProfile -ExecutionPolicy Bypass -File tools\fetch-gradle.ps1 -Version 8.10

    Proxy: defaults to http://127.0.0.1:10808, same as gradle.properties.
    Pass -NoProxy to download directly (used by CI, which has open internet).
    Passing -Proxy "" also works, but a bare -NoProxy avoids the empty-string
    quoting pitfall when invoked from a batch file.

    Exits 0 if Gradle is already present or was unpacked successfully.
    NOTE: $home is a read-only PowerShell automatic variable - do not use it
    as a local variable name here.
#>

if ($NoProxy) { $Proxy = "" }

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$rootDir     = Split-Path -Parent $PSScriptRoot
$distDir     = Join-Path $rootDir "gradle-dist"
$gradleHome  = Join-Path $distDir "gradle-$Version"
$gradleBat   = Join-Path $gradleHome "bin\gradle.bat"
$zipFile     = Join-Path $distDir "gradle-$Version-bin.zip"

if (Test-Path $gradleBat) {
    Write-Host "[gradle] already installed: $gradleBat"
    exit 0
}

if (-not (Test-Path $distDir)) { New-Item -ItemType Directory -Path $distDir -Force | Out-Null }

$urls = @(
    "https://mirrors.cloud.tencent.com/gradle/gradle-$Version-bin.zip",
    "https://mirrors.aliyun.com/gradle/gradle-$Version-bin.zip",
    "https://mirrors.huaweicloud.com/gradle/gradle-$Version-bin.zip",
    "https://services.gradle.org/distributions/gradle-$Version-bin.zip",
    "https://downloads.gradle.org/distributions/gradle-$Version-bin.zip"
)

if ($Proxy) {
    Write-Host "[gradle] using proxy: $Proxy"
} else {
    Write-Host "[gradle] downloading directly (no proxy)"
}

Write-Host "[gradle] downloading Gradle $Version (about 130 MB, one-time) ..."
$downloaded = $false
foreach ($url in $urls) {
    try {
        Write-Host "          try $url"
        if ($Proxy) {
            Invoke-WebRequest -Uri $url -OutFile $zipFile -UseBasicParsing -TimeoutSec 900 -Proxy $Proxy
        } else {
            Invoke-WebRequest -Uri $url -OutFile $zipFile -UseBasicParsing -TimeoutSec 900
        }
        if (Test-Path $zipFile) {
            $size = (Get-Item $zipFile).Length
            if ($size -gt 10MB) {
                Write-Host "          downloaded $([math]::Round($size / 1MB)) MB"
                $downloaded = $true
                break
            }
        }
        Remove-Item $zipFile -Force -ErrorAction SilentlyContinue
    } catch {
        Write-Host "          failed: $($_.Exception.Message)"
        Remove-Item $zipFile -Force -ErrorAction SilentlyContinue
    }
}

if (-not $downloaded) {
    Write-Host ""
    Write-Host "[gradle] ERROR: could not download Gradle $Version from any mirror." -ForegroundColor Red
    Write-Host "         Check that your proxy is listening on 10808, or download manually" -ForegroundColor Red
    Write-Host "         and unzip into: $distDir" -ForegroundColor Red
    Write-Host "         so that this file exists: $gradleBat" -ForegroundColor Red
    Write-Host "         https://services.gradle.org/distributions/" -ForegroundColor Red
    exit 1
}

Write-Host "[gradle] unzipping ..."
if (Test-Path $gradleHome) { Remove-Item $gradleHome -Recurse -Force -ErrorAction SilentlyContinue }

$unzipped = $false
try {
    Expand-Archive -Path $zipFile -DestinationPath $distDir -Force
    $unzipped = Test-Path $gradleBat
} catch {
    Write-Host "          Expand-Archive failed: $($_.Exception.Message)"
}

if (-not $unzipped) {
    # Fallback for older PowerShell / long path issues
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [System.IO.Compression.ZipFile]::ExtractToDirectory($zipFile, $distDir)
        $unzipped = Test-Path $gradleBat
    } catch {
        Write-Host "[gradle] ERROR: unzip failed: $($_.Exception.Message)" -ForegroundColor Red
    }
}

Remove-Item $zipFile -Force -ErrorAction SilentlyContinue

if (-not (Test-Path $gradleBat)) {
    Write-Host "[gradle] ERROR: unzip finished but $gradleBat is missing." -ForegroundColor Red
    exit 1
}

Write-Host "[gradle] ready: $gradleBat"
exit 0
