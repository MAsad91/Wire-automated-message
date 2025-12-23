# Installation Guide - Wire Auto Messenger

## Quick Start (5 Minutes)

### Prerequisites
- Android phone (Android 7.0 or newer)
- Wire app installed and logged in
- APK file: `app-release.apk`

---

## Step-by-Step Installation

### 1. Prepare Your Device

**Enable Developer Options** (if not already enabled):
1. Go to **Settings** → **About Phone**
2. Tap **Build Number** 7 times
3. You'll see "You are now a developer!"

**Enable USB Debugging** (Optional, for testing):
1. Go to **Settings** → **Developer Options**
2. Enable **USB Debugging**

**Allow Unknown Sources**:
1. Go to **Settings** → **Security** (or **Apps** → **Special Access**)
2. Enable **Install Unknown Apps** or **Unknown Sources**
3. Select your file manager app and allow it

---

### 2. Install the APK

**Method A: Direct Install (Recommended)**
1. Transfer `app-release.apk` to your phone (via USB, email, or cloud storage)
2. Open **Files** or **File Manager** app
3. Navigate to where you saved the APK
4. Tap the APK file
5. Tap **Install**
6. Wait for installation
7. Tap **Open** or find "Wire Auto Messenger" in your app drawer

**Method B: Using ADB (For Developers)**
```bash
adb install app-release.apk
```

---

### 3. Enable Accessibility Service

**This is CRITICAL - the app won't work without this!**

1. Open **Wire Auto Messenger** app
2. You'll see a red status: "✗ Not Enabled"
3. Tap **"Enable Accessibility Service"** button
4. You'll be taken to **Accessibility Settings**
5. Scroll down and find **"Wire Auto Messenger"**
6. Tap on it
7. Toggle the switch **ON**
8. Read and tap **"Allow"** on the warning dialog
9. Return to the Wire Auto Messenger app
10. Status should now show: "✓ Enabled" (green)

**Alternative Method:**
1. Go to **Settings** → **Accessibility**
2. Find **Wire Auto Messenger**
3. Toggle it **ON**

---

### 4. First-Time Setup

1. Open **Wire Auto Messenger**
2. Verify Accessibility Service shows "✓ Enabled" (green)
3. Type your message in the text field
4. Tap **"Send Now"** to test (optional)
5. Or toggle **"Send every 3 days"** to enable scheduling

---

## Building from Source (For Developers)

### Requirements
- Android Studio (latest version)
- JDK 8 or higher
- Android SDK (API 24+)

### Steps

1. **Clone/Download the project**
   ```bash
   git clone <repository-url>
   cd "Automate Message"
   ```

2. **Open in Android Studio**
   - File → Open → Select project folder
   - Wait for Gradle sync

3. **Build the APK**
   - Build → Build Bundle(s) / APK(s) → Build APK(s)
   - Or: `./gradlew assembleRelease`

4. **Find the APK**
   - Location: `app/build/outputs/apk/release/app-release.apk`

5. **Sign the APK** (for release)
   - Build → Generate Signed Bundle / APK
   - Follow the signing wizard

---

## Verification Checklist

After installation, verify:

- [ ] App appears in app drawer
- [ ] App opens without crashes
- [ ] Accessibility Service can be enabled
- [ ] Accessibility Service shows "Enabled" status
- [ ] Message input field is visible
- [ ] Schedule switch is visible
- [ ] Wire app is installed on device

---

## Common Installation Issues

### "App not installed" Error

**Causes:**
- Insufficient storage space
- Conflicting app signature
- Corrupted APK file

**Solutions:**
1. Free up storage space (need at least 50MB)
2. Uninstall any previous version first
3. Re-download the APK file
4. Try installing via ADB: `adb install -r app-release.apk`

### "Parse Error" or "Package Invalid"

**Causes:**
- Corrupted download
- Incompatible Android version

**Solutions:**
1. Re-download the APK
2. Check Android version (must be 7.0+)
3. Try downloading on a different network

### Accessibility Service Won't Enable

**Causes:**
- Device restrictions
- Conflicting services
- Android security policy

**Solutions:**
1. Restart your device
2. Check if any other accessibility services are conflicting
3. Try disabling and re-enabling
4. Check device administrator settings

### App Crashes on Launch

**Causes:**
- Incompatible device
- Missing dependencies
- Corrupted installation

**Solutions:**
1. Clear app data: Settings → Apps → Wire Auto Messenger → Clear Data
2. Uninstall and reinstall
3. Check device compatibility (Android 7.0+)
4. Report the issue with device model and Android version

---

## Post-Installation

### Recommended Settings

1. **Battery Optimization**
   - Go to Settings → Battery → Battery Optimization
   - Find "Wire Auto Messenger"
   - Set to "Not Optimized" (for scheduled sending)

2. **Auto-start** (if available on your device)
   - Enable auto-start for the app
   - Ensures scheduled tasks run after reboot

3. **Notifications**
   - Allow notifications from Wire Auto Messenger
   - Helps track sending progress

---

## Uninstallation

1. Go to **Settings** → **Apps**
2. Find **Wire Auto Messenger**
3. Tap **Uninstall**
4. Confirm

**Note**: Disable Accessibility Service first (Settings → Accessibility) before uninstalling.

---

## Support

If you encounter issues during installation:

1. Check the **Troubleshooting** section in README.md
2. Verify all prerequisites are met
3. Ensure your device is compatible (Android 7.0+)
4. Try the common solutions above

---

## Next Steps

After successful installation:

1. Read the **Usage Guide** in README.md
2. Test with a small number of contacts first
3. Set up your scheduled sending
4. Monitor the first automated send

**Ready to use!** 🎉

