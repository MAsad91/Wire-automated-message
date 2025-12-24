# Wire API Implementation Guide

## Step-by-Step Implementation

### Step 1: Get Wire App Credentials

1. Log in to your **Wire Team account** (not personal account)
2. Go to **Team Settings** → **Apps** section
3. Click **"Create New App"**
4. Fill in:
   - App Name: "Wire Auto Messenger" (or your preferred name)
   - Avatar: (optional)
   - Description: "App for sending broadcast messages"
5. Click **"Create App"**
6. **IMPORTANT**: Copy and save:
   - **App ID** (e.g., `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`)
   - **API Token** (e.g., `Bearer xxxxxxxxxxxxxxxxxxxxxx`)
   - ⚠️ **Token cannot be retrieved later** - save it securely!

### Step 2: Add Dependencies

The dependencies have been added to `app/build.gradle`:
- OkHttp for HTTP requests
- Gson for JSON parsing

### Step 3: Configure App ID and Token

1. Open the app
2. Go to Settings (we'll add this)
3. Enter your **App ID** and **API Token**
4. Save the configuration

### Step 4: Implementation Status

✅ **Completed:**
- WireApiManager class created
- API client with OkHttp
- Methods for sending messages
- Broadcast messaging support

🔄 **In Progress:**
- Adding configuration UI in MainActivity
- Integrating API mode toggle
- Updating send button to use API

### Step 5: API Endpoints Used

- **Base URL**: `https://prod-nginz-https.wire.com`
- **Create Conversation**: `POST /conversations`
- **Send Message**: `POST /conversations/{id}/messages`
- **Get Users**: `GET /users`

### Step 6: How It Works

1. User enters message and clicks "Send Now"
2. App gets list of team members (or uses provided list)
3. For each user:
   - Creates/gets conversation with user
   - Sends message to conversation
4. Shows progress and results

### Step 7: Testing

1. Enter App ID and Token in settings
2. Enter a test message
3. Click "Send Now"
4. Check logs for API responses
5. Verify messages are sent in Wire

## Important Notes

⚠️ **API vs Accessibility Service:**
- **API Mode**: Requires Team account, more reliable, faster
- **Accessibility Mode**: Works with personal accounts, less reliable

⚠️ **Rate Limiting:**
- Wire API may have rate limits
- Current implementation adds 500ms delay between messages
- Adjust if needed based on Wire's limits

⚠️ **Authentication:**
- Token format: `Bearer {your-token}`
- Token must be included in Authorization header
- Token doesn't expire (unless regenerated)

## Next Steps

1. Add configuration UI for App ID/Token
2. Add toggle to switch between API and Accessibility modes
3. Update send button to use API when configured
4. Test with your Wire Team account
5. Handle errors gracefully

## Troubleshooting

**"Failed to create conversation"**
- Check App ID and Token are correct
- Verify you have permission to create conversations
- Check network connectivity

**"Failed to send message"**
- Verify conversation was created successfully
- Check message format is correct
- Review API response for error details

**"Failed to get team members"**
- Verify API token has read permissions
- Check if team has members
- Review API documentation for correct endpoint

