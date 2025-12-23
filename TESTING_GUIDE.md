# Testing Guide - Wire Auto Messenger

## Complete Step-by-Step Testing Instructions

### Prerequisites

1. **Android Device** (Redmi Note 13 Pro or any Android 7.0+)
2. **Wire App** installed from Google Play Store
3. **Wire Auto Messenger APK** downloaded from GitHub Actions

---

## Step 1: Install Wire App

1. Open **Google Play Store** on your phone
2. Search for **"Wire"**
3. Install the official **Wire** app
4. Open Wire app and create an account:
   - Sign up with your email or phone number
   - Verify your account
   - Complete the setup

---

## Step 2: Add Test Contacts

1. Open **Wire** app
2. Tap the **"+"** or **"Add Contact"** button
3. Add at least **2-3 test contacts** (friends or test accounts)
   - You can create multiple Wire accounts for testing
   - Or add real friends who have Wire
4. Make sure you have conversations with these contacts

**For Testing:**
- Add 2-3 contacts initially (easier to test)
- Once it works, you can test with more contacts
- The app supports 500+ contacts as per client requirements

---

## Step 3: Install Wire Auto Messenger APK

1. Download the APK from GitHub Actions:
   - Go to: https://github.com/MAsad91/Wire-automated-message/actions
   - Click latest workflow run
   - Download `app-debug-apk` from Artifacts

2. Transfer APK to your phone (via USB, email, or cloud)

3. Install the APK:
   - Open **Files** app on your phone
   - Find the downloaded APK
   - Tap to install
   - Allow installation from unknown sources if prompted

---

## Step 4: Set Up Wire Auto Messenger

### 4.1 First Launch - Permissions

When you open the app for the first time:

1. **Notification Permission Dialog** (Android 13+):
   - Tap **"Allow"** to enable notifications
   - Or tap **"Skip"** (you can enable later)

2. **Accessibility Service Dialog**:
   - Read the instructions
   - Tap **"Enable Now"**
   - You'll see step-by-step instructions:
     - Step 1: Tap 'Enable Now' below
     - Step 2: Find 'Wire Auto Messenger' in the list
     - Step 3: Toggle the switch ON
     - Step 4: Tap 'Allow' on the warning dialog
     - Step 5: Return to this app

### 4.2 Enable Accessibility Service

1. After tapping "Enable Now", you'll be taken to **Accessibility Settings**

2. **On Redmi/ Xiaomi devices:**
   - You might see multiple options
   - Look for **"Installed Services"** or **"Downloaded Apps"**
   - Scroll down to find **"Wire Auto Messenger"**

3. **Enable the Service:**
   - Tap on **"Wire Auto Messenger"**
   - Toggle the switch **ON**
   - A warning dialog will appear
   - Read and tap **"Allow"** or **"OK"**

4. **Return to the App:**
   - Press back button or use recent apps
   - Return to **Wire Auto Messenger**
   - Status should now show: **"✓ Enabled"** (green badge)

---

## Step 5: Write Your Test Message

1. In **Wire Auto Messenger** app, find the **"Your Message"** section

2. Tap in the text area (5 rows, scrollable)

3. Type your test message, for example:
   ```
   Hello! This is a test message from Wire Auto Messenger.
   ```

4. The message will be saved automatically

---

## Step 6: Test Sending Messages

### Option A: Send Now (Immediate Test)

1. Make sure:
   - ✅ Accessibility Service shows "Enabled" (green)
   - ✅ You have entered a message
   - ✅ Wire app is installed and you're logged in
   - ✅ You have at least 2-3 contacts in Wire

2. Tap the **"Send Now"** button (purple button at bottom)

3. **What Happens:**
   - The app will automatically:
     - Open Wire app
     - Navigate to your contacts list
     - Open each conversation
     - Type your message
     - Send the message
     - Move to next contact
     - Repeat for all contacts

4. **You'll See:**
   - Progress indicator (spinning circle)
   - "Sending messages..." status
   - Notification showing progress

