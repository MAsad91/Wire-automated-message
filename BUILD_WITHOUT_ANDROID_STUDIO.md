# Building APK Without Android Studio

Since you don't have Android Studio, here are **3 FREE solutions** to build your APK:

---

## 🎯 **Option 1: GitHub Actions (EASIEST - Recommended)**

**Free, automatic, no setup needed!**

### Steps:

1. **Create GitHub Account** (if you don't have one - it's free)
   - Go to https://github.com
   - Sign up (free)

2. **Upload Your Project to GitHub**
   ```powershell
   # In your project folder
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/wire-auto-messenger.git
   git push -u origin main
   ```

3. **Create GitHub Actions Workflow**
   - In GitHub, go to your repository
   - Click **Actions** tab
   - Click **New workflow**
   - Click **Set up a workflow yourself**
   - Name it: `build-apk.yml`
   - Paste this content:

   ```yaml
   name: Build APK

   on:
     workflow_dispatch:  # Manual trigger
     push:
       branches: [ main ]

   jobs:
     build:
       runs-on: ubuntu-latest
       
       steps:
       - uses: actions/checkout@v3
       
       - name: Set up JDK 11
         uses: actions/setup-java@v3
         with:
           java-version: '11'
           distribution: 'temurin'
       
       - name: Grant execute permission for gradlew
         run: chmod +x gradlew
       
       - name: Build APK
         run: ./gradlew assembleRelease
       
       - name: Upload APK
         uses: actions/upload-artifact@v3
         with:
           name: app-release
           path: app/build/outputs/apk/release/app-release.apk
   ```

4. **Build the APK**
   - Click **Actions** tab
   - Click **Build APK** workflow
   - Click **Run workflow** button
   - Wait 5-10 minutes
   - Download the APK from **Artifacts**

**✅ Done! No Android Studio needed!**

---

## 🛠️ **Option 2: Command Line Build (Requires Android SDK)**

### Prerequisites:

1. **Install Java JDK 11+**
   - Download: https://adoptium.net/
   - Install and set JAVA_HOME

2. **Install Android SDK Command Line Tools**
   - Download: https://developer.android.com/studio#command-tools
   - Extract to: `C:\Android\sdk`
   - Set environment variables:
     ```
     ANDROID_HOME=C:\Android\sdk
     PATH=%PATH%;%ANDROID_HOME%\tools;%ANDROID_HOME%\platform-tools
     ```

3. **Install Required SDK Components**
   ```powershell
   # Accept licenses
   sdkmanager --licenses
   
   # Install required components
   sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
   ```

4. **Create local.properties**
   Create file `local.properties` in project root:
   ```
   sdk.dir=C:\\Android\\sdk
   ```

5. **Download Gradle Wrapper JAR**
   ```powershell
   # Download gradle-wrapper.jar
   Invoke-WebRequest -Uri "https://raw.githubusercontent.com/gradle/gradle/master/gradle/wrapper/gradle-wrapper.jar" -OutFile "gradle\wrapper\gradle-wrapper.jar"
   ```

6. **Build APK**
   ```powershell
   .\gradlew.bat assembleRelease
   ```

**APK Location**: `app\build\outputs\apk\release\app-release.apk`

---

## 🌐 **Option 3: Online Build Services (FREE)**

### **Appetize.io / Codemagic / Bitrise**

These services can build Android APKs from GitHub:

1. **Codemagic** (Free tier available)
   - Sign up: https://codemagic.io
   - Connect GitHub repository
   - Auto-builds on push
   - Downloads APK

2. **GitHub Actions** (Best option - see Option 1)

---

## 🚀 **Quick Start Script for Windows**

I'll create a PowerShell script to automate Option 2 setup:

```powershell
# Save as: setup-build-env.ps1

Write-Host "Setting up Android Build Environment..." -ForegroundColor Green

# Check Java
Write-Host "Checking Java..." -ForegroundColor Yellow
if (Get-Command java -ErrorAction SilentlyContinue) {
    Write-Host "✓ Java found" -ForegroundColor Green
} else {
    Write-Host "✗ Java not found. Please install JDK 11+" -ForegroundColor Red
    Write-Host "Download from: https://adoptium.net/" -ForegroundColor Yellow
    exit
}

# Download Gradle Wrapper JAR
Write-Host "Downloading Gradle Wrapper..." -ForegroundColor Yellow
$jarPath = "gradle\wrapper\gradle-wrapper.jar"
if (-not (Test-Path $jarPath)) {
    New-Item -ItemType Directory -Force -Path "gradle\wrapper" | Out-Null
    Invoke-WebRequest -Uri "https://raw.githubusercontent.com/gradle/gradle/v8.2.0/gradle/wrapper/gradle-wrapper.jar" -OutFile $jarPath
    Write-Host "✓ Gradle wrapper downloaded" -ForegroundColor Green
}

Write-Host "`nSetup complete! Next steps:" -ForegroundColor Green
Write-Host "1. Install Android SDK (see Option 2 instructions)" -ForegroundColor Yellow
Write-Host "2. Create local.properties with SDK path" -ForegroundColor Yellow
Write-Host "3. Run: .\gradlew.bat assembleRelease" -ForegroundColor Yellow
```

---

## 📋 **Recommended: Use GitHub Actions**

**Why GitHub Actions is best:**
- ✅ Completely FREE
- ✅ No Android SDK installation needed
- ✅ No local setup required
- ✅ Automatic builds
- ✅ Works on any computer
- ✅ Easy to use

**Time to setup**: 10 minutes  
**Time to build**: 5-10 minutes per build

---

## 🆘 **Need Help?**

If you need help with any option, let me know which one you prefer and I'll guide you through it step-by-step!

**My Recommendation**: Start with **Option 1 (GitHub Actions)** - it's the easiest and requires zero local setup.

