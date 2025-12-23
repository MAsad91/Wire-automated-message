# Android Build Environment Setup Script
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Android Build Environment Setup" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Check Java
Write-Host "Step 1: Checking Java installation..." -ForegroundColor Yellow
$javaInstalled = $false
$javaCheck = Get-Command java -ErrorAction SilentlyContinue
if ($javaCheck) {
    $javaInstalled = $true
    Write-Host "✓ Java found" -ForegroundColor Green
} else {
    Write-Host "✗ Java not found!" -ForegroundColor Red
    Write-Host "  Please install JDK 11 or higher" -ForegroundColor Yellow
    Write-Host "  Download from: https://adoptium.net/" -ForegroundColor Yellow
}

# Check JAVA_HOME
Write-Host ""
Write-Host "Step 2: Checking JAVA_HOME..." -ForegroundColor Yellow
if ($env:JAVA_HOME) {
    Write-Host "✓ JAVA_HOME is set: $env:JAVA_HOME" -ForegroundColor Green
} else {
    Write-Host "⚠ JAVA_HOME is not set (optional)" -ForegroundColor Yellow
}

# Setup Gradle Wrapper
Write-Host ""
Write-Host "Step 3: Setting up Gradle Wrapper..." -ForegroundColor Yellow
$jarPath = "gradle\wrapper\gradle-wrapper.jar"
$jarDir = "gradle\wrapper"

if (-not (Test-Path $jarDir)) {
    New-Item -ItemType Directory -Force -Path $jarDir | Out-Null
}

if (-not (Test-Path $jarPath)) {
    Write-Host "  Downloading gradle-wrapper.jar..." -ForegroundColor Gray
    $gradleVersion = "8.2"
    $jarUrl = "https://github.com/gradle/gradle/raw/v$gradleVersion/gradle/wrapper/gradle-wrapper.jar"
    Invoke-WebRequest -Uri $jarUrl -OutFile $jarPath -ErrorAction SilentlyContinue
    if (Test-Path $jarPath) {
        Write-Host "✓ Gradle wrapper downloaded" -ForegroundColor Green
    } else {
        Write-Host "✗ Download failed - download manually" -ForegroundColor Red
    }
} else {
    Write-Host "✓ Gradle wrapper already exists" -ForegroundColor Green
}

# Check local.properties
Write-Host ""
Write-Host "Step 4: Checking Android SDK..." -ForegroundColor Yellow
if (Test-Path "local.properties") {
    Write-Host "✓ local.properties found" -ForegroundColor Green
} else {
    Write-Host "⚠ local.properties not found" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "RECOMMENDED: Use GitHub Actions (no SDK needed!)" -ForegroundColor Green
    Write-Host "  See: BUILD_WITHOUT_ANDROID_STUDIO.md" -ForegroundColor Cyan
}

# Summary
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Summary" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

if ($javaInstalled) {
    Write-Host "✓ Java: Installed" -ForegroundColor Green
} else {
    Write-Host "✗ Java: Not installed" -ForegroundColor Red
}

if (Test-Path $jarPath) {
    Write-Host "✓ Gradle Wrapper: Ready" -ForegroundColor Green
} else {
    Write-Host "✗ Gradle Wrapper: Missing" -ForegroundColor Red
}

if (Test-Path "local.properties") {
    Write-Host "✓ Android SDK: Configured" -ForegroundColor Green
    Write-Host ""
    Write-Host "Ready to build! Run:" -ForegroundColor Green
    Write-Host "  .\gradlew.bat assembleRelease" -ForegroundColor Cyan
} else {
    Write-Host "⚠ Android SDK: Not configured" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Next: Use GitHub Actions (see BUILD_WITHOUT_ANDROID_STUDIO.md)" -ForegroundColor Yellow
}

Write-Host ""