5. **Wait for Completion:**
   - For 2-3 contacts: ~1-2 minutes
   - For 500+ contacts: ~30-60 minutes
   - You'll see "Messages sent successfully!" when done

6. **Verify:**
   - Open Wire app
   - Check your conversations
   - You should see your message sent to all contacts

### Option B: Test Scheduled Sending

1. Enter your message in the text area

2. Toggle **"Send every 3 days"** switch to **ON**

3. **What Happens:**
   - The app will schedule automatic sending
   - You'll see "Next send: [date and time]"
   - The message will be sent automatically every 3 days

4. **To Test Immediately:**
   - You can manually trigger by tapping "Send Now"
   - Or wait for the scheduled time

---

## Step 7: Testing Checklist

### ✅ Basic Functionality

- [ ] App installs successfully
- [ ] Accessibility Service can be enabled
- [ ] Message input works (5 rows, scrollable)
- [ ] Message is saved when typing

### ✅ Sending Messages

- [ ] "Send Now" button works
- [ ] Wire app opens automatically
- [ ] Messages are sent to all contacts
- [ ] Progress indicator shows during sending
- [ ] Success message appears when complete

### ✅ Scheduled Sending

- [ ] Schedule toggle works
- [ ] Next send time is displayed
- [ ] Schedule persists after app restart
- [ ] Schedule works after device reboot

### ✅ Permissions

- [ ] Notification permission requested (Android 13+)
- [ ] Accessibility Service dialog appears on first launch
- [ ] Accessibility Service can be enabled
- [ ] Status updates correctly

---

## Troubleshooting

### Messages Not Sending

**Check:**
1. Is Accessibility Service enabled? (Should show green "✓ Enabled")
2. Is Wire app installed and logged in?
3. Do you have contacts in Wire?
4. Is the message field not empty?
5. Is your device unlocked during sending?

**Solution:**
- Re-enable Accessibility Service
- Make sure Wire app is open and logged in
- Add at least one contact in Wire
- Try with 1-2 contacts first

### Accessibility Service Not Working

**On Redmi/Xiaomi:**
1. Go to **Settings** → **Additional Settings** → **Privacy**
2. Disable **"MIUI Security"** temporarily
3. Try enabling Accessibility Service again
4. Re-enable MIUI Security after setup

### App Crashes

**Solution:**
1. Force close the app
2. Clear app data: **Settings** → **Apps** → **Wire Auto Messenger** → **Clear Data**
3. Re-open and set up again

---

## Testing with Multiple Contacts

### Small Test (2-5 contacts)
- **Time:** ~1-2 minutes
- **Purpose:** Verify basic functionality
- **Recommended for:** Initial testing

### Medium Test (10-20 contacts)
- **Time:** ~5-10 minutes
- **Purpose:** Test with more contacts
- **Recommended for:** Extended testing

### Full Test (500+ contacts)
- **Time:** ~30-60 minutes
- **Purpose:** Production scenario
- **Recommended for:** Final verification

---

## Expected Behavior

### During Sending:
1. Wire app opens automatically
2. App navigates through contacts
3. Each conversation opens
4. Message is typed
5. Message is sent
6. App moves to next contact
7. Progress shown in notification

### After Completion:
1. All contacts receive the message
2. Success notification appears
3. Status updates in app
4. "Send Now" button becomes enabled again

---

## Tips for Testing

1. **Start Small:** Test with 2-3 contacts first
2. **Keep Device Unlocked:** During message sending
3. **Don't Interrupt:** Let the process complete
4. **Check Wire App:** Verify messages were sent
5. **Test Schedule:** Enable schedule and verify it works

---

## What to Verify

✅ Messages are sent to all contacts  
✅ Message content is correct  
✅ No duplicate messages  
✅ Schedule works correctly  
✅ App works after device restart  
✅ Permissions persist  

---

## Need Help?

If something doesn't work:
1. Check the troubleshooting section above
2. Verify all prerequisites are met
3. Try with fewer contacts first
4. Check Accessibility Service is enabled
5. Make sure Wire app is working normally

---

**Happy Testing!** 🚀

