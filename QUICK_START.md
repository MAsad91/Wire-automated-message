# 🚀 Quick Start - Build APK Without Android Studio

## Easiest Method: GitHub Actions (5 minutes)

### Step 1: Create GitHub Account
- Go to https://github.com (free)

### Step 2: Upload Project
```powershell
# In your project folder
git init
git add .
git commit -m "Initial commit"

# Create repository on GitHub first, then:
git remote add origin https://github.com/YOUR_USERNAME/wire-auto-messenger.git
git branch -M main
git push -u origin main
```

### Step 3: Build APK
1. Go to your GitHub repository
2. Click **Actions** tab
3. Click **Build APK** workflow
4. Click **Run workflow** → **Run workflow**
5. Wait 5-10 minutes
6. Click on the completed workflow run
7. Download **app-release-apk** from Artifacts

**✅ Done! You have your APK!**

---

## Alternative: Local Build (If you have Android SDK)

### Step 1: Run Setup Script
```powershell
.\setup-build-env.ps1
```

### Step 2: Install Android SDK (if needed)
- Download: https://developer.android.com/studio#command-tools
- Extract to `C:\Android\sdk`
- Create `local.properties`:
  ```
  sdk.dir=C:\\Android\\sdk
  ```

### Step 3: Build
```powershell
.\gradlew.bat assembleRelease
```

APK will be in: `app\build\outputs\apk\release\app-release.apk`

---

## Need Help?

- **GitHub Actions**: See `BUILD_WITHOUT_ANDROID_STUDIO.md` → Option 1
- **Local Build**: See `BUILD_WITHOUT_ANDROID_STUDIO.md` → Option 2
- **Troubleshooting**: Check the detailed guide

**Recommended**: Use GitHub Actions - it's free and requires zero setup!

