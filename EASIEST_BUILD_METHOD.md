# 🎯 EASIEST WAY TO BUILD APK (No Android Studio Needed!)

## ✅ Use GitHub Actions - 100% FREE & EASY

This is the **recommended method** - no Android Studio, no Android SDK, no local setup needed!

---

## Step-by-Step Guide

### Step 1: Create GitHub Account (2 minutes)
1. Go to https://github.com
2. Click **Sign up** (it's free!)
3. Create your account

### Step 2: Upload Your Project (3 minutes)

**Option A: Using GitHub Website (Easiest)**
1. Go to https://github.com/new
2. Repository name: `wire-auto-messenger`
3. Make it **Private** (or Public - your choice)
4. Click **Create repository**
5. Scroll down to "uploading an existing file"
6. Drag and drop ALL files from your project folder
7. Click **Commit changes**

**Option B: Using Git Command Line**
```powershell
# In your project folder
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/wire-auto-messenger.git
git push -u origin main
```
(Replace YOUR_USERNAME with your GitHub username)

### Step 3: Build the APK (5 minutes)

1. Go to your repository on GitHub
2. Click the **Actions** tab (at the top)
3. You should see "Build APK" workflow
4. Click **Run workflow** button (on the right)
5. Click the green **Run workflow** button
6. Wait 5-10 minutes
7. When it's done (green checkmark), click on the workflow run
8. Scroll down to **Artifacts**
9. Click **app-release-apk** to download your APK!

**✅ Done! You have your APK file!**

---

## That's It!

No Android Studio needed. No Android SDK needed. No local setup needed.

Just:
1. Upload to GitHub
2. Click "Run workflow"
3. Download APK

**Total time: ~10 minutes**

---

## Troubleshooting

### "No workflows found"
- Make sure you uploaded the `.github/workflows/build-apk.yml` file
- If missing, create it manually in GitHub (see BUILD_WITHOUT_ANDROID_STUDIO.md)

### "Workflow failed"
- Check the error message in the Actions tab
- Usually it's a syntax error in the workflow file
- Make sure all files are uploaded correctly

### "Can't find Artifacts"
- Wait a bit longer - the build might still be running
- Check the workflow status (should be green checkmark)

---

## Alternative: If You Have Java Installed

If you already have Java JDK installed, you can try building locally:

```powershell
# Make sure gradle-wrapper.jar exists
# Then run:
.\gradlew.bat assembleRelease
```

But GitHub Actions is still easier! 😊

---

## Need Help?

- Check `BUILD_WITHOUT_ANDROID_STUDIO.md` for detailed instructions
- Check `QUICK_START.md` for quick reference

**Remember: GitHub Actions is the easiest method - no local setup required!**

