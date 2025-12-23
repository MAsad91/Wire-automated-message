# Build Instructions - Wire Auto Messenger

## Prerequisites

- **Android Studio** (Arctic Fox or newer recommended)
- **JDK 8** or higher
- **Android SDK** with API Level 24+ (Android 7.0)
- **Gradle** 8.2+ (included in project)

## Building the APK

### Method 1: Using Android Studio (Recommended)

1. **Open the Project**
   ```
   File → Open → Select project folder
   ```

2. **Wait for Gradle Sync**
   - Android Studio will automatically sync Gradle
   - Wait for "Gradle sync finished" message

3. **Build APK**
   ```
   Build → Build Bundle(s) / APK(s) → Build APK(s)
   ```

4. **Locate APK**
   - After build completes, click "locate" in the notification
   - Or navigate to: `app/build/outputs/apk/debug/app-debug.apk`

### Method 2: Using Command Line

**Windows:**
```cmd
gradlew.bat assembleRelease
```

**Linux/Mac:**
```bash
./gradlew assembleRelease
```

**Output:** `app/build/outputs/apk/release/app-release.apk`

### Method 3: Build Debug APK

```bash
./gradlew assembleDebug
```

**Output:** `app/build/outputs/apk/debug/app-debug.apk`

## Signing the APK (For Release)

### Generate Keystore

```bash
keytool -genkey -v -keystore wire-automessenger.jks -keyalg RSA -keysize 2048 -validity 10000 -alias wire-automessenger
```

### Configure Signing in build.gradle

Add to `app/build.gradle`:

```gradle
android {
    ...
    signingConfigs {
        release {
            storeFile file('path/to/wire-automessenger.jks')
            storePassword 'your-store-password'
            keyAlias 'wire-automessenger'
            keyPassword 'your-key-password'
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
            ...
        }
    }
}
```

## Project Structure

```
WireAutoMessenger/
├── app/
│   ├── build.gradle              # App-level build config
│   ├── src/
│   │   └── main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/com/wireautomessenger/
│   │       │   ├── MainActivity.kt
│   │       │   ├── service/
│   │       │   │   └── WireAutomationService.kt
│   │       │   ├── work/
│   │       │   │   └── MessageSendingWorker.kt
│   │       │   └── receiver/
│   │       │       └── BootReceiver.kt
│   │       └── res/
│   │           ├── layout/
│   │           ├── values/
│   │           └── xml/
├── build.gradle                   # Project-level build config
├── settings.gradle
└── gradle.properties
```

## Dependencies

Key dependencies (already configured in `app/build.gradle`):

- `androidx.core:core-ktx:1.12.0`
- `androidx.appcompat:appcompat:1.6.1`
- `com.google.android.material:material:1.10.0`
- `androidx.work:work-runtime-ktx:2.9.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3`

## Build Variants

### Debug
- Includes debugging symbols
- Not optimized
- Can be installed via ADB

### Release
- Optimized
- Minified (if enabled)
- Requires signing for distribution

## Testing the Build

### Install via ADB

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Install Release APK

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Troubleshooting Build Issues

### Gradle Sync Failed

**Solution:**
1. File → Invalidate Caches / Restart
2. Check internet connection (Gradle downloads dependencies)
3. Update Android Studio

### Build Errors

**Common Issues:**
- **SDK not found**: Install required SDK via SDK Manager
- **Kotlin version mismatch**: Update Kotlin plugin
- **Dependency conflicts**: Check `app/build.gradle`

### APK Too Large

**Solution:**
1. Enable ProGuard/R8 in `build.gradle`
2. Remove unused resources
3. Use Android App Bundle instead of APK

## Version Management

Update version in `app/build.gradle`:

```gradle
defaultConfig {
    versionCode 2        // Increment for each release
    versionName "1.0.1"  // User-visible version
}
```

## Distribution

### Internal Testing
- Share APK directly
- Use Google Play Internal Testing
- Use Firebase App Distribution

### Production Release
1. Build signed release APK
2. Test thoroughly
3. Upload to distribution platform
4. Update version number

## Continuous Integration (CI)

Example GitHub Actions workflow:

```yaml
name: Build APK

on: [push]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK
        uses: actions/setup-java@v2
        with:
          java-version: '11'
      - name: Build APK
        run: ./gradlew assembleRelease
      - name: Upload APK
        uses: actions/upload-artifact@v2
        with:
          name: app-release
          path: app/build/outputs/apk/release/app-release.apk
```

## Notes

- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Compile SDK**: 34
- **Kotlin Version**: 1.9.20

## Support

For build issues:
1. Check Android Studio version compatibility
2. Verify all prerequisites are installed
3. Review build error messages
4. Check Gradle logs: `View → Tool Windows → Build`

