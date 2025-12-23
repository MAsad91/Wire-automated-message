# Update Installation Guide

## Package Conflict Resolution

If you get "package conflicts with an existing package" error when installing an update:

### Solution 1: Uninstall Old Version First (Recommended)

1. **Go to Settings** → **Apps** → **Wire Auto Messenger**
2. **Tap "Uninstall"**
3. **Confirm uninstallation**
4. **Install the new APK**

### Solution 2: Use ADB to Force Install

If you have ADB access:

```bash
adb uninstall com.wireautomessenger
adb install WireAutoMessenger-1.0.2-3.apk
```

Or force reinstall:

```bash
adb install -r WireAutoMessenger-1.0.2-3.apk
```

### Solution 3: Clear App Data First

1. **Go to Settings** → **Apps** → **Wire Auto Messenger**
2. **Tap "Storage"**
3. **Tap "Clear Data"** and **"Clear Cache"**
4. **Uninstall the app**
5. **Install the new APK**

---

## Why This Happens

Package conflicts occur when:
- The signing key is different between versions
- The package name has changed
- Android detects a signature mismatch

**Note:** The app now uses consistent debug signing for both debug and release builds to prevent this issue in future updates.

---

## Current Version Info

- **Version Code:** 3
- **Version Name:** 1.0.2
- **Package Name:** com.wireautomessenger

---

## After Installing Update

1. **Re-enable Accessibility Service** (if needed)
2. **Check Wire app detection** works correctly
3. **Test message sending** with a few contacts first
4. **Verify dialogs are light themed**

