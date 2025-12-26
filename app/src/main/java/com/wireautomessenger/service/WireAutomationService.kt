package com.wireautomessenger.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.wireautomessenger.MainActivity
import com.wireautomessenger.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter

class WireAutomationService : AccessibilityService() {

    // State machine flags
    private val isRunning = AtomicBoolean(false)
    private val isWireOpened = AtomicBoolean(false)
    private val isSendingInProgress = AtomicBoolean(false)
    
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("WireAutoMessenger", MODE_PRIVATE)
    }
    private val scope = CoroutineScope(Dispatchers.Main)
    
    // Debug logging system
    private val debugLog = StringBuilder()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val maxLogSize = 50000 // Max 50KB of logs to prevent memory issues

    companion object {
        const val ACTION_SEND_MESSAGES = "com.wireautomessenger.SEND_MESSAGES"
        const val WIRE_PACKAGE = "com.wire"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "wire_automation_channel"
        
        // Broadcast actions
        const val ACTION_PROGRESS_UPDATE = "com.wireautomessenger.PROGRESS_UPDATE"
        const val ACTION_COMPLETED = "com.wireautomessenger.COMPLETED"
        const val ACTION_ERROR = "com.wireautomessenger.ERROR"
        const val ACTION_CONTACT_UPDATE = "com.wireautomessenger.CONTACT_UPDATE"
        
        // Broadcast extras
        const val EXTRA_PROGRESS_TEXT = "progress_text"
        const val EXTRA_CONTACTS_SENT = "contacts_sent"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_CONTACT_NAME = "contact_name"
        const val EXTRA_CONTACT_STATUS = "contact_status" // "sent", "failed", "skipped"
        const val EXTRA_CONTACT_POSITION = "contact_position"
        const val EXTRA_CONTACT_ERROR = "contact_error"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            createNotificationChannel()
            debugLog("SERVICE", "WireAutomationService connected and initialized")
        } catch (e: Exception) {
            // Log error but don't crash the service
            debugLog("ERROR", "Service connection error: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * Comprehensive debug logging system
     * Captures every event with timestamp for easy debugging
     * Logs are stored in memory and can be copied/retrieved
     */
    private fun debugLog(tag: String, message: String, exception: Exception? = null) {
        val timestamp = dateFormat.format(Date())
        val threadName = Thread.currentThread().name
        val logEntry = StringBuilder()
        
        logEntry.append("[$timestamp] ")
        logEntry.append("[$tag] ")
        logEntry.append("[$threadName] ")
        logEntry.append(message)
        
        if (exception != null) {
            logEntry.append("\n")
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            exception.printStackTrace(pw)
            logEntry.append("EXCEPTION STACK TRACE:\n")
            logEntry.append(sw.toString())
        }
        
        val logLine = logEntry.toString()
        
        // Add to debug log buffer
        synchronized(debugLog) {
            if (debugLog.length > maxLogSize) {
                // Keep last 25KB, remove first 25KB
                val keepSize = 25000
                val currentLog = debugLog.toString()
                debugLog.clear()
                debugLog.append("... [LOG TRUNCATED - KEEPING LAST 25KB] ...\n")
                debugLog.append(currentLog.substring(currentLog.length - keepSize))
            }
            debugLog.append(logLine)
            debugLog.append("\n")
        }
        
        // Also log to Android LogCat with appropriate level
        when (tag) {
            "ERROR", "EXCEPTION" -> android.util.Log.e("WireAutoDebug", logLine)
            "WARN", "WARNING" -> android.util.Log.w("WireAutoDebug", logLine)
            "INFO" -> android.util.Log.i("WireAutoDebug", logLine)
            "EVENT", "ACTION", "NAVIGATION", "CLICK", "TEXT", "STATE" -> android.util.Log.d("WireAutoDebug", logLine)
            else -> android.util.Log.v("WireAutoDebug", logLine)
        }
        
        // Save to SharedPreferences periodically (last 10KB for quick access)
        if (debugLog.length % 1000 == 0) { // Every ~1000 characters
            saveDebugLogToPrefs()
        }
    }
    
    /**
     * Save debug log to SharedPreferences (full log and last portion for quick access)
     */
    private fun saveDebugLogToPrefs() {
        try {
            synchronized(debugLog) {
                val logText = debugLog.toString()
                
                // Save full log (up to 50KB)
                val fullLog = if (logText.length > 50000) {
                    logText.substring(logText.length - 50000)
                } else {
                    logText
                }
                prefs.edit().putString("debug_log_full", fullLog).apply()
                
                // Save last 10KB for quick access
                val lastPortion = if (logText.length > 10000) {
                    logText.substring(logText.length - 10000)
                } else {
                    logText
                }
                prefs.edit().putString("debug_log_last", lastPortion).apply()
                
                // Also save to a file in app's external files directory for easy copying
                try {
                    val logFile = File(getExternalFilesDir(null), "wire_automation_debug.log")
                    FileWriter(logFile, false).use { writer ->
                        writer.write(logText)
                    }
                    debugLog("DATA", "Debug log saved to file: ${logFile.absolutePath}")
                } catch (e: Exception) {
                    debugLog("WARN", "Could not save debug log to file: ${e.message}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WireAuto", "Error saving debug log: ${e.message}")
        }
    }
    
    /**
     * Get debug log from SharedPreferences
     */
    fun getDebugLogFromPrefs(): String {
        return try {
            prefs.getString("debug_log_full", "") ?: ""
        } catch (e: Exception) {
            android.util.Log.e("WireAuto", "Error retrieving debug log: ${e.message}")
            ""
        }
    }
    
    /**
     * Get full debug log for copying
     */
    fun getDebugLog(): String {
        synchronized(debugLog) {
            return debugLog.toString()
        }
    }
    
    /**
     * Clear debug log
     */
    fun clearDebugLog() {
        synchronized(debugLog) {
            debugLog.clear()
            debugLog.append("[${dateFormat.format(Date())}] DEBUG LOG CLEARED\n")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // CRITICAL: NO app-launch logic here to prevent infinite loops
        // This method only observes events, never launches apps
        // All app launching happens ONLY from user action (Start button) via onStartCommand
        try {
            // PACKAGE VERIFICATION: Explicitly verify package name matches Wire
            if (event != null) {
                val packageName = event.packageName?.toString()
                if (packageName != null && packageName == WIRE_PACKAGE) {
                    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && isSendingInProgress.get()) {
                        android.util.Log.d("WireAuto", "Wire app window state changed - verified package: $packageName")
                    }
                } else if (packageName != null && packageName != WIRE_PACKAGE) {
                    // Log non-Wire packages for debugging (but don't act on them)
                    android.util.Log.v("WireAuto", "Non-Wire package event: $packageName")
                }
            }
        } catch (e: Exception) {
            // Silently handle any errors - never crash the service
            e.printStackTrace()
        }
    }

    override fun onInterrupt() {
        // Service interrupted - reset all state flags
        // This method must not throw exceptions
        try {
            android.util.Log.w("WireAuto", "Service interrupted - resetting state")
            isRunning.set(false)
            isWireOpened.set(false)
            isSendingInProgress.set(false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            debugLog("EVENT", "onStartCommand called - action: ${intent?.action}, flags: $flags, startId: $startId")
            if (intent?.action == ACTION_SEND_MESSAGES) {
                debugLog("ACTION", "SEND_MESSAGES action received")
                if (!isRunning.get()) {
                    debugLog("STATE", "Service not running, starting automation process")
                    try {
                        startForeground(NOTIFICATION_ID, createNotification("Starting automation..."))
                        debugLog("EVENT", "Foreground notification started")
                    } catch (e: Exception) {
                        debugLog("ERROR", "Failed to start foreground notification", e)
                        e.printStackTrace()
                    }
                    // STABILITY HANDLER: Wrap in Handler().postDelayed() to prevent blocking main thread
                    Handler(Looper.getMainLooper()).postDelayed({
                        scope.launch {
                            debugLog("ACTION", "Launching sendMessagesToAllContacts coroutine")
                            sendMessagesToAllContacts()
                        }
                    }, 100) // Small delay to ensure service is fully initialized
                } else {
                    debugLog("WARN", "Service already running - ignoring duplicate request")
                }
            } else {
                debugLog("INFO", "Unknown action received: ${intent?.action}")
            }
            START_NOT_STICKY
        } catch (e: Exception) {
            debugLog("ERROR", "Error in onStartCommand", e)
            e.printStackTrace()
            START_NOT_STICKY
        }
    }

    private suspend fun sendMessagesToAllContacts() {
        // Clear previous debug log at start of new session
        clearDebugLog()
        debugLog("EVENT", "=== STARTING NEW BROADCAST MESSAGE SESSION ===")
        debugLog("STATE", "Initial state - isRunning: ${isRunning.get()}, isWireOpened: ${isWireOpened.get()}, isSendingInProgress: ${isSendingInProgress.get()}")
        
        // State machine: Check if already running
        if (isRunning.getAndSet(true)) {
            debugLog("WARN", "Already running - ignoring duplicate request")
            android.util.Log.w("WireAuto", "Already running - ignoring duplicate request")
            return
        }

        // Reset state flags
        isWireOpened.set(false)
        isSendingInProgress.set(false)
        debugLog("STATE", "State flags reset - isWireOpened: false, isSendingInProgress: false")

        try {
            val message = prefs.getString("pending_message", "") ?: ""
            debugLog("DATA", "Retrieved pending message from preferences - length: ${message.length} characters")
            if (message.isEmpty()) {
                debugLog("ERROR", "No message to send - message is empty")
                android.util.Log.e("WireAuto", "No message to send")
                updateNotification("No message to send")
                sendErrorBroadcast("No message to send. Please enter a message first.")
                resetState()
                return
            }
            debugLog("DATA", "Message content: ${message.take(100)}${if (message.length > 100) "..." else ""}")

            debugLog("EVENT", "=== STARTING MESSAGE SENDING PROCESS ===")
            android.util.Log.i("WireAuto", "=== STARTING MESSAGE SENDING PROCESS ===")
            debugLog("STATE", "State: isRunning=true, isWireOpened=false, isSendingInProgress=false")
            android.util.Log.i("WireAuto", "State: isRunning=true, isWireOpened=false, isSendingInProgress=false")

            // STEP 1: Launch Wire app ONCE (only from user action)
            debugLog("NAVIGATION", "STEP 1: Launching Wire app")
            updateNotification("Opening Wire app...")
            sendProgressBroadcast("Opening Wire app...")
            
            val launchResult = launchWireAppOnce()
            debugLog("NAVIGATION", "Wire app launch result: $launchResult")
            if (!launchResult) {
                debugLog("ERROR", "Failed to launch Wire app - aborting")
                resetState()
                return
            }

            // STEP 2: Wait for Wire to be in foreground
            debugLog("NAVIGATION", "STEP 2: Waiting for Wire app to be in foreground (max 15 seconds)")
            android.util.Log.i("WireAuto", "Waiting for Wire app to be in foreground...")
            val wireInForeground = waitForWireInForeground(maxWaitSeconds = 15)
            debugLog("NAVIGATION", "Wire app foreground check result: $wireInForeground")
            if (!wireInForeground) {
                val errorMsg = "Wire app did not come to foreground. Please ensure Wire is installed and accessible."
                debugLog("ERROR", errorMsg)
                android.util.Log.e("WireAuto", errorMsg)
                updateNotification("Wire app not accessible")
                sendErrorBroadcast(errorMsg)
                resetState()
                return
            }

            isWireOpened.set(true)
            debugLog("STATE", "Wire app is now in foreground - isWireOpened=true")
            android.util.Log.i("WireAuto", "Wire app is now in foreground - State: isWireOpened=true")

            // STEP 3: Navigate to contacts and send messages
            isSendingInProgress.set(true)
            debugLog("STATE", "Starting message sending process - isSendingInProgress=true")
            android.util.Log.i("WireAuto", "Starting message sending process - State: isSendingInProgress=true")
            
            debugLog("ACTION", "STEP 3: Calling navigateAndSendMessages with message length: ${message.length}")
            navigateAndSendMessages(message)

                } catch (e: Exception) {
            val errorMsg = "Error: ${e.message ?: "Unknown error"}"
            debugLog("ERROR", "Fatal error in sendMessagesToAllContacts: $errorMsg", e)
            android.util.Log.e("WireAuto", "Fatal error in sendMessagesToAllContacts: $errorMsg", e)
            updateNotification(errorMsg)
            sendErrorBroadcast(errorMsg)
            // Save debug log even on error
            saveDebugLogToPrefs()
        } finally {
            debugLog("EVENT", "=== MESSAGE SENDING PROCESS COMPLETED ===")
            debugLog("STATE", "Final state - isRunning: ${isRunning.get()}, isWireOpened: ${isWireOpened.get()}, isSendingInProgress: ${isSendingInProgress.get()}")
            android.util.Log.i("WireAuto", "=== MESSAGE SENDING PROCESS COMPLETED ===")
            // Final save of debug log
            saveDebugLogToPrefs()
            resetState()
            delay(2000)
            stopForeground(true)
            stopSelf()
        }
    }

    /**
     * Launch Wire app ONCE - called only from user action (Start button)
     * Returns true if launch was successful, false otherwise
     */
    private suspend fun launchWireAppOnce(): Boolean {
        android.util.Log.i("WireAuto", "STEP 1: Launching Wire app (ONCE)")

        val allPackages = listOf(WIRE_PACKAGE, "com.wire", "ch.wire", "wire")
        var wireIntent: Intent? = null
        var foundPackage: String? = null
        
        // Try to get launch intent
                for (pkg in allPackages) {
                    try {
                            wireIntent = packageManager.getLaunchIntentForPackage(pkg)
                            if (wireIntent != null) {
                                foundPackage = pkg
                    android.util.Log.i("WireAuto", "Found Wire app package: $pkg")
                                break
                            }
                    } catch (e: Exception) {
                android.util.Log.d("WireAuto", "Package check failed for $pkg: ${e.message}")
            }
        }
        
        if (wireIntent == null || foundPackage == null) {
            val errorMsg = "Wire app not found. Please install Wire from Google Play Store."
            android.util.Log.e("WireAuto", errorMsg)
            updateNotification("Wire app not found")
            sendErrorBroadcast(errorMsg)
            return false
        }
        
        // Launch Wire app ONCE
        wireIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        android.util.Log.i("WireAuto", "Launching Wire app with package: $foundPackage")
        try {
            startActivity(wireIntent)
            android.util.Log.i("WireAuto", "Wire app launch intent sent successfully")
            delay(2000) // Initial wait for app to start
            return true
                } catch (e: Exception) {
            android.util.Log.e("WireAuto", "Failed to launch Wire app: ${e.message}", e)
            sendErrorBroadcast("Failed to launch Wire app: ${e.message}")
            return false
        }
    }

    /**
     * Wait for Wire app to be in foreground
     * Uses polling to check rootInActiveWindow
     * Returns true when Wire is in foreground, false if timeout
     */
    private suspend fun waitForWireInForeground(maxWaitSeconds: Int = 15): Boolean {
        android.util.Log.i("WireAuto", "STEP 2: Waiting for Wire app to be in foreground (max ${maxWaitSeconds}s)")
        
        val startTime = System.currentTimeMillis()
        val maxWaitMillis = maxWaitSeconds * 1000L
        var attempt = 0
        
        while (System.currentTimeMillis() - startTime < maxWaitMillis) {
            attempt++
            val rootNode = rootInActiveWindow
            
            if (rootNode != null && rootNode.packageName == WIRE_PACKAGE) {
                android.util.Log.i("WireAuto", "Wire app is in foreground (attempt $attempt)")
                delay(1000) // Additional wait for UI to stabilize
                return true
            }
            
            android.util.Log.d("WireAuto", "Waiting for Wire... (attempt $attempt, current package: ${rootNode?.packageName ?: "null"})")
            delay(1000) // Poll every 1 second
        }
        
        android.util.Log.w("WireAuto", "Timeout waiting for Wire app to be in foreground")
        return false
    }

    /**
     * Reset all state flags
     */
    private fun resetState() {
        android.util.Log.i("WireAuto", "Resetting state flags")
            isRunning.set(false)
        isWireOpened.set(false)
        isSendingInProgress.set(false)
    }

    /**
     * BROADCAST MESSAGE SENDER MODE
     * 
     * This function implements broadcast message sending to all contacts in Wire app.
     * Flow for each contact:
     * 1. Navigate to contacts list (if not already there)
     * 2. Filter out search input field - ONLY process actual contacts
     * 3. Find profile placeholder/avatar for each contact
     * 4. Click on profile placeholder to open conversation
     * 5. Wait for conversation screen to load
     * 6. Type and send the message
     * 7. Go back to contacts list
     * 8. Repeat for next contact until all contacts have received the message
     * 
     * Features:
     * - SKIPS search input field in contacts list (only tabs/clicks contacts)
     * - Tracks sent contacts by name to avoid duplicates
     * - Tracks sent contacts by index to avoid duplicates
     * - Detailed logging for each step
     * - Proper navigation back to contacts list after each message
     * - Processes ALL contacts until every contact has received the message
     */
    private suspend fun navigateAndSendMessages(message: String) {
        android.util.Log.i("WireAuto", "=== STEP 3: Starting navigateAndSendMessages (BROADCAST MODE) ===")
        android.util.Log.i("WireAuto", "State check: isWireOpened=${isWireOpened.get()}, isSendingInProgress=${isSendingInProgress.get()}")
        
        var contactsProcessed = 0
        var contactsSent = 0
        val maxContacts = 500 // Safety limit
        
        // State machine validation
        if (!isWireOpened.get() || !isSendingInProgress.get()) {
            android.util.Log.e("WireAuto", "State machine violation: Wire not opened or sending not in progress")
            sendErrorBroadcast("State machine error: Wire app not properly initialized")
            return
        }
        
        updateNotification("Waiting for Wire app to load...")
        sendProgressBroadcast("Waiting for Wire app to load...")
        android.util.Log.i("WireAuto", "Waiting for Wire app UI to stabilize...")
        delay(3000) // Increased to 3 seconds to allow slow UI rendering to finish

        // PERSISTENT ROOT NODE: Use retry helper to get root node
        var rootNode = getRootWithRetry(maxRetries = 3, delayMs = 500)
        if (rootNode == null) {
            android.util.Log.e("WireAuto", "Could not access Wire app after retries")
                sendErrorBroadcast("Could not access Wire app. Please ensure Wire is open and try again.")
                return
        }
        
        android.util.Log.i("WireAuto", "Wire app is accessible - package: ${rootNode.packageName}")

        // EXPLICIT FOCUS: Perform a small scroll or global action to refresh accessibility node tree
        android.util.Log.d("WireAuto", "Refreshing accessibility tree with global action...")
        try {
            // Try a small scroll action to refresh the tree
            val scrollableView = findScrollableView(rootNode)
            if (scrollableView != null && scrollableView.isScrollable) {
                scrollableView.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(500)
                android.util.Log.d("WireAuto", "Performed scroll to refresh accessibility tree")
            } else {
                // Fallback: perform a global action to refresh
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(300)
                performGlobalAction(GLOBAL_ACTION_BACK) // Go back forward if we went back
                delay(300)
                android.util.Log.d("WireAuto", "Performed global action to refresh accessibility tree")
            }
            // Refresh root after action using retry helper
            rootNode = getRootWithRetry(maxRetries = 3, delayMs = 500)
        } catch (e: Exception) {
            android.util.Log.w("WireAuto", "Could not refresh accessibility tree: ${e.message}")
        }
        
        if (rootNode == null) {
            rootNode = getRootWithRetry(maxRetries = 3, delayMs = 500)
            if (rootNode == null) {
                android.util.Log.e("WireAuto", "Could not get root node after refresh")
                sendErrorBroadcast("Could not access Wire app. Please ensure Wire is open and try again.")
                return
            }
        }

        updateNotification("Navigating to conversations...")
        sendProgressBroadcast("Navigating to conversations...")
        
        // UI INTERACTION FIX: Search for "Search conversations" bar as starting point if main list fails
        val searchBar = findSearchConversationsBar(rootNode)
        if (searchBar != null) {
            android.util.Log.d("WireAuto", "Found 'Search conversations' bar - using as reference point")
            // Click on search bar to ensure we're on the right screen - use gesture dispatch
            try {
                if (clickNodeWithGesture(searchBar)) {
                    delay(500)
                    // Dismiss search if it opened
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    delay(500)
                }
            } catch (e: Exception) {
                android.util.Log.d("WireAuto", "Could not interact with search bar: ${e.message}")
            }
            rootNode = getRootWithRetry(maxRetries = 3, delayMs = 500)
        }
        
        // Try to navigate to conversations/contacts list
        // Wire typically shows conversations by default, but we need to ensure we're on the right screen
        if (rootNode != null) {
            navigateToConversationsList(rootNode)
        }
        delay(4000) // Wait longer for navigation to complete

        // Refresh root after navigation using retry helper
        rootNode = getRootWithRetry(maxRetries = 3, delayMs = 500)
        if (rootNode == null) {
            android.util.Log.e("WireAuto", "Could not get root node after navigation")
            sendErrorBroadcast("Lost access to Wire app. Please try again.")
            return
        }
        
        // Log UI structure for debugging
        android.util.Log.d("WireAuto", "Wire app UI structure:")
        android.util.Log.d("WireAuto", "Root className: ${rootNode.className}")
        android.util.Log.d("WireAuto", "Root childCount: ${rootNode.childCount}")
        logUIStructure(rootNode, 0, 3) // Log first 3 levels
        
        // Get all contact/conversation items
        var contactItems = getAllContactItems(rootNode)
        
        if (contactItems.isEmpty()) {
            // Try scrolling to load more contacts
            android.util.Log.d("WireAuto", "No contacts found, trying to scroll...")
            try {
                // Find scrollable view and scroll down
                val scrollableView = findScrollableView(rootNode)
                if (scrollableView != null) {
                    // Use ACTION_SCROLL_FORWARD to scroll down in vertical lists
                    val scrolled = scrollableView.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    if (scrolled) {
                        android.util.Log.d("WireAuto", "Scrolled down in scrollable view")
                    } else {
                        android.util.Log.w("WireAuto", "Scroll action failed")
                    }
                } else {
                    android.util.Log.w("WireAuto", "No scrollable view found for scrolling")
                }
                delay(2000)
                rootNode = getRootWithRetry(maxRetries = 3, delayMs = 500)
                if (rootNode != null) {
                    contactItems = getAllContactItems(rootNode)
                    if (contactItems.isNotEmpty()) {
                        android.util.Log.d("WireAuto", "Found ${contactItems.size} contacts after scrolling")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("WireAuto", "Error scrolling: ${e.message}")
            }
            
            // Try one more time if still empty - wait longer for Wire to load
            if (contactItems.isEmpty()) {
                android.util.Log.w("WireAuto", "No contacts found, waiting longer and trying again...")
                delay(3000)
                rootNode = getRootWithRetry(maxRetries = 3, delayMs = 500)
                if (rootNode != null) {
                    contactItems = getAllContactItems(rootNode)
                    android.util.Log.d("WireAuto", "After longer wait: ${contactItems.size} contacts found")
                }
            }
            
            // GENERIC LIST INTERACTION: If still empty, try clicking first 3 children of scrollable view
            if (contactItems.isEmpty()) {
                android.util.Log.w("WireAuto", "No contacts found via standard methods, trying generic list interaction...")
                rootNode = getRootWithRetry(maxRetries = 3, delayMs = 500)
                if (rootNode != null) {
                    if (clickFirstScrollableChildren(rootNode!!, maxChildren = 3)) {
                        delay(2000) // Wait for UI to update
                        rootNode = getRootWithRetry(maxRetries = 3, delayMs = 500)
                        if (rootNode != null) {
                            contactItems = getAllContactItems(rootNode!!)
                            android.util.Log.d("WireAuto", "After generic interaction: ${contactItems.size} contacts found")
                        }
                    }
                }
            }
            
            if (contactItems.isEmpty()) {
                // Collect debug info about what we found, including class names
                val debugInfo = if (rootNode != null) collectDebugInfo(rootNode!!) else "Root node is null"
                val classNamesInfo = if (rootNode != null) collectClassNamesInfo(rootNode!!) else "Root node is null"
                
                val errorMsg = "No contacts found in Wire app.\n\n" +
                        "📋 Troubleshooting Steps:\n\n" +
                        "1. Open Wire app manually first\n" +
                        "2. Go to the Conversations/Chats screen\n" +
                        "3. Make sure you can see your contacts/conversations\n" +
                        "4. Return to this app and try again\n\n" +
                        "💡 Tips:\n" +
                        "- Wire must be on the main conversations screen\n" +
                        "- You need active conversations with contacts\n" +
                        "- Try scrolling in Wire to load all conversations\n\n" +
                        "🔍 Debug Info:\n" +
                        debugInfo + "\n\n" +
                        "📦 Found Elements (Class Names):\n" +
                        classNamesInfo
                
                updateNotification("No contacts found - see details in app")
                sendErrorBroadcast(errorMsg)
                return
            }
        }
        
        // NEW APPROACH: Find RecyclerView and identify actual conversation row items
        // Filter out UI elements like search bars, headers, FAB buttons
        if (rootNode == null) {
            android.util.Log.e("WireAuto", "Root node is null, cannot find RecyclerView")
            sendErrorBroadcast("Lost access to Wire app. Please try again.")
            return
        }
        val recyclerView = findRecyclerView(rootNode!!)
        val rowItems = if (recyclerView != null) {
            android.util.Log.d("WireAuto", "Found RecyclerView, identifying conversation row items...")
            val items = mutableListOf<AccessibilityNodeInfo>()
            val seenRowBounds = mutableSetOf<String>() // Track seen rows by position to avoid duplicates
            
            // Get all children and filter for actual conversation rows
            for (i in 0 until recyclerView.childCount) {
                val child = recyclerView.getChild(i)
                if (child != null) {
                    // Check if this is a real conversation row (not search bar, header, FAB, etc.)
                    if (isActualConversationRow(child)) {
                        // Find the actual row container
                        val rowContainer = findConversationRowContainer(child) ?: child
                        
                        // Get bounds to check for duplicates
                        val bounds = android.graphics.Rect()
                        rowContainer.getBoundsInScreen(bounds)
                        
                        // Use top position as key (rows are typically at different Y positions)
                        // Allow some tolerance for slight variations
                        val rowKey = "${bounds.top / 100}" // Group by 100px vertical position
                        
                        if (!seenRowBounds.contains(rowKey)) {
                            seenRowBounds.add(rowKey)
                            items.add(rowContainer)
                            val contactName = extractContactNameFromRow(rowContainer) ?: "Contact ${items.size}"
                            android.util.Log.d("WireAuto", "Conversation row ${items.size}: name='$contactName', className=${rowContainer.className}, clickable=${rowContainer.isClickable}, bounds=(${bounds.left},${bounds.top})")
                        } else {
                            android.util.Log.d("WireAuto", "Skipping duplicate row at position $i (same Y position)")
                        }
                    } else {
                        android.util.Log.d("WireAuto", "Skipping non-conversation item $i: className=${child.className}")
                    }
                }
            }
            
            // If no items found via direct children, try finding all clickable containers
            if (items.isEmpty()) {
                android.util.Log.d("WireAuto", "No direct conversation rows found, searching for clickable containers...")
                val allContainers = mutableListOf<AccessibilityNodeInfo>()
                findClickableContainersInRecyclerView(recyclerView, allContainers)
                
                // Filter to only actual conversation rows and deduplicate by position
                val filteredContainers = allContainers.filter { isActualConversationRow(it) }
                val uniqueContainers = filteredContainers.distinctBy { container ->
                    val bounds = android.graphics.Rect()
                    container.getBoundsInScreen(bounds)
                    "${bounds.top / 100}" // Group by vertical position
                }
                items.addAll(uniqueContainers)
                android.util.Log.d("WireAuto", "Found ${items.size} unique conversation containers after filtering")
            }
            
            items
        } else {
            android.util.Log.w("WireAuto", "No RecyclerView found, falling back to contact items list")
            // Deduplicate contact items by position
            contactItems.distinctBy { 
                val bounds = android.graphics.Rect()
                it.getBoundsInScreen(bounds)
                "${bounds.top / 100}" // Group by vertical position
            }
        }
        
        val totalContacts = rowItems.size
        android.util.Log.i("WireAuto", "=== STEP 4: Found $totalContacts contacts ===")
        android.util.Log.i("WireAuto", "Starting to send messages to $totalContacts contacts")
        android.util.Log.i("WireAuto", "State check: isWireOpened=${isWireOpened.get()}, isSendingInProgress=${isSendingInProgress.get()}")
        
        updateNotification("Found $totalContacts contacts. Sending messages...")
        sendProgressBroadcast("Found $totalContacts contacts. Sending messages...", 0)
        android.util.Log.i("WireAuto", "=== Starting to process $totalContacts contacts from top to bottom ===")
        
        val processedContactIndices = mutableSetOf<Int>() // Track processed contacts by index to avoid duplicates
        val sentContactNames = mutableSetOf<String>() // Track sent contacts by name to avoid duplicates
        val contactResults = mutableListOf<com.wireautomessenger.model.ContactResult>()
        
        for ((index, rowItem) in rowItems.withIndex()) {
            debugLog("EVENT", "--- Processing contact ${index + 1}/$totalContacts ---")
            android.util.Log.d("WireAuto", "--- Processing contact ${index + 1}/$totalContacts ---")
            if (contactsProcessed >= maxContacts) {
                debugLog("WARN", "Reached max contacts limit ($maxContacts), stopping")
                android.util.Log.i("WireAuto", "Reached max contacts limit ($maxContacts), stopping")
                break
            }
            
            try {
                // Extract contact name from the row item
                debugLog("DATA", "Extracting contact name from row item at index $index")
                val contactName = extractContactNameFromRow(rowItem) ?: "Contact ${index + 1}"
                val normalizedContactName = contactName.trim().lowercase()
                debugLog("DATA", "Contact name extracted: '$contactName' (normalized: '$normalizedContactName')")
                
                // Skip if already processed by index (avoid duplicates)
                if (processedContactIndices.contains(index)) {
                    android.util.Log.d("WireAuto", "Skipping duplicate contact at index $index: $contactName")
                    contactResults.add(com.wireautomessenger.model.ContactResult(
                        name = contactName,
                        status = com.wireautomessenger.model.ContactStatus.SKIPPED,
                        errorMessage = "Duplicate contact (already processed at this position)",
                        position = index + 1
                    ))
                    sendContactUpdate(contactName, "skipped", index + 1, "Duplicate contact")
                    continue
                }
                
                // Skip if already sent to this contact by name (avoid duplicates)
                if (sentContactNames.contains(normalizedContactName)) {
                    android.util.Log.i("WireAuto", "⚠️ SKIPPING DUPLICATE: Already sent message to '$contactName' (normalized: '$normalizedContactName')")
                    contactResults.add(com.wireautomessenger.model.ContactResult(
                        name = contactName,
                        status = com.wireautomessenger.model.ContactStatus.SKIPPED,
                        errorMessage = "Duplicate contact name (already sent message to this contact)",
                        position = index + 1
                    ))
                    sendContactUpdate(contactName, "skipped", index + 1, "Already sent to this contact")
                    continue
                }
                
                contactsProcessed++
                processedContactIndices.add(index)
                
                android.util.Log.i("WireAuto", "=== Processing contact $contactsProcessed/$totalContacts: $contactName ===")
                android.util.Log.d("WireAuto", "Row item: className=${rowItem.className}, clickable=${rowItem.isClickable}, childCount=${rowItem.childCount}")
                android.util.Log.d("WireAuto", "State check: isWireOpened=${isWireOpened.get()}, isSendingInProgress=${isSendingInProgress.get()}")
                
                updateNotification("Sending to contact $contactsProcessed/$totalContacts: $contactName...")
                sendProgressBroadcast("Sending to contact $contactsProcessed/$totalContacts: $contactName...", contactsSent)
                
                // Check if we're still in Wire app (NO relaunch - state machine prevents this) - use retry helper
                var currentRoot = getRootWithRetry(maxRetries = 3, delayMs = 500)
                if (currentRoot == null) {
                    android.util.Log.w("WireAuto", "Not in Wire app - checking state machine")
                    
                    // State machine check: If Wire was opened but we lost access, wait and retry
                    if (isWireOpened.get() && isSendingInProgress.get()) {
                        android.util.Log.w("WireAuto", "Wire was opened but lost access - waiting for recovery...")
                        delay(2000)
                        currentRoot = getRootWithRetry(maxRetries = 3, delayMs = 500)
                    
                        if (currentRoot == null) {
                            android.util.Log.w("WireAuto", "Still not in Wire app after wait - marking contact as failed")
                        contactResults.add(com.wireautomessenger.model.ContactResult(
                            name = contactName,
                            status = com.wireautomessenger.model.ContactStatus.FAILED,
                                errorMessage = "Lost access to Wire app during sending",
                                position = index + 1
                            ))
                            sendContactUpdate(contactName, "failed", index + 1, "Lost access to Wire app")
                            continue
                        }
                    } else {
                        // State machine violation - should not happen
                        android.util.Log.e("WireAuto", "State machine violation: Wire not opened but trying to send")
                        contactResults.add(com.wireautomessenger.model.ContactResult(
                            name = contactName,
                            status = com.wireautomessenger.model.ContactStatus.FAILED,
                            errorMessage = "Wire app not accessible (state machine error)",
                            position = index + 1
                        ))
                        sendContactUpdate(contactName, "failed", index + 1, "Wire app not accessible")
                        continue
                    }
                }
                
                // Ensure we're on the contacts list, not in a conversation
                // Check if we're in a conversation by looking for message input
                val messageInputExists = findMessageInput(currentRoot) != null
                if (messageInputExists) {
                    android.util.Log.d("WireAuto", "Currently in a conversation, going back to contacts list...")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    delay(3000) // Wait longer for navigation
                    currentRoot = getRootWithRetry(maxRetries = 3, delayMs = 500)
                    
                    // Verify we're still in Wire app and on the list (NO relaunch - state machine)
                    if (currentRoot == null) {
                        android.util.Log.w("WireAuto", "Lost access to Wire after going back - waiting for recovery...")
                        // Wait and retry (NO relaunch - state machine prevents infinite loops)
                        delay(2000)
                        currentRoot = getRootWithRetry(maxRetries = 3, delayMs = 500)
                        
                        if (currentRoot == null) {
                            android.util.Log.w("WireAuto", "Still not in Wire app after wait - marking contact as failed")
                            contactResults.add(com.wireautomessenger.model.ContactResult(
                                name = contactName,
                                status = com.wireautomessenger.model.ContactStatus.FAILED,
                                errorMessage = "Lost access to Wire app",
                                position = index + 1
                            ))
                            sendContactUpdate(contactName, "failed", index + 1, "Lost access to Wire app")
                            continue
                        }
                    }
                    
                    // Double-check we're not still in a conversation
                    val stillInConversation = findMessageInput(currentRoot) != null
                    if (stillInConversation) {
                        android.util.Log.w("WireAuto", "Still in conversation after going back, trying once more...")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        delay(2000)
                        currentRoot = getRootWithRetry(maxRetries = 3, delayMs = 500)
                    }
                }
                
                // PACKAGE VERIFICATION: Verify we're still in Wire app before interacting
                if (currentRoot == null || currentRoot.packageName?.toString() != WIRE_PACKAGE) {
                    android.util.Log.w("WireAuto", "Package verification failed: expected $WIRE_PACKAGE, got ${currentRoot?.packageName}")
                    contactResults.add(com.wireautomessenger.model.ContactResult(
                        name = contactName,
                        status = com.wireautomessenger.model.ContactStatus.FAILED,
                        errorMessage = "Package verification failed - not in Wire app",
                        position = index + 1
                    ))
                    sendContactUpdate(contactName, "failed", index + 1, "Package verification failed")
                    continue
                }
                
                // Refresh the row item from current root (it might be stale)
                // Get fresh RecyclerView and find the row at this index
                var refreshedRowItem: AccessibilityNodeInfo? = null
                if (currentRoot != null) {
                    val freshRecyclerView = findRecyclerView(currentRoot!!)
                    
                    if (freshRecyclerView != null && index < freshRecyclerView.childCount) {
                        // Get fresh child at this index
                        val child = freshRecyclerView.getChild(index)
                        if (child != null) {
                            // Find the actual row container
                            refreshedRowItem = findConversationRowContainer(child) ?: child
                        }
                    }
                    
                    // Fallback: try to find by contact name
                    if (refreshedRowItem == null) {
                        val cleanContactName = contactName.removePrefix("You: ").trim()
                        refreshedRowItem = findContactNodeByText(currentRoot!!, contactName)
                            ?: findContactNodeByText(currentRoot!!, cleanContactName)
                    }
                }
                
                // Final fallback: use original row item
                if (refreshedRowItem == null) {
                    refreshedRowItem = rowItem
                    android.util.Log.w("WireAuto", "Could not refresh row item, using original (may be stale)")
                }
                
                // BROADCAST MODE: Find and click profile placeholder/avatar instead of entire row
                debugLog("CLICK", "BROADCAST MODE: Looking for profile placeholder/avatar for contact: $contactName")
                android.util.Log.i("WireAuto", "📸 BROADCAST MODE: Looking for profile placeholder/avatar for contact: $contactName")
                debugLog("ACTION", "STEP 4.${contactsProcessed}.1: Finding profile placeholder for contact at index $index: $contactName")
                android.util.Log.i("WireAuto", "STEP 4.${contactsProcessed}.1: Finding profile placeholder for contact at index $index: $contactName")
                
                // Find profile placeholder/avatar in the contact row
                debugLog("SEARCH", "Searching for profile placeholder in row item")
                val profilePlaceholder = findProfilePlaceholderInRow(refreshedRowItem)
                debugLog("SEARCH", "Profile placeholder search result: ${if (profilePlaceholder != null) "FOUND" else "NOT FOUND"}")
                
                val targetNode: AccessibilityNodeInfo
                if (profilePlaceholder != null) {
                    android.util.Log.i("WireAuto", "✓ Found profile placeholder/avatar for $contactName")
                    android.util.Log.d("WireAuto", "Profile placeholder: className=${profilePlaceholder.className}, clickable=${profilePlaceholder.isClickable}, bounds=${getBoundsString(profilePlaceholder)}")
                    targetNode = profilePlaceholder
                } else {
                    android.util.Log.w("WireAuto", "⚠️ Profile placeholder not found, falling back to clicking entire row for: $contactName")
                    targetNode = refreshedRowItem
                }
                
                // Get bounds for gesture dispatch (most reliable method)
                val bounds = android.graphics.Rect()
                targetNode.getBoundsInScreen(bounds)
                
                if (bounds.width() <= 0 || bounds.height() <= 0) {
                    android.util.Log.w("WireAuto", "Invalid bounds for target node, cannot click")
                    contactResults.add(com.wireautomessenger.model.ContactResult(
                        name = contactName,
                        status = com.wireautomessenger.model.ContactStatus.FAILED,
                        errorMessage = "Invalid bounds for profile placeholder/contact row",
                        position = index + 1
                    ))
                    sendContactUpdate(contactName, "failed", index + 1, "Invalid bounds")
                    continue
                }
                
                val centerX = bounds.centerX().toFloat()
                val centerY = bounds.centerY().toFloat()
                
                android.util.Log.d("WireAuto", "Target node bounds: left=${bounds.left}, top=${bounds.top}, right=${bounds.right}, bottom=${bounds.bottom}")
                android.util.Log.d("WireAuto", "Click center: ($centerX, $centerY)")
                debugLog("CLICK", "STEP 4.${contactsProcessed}.2: Attempting to click profile placeholder/contact for: $contactName")
                debugLog("CLICK", "Click target bounds: left=${bounds.left}, top=${bounds.top}, width=${bounds.width()}, height=${bounds.height()}")
                debugLog("CLICK", "Click center coordinates: ($centerX, $centerY)")
                android.util.Log.i("WireAuto", "STEP 4.${contactsProcessed}.2: Clicking profile placeholder/contact for: $contactName")
                
                // Try multiple click methods - prioritize gesture dispatch as it's most reliable
                var clicked = false
                
                // Method 1: Use gesture dispatch (simulate touch) - MOST RELIABLE
                debugLog("CLICK", "Method 1: Attempting gesture dispatch click at ($centerX, $centerY)")
                try {
                    val path = android.graphics.Path()
                    path.moveTo(centerX, centerY)
                    
                    val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
                        path, 0, 150 // 150ms tap
                    )
                    
                    val gesture = android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(stroke)
                        .build()
                    
                    var gestureCompleted = false
                    clicked = dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                            gestureCompleted = true
                            debugLog("CLICK", "Gesture dispatch completed successfully")
                            android.util.Log.d("WireAuto", "Gesture completed successfully")
                        }
                        override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                            debugLog("WARN", "Gesture dispatch was cancelled")
                            android.util.Log.w("WireAuto", "Gesture was cancelled")
                        }
                    }, null)
                    
                    debugLog("CLICK", "Method 1 - Gesture dispatch result: $clicked")
                    android.util.Log.d("WireAuto", "Method 1 - Gesture dispatch on profile placeholder: $clicked")
                    if (clicked) {
                        debugLog("CLICK", "Waiting 800ms for gesture to complete and UI to respond")
                        delay(800) // Wait for gesture to complete and UI to respond
                    }
                } catch (e: Exception) {
                    debugLog("ERROR", "Gesture dispatch failed: ${e.message}", e)
                    android.util.Log.e("WireAuto", "Gesture dispatch failed: ${e.message}")
                }
                
                // Method 2: USE GESTURE DISPATCH FOR EVERYTHING - Use gesture dispatch instead of ACTION_CLICK
                if (!clicked) {
                    clicked = clickNodeWithGesture(targetNode)
                    android.util.Log.d("WireAuto", "Method 2 - Gesture dispatch on target node: $clicked")
                    if (clicked) delay(500)
                }
                
                // Method 2.5: Fallback to ACTION_CLICK if gesture failed
                if (!clicked && targetNode.isClickable) {
                    clicked = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    android.util.Log.d("WireAuto", "Method 2.5 - Fallback ACTION_CLICK on target node: $clicked")
                    if (clicked) delay(500)
                }
                
                // Method 3: Find and click clickable parent if target node itself not clickable
                if (!clicked) {
                    var parent = targetNode.parent
                    var depth = 0
                    while (parent != null && depth < 5 && !clicked) {
                        if (parent.isClickable) {
                            clicked = clickNodeWithGesture(parent)
                            android.util.Log.d("WireAuto", "Method 3 - Gesture dispatch on clickable parent at depth $depth: $clicked")
                            if (clicked) {
                                delay(500)
                                break
                            }
                        }
                        parent = parent.parent
                        depth++
                    }
                }
                
                // Method 4: Fallback to clicking entire row if profile placeholder click failed
                if (!clicked && profilePlaceholder != null) {
                    android.util.Log.w("WireAuto", "Profile placeholder click failed, trying entire row as fallback")
                    clicked = clickNodeWithGesture(refreshedRowItem)
                    android.util.Log.d("WireAuto", "Method 4 - Gesture dispatch on entire row (fallback): $clicked")
                    if (clicked) delay(500)
                }
                
                if (!clicked) {
                    android.util.Log.w("WireAuto", "All click methods failed for contact: $contactName")
                    contactResults.add(com.wireautomessenger.model.ContactResult(
                        name = contactName,
                        status = com.wireautomessenger.model.ContactStatus.FAILED,
                        errorMessage = "Could not click profile placeholder/contact after trying multiple methods",
                        position = index + 1
                    ))
                    sendContactUpdate(contactName, "failed", index + 1, "Could not click profile placeholder/contact")
                    continue
                }
                
                android.util.Log.i("WireAuto", "✓ Successfully clicked profile placeholder/contact for: $contactName")
                
                android.util.Log.d("WireAuto", "Contact Found: $contactName")
                android.util.Log.i("WireAuto", "STEP 4.${contactsProcessed}.2: Waiting for conversation to open...")
                
                // Add 2-second delay after opening contact's chat to allow UI to load
                    delay(2000)
                
                // DON'T AUTO-EXIT: Increase timeout to 10 seconds before giving up
                android.util.Log.d("WireAuto", "Waiting up to 10 seconds for conversation to open...")
                currentRoot = getRootWithRetry(maxRetries = 10, delayMs = 1000) // 10 seconds total
                
                if (currentRoot == null) {
                    android.util.Log.w("WireAuto", "Not in Wire app after clicking contact: $contactName after 10 seconds")
                        contactResults.add(com.wireautomessenger.model.ContactResult(
                            name = contactName,
                            status = com.wireautomessenger.model.ContactStatus.FAILED,
                        errorMessage = "Lost access after clicking contact (10s timeout)",
                            position = index + 1
                        ))
                        sendContactUpdate(contactName, "failed", index + 1, "Lost access after clicking")
                        continue
                }

                // PACKAGE VERIFICATION: Verify we're still in Wire app before looking for message input
                if (currentRoot == null || currentRoot.packageName?.toString() != WIRE_PACKAGE) {
                    android.util.Log.w("WireAuto", "Package verification failed before message input search: expected $WIRE_PACKAGE, got ${currentRoot?.packageName}")
                    contactResults.add(com.wireautomessenger.model.ContactResult(
                        name = contactName,
                        status = com.wireautomessenger.model.ContactStatus.FAILED,
                        errorMessage = "Package verification failed - not in Wire app",
                        position = index + 1
                    ))
                    sendContactUpdate(contactName, "failed", index + 1, "Package verification failed")
                    continue
                }
                
                // DON'T AUTO-EXIT: Wait up to 10 seconds for message input to appear
                android.util.Log.d("WireAuto", "Waiting up to 10 seconds for message input to appear...")
                var messageInput = findMessageInput(currentRoot!!)
                var inputAttempts = 0
                while (messageInput == null && inputAttempts < 10) {
                    android.util.Log.d("WireAuto", "Message input not found, waiting... (attempt ${inputAttempts + 1}/10)")
                    delay(1000)
                    currentRoot = getRootWithRetry(maxRetries = 3, delayMs = 500)
                    // PACKAGE VERIFICATION: Verify package before each attempt
                    if (currentRoot != null && currentRoot.packageName?.toString() == WIRE_PACKAGE) {
                        messageInput = findMessageInput(currentRoot!!)
                    } else {
                        android.util.Log.w("WireAuto", "Package verification failed during message input search")
                        break
                    }
                    inputAttempts++
                }

                if (messageInput == null) {
                    android.util.Log.w("WireAuto", "Message input not found for contact: $contactName")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    delay(2000)
                    contactResults.add(com.wireautomessenger.model.ContactResult(
                        name = contactName,
                        status = com.wireautomessenger.model.ContactStatus.FAILED,
                        errorMessage = "Message input not found - not in conversation view",
                        position = index + 1
                    ))
                    sendContactUpdate(contactName, "failed", index + 1, "Message input not found")
                    continue
                }

                debugLog("TEXT", "STEP 4.${contactsProcessed}.3: Found message input, preparing to type message")
                debugLog("TEXT", "Message to send: ${message.take(50)}${if (message.length > 50) "..." else ""}")
                android.util.Log.i("WireAuto", "STEP 4.${contactsProcessed}.3: Found message input, preparing to type message...")

                // Handle keyboard blocking - scroll if needed
                debugLog("NAVIGATION", "Checking if keyboard is blocking message input")
                try {
                    val inputBounds = android.graphics.Rect()
                    messageInput.getBoundsInScreen(inputBounds)
                    val screenBounds = android.graphics.Rect()
                    if (currentRoot == null) {
                        android.util.Log.w("WireAuto", "Current root is null, cannot check keyboard blocking")
                    } else {
                        currentRoot.getBoundsInScreen(screenBounds)
                        
                        // Check if input is in bottom 30% of screen (likely blocked by keyboard)
                        val screenHeight = screenBounds.height()
                        val inputBottom = inputBounds.bottom
                        val bottomThreshold = screenHeight * 0.7
                        
                        if (inputBottom > bottomThreshold) {
                            android.util.Log.d("WireAuto", "Message input may be blocked by keyboard, attempting to scroll...")
                            // Try to scroll the message input into view
                            if (messageInput.isScrollable) {
                                messageInput.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                                delay(500)
                            } else {
                                // Find scrollable parent and scroll
                                var parent = messageInput.parent
                                var depth = 0
                                while (parent != null && depth < 5) {
                                    if (parent.isScrollable) {
                                        parent.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                                        delay(500)
                                        android.util.Log.d("WireAuto", "Scrolled parent at depth $depth to reveal input")
                                        break
                                    }
                                    parent = parent.parent
                                    depth++
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.d("WireAuto", "Could not handle keyboard blocking: ${e.message}")
                }

                // FORCE FOCUS BEFORE TYPING: Perform ACTION_CLICK and ACTION_FOCUS before ACTION_SET_TEXT
                android.util.Log.d("WireAuto", "Force focusing message input before typing...")
                try {
                    // First, click on the input field using gesture dispatch
                    if (clickNodeWithGesture(messageInput)) {
                        android.util.Log.d("WireAuto", "Clicked message input via gesture")
                        delay(500)
                    }
                    
                    // Then, perform ACTION_FOCUS
                    messageInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    android.util.Log.d("WireAuto", "Focused message input")
                    delay(500)
                } catch (e: Exception) {
                    android.util.Log.w("WireAuto", "Could not force focus message input: ${e.message}")
                }

                // Clear any existing text first
                try {
                    // Try to select all and delete
                    val bundleClear = android.os.Bundle()
                    bundleClear.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                    messageInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundleClear)
                    delay(300)
                } catch (e: Exception) {
                    android.util.Log.d("WireAuto", "Could not clear text: ${e.message}")
                }

                // Type message - use ACTION_SET_TEXT to set the entire message at once
                debugLog("TEXT", "STEP 4.${contactsProcessed}.4: Setting message text in input field")
                debugLog("TEXT", "Message content: $message")
                android.util.Log.d("WireAuto", "Text Entered: $message")
                android.util.Log.i("WireAuto", "STEP 4.${contactsProcessed}.4: Setting message text: $message")
                val bundle = android.os.Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
                val textSet = messageInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                debugLog("TEXT", "ACTION_SET_TEXT result: $textSet")
                android.util.Log.i("WireAuto", "Message text set: $textSet")
                
                // Wait for text to be set and send button to be enabled (random delay 1-3 sec)
                val typingDelay = (1000..3000).random()
                android.util.Log.d("WireAuto", "Waiting ${typingDelay}ms for send button to be enabled...")
                delay(typingDelay.toLong())

                // Refresh root after typing using retry helper
                currentRoot = getRootWithRetry(maxRetries = 3, delayMs = 500)
                if (currentRoot == null) {
                    android.util.Log.w("WireAuto", "Lost access to Wire after typing: $contactName")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    delay(1000)
                    contactResults.add(com.wireautomessenger.model.ContactResult(
                        name = contactName,
                        status = com.wireautomessenger.model.ContactStatus.FAILED,
                        errorMessage = "Lost access after typing",
                        position = index + 1
                    ))
                    sendContactUpdate(contactName, "failed", index + 1, "Lost access after typing")
                    continue
                }

                // Find and click send button - enhanced detection and verification
                var messageSent = false
                debugLog("CLICK", "STEP 4.${contactsProcessed}.5: Looking for send button")
                android.util.Log.i("WireAuto", "STEP 4.${contactsProcessed}.5: Looking for send button...")
                
                // DON'T AUTO-EXIT: Try up to 10 seconds to find send button
                var sendButton: AccessibilityNodeInfo? = null
                debugLog("SEARCH", "Attempting to find send button (max 10 attempts)")
                for (attempt in 1..10) { // 10 attempts = 10 seconds
                    debugLog("SEARCH", "Send button search attempt $attempt/10")
                    currentRoot = getRootWithRetry(maxRetries = 3, delayMs = 500)
                    if (currentRoot == null) {
                        android.util.Log.w("WireAuto", "Lost access to Wire while finding send button (attempt $attempt)")
                        delay(1000)
                        continue
                    }
                    
                    // Refresh message input
                    messageInput = findMessageInput(currentRoot)
                    if (messageInput == null) {
                        android.util.Log.w("WireAuto", "Message input not found on attempt $attempt")
                        delay(1000)
                        continue
                    }
                    
                    // Strategy 1: Standard send button detection
                    sendButton = findSendButton(currentRoot)
                    
                    // Strategy 2: Find button near message input as fallback
                    if (sendButton == null) {
                        android.util.Log.d("WireAuto", "Send button not found by standard methods (attempt $attempt), trying near input...")
                        sendButton = findSendButtonNearInput(currentRoot, messageInput)
                        if (sendButton != null) {
                            sendButton = getClickableParent(sendButton) // Ensure we get clickable parent
                            android.util.Log.i("WireAuto", "Send button found near input: className=${sendButton?.className}")
                        }
                    }
                    
                    // Strategy 3: Last resort - find ANY clickable button in bottom-right area
                    if (sendButton == null) {
                        android.util.Log.d("WireAuto", "Trying last resort: finding any clickable button in bottom-right area...")
                        val allClickable = mutableListOf<AccessibilityNodeInfo>()
                        findAllClickableButtons(currentRoot, allClickable)
                        
                        // Filter buttons in bottom-right area (send button is usually there)
                        val screenBounds = android.graphics.Rect()
                        currentRoot.getBoundsInScreen(screenBounds)
                        val screenWidth = screenBounds.width()
                        val screenHeight = screenBounds.height()
                        
                        val bottomRightButtons = allClickable.filter { button ->
                            val bounds = android.graphics.Rect()
                            button.getBoundsInScreen(bounds)
                            // Bottom-right area: right 30% of screen, bottom 20%
                            bounds.right > screenWidth * 0.7 && bounds.bottom > screenHeight * 0.8
                        }
                        
                        if (bottomRightButtons.isNotEmpty()) {
                            // Prefer ImageButton or buttons with no text (send button is usually an icon)
                            val iconButton = bottomRightButtons.firstOrNull { button ->
                                val className = button.className?.toString() ?: ""
                                val text = button.text?.toString()?.trim() ?: ""
                                className.contains("ImageButton", ignoreCase = true) || 
                                className.contains("Image", ignoreCase = true) ||
                                text.isEmpty()
                            }
                            sendButton = iconButton ?: bottomRightButtons.last()
                            android.util.Log.i("WireAuto", "Found button in bottom-right area: className=${sendButton?.className}")
                        }
                    }
                    
                    if (sendButton != null) {
                        android.util.Log.i("WireAuto", "Send button found on attempt $attempt")
                        break
                    }
                    
                    if (attempt < 5) {
                        val retryDelay = (1000..2000).random() // Random delay 1-2 seconds
                        android.util.Log.d("WireAuto", "Send button not found, waiting ${retryDelay}ms before retry...")
                        delay(retryDelay.toLong())
                    }
                }
                
                // Try to send the message
                if (sendButton != null) {
                    android.util.Log.i("WireAuto", "STEP 4.${contactsProcessed}.6: Send button found - attempting to click...")
                    android.util.Log.i("WireAuto", "Send button: className=${sendButton.className}, clickable=${sendButton.isClickable}, bounds=${getBoundsString(sendButton)}")
                    
                    // Get bounds for gesture dispatch
                    val sendBounds = android.graphics.Rect()
                    sendButton.getBoundsInScreen(sendBounds)
                    
                    if (sendBounds.width() <= 0 || sendBounds.height() <= 0) {
                        android.util.Log.w("WireAuto", "Invalid send button bounds")
                        messageSent = false
                    } else {
                        // Step 1: Focus on send button (if supported)
                        try {
                            if (sendButton.isFocusable) {
                                sendButton.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                                android.util.Log.d("WireAuto", "Focused on send button")
                                delay(300)
                            }
                        } catch (e: Exception) {
                            android.util.Log.d("WireAuto", "Focus action not supported: ${e.message}")
                        }
                        
                        // Step 2: Click send button - try multiple methods
                        var clicked = false
                        
                        android.util.Log.d("WireAuto", "Send Clicked: Attempting to click send button")
                        
                        // Method 1: Use gesture dispatch instead of ACTION_CLICK (harder for Wire to block)
                        clicked = clickNodeWithGesture(sendButton)
                        android.util.Log.d("WireAuto", "Send Clicked: Method 1 - Gesture dispatch on send button: $clicked")
                        android.util.Log.i("WireAuto", "Method 1 - Gesture dispatch on send button: $clicked")
                        if (clicked) {
                            val clickDelay = (1000..3000).random()
                            delay(clickDelay.toLong())
                        }
                        
                        // Fallback: Try ACTION_CLICK if gesture failed
                        if (!clicked && sendButton.isClickable) {
                            clicked = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            android.util.Log.d("WireAuto", "Send Clicked: Method 1.5 - Fallback ACTION_CLICK: $clicked")
                            if (clicked) {
                                val clickDelay = (1000..3000).random()
                                delay(clickDelay.toLong())
                            }
                        }
                        
                        // Method 2: Gesture dispatch (simulate touch) - MOST RELIABLE
                        if (!clicked) {
                            try {
                                val centerX = sendBounds.centerX().toFloat()
                                val centerY = sendBounds.centerY().toFloat()
                                
                                val path = android.graphics.Path()
                                path.moveTo(centerX, centerY)
                                
                                val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
                                    path, 0, 200 // 200ms tap
                                )
                                
                                val gesture = android.accessibilityservice.GestureDescription.Builder()
                                    .addStroke(stroke)
                                    .build()
                                
                                clicked = dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                                    override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                                        android.util.Log.i("WireAuto", "Send button gesture completed")
                                    }
                                    override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                                        android.util.Log.w("WireAuto", "Send button gesture cancelled")
                                    }
                                }, null)
                                
                                android.util.Log.i("WireAuto", "Method 2 - Gesture dispatch on send button: $clicked")
                                if (clicked) {
                                    val gestureDelay = (1000..3000).random()
                                    delay(gestureDelay.toLong())
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("WireAuto", "Gesture dispatch failed: ${e.message}")
                            }
                        }
                        
                        // Method 3: Try clicking parent if node itself not clickable
                        if (!clicked) {
                            var parent = sendButton.parent
                            var depth = 0
                            while (parent != null && depth < 5 && !clicked) {
                                if (parent.isClickable) {
                                    clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    android.util.Log.i("WireAuto", "Method 3 - Clicked send button parent at depth $depth: $clicked")
                                    if (clicked) {
                                        val parentClickDelay = (1000..3000).random()
                                        delay(parentClickDelay.toLong())
                                    }
                                    break
                                }
                                parent = parent.parent
                                depth++
                            }
                        }
                        
                        // Step 3: Verify click success - STRICT VERIFICATION
                        if (clicked) {
                            android.util.Log.i("WireAuto", "Send button clicked - verifying message was sent (strict check)...")
                            
                            // Wait longer for UI to update and message to actually send
                            val verificationDelay = (2000..3500).random()
                            delay(verificationDelay.toLong())
                            
                            // Refresh root to check verification - try multiple times
                            var verificationPassed = false
                            for (verifyAttempt in 1..3) {
                                currentRoot = getRootWithRetry(maxRetries = 3, delayMs = 500)
                                if (currentRoot != null) {
                                    val refreshedInput = findMessageInput(currentRoot)
                                    val inputText = refreshedInput?.text?.toString()?.trim() ?: ""
                                    
                                    // STRICT: Input must be cleared (empty) for message to be considered sent
                                    if (inputText.isEmpty()) {
                                        // Input cleared - message was sent!
                                        messageSent = true
                                        verificationPassed = true
                                        android.util.Log.i("WireAuto", "Send confirmed (attempt $verifyAttempt): Input box is empty - message sent successfully!")
                                        break
                                    } else if (inputText != message) {
                                        // Input changed but not empty - might have sent, wait a bit more
                                        android.util.Log.d("WireAuto", "Input text changed but not empty: '$inputText', waiting more...")
                                        delay(1000)
                                        continue
                                    } else {
                                        // Input still has the same message - message NOT sent
                                        android.util.Log.w("WireAuto", "Input still contains message (attempt $verifyAttempt): '$inputText' - message may not have been sent")
                                        if (verifyAttempt < 3) {
                                            delay(1500)
                                            continue
                                        } else {
                                            // After 3 attempts, if input still has message, consider it failed
                                            messageSent = false
                                            android.util.Log.e("WireAuto", "Send verification FAILED: Input still contains message after multiple checks")
                                        }
                                    }
                                } else {
                                    android.util.Log.w("WireAuto", "Lost access during verification (attempt $verifyAttempt)")
                                    if (verifyAttempt < 3) {
                                        delay(1000)
                                        continue
                                    }
                                }
                            }
                            
                            if (!verificationPassed && !messageSent) {
                                android.util.Log.e("WireAuto", "Send verification failed - message was NOT sent for contact: $contactName")
                            }
                        } else {
                            android.util.Log.w("WireAuto", "Could not click send button for contact: $contactName")
                        }
                    }
                } else {
                    android.util.Log.w("WireAuto", "Send button not found for contact: $contactName")
                }
                
                // Log final result
                if (messageSent) {
                    android.util.Log.i("WireAuto", "✓ Send confirmed for: $contactName")
                } else {
                    android.util.Log.e("WireAuto", "✗ Send failed for: $contactName")
                }
                
                // Track result for this contact
                if (messageSent) {
                    contactsSent++
                    sentContactNames.add(normalizedContactName) // Track sent contact by name to avoid duplicates
                    debugLog("SUCCESS", "✅ MESSAGE SENT SUCCESSFULLY to contact: $contactName")
                    debugLog("SUCCESS", "Contact position: ${index + 1}, Progress: $contactsSent/$totalContacts")
                    debugLog("SUCCESS", "Total contacts sent so far: $contactsSent out of $totalContacts")
                    android.util.Log.i("WireAuto", "✅ ✓✓✓ MESSAGE SENT SUCCESSFULLY ✓✓✓ ✅")
                    android.util.Log.i("WireAuto", "📤 Contact: $contactName (Position: ${index + 1})")
                    android.util.Log.i("WireAuto", "📊 Progress: $contactsSent/$totalContacts contacts sent")
                    android.util.Log.i("WireAuto", "✓ Message successfully sent to contact $contactsSent/$totalContacts: $contactName")
                    contactResults.add(com.wireautomessenger.model.ContactResult(
                        name = contactName,
                        status = com.wireautomessenger.model.ContactStatus.SENT,
                        position = index + 1
                    ))
                    sendContactUpdate(contactName, "sent", index + 1, null)
                } else {
                    android.util.Log.w("WireAuto", "✗ Failed to send message to contact: $contactName")
                    contactResults.add(com.wireautomessenger.model.ContactResult(
                        name = contactName,
                        status = com.wireautomessenger.model.ContactStatus.FAILED,
                        errorMessage = "Send button not found or not clickable",
                        position = index + 1
                    ))
                    sendContactUpdate(contactName, "failed", index + 1, "Send button not found or not clickable")
                }
                
                updateNotification("Sent to $contactsSent/$totalContacts contacts...")
                sendProgressBroadcast("Sent to $contactsSent/$totalContacts contacts...", contactsSent)

                // BROADCAST MODE: Go back to contacts list after sending (or if failed)
                // This ensures we're ready for the next contact
                debugLog("NAVIGATION", "BROADCAST MODE: Returning to contacts list after processing: $contactName")
                debugLog("NAVIGATION", "STEP 4.${contactsProcessed}.7: Going back to contacts list")
                android.util.Log.i("WireAuto", "🔄 BROADCAST MODE: Returning to contacts list after processing: $contactName")
                android.util.Log.i("WireAuto", "STEP 4.${contactsProcessed}.7: Going back to contacts list...")
                currentRoot = getRootWithRetry(maxRetries = 3, delayMs = 500)
                debugLog("NAVIGATION", "Root node retrieved after back navigation: ${if (currentRoot != null) "SUCCESS" else "FAILED"}")
                
                if (currentRoot != null) {
                    // Check if we're still in conversation (message input exists)
                    val stillInConversation = findMessageInput(currentRoot) != null
                    if (stillInConversation) {
                        android.util.Log.d("WireAuto", "Still in conversation, performing back gesture to return to contact list...")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        val backDelay = (2000..4000).random() // Random delay 2-4 seconds
                        android.util.Log.d("WireAuto", "Waiting ${backDelay}ms for navigation...")
                        delay(backDelay.toLong())
                        
                        // Verify we're back on the list
                        currentRoot = getRootWithRetry(maxRetries = 3, delayMs = 500)
                        if (currentRoot != null) {
                            val stillInConv = findMessageInput(currentRoot) != null
                            if (stillInConv) {
                                android.util.Log.w("WireAuto", "Still in conversation after back, trying once more...")
                                performGlobalAction(GLOBAL_ACTION_BACK)
                                delay(2000)
                                
                                // One more check
                                currentRoot = getRootWithRetry(maxRetries = 3, delayMs = 500)
                                if (currentRoot != null) {
                                    val stillInConv2 = findMessageInput(currentRoot) != null
                                    if (stillInConv2) {
                                        android.util.Log.e("WireAuto", "Failed to exit conversation after multiple attempts")
                                    } else {
                                        android.util.Log.d("WireAuto", "Successfully returned to contacts list")
                                    }
                                }
                            } else {
                                android.util.Log.d("WireAuto", "Successfully returned to contacts list")
                            }
                        }
                    } else {
                        android.util.Log.d("WireAuto", "Already on contacts list, no need to go back")
                    }
                } else {
                    android.util.Log.w("WireAuto", "Not in Wire app after sending - waiting for recovery...")
                    // Wait and retry (NO relaunch - state machine prevents infinite loops)
                    delay(2000)
                    currentRoot = getRootWithRetry(maxRetries = 3, delayMs = 500)
                    
                    if (currentRoot == null) {
                        android.util.Log.w("WireAuto", "Still not in Wire app - may need manual intervention")
                        // Don't relaunch - let user handle it or continue with next contact
                    }
                }
                
                // Small random delay before processing next contact to ensure UI is stable (1-3 sec)
                val nextContactDelay = (1000..3000).random()
                android.util.Log.d("WireAuto", "Waiting ${nextContactDelay}ms before next contact...")
                delay(nextContactDelay.toLong())

            } catch (e: Exception) {
                debugLog("ERROR", "Error processing contact $contactsProcessed: ${e.message}", e)
                android.util.Log.e("WireAuto", "Error processing contact $contactsProcessed: ${e.message}", e)
                // Try to go back to list on error
                debugLog("NAVIGATION", "Attempting to go back to contacts list after error")
                try {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    delay(1500)
                    debugLog("NAVIGATION", "Successfully went back to contacts list after error")
                } catch (e2: Exception) {
                    debugLog("ERROR", "Error going back to list: ${e2.message}", e2)
                    android.util.Log.e("WireAuto", "Error going back to list: ${e2.message}")
                }
                // Continue to next contact even if this one failed
                debugLog("EVENT", "Continuing to next contact despite error")
            }
        }
        
        debugLog("EVENT", "=== Finished processing all contacts ===")
        debugLog("STATS", "Total contacts in list: $totalContacts")
        debugLog("STATS", "Total contacts processed: $contactsProcessed")
        debugLog("STATS", "Total messages sent successfully: $contactsSent")
        debugLog("STATS", "Failed: ${contactResults.count { it.status == com.wireautomessenger.model.ContactStatus.FAILED }}")
        debugLog("STATS", "Skipped: ${contactResults.count { it.status == com.wireautomessenger.model.ContactStatus.SKIPPED }}")
        android.util.Log.i("WireAuto", "=== Finished processing all contacts ===")
        android.util.Log.i("WireAuto", "Total contacts processed: $contactsProcessed")
        android.util.Log.i("WireAuto", "Total messages sent: $contactsSent")

        // Save debug log to SharedPreferences for retrieval
        saveDebugLogToPrefs()
        debugLog("EVENT", "Debug log saved to SharedPreferences for retrieval")

        // Save last send time and completion status
        prefs.edit()
            .putLong("last_send_time", System.currentTimeMillis())
            .putBoolean("sending_complete", true)
            .putInt("last_contacts_sent", contactsSent)
            .putInt("last_contacts_processed", contactsProcessed)
            .putInt("last_total_contacts", totalContacts)
            .apply()
        debugLog("DATA", "Session statistics saved to SharedPreferences")
        
        val finalMessage = if (contactsSent > 0) {
            "Completed! Sent to $contactsSent out of $contactsProcessed contacts."
        } else {
            "Completed but no messages were sent. Please check Wire app and try again."
        }
        
        updateNotification(finalMessage)
        sendCompletionBroadcast(contactsSent, contactResults)
        
        android.util.Log.i("WireAuto", "=== Final Results ===")
        android.util.Log.i("WireAuto", "Total contacts in list: $totalContacts")
        android.util.Log.i("WireAuto", "Contacts processed: $contactsProcessed")
        android.util.Log.i("WireAuto", "Messages sent successfully: $contactsSent")
        android.util.Log.i("WireAuto", "Failed: ${contactResults.count { it.status == com.wireautomessenger.model.ContactStatus.FAILED }}")
        android.util.Log.i("WireAuto", "Skipped: ${contactResults.count { it.status == com.wireautomessenger.model.ContactStatus.SKIPPED }}")
        
        // Show toast
        scope.launch(Dispatchers.Main) {
            val toastMessage = if (contactsSent > 0) {
                "Messages sent to $contactsSent out of $contactsProcessed contacts"
            } else {
                "No messages were sent. Please check Wire app."
            }
            Toast.makeText(this@WireAutomationService, toastMessage, Toast.LENGTH_LONG).show()
        }
    }

    private fun findContactsList(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNodeByText(root, "Contacts") 
            ?: findNodeByText(root, "Conversations")
            ?: findNodeByContentDescription(root, "Contacts")
            ?: findNodeByContentDescription(root, "Conversations")
    }

    private fun navigateToContacts(root: AccessibilityNodeInfo) {
        // Try to find and click contacts/conversations tab
        val contactsTab = findNodeByText(root, "Contacts")
            ?: findNodeByText(root, "Conversations")
            ?: findNodeByContentDescription(root, "Contacts")
            ?: findNodeByContentDescription(root, "Conversations")
        
        contactsTab?.let {
            if (it.isClickable) {
                it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                var parent = it.parent
                var attempts = 0
                while (parent != null && attempts < 5) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        break
                    }
                    parent = parent.parent
                    attempts++
                }
            }
        }
    }

    private fun getAllContactItems(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val rowContainers = mutableListOf<AccessibilityNodeInfo>()
        
        android.util.Log.d("WireAuto", "=== Starting contact detection (using row containers) ===")
        android.util.Log.d("WireAuto", "Root package: ${root.packageName}, className: ${root.className}")
        
        // Method 1: Find RecyclerView and get actual row containers (not individual text nodes)
        val recyclerView = findRecyclerView(root)
        if (recyclerView != null) {
            android.util.Log.d("WireAuto", "Found RecyclerView with ${recyclerView.childCount} direct children")
            
            // Find clickable containers that are actual conversation rows
            findClickableContainersInRecyclerView(recyclerView, rowContainers)
            
            android.util.Log.d("WireAuto", "After RecyclerView container search: ${rowContainers.size} row containers found")
        } else {
            android.util.Log.d("WireAuto", "No RecyclerView found, searching for containers in root...")
            // Search for conversation row containers in the entire root
            findClickableContainersInRecyclerView(root, rowContainers)
        }
        
        // Method 2: If no containers found, fall back to finding individual contact items
        if (rowContainers.isEmpty()) {
            android.util.Log.d("WireAuto", "No row containers found, falling back to individual contact items...")
            val contacts = mutableListOf<AccessibilityNodeInfo>()
            findContactItemsRecursive(root, contacts)
            
            // For each contact item, find its row container
            for (contact in contacts) {
                val container = findConversationRowContainer(contact)
                if (container != null && !rowContainers.contains(container)) {
                    rowContainers.add(container)
                }
            }
        }
        
        // Filter out search inputs and other UI elements before deduplication
        val filteredRows = rowContainers.filter { row ->
            val isSearchInput = isSearchInputOrContainer(row)
            if (isSearchInput) {
                android.util.Log.d("WireAuto", "Filtering out search input/container from contact list")
            }
            !isSearchInput
        }
        
        // Remove duplicates based on row container bounds (same Y position = same row)
        val uniqueRows = filteredRows.distinctBy { row ->
            val bounds = android.graphics.Rect()
            row.getBoundsInScreen(bounds)
            // Group by vertical position (same row = same top position within 50px)
            "${bounds.top / 100}" // Divide by 100 to group rows that are close vertically
        }
        
        android.util.Log.i("WireAuto", "=== Contact detection complete: ${uniqueRows.size} unique contact rows found (after filtering search inputs) ===")
        android.util.Log.i("WireAuto", "✅ Search input field has been filtered out - only contacts will be processed")
        if (uniqueRows.isNotEmpty()) {
            uniqueRows.take(10).forEachIndexed { index, row ->
                val contactName = extractContactNameFromRow(row) ?: "Unknown"
                val bounds = android.graphics.Rect()
                row.getBoundsInScreen(bounds)
                android.util.Log.d("WireAuto", "Contact ${index + 1}: name='$contactName', className=${row.className}, bounds=(${bounds.top},${bounds.left})")
            }
            android.util.Log.i("WireAuto", "📋 Will process ${uniqueRows.size} contacts (search input skipped)")
        } else {
            android.util.Log.w("WireAuto", "No contacts found! This might indicate:")
            android.util.Log.w("WireAuto", "1. Wire UI structure is different than expected")
            android.util.Log.w("WireAuto", "2. Contacts are in a different screen/view")
            android.util.Log.w("WireAuto", "3. Wire uses custom views not detected by accessibility")
        }
        
        return uniqueRows
    }
    
    private fun findContactItemsInNode(node: AccessibilityNodeInfo, contacts: MutableList<AccessibilityNodeInfo>) {
        if (isContactItem(node)) {
            contacts.add(node)
            return
        }
        
        // Check children recursively
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findContactItemsInNode(child, contacts)
            }
        }
    }
    
    private fun findClickableItemsWithText(root: AccessibilityNodeInfo, contacts: MutableList<AccessibilityNodeInfo>) {
        // Find all nodes that are clickable and have text
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, allNodes)
        
        android.util.Log.d("WireAuto", "Collected ${allNodes.size} total nodes for broader search")
        
        for (node in allNodes) {
            if (node.packageName != WIRE_PACKAGE) continue
            
            val text = node.text?.toString()?.trim() ?: ""
            val hasText = text.isNotEmpty() && text.length > 1
            val isClickable = node.isClickable || findClickableNode(node) != null
            val className = node.className?.toString() ?: ""
            
            // Exclude common UI elements
            val isExcluded = className.contains("Button", ignoreCase = true) ||
                            className.contains("EditText", ignoreCase = true) ||
                            className.contains("ImageButton", ignoreCase = true) ||
                            className.contains("Toolbar", ignoreCase = true) ||
                            className.contains("ActionBar", ignoreCase = true) ||
                            text.lowercase() in listOf("send", "back", "menu", "search", "settings", "ok", "cancel", 
                                                       "conversations", "contacts", "chats", "messages")
            
            if (hasText && isClickable && !isExcluded) {
                // Additional check: should be meaningful text (not just UI labels)
                if (text.length > 1 && 
                    text !in listOf("OK", "Cancel", "Yes", "No", "Close", "Done", "Next", "Previous", "New", "Add")) {
                    contacts.add(node)
                    android.util.Log.v("WireAuto", "Found potential contact in broader search: '$text'")
                }
            }
        }
    }
    
    private fun collectAllNodes(node: AccessibilityNodeInfo, allNodes: MutableList<AccessibilityNodeInfo>) {
        allNodes.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectAllNodes(child, allNodes)
            }
        }
    }

    private fun findRecyclerView(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Try multiple RecyclerView class names
        val recyclerView = findNodeByClassName(root, "androidx.recyclerview.widget.RecyclerView")
            ?: findNodeByClassName(root, "android.widget.ListView")
            ?: findNodeByClassName(root, "androidx.recyclerview.widget.RecyclerView")
            ?: findNodeContaining(root) { node ->
                val className = node.className?.toString() ?: ""
                className.contains("RecyclerView", ignoreCase = true) ||
                className.contains("ListView", ignoreCase = true) ||
                className.contains("ScrollView", ignoreCase = true)
            }
        
        return recyclerView
    }
    
    /**
     * Find profile placeholder/avatar image in a contact row
     * 
     * Based on Wire app UI:
     * - Profile placeholders are circular images on the LEFT side of each conversation row
     * - They can be:
     *   * Grey circles with initials (e.g., "MA", "M.")
     *   * Actual profile photos
     * - They are typically ImageView or ImageButton nodes
     * - Located in the left 30% of the row width
     * - Usually 40-100px in size (circular/square)
     * - Clicking them opens the conversation
     * - May have content description containing "avatar", "profile", "picture", or contact name
     */
    private fun findProfilePlaceholderInRow(rowItem: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        android.util.Log.d("WireAuto", "🔍 Searching for profile placeholder/avatar in contact row...")
        android.util.Log.d("WireAuto", "Row item: className=${rowItem.className}, childCount=${rowItem.childCount}")
        
        val profilePlaceholders = mutableListOf<AccessibilityNodeInfo>()
        
        // Recursively search for ImageView/ImageButton nodes in the row
        findImageNodesInRow(rowItem, profilePlaceholders)
        
        if (profilePlaceholders.isEmpty()) {
            android.util.Log.d("WireAuto", "No ImageView/ImageButton nodes found in contact row")
            return null
        }
        
        android.util.Log.d("WireAuto", "Found ${profilePlaceholders.size} image nodes, filtering for profile placeholder...")
        
        // Filter to find the most likely profile placeholder
        // Profile placeholders are usually:
        // 1. Square or circular (similar width and height)
        // 2. Located on the left side of the row
        // 3. Have reasonable size (typically 40-100px)
        // 4. May have content description with "avatar", "profile", "picture", or contact name
        
        val rowBounds = android.graphics.Rect()
        rowItem.getBoundsInScreen(rowBounds)
        val rowLeft = rowBounds.left
        val rowRight = rowBounds.right
        val rowWidth = rowBounds.width()
        
        // Score each image node to find the best profile placeholder
        val scoredPlaceholders = profilePlaceholders.map { imageNode ->
            val bounds = android.graphics.Rect()
            imageNode.getBoundsInScreen(bounds)
            
            val width = bounds.width()
            val height = bounds.height()
            val imageLeft = bounds.left
            val imageRight = bounds.right
            val imageCenterX = bounds.centerX()
            
            val className = imageNode.className?.toString() ?: ""
            val contentDesc = imageNode.contentDescription?.toString()?.lowercase() ?: ""
            val text = imageNode.text?.toString()?.lowercase() ?: ""
            
            var score = 0
            
            // Check if it's an ImageView or ImageButton
            if (className.contains("ImageView", ignoreCase = true) || 
                className.contains("ImageButton", ignoreCase = true) ||
                className.contains("Image", ignoreCase = true)) {
                score += 10
            }
            
            // Check if it's on the left side of the row (profile pictures are ALWAYS on the left in Wire)
            // Based on Wire UI: profile placeholders are in the left 30% of the row
            val leftSideThreshold = rowLeft + (rowWidth * 0.3) // Left 30% of row
            if (imageCenterX < leftSideThreshold) {
                score += 20 // High priority for left side (Wire always has them on left)
            } else if (imageCenterX < rowLeft + (rowWidth * 0.5)) {
                score += 5 // Still on left half (less likely but possible)
            } else {
                // Not on left side - very unlikely to be profile placeholder in Wire
                score -= 10
            }
            
            // Check if it's roughly square/circular (profile pictures are circular in Wire)
            // Wire uses circular avatars, so aspect ratio should be close to 1.0
            val aspectRatio = if (height > 0) width.toFloat() / height.toFloat() else 0f
            if (aspectRatio >= 0.8 && aspectRatio <= 1.2) {
                score += 15 // Very close to circular (Wire uses circular avatars)
            } else if (aspectRatio >= 0.7 && aspectRatio <= 1.3) {
                score += 8 // Roughly square/circular
            }
            
            // Check for reasonable size (Wire profile pictures are typically 50-80px)
            // Based on Wire UI: circular avatars are medium-sized
            if (width >= 40 && width <= 100 && height >= 40 && height <= 100) {
                score += 12 // Perfect size for Wire avatars
            } else if (width >= 30 && width <= 120 && height >= 30 && height <= 120) {
                score += 5 // Acceptable size range
            }
            
            // Check content description for profile-related keywords
            if (contentDesc.contains("avatar") || contentDesc.contains("profile") || 
                contentDesc.contains("picture") || contentDesc.contains("photo") ||
                contentDesc.contains("image") || contentDesc.contains("contact")) {
                score += 20
            }
            
            // Check if clickable (profile pictures are often clickable)
            if (imageNode.isClickable) {
                score += 5
            }
            
            // Check parent for clickability (sometimes the container is clickable)
            var parent = imageNode.parent
            var depth = 0
            while (parent != null && depth < 3) {
                if (parent.isClickable) {
                    score += 3
                    break
                }
                parent = parent.parent
                depth++
            }
            
            android.util.Log.d("WireAuto", "Image node scored: score=$score, className=$className, bounds=(${bounds.left},${bounds.top},${bounds.width()}x${bounds.height()}), contentDesc='$contentDesc'")
            
            Pair(imageNode, score)
        }
        
        // Sort by score (highest first) and return the best match
        val bestPlaceholder = scoredPlaceholders.sortedByDescending { it.second }.firstOrNull()
        
        if (bestPlaceholder != null && bestPlaceholder.second >= 20) { // Minimum score threshold (raised for better accuracy)
            android.util.Log.i("WireAuto", "✅ ✓ Found profile placeholder/avatar with score ${bestPlaceholder.second}")
            val bounds = android.graphics.Rect()
            bestPlaceholder.first.getBoundsInScreen(bounds)
            android.util.Log.d("WireAuto", "Profile placeholder bounds: left=${bounds.left}, top=${bounds.top}, size=${bounds.width()}x${bounds.height()}")
            return bestPlaceholder.first
        } else if (bestPlaceholder != null && bestPlaceholder.second >= 15) {
            android.util.Log.w("WireAuto", "⚠️ Found profile placeholder with low score ${bestPlaceholder.second}, using it anyway")
            return bestPlaceholder.first
        } else {
            android.util.Log.w("WireAuto", "❌ No suitable profile placeholder found (best score: ${bestPlaceholder?.second ?: 0})")
            // Return the first image node on the left side as fallback if no good match
            val leftSideFallback = profilePlaceholders.firstOrNull { imageNode ->
                val bounds = android.graphics.Rect()
                imageNode.getBoundsInScreen(bounds)
                val imageCenterX = bounds.centerX()
                imageCenterX < rowLeft + (rowWidth * 0.3) // Left 30% of row
            }
            if (leftSideFallback != null) {
                android.util.Log.d("WireAuto", "Using left-side image as fallback profile placeholder")
                return leftSideFallback
            }
            return profilePlaceholders.firstOrNull()
        }
    }
    
    /**
     * Recursively find all ImageView/ImageButton nodes in a contact row
     */
    private fun findImageNodesInRow(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (depth > 10) return // Prevent infinite recursion
        
        val className = node.className?.toString() ?: ""
        
        // Check if this is an ImageView or ImageButton
        if (className.contains("ImageView", ignoreCase = true) || 
            className.contains("ImageButton", ignoreCase = true) ||
            className.contains("Image", ignoreCase = true)) {
            // Verify it has valid bounds
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                result.add(node)
            }
        }
        
        // Recursively search children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findImageNodesInRow(child, result, depth + 1)
            }
        }
    }
    
    private fun findNodeContaining(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(root)) {
            return root
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                val found = findNodeContaining(child, predicate)
                if (found != null) return found
            }
        }
        
        return null
    }
    
    private fun logUIStructure(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        
        val indent = "  ".repeat(depth)
        val text = node.text?.toString()?.take(30) ?: ""
        val desc = node.contentDescription?.toString()?.take(30) ?: ""
        val className = node.className?.toString() ?: ""
        
        android.util.Log.d("WireAuto", "$indent- $className | text: '$text' | desc: '$desc' | clickable: ${node.isClickable} | children: ${node.childCount}")
        
        if (depth < maxDepth) {
            for (i in 0 until minOf(node.childCount, 10)) { // Limit to first 10 children
                val child = node.getChild(i)
                if (child != null) {
                    logUIStructure(child, depth + 1, maxDepth)
                }
            }
        }
    }
    
    private fun collectDebugInfo(root: AccessibilityNodeInfo): String {
        val info = StringBuilder()
        
        // Count different types of nodes
        var recyclerViewCount = 0
        var clickableCount = 0
        var textNodeCount = 0
        var wirePackageCount = 0
        val sampleTexts = mutableListOf<String>()
        
        collectNodeStats(root, recyclerViewCount, clickableCount, textNodeCount, wirePackageCount, sampleTexts, 0, 50)
        
        // Actually collect stats (the function above won't modify the variables, need to fix)
        val stats = collectNodeStatsProper(root)
        
        info.append("Found ${stats.recyclerViewCount} list views\n")
        info.append("Found ${stats.clickableCount} clickable items\n")
        info.append("Found ${stats.textNodeCount} items with text\n")
        info.append("Found ${stats.wirePackageCount} Wire app elements\n")
        
        if (stats.sampleTexts.isNotEmpty()) {
            info.append("\nSample texts found:\n")
            stats.sampleTexts.take(5).forEach { text ->
                info.append("- '$text'\n")
            }
        }
        
        return info.toString()
    }
    
    private data class NodeStats(
        var recyclerViewCount: Int = 0,
        var clickableCount: Int = 0,
        var textNodeCount: Int = 0,
        var wirePackageCount: Int = 0,
        val sampleTexts: MutableList<String> = mutableListOf()
    )
    
    private fun collectNodeStatsProper(node: AccessibilityNodeInfo): NodeStats {
        val stats = NodeStats()
        collectNodeStatsRecursive(node, stats, 0, 100) // Limit to 100 nodes for performance
        return stats
    }
    
    private fun collectNodeStatsRecursive(node: AccessibilityNodeInfo, stats: NodeStats, depth: Int, maxNodes: Int) {
        if (depth > 5 || stats.wirePackageCount > maxNodes) return // Limit depth and total nodes
        
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString()?.trim() ?: ""
        
        if (node.packageName == WIRE_PACKAGE) {
            stats.wirePackageCount++
            
            if (className.contains("RecyclerView", ignoreCase = true) ||
                className.contains("ListView", ignoreCase = true)) {
                stats.recyclerViewCount++
            }
            
            if (node.isClickable) {
                stats.clickableCount++
            }
            
            if (text.isNotEmpty() && text.length > 1) {
                stats.textNodeCount++
                if (stats.sampleTexts.size < 10) {
                    stats.sampleTexts.add(text.take(30))
                }
            }
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectNodeStatsRecursive(child, stats, depth + 1, maxNodes)
            }
        }
    }
    
    private fun collectNodeStats(
        node: AccessibilityNodeInfo, 
        recyclerViewCount: Int, 
        clickableCount: Int, 
        textNodeCount: Int, 
        wirePackageCount: Int, 
        sampleTexts: MutableList<String>, 
        depth: Int, 
        maxDepth: Int
    ) {
        // This is a placeholder - the actual implementation uses collectNodeStatsProper
    }

    private fun isContactItem(node: AccessibilityNodeInfo): Boolean {
        // A contact/conversation item in Wire typically:
        // - Has text (contact name or last message)
        // - Is clickable or has clickable parent
        // - Is not a button or input field
        // - Is not empty
        // - Is in Wire package
        
        // Must be in Wire package
        if (node.packageName != WIRE_PACKAGE) {
            return false
        }
        
        val text = node.text?.toString()?.trim() ?: ""
        val description = node.contentDescription?.toString()?.trim() ?: ""
        val hasText = text.isNotEmpty()
        val hasDescription = description.isNotEmpty()
        
        // Must have some text content
        if (!hasText && !hasDescription) {
            return false
        }
        
        // Exclude buttons, input fields, and other UI elements
        val className = node.className?.toString() ?: ""
        val isUIElement = className.contains("Button", ignoreCase = true) ||
                         className.contains("EditText", ignoreCase = true) ||
                         className.contains("ImageButton", ignoreCase = true) ||
                         className.contains("Toolbar", ignoreCase = true) ||
                         className.contains("ActionBar", ignoreCase = true) ||
                         className.contains("TextView", ignoreCase = true) && text.length < 3 // Very short text in TextView is likely UI
        
        if (isUIElement) {
            return false
        }
        
        // Exclude common UI text (but be less strict)
        val lowerText = text.lowercase()
        val isCommonUIText = lowerText in listOf("send", "back", "menu", "search", "settings", "ok", "cancel", 
                                                  "yes", "no", "close", "done", "next", "previous", "new", "add")
        
        // Allow "conversations", "contacts", "chats", "messages" as they might be section headers
        // But exclude them if they're buttons
        if (isCommonUIText && className.contains("Button", ignoreCase = true)) {
            return false
        }
        
        // Must be clickable or have clickable parent/child
        val isClickable = node.isClickable || 
                         findClickableNode(node) != null ||
                         hasClickableChild(node)
        
        // If not directly clickable, check if parent is clickable (common in list items)
        if (!isClickable) {
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 5) {
                if (parent.isClickable) {
                    // Parent is clickable, this might be a contact item
                    break
                }
                parent = parent.parent
                depth++
            }
            if (parent == null || !parent.isClickable) {
                return false
            }
        }
        
        // Text should be at least 1 character (be more lenient)
        if (hasText && text.length < 1) {
            return false
        }
        
        // Additional check: if it's in a RecyclerView or ListView, it's more likely a contact
        val isInList = isInListView(node)
        
        // If in a list and has text, it's very likely a contact
        if (isInList && hasText && text.length >= 2) {
            android.util.Log.v("WireAuto", "✓ Contact found (in list): text='$text', className=$className")
            return true
        }
        
        // Even if not in list, if it has meaningful text and is clickable, consider it
        if (hasText && text.length >= 2 && isClickable) {
            android.util.Log.v("WireAuto", "✓ Contact found (clickable with text): text='$text', className=$className")
            return true
        }
        
        android.util.Log.v("WireAuto", "✗ Not a contact: text='$text', clickable=$isClickable, inList=$isInList, className=$className")
        return false
    }
    
    private fun hasClickableChild(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null && child.isClickable) {
                return true
            }
        }
        return false
    }
    
    private fun findClickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Check if node itself is clickable
        if (node.isClickable) {
            return node
        }
        
        // Check parent
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable) {
                return parent
            }
            parent = parent.parent
            depth++
        }
        
        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null && child.isClickable) {
                return child
            }
        }
        
        return null
    }
    
    /**
     * PERSISTENT ROOT NODE: Retry getting rootInActiveWindow at least 3 times with 500ms delay
     * Includes global action refresh when root is null
     */
    private suspend fun getRootWithRetry(maxRetries: Int = 3, delayMs: Long = 500): AccessibilityNodeInfo? {
        for (attempt in 1..maxRetries) {
            var root = rootInActiveWindow
            
            // GLOBAL ACTION REFRESH: If root is null, perform a dummy action to refresh window content
            if (root == null) {
                android.util.Log.d("WireAuto", "Root is null on attempt $attempt, performing global action refresh...")
                try {
                    // Use GLOBAL_ACTION_TAKE_SCREENSHOT as a dummy trigger to refresh window content
                    // This doesn't actually take a screenshot but forces the system to refresh
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                    } else {
                        // Fallback: perform a small scroll gesture or back action
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        delay(100)
                        performGlobalAction(GLOBAL_ACTION_BACK) // Go back forward
                    }
                    delay(300) // Wait for refresh
                    root = rootInActiveWindow
                } catch (e: Exception) {
                    android.util.Log.w("WireAuto", "Global action refresh failed: ${e.message}")
                }
            }
            
            // PACKAGE VERIFICATION: Explicitly verify package name matches Wire
            if (root != null) {
                val packageName = root.packageName?.toString()
                if (packageName == WIRE_PACKAGE) {
                    android.util.Log.d("WireAuto", "Successfully got root node on attempt $attempt - verified package: $packageName")
                    return root
                } else {
                    android.util.Log.d("WireAuto", "Root node has wrong package: $packageName (expected: $WIRE_PACKAGE)")
                }
            }
            
            if (attempt < maxRetries) {
                android.util.Log.d("WireAuto", "Root node is null or wrong package on attempt $attempt, retrying in ${delayMs}ms...")
                delay(delayMs)
            }
        }
        android.util.Log.w("WireAuto", "Failed to get root node after $maxRetries attempts")
        return null
    }
    
    /**
     * GENERIC LIST INTERACTION: Find first scrollable view and click first 3 children by coordinates
     */
    private suspend fun clickFirstScrollableChildren(root: AccessibilityNodeInfo, maxChildren: Int = 3): Boolean {
        android.util.Log.d("WireAuto", "Attempting generic list interaction - finding scrollable view...")
        val scrollableView = findScrollableView(root)
        
        if (scrollableView == null) {
            android.util.Log.w("WireAuto", "No scrollable view found for generic interaction")
            return false
        }
        
        android.util.Log.d("WireAuto", "Found scrollable view with ${scrollableView.childCount} children")
        var clickedCount = 0
        
        // Click first 3 children by coordinates
        for (i in 0 until minOf(scrollableView.childCount, maxChildren)) {
            val child = scrollableView.getChild(i)
            if (child != null) {
                val bounds = android.graphics.Rect()
                child.getBoundsInScreen(bounds)
                
                if (bounds.width() > 0 && bounds.height() > 0) {
                    val centerX = bounds.centerX().toFloat()
                    val centerY = bounds.centerY().toFloat()
                    
                    android.util.Log.d("WireAuto", "Clicking child $i at coordinates ($centerX, $centerY)")
                    
                    // Use gesture dispatch to click at center coordinates
                    try {
                        val path = android.graphics.Path()
                        path.moveTo(centerX, centerY)
                        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
                            path, 0, 200 // 200ms tap
                        )
                        val gesture = android.accessibilityservice.GestureDescription.Builder()
                            .addStroke(stroke)
                            .build()
                        
                        val clicked = dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                                android.util.Log.d("WireAuto", "Gesture completed for child $i")
                            }
                            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                                android.util.Log.w("WireAuto", "Gesture cancelled for child $i")
                            }
                        }, null)
                        
                        if (clicked) {
                            clickedCount++
                            delay(1000) // Wait between clicks
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("WireAuto", "Error clicking child $i: ${e.message}")
                    }
                }
            }
        }
        
        android.util.Log.d("WireAuto", "Generic list interaction: clicked $clickedCount out of ${minOf(scrollableView.childCount, maxChildren)} children")
        return clickedCount > 0
    }
    
    /**
     * Use GestureDispatch for clicking - harder for Wire to block
     */
    private fun clickNodeWithGesture(node: AccessibilityNodeInfo): Boolean {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            android.util.Log.w("WireAuto", "Invalid bounds for gesture click")
            return false
        }
        
        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.centerY().toFloat()
        
        try {
            val path = android.graphics.Path()
            path.moveTo(centerX, centerY)
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
                path, 0, 200 // 200ms tap
            )
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            
            return dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    android.util.Log.d("WireAuto", "Gesture click completed")
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    android.util.Log.w("WireAuto", "Gesture click cancelled")
                }
            }, null)
        } catch (e: Exception) {
            android.util.Log.e("WireAuto", "Gesture click failed: ${e.message}")
            return false
        }
    }
    
    private suspend fun navigateToConversationsList(root: AccessibilityNodeInfo) {
        // Try to find and click on "Conversations" or "Chats" tab/button
        val conversationsButton = findNodeByText(root, "Conversations")
            ?: findNodeByText(root, "Chats")
            ?: findNodeByText(root, "Messages")
            ?: findNodeByContentDescription(root, "Conversations")
            ?: findNodeByContentDescription(root, "Chats")
        
        if (conversationsButton != null) {
            val clickableNode = findClickableNode(conversationsButton)
            if (clickableNode != null) {
                // Use gesture dispatch instead of ACTION_CLICK
                if (clickNodeWithGesture(clickableNode)) {
                    android.util.Log.d("WireAuto", "Clicked on Conversations button via gesture")
                    delay(500)
                return
                }
            }
        }
        
        // If not found, try to find bottom navigation or tab bar
        // Wire typically shows conversations by default, so this might not be needed
        android.util.Log.d("WireAuto", "Conversations button not found, assuming already on conversations screen")
    }
    
    /**
     * Find the "Search conversations" bar as a reference point
     * This helps identify if we're on the right screen
     */
    private fun findSearchConversationsBar(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val allEditTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllNodesByClassName(root, "android.widget.EditText", allEditTexts)
        findAllNodesByClassName(root, "androidx.appcompat.widget.AppCompatEditText", allEditTexts)
        
        for (editText in allEditTexts) {
            val hint = editText.hintText?.toString()?.trim() ?: ""
            val contentDesc = editText.contentDescription?.toString()?.trim() ?: ""
            val text = editText.text?.toString()?.trim() ?: ""
            
            // Look for "Search conversations" or similar
            if (hint.lowercase().contains("search") && 
                (hint.lowercase().contains("conversation") || hint.lowercase().contains("chat") || hint.lowercase().contains("message"))) {
                android.util.Log.d("WireAuto", "Found search conversations bar: hint='$hint'")
                return editText
            }
            
            if (contentDesc.lowercase().contains("search") && 
                (contentDesc.lowercase().contains("conversation") || contentDesc.lowercase().contains("chat"))) {
                android.util.Log.d("WireAuto", "Found search conversations bar: contentDesc='$contentDesc'")
                return editText
            }
        }
        
        return null
    }
    
    /**
     * Collect class names of all found elements for debugging
     */
    private fun collectClassNamesInfo(root: AccessibilityNodeInfo): String {
        val classNames = mutableSetOf<String>()
        val classNamesWithCount = mutableMapOf<String, Int>()
        
        collectClassNamesRecursive(root, classNames, classNamesWithCount, 0, 50) // Limit to 50 elements
        
        val info = StringBuilder()
        if (classNamesWithCount.isNotEmpty()) {
            // Sort by count (most common first)
            val sorted = classNamesWithCount.toList().sortedByDescending { it.second }
            sorted.take(20).forEach { (className, count) ->
                info.append("- $className (found $count times)\n")
            }
        } else if (classNames.isNotEmpty()) {
            classNames.take(20).forEach { className ->
                info.append("- $className\n")
            }
        } else {
            info.append("- No elements found\n")
        }
        
        return info.toString()
    }
    
    private fun collectClassNamesRecursive(
        node: AccessibilityNodeInfo, 
        classNames: MutableSet<String>,
        classNamesWithCount: MutableMap<String, Int>,
        depth: Int, 
        maxElements: Int
    ) {
        if (depth > 5 || classNamesWithCount.size > maxElements) return
        
        if (node.packageName == WIRE_PACKAGE) {
            val className = node.className?.toString() ?: "Unknown"
            classNames.add(className)
            classNamesWithCount[className] = (classNamesWithCount[className] ?: 0) + 1
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectClassNamesRecursive(child, classNames, classNamesWithCount, depth + 1, maxElements)
            }
        }
    }
    
    private fun findScrollableView(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Look for RecyclerView, ListView, or ScrollView
        val className = root.className?.toString() ?: ""
        if (className.contains("RecyclerView", ignoreCase = true) ||
            className.contains("ListView", ignoreCase = true) ||
            className.contains("ScrollView", ignoreCase = true)) {
            if (root.isScrollable) {
                return root
            }
        }
        
        // Check children recursively
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                val scrollable = findScrollableView(child)
                if (scrollable != null) {
                    return scrollable
                }
            }
        }
        
        return null
    }
    
    private fun isInListView(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 10) {
            val className = parent.className?.toString() ?: ""
            if (className.contains("RecyclerView", ignoreCase = true) ||
                className.contains("ListView", ignoreCase = true) ||
                className.contains("ScrollView", ignoreCase = true)) {
                return true
            }
            parent = parent.parent
            depth++
        }
        return false
    }

    private fun findContactItemsRecursive(node: AccessibilityNodeInfo, contacts: MutableList<AccessibilityNodeInfo>) {
        if (isContactItem(node) && node.packageName == WIRE_PACKAGE) {
            contacts.add(node)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findContactItemsRecursive(child, contacts)
            }
        }
    }

    private fun findMessageInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        android.util.Log.d("WireAuto", "=== Finding message input field ===")
        
        // Strategy 1: Find by specific view ID (com.witaletr.wire:id/message_input)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                val viewIdNodes = root.findAccessibilityNodeInfosByViewId("com.witaletr.wire:id/message_input")
                if (viewIdNodes != null && viewIdNodes.isNotEmpty()) {
                    val inputNode = viewIdNodes[0]
                    android.util.Log.d("WireAuto", "Message input found via ViewId: com.witaletr.wire:id/message_input")
                    return inputNode
                }
            } catch (e: Exception) {
                android.util.Log.d("WireAuto", "ViewId search failed: ${e.message}")
            }
        }
        
        // Strategy 2: Find by hint text "Type a message"
        val allEditTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllNodesByClassName(root, "android.widget.EditText", allEditTexts)
        findAllNodesByClassName(root, "androidx.appcompat.widget.AppCompatEditText", allEditTexts)
        
        for (editText in allEditTexts) {
            val contentDesc = editText.contentDescription?.toString()?.lowercase() ?: ""
            val hint = editText.hintText?.toString()?.lowercase() ?: ""
            
            // Exclude search boxes
            if (contentDesc.contains("search") || hint.contains("search")) {
                continue
            }
            
            // Prefer message input fields with specific hint
            if (hint.contains("type a message") || 
                hint.contains("type a message", ignoreCase = true) ||
                contentDesc.contains("type a message") ||
                contentDesc.contains("type a message", ignoreCase = true)) {
                android.util.Log.d("WireAuto", "Message input found via hint 'Type a message'")
                return editText
            }
            
            // Also check for general message hints
            if (contentDesc.contains("message") || 
                hint.contains("message")) {
                android.util.Log.d("WireAuto", "Message input found via message hint")
                return editText
            }
        }
        
        // Strategy 3: If no specific message input found, return first non-search EditText
        for (editText in allEditTexts) {
            val contentDesc = editText.contentDescription?.toString()?.lowercase() ?: ""
            val hint = editText.hintText?.toString()?.lowercase() ?: ""
            if (!contentDesc.contains("search") && !hint.contains("search")) {
                android.util.Log.d("WireAuto", "Message input found via fallback (first non-search EditText)")
                return editText
            }
        }
        
        android.util.Log.w("WireAuto", "Message input not found")
        return null
    }
    
    private fun findAllNodesByClassName(root: AccessibilityNodeInfo, className: String, result: MutableList<AccessibilityNodeInfo>) {
        if (root.className?.toString() == className) {
            result.add(root)
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                findAllNodesByClassName(child, className, result)
            }
        }
    }

    /**
     * Find send button using multiple strategies (most reliable first)
     * Returns the clickable parent node, not the child icon
     */
    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        android.util.Log.d("WireAuto", "=== Finding send button using multiple strategies ===")
        
        // Strategy 1: Find by specific view ID (com.witaletr.wire:id/send_button) - HIGHEST PRIORITY
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                val viewIdNodes = root.findAccessibilityNodeInfosByViewId("com.witaletr.wire:id/send_button")
                if (viewIdNodes != null && viewIdNodes.isNotEmpty()) {
                    val sendNode = viewIdNodes[0]
                    android.util.Log.d("WireAuto", "Send button found via ViewId: com.witaletr.wire:id/send_button")
                    return getClickableParent(sendNode)
                }
            } catch (e: Exception) {
                android.util.Log.d("WireAuto", "ViewId search for send_button failed: ${e.message}")
            }
        }
        
        // Strategy 2: Find by content description "Send" - SECOND PRIORITY
        val contentDescNodes = findAllNodesByContentDescription(root, "send")
        if (contentDescNodes.isNotEmpty()) {
            // Filter for exact match "Send" (case-insensitive)
            val exactMatch = contentDescNodes.firstOrNull { node ->
                val desc = node.contentDescription?.toString()?.trim() ?: ""
                desc.equals("send", ignoreCase = true)
            }
            if (exactMatch != null) {
                android.util.Log.d("WireAuto", "Send button found via ContentDescription: 'Send'")
                val clickableParent = getClickableParent(exactMatch)
                if (clickableParent != null && clickableParent.isClickable) {
                    return clickableParent
                }
                return clickableParent
            }
            // Fallback to any node containing "send" in description
            android.util.Log.d("WireAuto", "Send button found via ContentDescription (contains 'send'): ${contentDescNodes.size} candidates")
            for (node in contentDescNodes) {
                val clickableParent = getClickableParent(node)
                if (clickableParent != null && clickableParent.isClickable) {
                    return clickableParent
                }
            }
            return getClickableParent(contentDescNodes[0])
        }
        
        // Strategy 3: findAccessibilityNodeInfosByText("Send")
        try {
            val textNodes = root.findAccessibilityNodeInfosByText("Send")
            if (textNodes != null && textNodes.isNotEmpty()) {
                for (node in textNodes) {
                    val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
                    val text = node.text?.toString()?.lowercase() ?: ""
                    if (contentDesc.contains("send") || text.contains("send")) {
                        android.util.Log.d("WireAuto", "Send button found via Text: 'Send'")
                        return getClickableParent(node)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.d("WireAuto", "Text search failed: ${e.message}")
        }
        
        // Strategy 4: Find all ImageButton/Button nodes and filter for send button
        val allButtons = mutableListOf<AccessibilityNodeInfo>()
        findAllButtons(root, allButtons)
        
        for (button in allButtons) {
            val contentDesc = button.contentDescription?.toString()?.lowercase() ?: ""
            val text = button.text?.toString()?.lowercase() ?: ""
            val viewId = button.viewIdResourceName?.lowercase() ?: ""
            
            if (contentDesc.contains("send") || 
                text.contains("send") || 
                viewId.contains("send")) {
                android.util.Log.d("WireAuto", "Send button found via Button search: className=${button.className}")
                return getClickableParent(button)
            }
        }
        
        android.util.Log.w("WireAuto", "Send button not found using standard strategies")
        return null
    }
    
    /**
     * Get the clickable parent of a node (the actual button container, not the icon)
     */
    private fun getClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // If node itself is clickable, return it
        if (node.isClickable) {
            return node
        }
        
        // Walk up the tree to find clickable parent
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable) {
                android.util.Log.d("WireAuto", "Found clickable parent at depth $depth: className=${parent.className}")
                return parent
            }
            parent = parent.parent
            depth++
        }
        
        // If no clickable parent found, return the node itself (will try to click anyway)
        return node
    }
    
    /**
     * Find all nodes with content description containing the search term
     */
    private fun findAllNodesByContentDescription(root: AccessibilityNodeInfo, searchTerm: String): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        findAllNodesByContentDescriptionRecursive(root, searchTerm, results)
        return results
    }
    
    private fun findAllNodesByContentDescriptionRecursive(
        node: AccessibilityNodeInfo, 
        searchTerm: String, 
        results: MutableList<AccessibilityNodeInfo>
    ) {
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (contentDesc.contains(searchTerm.lowercase())) {
            results.add(node)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findAllNodesByContentDescriptionRecursive(child, searchTerm, results)
            }
        }
    }
    
    /**
     * Find all button-like nodes (ImageButton, Button, etc.)
     */
    private fun findAllButtons(root: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>) {
        val className = root.className?.toString() ?: ""
        if (className.contains("Button", ignoreCase = true) || 
            className.contains("ImageButton", ignoreCase = true) ||
            className.contains("Image", ignoreCase = true)) {
            results.add(root)
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                findAllButtons(child, results)
            }
        }
    }
    
    private fun findSendButtonNearInput(root: AccessibilityNodeInfo, inputField: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Look for buttons near the input field (siblings or in same parent)
        // This is a fallback when standard detection fails
        android.util.Log.d("WireAuto", "Searching for send button near input field...")
        
        var parent = inputField.parent
        var depth = 0
        
        while (parent != null && depth < 5) {
            // Look for buttons in the same parent
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i)
                if (child != null && child != inputField) {
                    val className = child.className?.toString() ?: ""
                    val contentDesc = child.contentDescription?.toString()?.lowercase() ?: ""
                    val text = child.text?.toString()?.lowercase() ?: ""
                    
                    // Check if it's a button-like element
                    val isButtonLike = className.contains("Button", ignoreCase = true) ||
                        className.contains("ImageButton", ignoreCase = true) ||
                        className.contains("Image", ignoreCase = true)
                    
                    val hasSendIndicator = contentDesc.contains("send") ||
                        text.contains("send")
                    
                    if (isButtonLike || hasSendIndicator) {
                        if (child.isClickable || child.isEnabled) {
                            android.util.Log.i("WireAuto", "Found potential send button near input: className=$className, desc=$contentDesc, clickable=${child.isClickable}")
                            return child
                        }
                    }
                }
            }
            parent = parent.parent
            depth++
        }
        
        // Last resort: Find last clickable button in the root (usually send button is last)
        val allClickableButtons = mutableListOf<AccessibilityNodeInfo>()
        findAllClickableButtons(root, allClickableButtons)
        
        // Filter buttons that are likely send buttons (bottom-right area, ImageButton, etc.)
        val candidateButtons = allClickableButtons.filter { button ->
            val className = button.className?.toString() ?: ""
            val bounds = android.graphics.Rect()
            button.getBoundsInScreen(bounds)
            
            // Prefer ImageButton or buttons in bottom-right area
            className.contains("ImageButton", ignoreCase = true) ||
            className.contains("Button", ignoreCase = true)
        }
        
        if (candidateButtons.isNotEmpty()) {
            // Return the last button (send button is usually the last clickable element)
            val lastButton = candidateButtons.last()
            android.util.Log.i("WireAuto", "Using last clickable button as fallback: className=${lastButton.className}")
            return lastButton
        }
        
        return null
    }
    
    /**
     * Find all clickable button-like nodes
     */
    private fun findAllClickableButtons(root: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>) {
        val className = root.className?.toString() ?: ""
        if (root.isClickable && 
            (className.contains("Button", ignoreCase = true) || 
             className.contains("ImageButton", ignoreCase = true) ||
             className.contains("Image", ignoreCase = true))) {
            results.add(root)
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                findAllClickableButtons(child, results)
            }
        }
    }
    
    /**
     * Get bounds string for logging
     */
    private fun getBoundsString(node: AccessibilityNodeInfo): String {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        return "(${bounds.left},${bounds.top})-(${bounds.right},${bounds.bottom})"
    }
    
    private fun findNodeByViewId(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        // Try to find node by view ID (resource name)
        // This is a simplified version - actual view IDs are integers
        // We'll look for nodes that might match the ID pattern
        val viewIdRes = root.viewIdResourceName
        if (viewIdRes != null && viewIdRes.contains(viewId, ignoreCase = true)) {
            return root
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                val found = findNodeByViewId(child, viewId)
                if (found != null) return found
            }
        }
        
        return null
    }

    // Helper functions to find nodes
    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (root.text?.toString()?.contains(text, ignoreCase = true) == true) {
            return root
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                val found = findNodeByText(child, text)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findNodeByContentDescription(root: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString()?.contains(description, ignoreCase = true) == true) {
            return root
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                val found = findNodeByContentDescription(child, description)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findNodeByClassName(root: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (root.className?.toString() == className) {
            return root
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                val found = findNodeByClassName(child, className)
                if (found != null) return found
            }
        }
        return null
    }
    
    private fun findContactNodeByText(root: AccessibilityNodeInfo, contactName: String): AccessibilityNodeInfo? {
        // Try to find a contact node that matches the contact name
        // This helps refresh stale contact nodes
        if (root.packageName != WIRE_PACKAGE) {
            return null
        }
        
        val rootText = root.text?.toString()?.trim() ?: ""
        val rootContentDesc = root.contentDescription?.toString()?.trim() ?: ""
        val cleanContactName = contactName.removePrefix("You: ").trim()
        
        // Check if root matches (exact or contains)
        if (rootText.equals(contactName, ignoreCase = true) ||
            rootText.equals(cleanContactName, ignoreCase = true) ||
            rootText.contains(cleanContactName, ignoreCase = true) ||
            rootContentDesc.equals(contactName, ignoreCase = true) ||
            rootContentDesc.equals(cleanContactName, ignoreCase = true) ||
            rootContentDesc.contains(cleanContactName, ignoreCase = true)) {
            // Check if it's clickable or has clickable parent (likely a contact)
            if (root.isClickable || findClickableNode(root) != null) {
                return root
            }
        }
        
        // Search children
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                val found = findContactNodeByText(child, contactName)
                if (found != null) return found
            }
        }
        
        return null
    }
    
    private fun findAllClickableNodes(root: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        if (root.isClickable && root.packageName == WIRE_PACKAGE) {
            result.add(root)
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                findAllClickableNodes(child, result)
            }
        }
    }
    
    private fun findClickableNodeAtBounds(root: AccessibilityNodeInfo, bounds: android.graphics.Rect): AccessibilityNodeInfo? {
        val rootBounds = android.graphics.Rect()
        root.getBoundsInScreen(rootBounds)
        
        if (rootBounds.intersect(bounds) && root.isClickable && root.packageName == WIRE_PACKAGE) {
            return root
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                val found = findClickableNodeAtBounds(child, bounds)
                if (found != null) return found
            }
        }
        
        return null
    }
    
    private fun extractContactNameFromRow(rowItem: AccessibilityNodeInfo): String? {
        // Extract contact name from a row item
        // Filter out tags ("Guest"), message previews ("You: ..."), and UI elements
        val textNodes = mutableListOf<Triple<String, Int, Int>>() // text, depth, textLength
        
        fun collectTextNodes(node: AccessibilityNodeInfo, depth: Int) {
            val text = node.text?.toString()?.trim()
            val contentDesc = node.contentDescription?.toString()?.trim()
            val className = node.className?.toString() ?: ""
            
            // Skip buttons, input fields, and other UI elements
            val isUIElement = className.contains("Button", ignoreCase = true) ||
                             className.contains("EditText", ignoreCase = true) ||
                             className.contains("ImageButton", ignoreCase = true)
            
            if (isUIElement) {
                // Skip UI elements
            } else {
                if (!text.isNullOrEmpty() && text.length >= 2) {
                    // Filter out common tags and message previews
                    val lowerText = text.lowercase()
                    val isTag = lowerText == "guest" || lowerText.contains("tag") || 
                               (text.length <= 6 && text.all { it.isLetter() && it.isUpperCase() })
                    // More strict message preview detection
                    val isMessagePreview = text.startsWith("You:") || 
                                          text.startsWith("you:") ||
                                          text.startsWith("W.Salam") || // Example from user's log
                                          text.contains(":") && text.length > 15 || // Contains colon and is long (likely message)
                                          (text.length > 25 && !text.contains(" ")) // Very long single word
                    val isCommonUI = lowerText in listOf("search", "conversations", "new", "filter", "sort", "contact")
                    
                    if (!isTag && !isMessagePreview && !isCommonUI) {
                        textNodes.add(Triple(text, depth, text.length))
                    }
                }
                if (!contentDesc.isNullOrEmpty() && contentDesc.length >= 2) {
                    val lowerDesc = contentDesc.lowercase()
                    val isTag = lowerDesc == "guest" || lowerDesc.contains("tag")
                    val isMessagePreview = contentDesc.startsWith("You:") || 
                                          contentDesc.startsWith("you:") ||
                                          contentDesc.contains(":") && contentDesc.length > 15
                    val isCommonUI = lowerDesc in listOf("search", "conversations", "new", "filter", "sort", "contact")
                    
                    if (!isTag && !isMessagePreview && !isCommonUI) {
                        textNodes.add(Triple(contentDesc, depth, contentDesc.length))
                    }
                }
            }
            
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    collectTextNodes(child, depth + 1)
                }
            }
        }
        
        collectTextNodes(rowItem, 0)
        
        // Find the best contact name:
        // 1. Prefer names at shallow depth (usually the main contact name)
        // 2. Prefer names with reasonable length (3-40 characters)
        // 3. Exclude message previews more strictly
        val candidateNames = textNodes.filter { (text, _, length) ->
            // Filter out message previews more strictly
            val isMessagePreview = text.contains(":") && text.length > 15 ||
                                  text.startsWith("You:", ignoreCase = true) ||
                                  text.startsWith("W.Salam") ||
                                  (length > 25 && text.split(" ").size == 1) // Very long single word
            length in 3..40 && !isMessagePreview && text.split(" ").size >= 1
        }
        
        // Sort by depth (shallower first), then by length (prefer shorter names - contact names are usually shorter than messages)
        val sortedNames = candidateNames.sortedWith(compareBy<Triple<String, Int, Int>> { it.second }
            .thenBy { it.third }) // Prefer shorter names (contact names vs message previews)
        
        val selectedName = sortedNames.firstOrNull()?.first
        
        // Final validation: ensure it's not a message preview
        if (selectedName != null) {
            val isMessagePreview = selectedName.contains(":") && selectedName.length > 15 ||
                                  selectedName.startsWith("You:", ignoreCase = true) ||
                                  selectedName.startsWith("W.Salam")
            if (isMessagePreview) {
                // Try next candidate
                return sortedNames.getOrNull(1)?.first
            }
        }
        
        return selectedName
    }
    
    private fun findClickableContainer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Find the actual clickable container (row/item) that wraps this contact
        // This is typically a ViewGroup, RecyclerView item, or LinearLayout that contains the contact info
        
        // First, check if the node itself is a container
        val className = node.className?.toString() ?: ""
        val isContainer = className.contains("ViewGroup", ignoreCase = true) ||
                         className.contains("LinearLayout", ignoreCase = true) ||
                         className.contains("RelativeLayout", ignoreCase = true) ||
                         className.contains("ConstraintLayout", ignoreCase = true) ||
                         className.contains("FrameLayout", ignoreCase = true) ||
                         className.contains("RecyclerView", ignoreCase = true) ||
                         className.contains("ListView", ignoreCase = true)
        
        if (isContainer && node.isClickable) {
            return node
        }
        
        // Go up the tree to find the container
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 10) {
            val parentClassName = parent.className?.toString() ?: ""
            val isParentContainer = parentClassName.contains("ViewGroup", ignoreCase = true) ||
                                   parentClassName.contains("LinearLayout", ignoreCase = true) ||
                                   parentClassName.contains("RelativeLayout", ignoreCase = true) ||
                                   parentClassName.contains("ConstraintLayout", ignoreCase = true) ||
                                   parentClassName.contains("FrameLayout", ignoreCase = true) ||
                                   parentClassName.contains("RecyclerView", ignoreCase = true) ||
                                   parentClassName.contains("ListView", ignoreCase = true) ||
                                   parentClassName.contains("CardView", ignoreCase = true)
            
            // If it's a container and clickable, or if it's a container at depth 2-5 (likely the row)
            if (isParentContainer && (parent.isClickable || (depth >= 2 && depth <= 5))) {
                return parent
            }
            
            parent = parent.parent
            depth++
        }
        
        // If no container found, return the clickable node itself
        return findClickableNode(node)
    }
    
    private fun findConversationRowContainer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Find the actual conversation row container
        // A conversation row typically:
        // - Is a ViewGroup/LinearLayout/RelativeLayout/ConstraintLayout
        // - Contains an avatar (ImageView or similar)
        // - Contains contact name text
        // - Contains message preview text
        // - Is clickable or has clickable parent
        
        val className = node.className?.toString() ?: ""
        val isContainer = className.contains("ViewGroup", ignoreCase = true) ||
                         className.contains("LinearLayout", ignoreCase = true) ||
                         className.contains("RelativeLayout", ignoreCase = true) ||
                         className.contains("ConstraintLayout", ignoreCase = true) ||
                         className.contains("FrameLayout", ignoreCase = true) ||
                         className.contains("CardView", ignoreCase = true)
        
        if (isContainer) {
            // Check if this container looks like a conversation row
            // It should have multiple children (avatar, name, message, etc.)
            val hasMultipleChildren = node.childCount >= 2
            val hasTextContent = hasTextContent(node)
            
            if (hasMultipleChildren && hasTextContent) {
                // Check if it's clickable or has clickable parent
                if (node.isClickable) {
                    return node
                }
                var parent = node.parent
                var depth = 0
                while (parent != null && depth < 3) {
                    if (parent.isClickable) {
                        return parent
                    }
                    parent = parent.parent
                    depth++
                }
                // Return container even if not explicitly clickable (some rows work without it)
                return node
            }
        }
        
        // Go up the tree to find the container
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            val parentClassName = parent.className?.toString() ?: ""
            val isParentContainer = parentClassName.contains("ViewGroup", ignoreCase = true) ||
                                   parentClassName.contains("LinearLayout", ignoreCase = true) ||
                                   parentClassName.contains("RelativeLayout", ignoreCase = true) ||
                                   parentClassName.contains("ConstraintLayout", ignoreCase = true) ||
                                   parentClassName.contains("FrameLayout", ignoreCase = true) ||
                                   parentClassName.contains("CardView", ignoreCase = true)
            
            if (isParentContainer && parent.childCount >= 2) {
                if (parent.isClickable) {
                    return parent
                }
                // Return container even if not explicitly clickable
                if (depth >= 1 && depth <= 3) {
                    return parent
                }
            }
            parent = parent.parent
            depth++
        }
        
        return null
    }
    
    private fun hasTextContent(node: AccessibilityNodeInfo): Boolean {
        // Check if node or its children have text content
        if (!node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty()) {
            return true
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null && hasTextContent(child)) {
                return true
            }
        }
        
        return false
    }
    
    private fun isActualConversationRow(node: AccessibilityNodeInfo): Boolean {
        // Filter out UI elements that are NOT conversation rows:
        // - Search bars (contain "search" in text/content description)
        // - Section headers (like "CONVERSATIONS")
        // - FAB buttons (floating action buttons)
        // - "New Message" buttons
        // - Toolbars, action bars
        // - Empty or very small items
        // - Empty layout nodes
        
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString()?.trim() ?: ""
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
        val lowerText = text.lowercase()
        val lowerDesc = contentDesc.lowercase()
        
        // Must be in Wire package
        if (node.packageName != WIRE_PACKAGE) {
            return false
        }
        
        // STRICT: Exclude search bars and search input fields
        // Check if this node or any child is a search input
        if (lowerText.contains("search") || lowerDesc.contains("search") ||
            lowerText.contains("search conversations") || lowerDesc.contains("search conversations") ||
            className.contains("SearchView", ignoreCase = true)) {
            android.util.Log.d("WireAuto", "Skipping search bar: text='$text', desc='$contentDesc', className='$className'")
            return false
        }
        
        // Check if this node is an EditText (could be search input)
        if (className.contains("EditText", ignoreCase = true)) {
            // Check if it's a search input by checking hint/content description
            val hint = node.hintText?.toString()?.lowercase() ?: ""
            if (hint.contains("search") || lowerText.contains("search") || lowerDesc.contains("search")) {
                android.util.Log.d("WireAuto", "Skipping search input field: hint='$hint', text='$text'")
                return false
            }
        }
        
        // Check children for search-related elements
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val childClassName = child.className?.toString() ?: ""
                val childText = child.text?.toString()?.lowercase() ?: ""
                val childDesc = child.contentDescription?.toString()?.lowercase() ?: ""
                val childHint = child.hintText?.toString()?.lowercase() ?: ""
                
                // If any child is a search input, exclude this row
                if (childClassName.contains("SearchView", ignoreCase = true) ||
                    childClassName.contains("EditText", ignoreCase = true) && 
                    (childHint.contains("search") || childText.contains("search") || childDesc.contains("search"))) {
                    android.util.Log.d("WireAuto", "Skipping row containing search input in child: className='$childClassName'")
                    return false
                }
            }
        }
        
        // Exclude section headers (all caps, short text)
        if (text == "CONVERSATIONS" || text == "CONVERSATION" || 
            text == "CHATS" || text == "MESSAGES" ||
            (text.isNotEmpty() && text.length < 20 && text.all { it.isLetter() && it.isUpperCase() })) {
            return false
        }
        
        // Exclude FAB buttons and "New Message" buttons
        if (className.contains("FloatingActionButton", ignoreCase = true) ||
            lowerText == "new" || lowerDesc == "new" ||
            lowerText.contains("new message") || lowerDesc.contains("new message") ||
            (text.contains("New") && className.contains("Button", ignoreCase = true))) {
            return false
        }
        
        // Exclude toolbars and action bars
        if (className.contains("Toolbar", ignoreCase = true) ||
            className.contains("ActionBar", ignoreCase = true) ||
            className.contains("AppBar", ignoreCase = true)) {
            return false
        }
        
        // Exclude items that are too small (likely not conversation rows)
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.height() < 50 || bounds.width() < 50) { // Conversation rows are typically taller/wider than 50px
            return false
        }
        
        // BROADENED SEARCH: Look for ViewGroup or RelativeLayout that contains TextView with person's name
        val isViewGroup = className.contains("ViewGroup", ignoreCase = true) ||
                         className.contains("RelativeLayout", ignoreCase = true) ||
                         className.contains("LinearLayout", ignoreCase = true) ||
                         className.contains("ConstraintLayout", ignoreCase = true) ||
                         className.contains("FrameLayout", ignoreCase = true) ||
                         className.contains("CardView", ignoreCase = true)
        
        // Check if this is a container with TextView containing person's name
        if (isViewGroup) {
            // Look for TextView children with person's name (not UI labels)
            var hasPersonName = false
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    val childClassName = child.className?.toString() ?: ""
                    val childText = child.text?.toString()?.trim() ?: ""
                    
                    // Check if child is a TextView with meaningful text (person's name)
                    if (childClassName.contains("TextView", ignoreCase = true) && 
                        childText.length >= 2 &&
                        !childText.lowercase().contains("search") &&
                        !childText.lowercase().contains("new") &&
                        childText != "CONVERSATIONS" && childText != "CHATS" &&
                        !childText.all { it.isLetter() && it.isUpperCase() && childText.length < 15 }) {
                        hasPersonName = true
                        break
                    }
                }
            }
            
            // If it's a ViewGroup/RelativeLayout with person's name and is clickable, it's likely a conversation row
            if (hasPersonName) {
        val isClickable = node.isClickable || findClickableNode(node) != null
                if (isClickable) {
                    return true
                }
            }
        }
        
        // Original logic: Check if node or any child has meaningful text (contact name)
        val hasText = text.isNotEmpty()
        var hasMeaningfulText = hasText && text.length >= 2
        if (!hasMeaningfulText) {
            // Check children for meaningful text
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    val childText = child.text?.toString()?.trim() ?: ""
                    if (childText.length >= 2 && 
                        !childText.lowercase().contains("search") &&
                        !childText.lowercase().contains("new") &&
                        childText != "CONVERSATIONS" && childText != "CHATS") {
                        hasMeaningfulText = true
                        break
                    }
                }
            }
        }
        
        val hasMultipleChildren = node.childCount >= 2
        val isClickable = node.isClickable || findClickableNode(node) != null
        
        // Must have meaningful text and be clickable
        return hasMeaningfulText && isClickable && (hasMultipleChildren || hasText)
    }
    
    /**
     * Check if a node is a search input or contains a search input
     */
    private fun isSearchInputOrContainer(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val hint = node.hintText?.toString()?.lowercase() ?: ""
        
        // Check if this node itself is a search input
        if (className.contains("SearchView", ignoreCase = true) ||
            (className.contains("EditText", ignoreCase = true) && 
             (hint.contains("search") || text.contains("search") || contentDesc.contains("search")))) {
            return true
        }
        
        // Check if text/content description indicates it's a search input
        if (text.contains("search conversations") || contentDesc.contains("search conversations") ||
            (text.contains("search") && hint.contains("search"))) {
            return true
        }
        
        // Check children for search inputs (but limit depth to avoid performance issues)
        for (i in 0 until minOf(node.childCount, 10)) {
            val child = node.getChild(i)
            if (child != null) {
                val childClassName = child.className?.toString() ?: ""
                val childHint = child.hintText?.toString()?.lowercase() ?: ""
                val childText = child.text?.toString()?.lowercase() ?: ""
                val childDesc = child.contentDescription?.toString()?.lowercase() ?: ""
                
                if (childClassName.contains("SearchView", ignoreCase = true) ||
                    (childClassName.contains("EditText", ignoreCase = true) && 
                     (childHint.contains("search") || childText.contains("search") || childDesc.contains("search")))) {
                    return true
                }
            }
        }
        
        return false
    }
    
    private fun findClickableContainersInRecyclerView(root: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        // SKIP search inputs - don't even process them
        if (isSearchInputOrContainer(root)) {
            android.util.Log.d("WireAuto", "Skipping search input container in RecyclerView")
            return
        }
        
        // Find all clickable containers that look like conversation rows
        val className = root.className?.toString() ?: ""
        val isContainer = className.contains("ViewGroup", ignoreCase = true) ||
                         className.contains("LinearLayout", ignoreCase = true) ||
                         className.contains("RelativeLayout", ignoreCase = true) ||
                         className.contains("ConstraintLayout", ignoreCase = true) ||
                         className.contains("FrameLayout", ignoreCase = true) ||
                         className.contains("CardView", ignoreCase = true)
        
        if (isContainer && root.childCount >= 2 && hasTextContent(root)) {
            // This looks like a conversation row - check if it's actually a conversation row
            if (isActualConversationRow(root)) {
                if (root.isClickable || findClickableNode(root) != null) {
                    result.add(root)
                }
            }
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                // Skip search inputs when recursing
                if (!isSearchInputOrContainer(child)) {
                    findClickableContainersInRecyclerView(child, result)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = getSystemService(android.app.NotificationManager::class.java)
                if (notificationManager != null) {
                    // Check if channel already exists
                    val existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
                    if (existingChannel == null) {
                        val channel = android.app.NotificationChannel(
                            CHANNEL_ID,
                            "Wire Automation",
                            android.app.NotificationManager.IMPORTANCE_LOW
                        ).apply {
                            description = "Notifications for Wire message automation"
                        }
                        notificationManager.createNotificationChannel(channel)
                    }
                }
            }
        } catch (e: Exception) {
            // Don't crash if notification channel creation fails
            e.printStackTrace()
        }
    }

    private fun createNotification(text: String): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wire Auto Messenger")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val notification = createNotification(text)
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Don't crash if notification fails
            e.printStackTrace()
        }
    }
    
    private fun sendProgressBroadcast(text: String, contactsSent: Int = 0) {
        try {
            val intent = Intent(ACTION_PROGRESS_UPDATE).apply {
                putExtra(EXTRA_PROGRESS_TEXT, text)
                putExtra(EXTRA_CONTACTS_SENT, contactsSent)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun sendContactUpdate(contactName: String, status: String, position: Int, errorMessage: String?) {
        try {
            val intent = Intent(ACTION_CONTACT_UPDATE).apply {
                putExtra(EXTRA_CONTACT_NAME, contactName)
                putExtra(EXTRA_CONTACT_STATUS, status)
                putExtra(EXTRA_CONTACT_POSITION, position)
                if (errorMessage != null) {
                    putExtra(EXTRA_CONTACT_ERROR, errorMessage)
                }
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun sendCompletionBroadcast(contactsSent: Int, contactResults: List<com.wireautomessenger.model.ContactResult>) {
        try {
            val intent = Intent(ACTION_COMPLETED).apply {
                putExtra(EXTRA_CONTACTS_SENT, contactsSent)
                // Store results in SharedPreferences for MainActivity to retrieve
                val resultsJson = com.google.gson.Gson().toJson(contactResults)
                prefs.edit().putString("last_contact_results", resultsJson).apply()
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun sendErrorBroadcast(errorMessage: String) {
        try {
            val intent = Intent(ACTION_ERROR).apply {
                putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

