# Wire Auto Messenger

A native Android application that automatically sends messages to all your Wire contacts with scheduling support.

## Features

- ✅ **Automatic Message Sending**: Send the same message to all your Wire contacts with one click
- ✅ **Scheduled Sending**: Automatically send messages every 3 days
- ✅ **Simple Interface**: Easy-to-use UI for composing messages and managing schedules
- ✅ **Standalone APK**: No external dependencies or technical setup required
- ✅ **Secure**: Runs entirely on your device using Android's Accessibility Service

## Requirements

- Android device running Android 7.0 (API 24) or higher
- Wire app installed and set up on your device
- At least 500+ contacts in Wire (as per client requirements)

## Installation Instructions

### Step 1: Enable Unknown Sources

1. Go to **Settings** on your Android device
2. Navigate to **Security** or **Apps** (depending on your Android version)
3. Enable **Install from Unknown Sources** or **Allow from this source**

### Step 2: Install the APK

1. Transfer the `app-release.apk` file to your Android device
2. Open the APK file using a file manager
3. Tap **Install** when prompted
4. Wait for installation to complete

### Step 3: Enable Accessibility Service

1. Open the **Wire Auto Messenger** app
2. Tap the **"Enable Accessibility Service"** button
3. You will be redirected to Accessibility Settings
4. Find **"Wire Auto Messenger"** in the list
5. Toggle it **ON**
6. Confirm the permission dialog
7. Return to the app

**Important**: The app requires Accessibility Service permission to automate message sending in Wire.

## Usage Guide

### Sending Messages Now

1. Open **Wire Auto Messenger**
2. Enter your message in the text field
3. Ensure the Accessibility Service is enabled (green checkmark)
4. Tap **"Send Now"** button
5. The app will automatically:
   - Open Wire app
   - Navigate through all contacts
   - Send your message to each contact
   - Show progress in notifications

### Setting Up Scheduled Sending

1. Open **Wire Auto Messenger**
2. Enter your message in the text field
3. Toggle **"Send every 3 days"** switch to ON
4. The app will automatically send your message every 3 days
5. You can see the next send time displayed below the switch

### Disabling Scheduled Sending

1. Open **Wire Auto Messenger**
2. Toggle **"Send every 3 days"** switch to OFF
3. Scheduled sending will be disabled

## How It Works

The app uses Android's Accessibility Service API to:
- Automatically navigate the Wire app interface
- Find and click on contacts
- Type messages into conversation windows
- Send messages by clicking the send button
- Return to the contacts list and repeat

**Authentication**: The app uses your existing Wire app installation. No separate authentication is required - it works with your already logged-in Wire account.

## Technical Details

### Libraries & Frameworks Used

- **Kotlin**: Modern Android development language
- **AndroidX**: Latest Android support libraries
- **WorkManager**: For reliable background scheduling
- **Accessibility Service API**: For UI automation
- **Material Design Components**: For modern UI

### Architecture

- **MainActivity**: User interface for message composition and settings
- **WireAutomationService**: Accessibility service that handles automation
- **MessageSendingWorker**: Background worker for scheduled sending
- **BootReceiver**: Restores scheduled sending after device reboot

## Troubleshooting

### Accessibility Service Not Working

- Ensure the service is enabled in Android Settings
- Restart the app after enabling the service
- Check that Wire app is installed and accessible

### Messages Not Sending

- Verify Wire app is installed and you're logged in
- Check that you have contacts in Wire
- Ensure the message field is not empty
- Try sending to a smaller group first to test

### Scheduled Sending Not Working

- Ensure the schedule switch is enabled
- Check that the Accessibility Service is enabled
- Verify the message is saved (not empty)
- Restart the device and check again

### App Crashes or Freezes

- Force close the app and reopen
- Restart your device
- Re-enable the Accessibility Service
- Uninstall and reinstall the app

## Privacy & Security

- All automation happens locally on your device
- No data is sent to external servers
- Messages are sent through your existing Wire app
- The app only accesses Wire app UI elements (no personal data access)

## Limitations

- Requires Accessibility Service permission (Android security requirement)
- Sending to 500+ contacts may take 30-60 minutes
- Wire app UI changes may require app updates
- Device must be unlocked during message sending

## Support

For issues or questions:
1. Check the Troubleshooting section above
2. Ensure all requirements are met
3. Verify Accessibility Service is properly enabled

## Version History

- **v1.0.0** (Initial Release)
  - Basic message sending to all contacts
  - Scheduled sending every 3 days
  - Simple user interface

## License

This application is developed for specific client requirements. All rights reserved.

---

**Note**: This app automates interactions with the Wire messaging app. Ensure you comply with Wire's Terms of Service when using automation features.

