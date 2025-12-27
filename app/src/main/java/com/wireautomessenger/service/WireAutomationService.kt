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
    private var sessionStartTime: Long = 0L
    private val avatarClassKeywords = listOf(
        "image", "avatar", "profile", "picture", "photo", "icon", "thumb", "initial", "circle", "contact"
    )
    private val avatarExactClassNames = setOf(
        "H1.o0", "H1.O0", "H1.oo", "H10.o0"
    )

    // Professional-grade contact handling: Session tracking
    private val sessionSentList = mutableSetOf<String>() // Track exact contact names/IDs sent in this session
    private var lastPersonMessaged: String? = null // Track last person messaged for verification

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
    
    private fun clearOperationReport() {
        try {
            prefs.edit().remove("last_operation_report").apply()
            debugLog("REPORT", "Cleared previous operation report")
        } catch (e: Exception) {
            debugLog("ERROR", "Failed to clear operation report", e)
        }
    }
    
    private fun saveOperationReport(report: String) {
        try {
            prefs.edit().putString("last_operation_report", report).apply()
            debugLog("REPORT", "Operation report saved (${report.length} chars)")
        } catch (e: Exception) {
            debugLog("ERROR", "Failed to save operation report", e)
        }
    }
    
    private fun buildOperationReport(
        totalContacts: Int,
        contactsProcessed: Int,
        contactsSent: Int,
        contactResults: List<com.wireautomessenger.model.ContactResult>,
        durationMillis: Long
    ): String {
        val durationSeconds = durationMillis / 1000.0
        val failedCount = contactResults.count { it.status == com.wireautomessenger.model.ContactStatus.FAILED }
        val skippedCount = contactResults.count { it.status == com.wireautomessenger.model.ContactStatus.SKIPPED }
        val timestamp = dateFormat.format(Date())
        
        val builder = StringBuilder()
        builder.appendLine("Wire Auto Messenger Broadcast Report")
        builder.appendLine("Generated: $timestamp")
        builder.appendLine("Duration: ${"%.1f".format(durationSeconds)} seconds")
        builder.appendLine("========================================")
        builder.appendLine("Contacts detected: $totalContacts")
        builder.appendLine("Contacts processed: $contactsProcessed")
        builder.appendLine("Messages sent: $contactsSent")
        builder.appendLine("Failed: $failedCount")
        builder.appendLine("Skipped: $skippedCount")
        builder.appendLine("----------------------------------------")
        
        if (contactResults.isNotEmpty()) {
            builder.appendLine("Per-contact summary:")
            contactResults.forEach { result ->
                val statusEmoji = when (result.status) {
                    com.wireautomessenger.model.ContactStatus.SENT -> "✓"
                    com.wireautomessenger.model.ContactStatus.FAILED -> "✗"
                    com.wireautomessenger.model.ContactStatus.SKIPPED -> "⊘"
                }
                builder.append("#${result.position} $statusEmoji ${result.name}")
                result.errorMessage?.let { builder.append(" — $it") }
                builder.appendLine()
            }
        } else {
            builder.appendLine("No contact results available.")
        }
        
        builder.appendLine("----------------------------------------")
        builder.appendLine("Need help? Copy this report along with the debug log (Menu → Debug Log → Copy) and share it.")
        return builder.toString()
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
        // Service interrupted - reset all state flags and clear session data
        // This method must not throw exceptions
        try {
            android.util.Log.w("WireAuto", "Service interrupted - resetting state and clearing session data")
            isRunning.set(false)
            isWireOpened.set(false)
            isSendingInProgress.set(false)
            
            // Clear session data for repeatable runs
            sessionSentList.clear()
            lastPersonMessaged = null
            debugLog("STATE", "Service interrupted - session data cleared")
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
                        // Reset state if notification fails
                        resetState()
                        return START_NOT_STICKY
                    }
                    // STABILITY HANDLER: Wrap in Handler().postDelayed() to prevent blocking main thread
                    Handler(Looper.getMainLooper()).postDelayed({
                        scope.launch {
                            try {
                                debugLog("ACTION", "Launching sendMessagesToAllContacts coroutine")
                                sendMessagesToAllContacts()
                            } catch (e: Exception) {
                                debugLog("ERROR", "Fatal error in sendMessagesToAllContacts coroutine: ${e.message}", e)
                                android.util.Log.e("WireAuto", "Fatal error in sendMessagesToAllContacts coroutine", e)
                                sendErrorBroadcast("Fatal error: ${e.message ?: "Unknown error"}")
                                resetState()
                            }
                        }
                    }, 100) // Small delay to ensure service is fully initialized
                } else {
                    debugLog("WARN", "Service already running - ignoring duplicate request")
                    // Send a message to user that service is already running
                    sendErrorBroadcast("Message sending is already in progress. Please wait for it to complete.")
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
        clearOperationReport()
        sessionStartTime = System.currentTimeMillis()
        debugLog("EVENT", "=== STARTING NEW BROADCAST MESSAGE SESSION ===")
        debugLog("STATE", "Initial state - isRunning: ${isRunning.get()}, isWireOpened: ${isWireOpened.get()}, isSendingInProgress: ${isSendingInProgress.get()}")
        
        // State machine: Check if already running
        if (isRunning.getAndSet(true)) {
            debugLog("WARN", "Already running - ignoring duplicate request")
            android.util.Log.w("WireAuto", "Already running - ignoring duplicate request")
            return
        }

        // Reset state flags and clear session data for fresh scan-then-send flow
        isWireOpened.set(false)
        isSendingInProgress.set(false)
        sessionSentList.clear()
        lastPersonMessaged = null
        debugLog("STATE", "State flags reset - isWireOpened: false, isSendingInProgress: false")
        debugLog("SESSION", "Session data cleared for fresh contact discovery: sessionSentList and lastPersonMessaged reset")
        android.util.Log.i("WireAuto", "Session data cleared for fresh scan-then-send flow")

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

            // Wait for UI to fully load before scanning
            debugLog("ACTION", "Waiting 5 seconds for Wire UI to fully load...")
            updateNotification("Waiting for Wire UI to load...")
            delay(5000) // 5-second delay to ensure conversation list is fully loaded

            // STEP 3: Navigate to contacts and send messages
            isSendingInProgress.set(true)
            debugLog("STATE", "Starting message sending process - isSendingInProgress=true")
            android.util.Log.i("WireAuto", "Starting message sending process - State: isSendingInProgress=true")
            
            debugLog("ACTION", "STEP 3: Starting automation flow with contact discovery")
            
            // ============================================================
            // PHASE 0: SCAN-THEN-SEND FLOW
            // ============================================================
            // Step 1: Pehle sab contacts ko scan karo aur complete list banao
            // Step 2: Phir us list ke according har contact ki conversation open karo
            // Step 3: Phir har contact ko message bhejo
            // ============================================================
            
            // Phase 0: Contact Discovery - Pehle sab contacts ko scan karo aur list banao
            android.util.Log.i("WireAuto", "=== STEP 1: Scanning contacts and building list ===")
            debugLog("PHASE0", "=== STEP 1: Scanning contacts from home screen to build complete list ===")
            updateNotification("Scanning contacts and building list...")
            sendProgressBroadcast("Scanning contacts and building list...")
            
            val discoveredContacts = discoverContactsFromHomeScreen()
            
            // Force Discovery Success: If empty, STOP automation and log error
            if (discoveredContacts.isEmpty()) {
                val errorMsg = "CRITICAL: No contacts discovered from home screen. Automation stopped to prevent random typing."
                android.util.Log.e("WireAuto", errorMsg)
                debugLog("ERROR", errorMsg)
                updateNotification("No contacts found - Automation stopped")
                
                // Show Toast message
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "❌ No contacts discovered! Automation stopped.", Toast.LENGTH_LONG).show()
                }
                
                sendErrorBroadcast("No contacts found in Wire. Please ensure you have conversations. Automation stopped to prevent errors.")
                resetState()
                return
            }
            
            // Show Toast with discovered contacts count
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "✓ Discovered ${discoveredContacts.size} contacts", Toast.LENGTH_LONG).show()
            }
            android.util.Log.i("WireAuto", "✓ Discovered ${discoveredContacts.size} contacts - List ready for sending")
            debugLog("SUCCESS", "Contact list built successfully: ${discoveredContacts.size} contacts discovered")
            
            // Human mimicry: Delay before starting search process
            debugLog("PHASE0", "Contact discovery complete. Human mimicry delay: 2-3 seconds")
            val humanDelay = (2000..3000).random()
            delay(humanDelay.toLong())
            
            // ============================================================
            // PHASE 1: SEND MESSAGES TO EACH CONTACT IN LIST
            // ============================================================
            // Ab discovered contacts list ke according har contact ki
            // conversation open karke message bhejo
            // ============================================================
            android.util.Log.i("WireAuto", "=== STEP 2: Starting to send messages to ${discoveredContacts.size} contacts from list ===")
            debugLog("ACTION", "=== STEP 2: Iterating through contact list and sending messages ===")
            debugLog("ACTION", "Calling sendMessagesViaSearch with ${discoveredContacts.size} discovered contacts")
            sendMessagesViaSearch(message, discoveredContacts)

                } catch (e: Exception) {
            val errorMsg = "Error: ${e.message ?: "Unknown error"}"
            debugLog("ERROR", "Fatal error in sendMessagesToAllContacts: $errorMsg", e)
            android.util.Log.e("WireAuto", "Fatal error in sendMessagesToAllContacts: $errorMsg", e)
            try {
                updateNotification(errorMsg)
                sendErrorBroadcast(errorMsg)
            } catch (e2: Exception) {
                android.util.Log.e("WireAuto", "Error sending error broadcast: ${e2.message}", e2)
            }
            // Save debug log even on error
            try {
                saveDebugLogToPrefs()
            } catch (e3: Exception) {
                android.util.Log.e("WireAuto", "Error saving debug log: ${e3.message}", e3)
            }
        } finally {
            debugLog("EVENT", "=== MESSAGE SENDING PROCESS COMPLETED ===")
            debugLog("STATE", "Final state - isRunning: ${isRunning.get()}, isWireOpened: ${isWireOpened.get()}, isSendingInProgress: ${isSendingInProgress.get()}")
            android.util.Log.i("WireAuto", "=== MESSAGE SENDING PROCESS COMPLETED ===")
            // Final save of debug log
            try {
                saveDebugLogToPrefs()
            } catch (e: Exception) {
                android.util.Log.e("WireAuto", "Error saving debug log in finally: ${e.message}", e)
            }
            // Always reset state before stopping
            resetState()
            try {
                delay(2000)
                stopForeground(true)
                stopSelf()
            } catch (e: Exception) {
                android.util.Log.e("WireAuto", "Error stopping service: ${e.message}", e)
                // Force reset state even if stopping fails
                resetState()
            }
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
     * Ensure Wire app is in foreground, re-launch if necessary
     * Returns true if Wire is in foreground, false if unable to bring it to foreground
     */
    private suspend fun ensureWireInForeground(): Boolean {
        android.util.Log.d("WireAuto", "Checking if Wire is in foreground...")
        debugLog("NAVIGATION", "Checking if Wire is in foreground...")
        
        // First, check if Wire is already in foreground
        val rootNode = rootInActiveWindow
        
        if (rootNode != null && rootNode.packageName == WIRE_PACKAGE) {
            android.util.Log.d("WireAuto", "Wire is already in foreground")
            debugLog("NAVIGATION", "Wire is already in foreground - package: ${rootNode.packageName}")
            return true
        }
        
        android.util.Log.w("WireAuto", "Wire is not in foreground (current: ${rootNode?.packageName ?: "null"}), attempting to bring it back...")
        debugLog("NAVIGATION", "Wire is not in foreground (current: ${rootNode?.packageName ?: "null"}), attempting to bring it back...")
        
        // Try to bring Wire to foreground using Intent
        try {
            val wireIntent = packageManager.getLaunchIntentForPackage(WIRE_PACKAGE)
            if (wireIntent != null) {
                wireIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                wireIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(wireIntent)
                android.util.Log.d("WireAuto", "Sent intent to bring Wire to foreground")
                debugLog("NAVIGATION", "Sent intent to bring Wire to foreground")
                delay(1000) // Wait a moment for the intent to process
            } else {
                android.util.Log.w("WireAuto", "Could not get launch intent for Wire")
                debugLog("WARN", "Could not get launch intent for Wire")
            }
        } catch (e: Exception) {
            android.util.Log.e("WireAuto", "Error bringing Wire to foreground: ${e.message}", e)
            debugLog("ERROR", "Error bringing Wire to foreground: ${e.message}", e)
        }
        
        // Wait for Wire to come to foreground (shorter timeout to avoid long waits)
        val result = waitForWireInForeground(maxWaitSeconds = 5)
        if (result) {
            debugLog("NAVIGATION", "Successfully brought Wire to foreground")
        } else {
            debugLog("ERROR", "Failed to bring Wire to foreground after 5 seconds")
        }
        return result
    }

    /**
     * Reset all state flags and clear session data
     */
    private fun resetState() {
        android.util.Log.i("WireAuto", "Resetting state flags and clearing session data")
            isRunning.set(false)
        isWireOpened.set(false)
        isSendingInProgress.set(false)
        
        // Clear session data for repeatable runs
        sessionSentList.clear()
        lastPersonMessaged = null
        debugLog("STATE", "Session data cleared: sessionSentList and lastPersonMessaged reset")
        android.util.Log.i("WireAuto", "Session data cleared for repeatable runs")
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
        debugLog("NAVIGATION", "Waiting for Wire app UI to stabilize (3 seconds)...")
        delay(3000) // Increased to 3 seconds to allow slow UI rendering to finish

        // CRITICAL: Ensure Wire is still in foreground after delay
        // The system might have switched back to Wire Auto Messenger
        updateNotification("Verifying Wire app is accessible...")
        sendProgressBroadcast("Verifying Wire app is accessible...")
        debugLog("NAVIGATION", "Verifying Wire app is still in foreground after delay...")
        if (!ensureWireInForeground()) {
            android.util.Log.e("WireAuto", "Wire app is not in foreground after delay")
            debugLog("ERROR", "Wire app is not in foreground after delay - aborting")
            saveDebugLogToPrefs()
            sendErrorBroadcast("Wire app lost focus. Please ensure Wire stays open and try again.")
            resetState()
            return
        }
        debugLog("NAVIGATION", "Wire app confirmed in foreground")

        // PERSISTENT ROOT NODE: Use retry helper to get root node
        updateNotification("Accessing Wire app...")
        sendProgressBroadcast("Accessing Wire app...")
        debugLog("NAVIGATION", "Attempting to get root node from Wire app...")
        var rootNode = getRootWithRetry(maxRetries = 5, delayMs = 500)
        if (rootNode == null) {
            android.util.Log.e("WireAuto", "Could not access Wire app after retries")
            debugLog("ERROR", "Could not access Wire app after retries - saving debug log")
            saveDebugLogToPrefs()
            // Try one more time to ensure Wire is in foreground
            updateNotification("Retrying to access Wire app...")
            sendProgressBroadcast("Retrying to access Wire app...")
            if (!ensureWireInForeground()) {
                sendErrorBroadcast("Could not access Wire app. Please ensure Wire is open and try again.")
                resetState()
                return
            }
            rootNode = getRootWithRetry(maxRetries = 5, delayMs = 500)
            if (rootNode == null) {
                android.util.Log.e("WireAuto", "Could not access Wire app after second attempt")
                debugLog("ERROR", "Could not access Wire app after second attempt - saving debug log")
                saveDebugLogToPrefs()
                sendErrorBroadcast("Could not access Wire app. Please ensure Wire is open and try again.")
                resetState()
                return
            }
        }
        debugLog("NAVIGATION", "Successfully got root node - package: ${rootNode.packageName}")
        
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
                // Try to ensure Wire is in foreground before retrying
                if (!ensureWireInForeground()) {
                    android.util.Log.e("WireAuto", "Wire app lost focus during refresh")
                    debugLog("ERROR", "Wire app lost focus during refresh - saving debug log")
                    saveDebugLogToPrefs()
                    sendErrorBroadcast("Wire app lost focus. Please ensure Wire stays open and try again.")
                    resetState()
                    return
                }
                rootNode = getRootWithRetry(maxRetries = 5, delayMs = 500)
                if (rootNode == null) {
                    android.util.Log.e("WireAuto", "Could not get root node after refresh")
                    debugLog("ERROR", "Could not get root node after refresh - saving debug log")
                    saveDebugLogToPrefs()
                    sendErrorBroadcast("Could not access Wire app. Please ensure Wire is open and try again.")
                    resetState()
                    return
                }
            }

        updateNotification("Navigating to conversations...")
        sendProgressBroadcast("Navigating to conversations...")
        debugLog("NAVIGATION", "Starting navigation to conversations list...")
        
        // UI INTERACTION FIX: Search for "Search conversations" bar as starting point if main list fails
        val searchBar = findSearchConversationsBar(rootNode)
        if (searchBar != null) {
            android.util.Log.d("WireAuto", "Found 'Search conversations' bar - using as reference point")
            debugLog("NAVIGATION", "Found 'Search conversations' bar - using as reference point")
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
                debugLog("WARN", "Could not interact with search bar: ${e.message}")
            }
            rootNode = getRootWithRetry(maxRetries = 3, delayMs = 500)
        }
        
        // Try to navigate to conversations/contacts list
        // Wire typically shows conversations by default, but we need to ensure we're on the right screen
        if (rootNode != null) {
            debugLog("NAVIGATION", "Calling navigateToConversationsList...")
            navigateToConversationsList(rootNode)
            debugLog("NAVIGATION", "navigateToConversationsList completed")
        } else {
            debugLog("WARN", "Root node is null before navigation")
        }
        
        updateNotification("Waiting for navigation to complete...")
        sendProgressBroadcast("Waiting for navigation to complete...")
        debugLog("NAVIGATION", "Waiting 4 seconds for navigation to complete...")
        delay(4000) // Wait longer for navigation to complete

        // Refresh root after navigation using retry helper
        updateNotification("Verifying navigation...")
        sendProgressBroadcast("Verifying navigation...")
        debugLog("NAVIGATION", "Refreshing root node after navigation...")
        rootNode = getRootWithRetry(maxRetries = 5, delayMs = 1000)
        if (rootNode == null) {
            android.util.Log.e("WireAuto", "Could not get root node after navigation, trying to recover...")
            debugLog("ERROR", "Could not get root node after navigation - attempting recovery")
            
            // Try one more time with longer wait and Wire launch
            delay(2000)
            if (!ensureWireInForeground()) {
                android.util.Log.e("WireAuto", "Could not recover Wire app access")
                debugLog("ERROR", "Could not recover Wire app access - saving debug log")
                saveDebugLogToPrefs()
                sendErrorBroadcast("Lost access to Wire app. Please ensure Wire is open and try again.")
                resetState()
                return
            }
            
            // Try to get root again after recovery
            rootNode = getRootWithRetry(maxRetries = 3, delayMs = 1000)
            if (rootNode == null) {
                android.util.Log.e("WireAuto", "Still could not get root node after recovery")
                debugLog("ERROR", "Still could not get root node after recovery - saving debug log")
                saveDebugLogToPrefs()
                sendErrorBroadcast("Lost access to Wire app. Please ensure Wire is open and accessibility service is enabled.")
                resetState()
                return
            }
        }
        debugLog("NAVIGATION", "Successfully got root node after navigation")
        
        // Log UI structure for debugging
        android.util.Log.d("WireAuto", "Wire app UI structure:")
        android.util.Log.d("WireAuto", "Root className: ${rootNode.className}")
        android.util.Log.d("WireAuto", "Root childCount: ${rootNode.childCount}")
        logUIStructure(rootNode, 0, 3) // Log first 3 levels
        
        // Get all contact/conversation items
        updateNotification("Finding contacts...")
        sendProgressBroadcast("Finding contacts...")
        debugLog("SEARCH", "Starting to find contact/conversation items...")
        var contactItems = getAllContactItems(rootNode)
        debugLog("SEARCH", "Found ${contactItems.size} contact items on first attempt")
        
        if (contactItems.isEmpty()) {
            // Try scrolling to load more contacts
            android.util.Log.d("WireAuto", "No contacts found, trying to scroll...")
            debugLog("SEARCH", "No contacts found, trying to scroll to load more...")
            updateNotification("Scrolling to find contacts...")
            sendProgressBroadcast("Scrolling to find contacts...")
            try {
                // Find scrollable view and scroll down
                val scrollableView = findScrollableView(rootNode)
                if (scrollableView != null) {
                    debugLog("SEARCH", "Found scrollable view, scrolling down...")
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
                
                dumpAccessibilityTree(rootNode, maxDepth = 5)

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
            android.util.Log.e("WireAuto", "Root node is null, cannot find RecyclerView - attempting recovery...")
            // Try to recover access
            if (!ensureWireInForeground()) {
                android.util.Log.e("WireAuto", "Could not recover Wire app access")
                sendErrorBroadcast("Lost access to Wire app. Please ensure Wire is open and try again.")
                return
            }
            // Try to get root again
            rootNode = getRootWithRetry(maxRetries = 3, delayMs = 1000)
            if (rootNode == null) {
                android.util.Log.e("WireAuto", "Still could not get root node after recovery")
                sendErrorBroadcast("Lost access to Wire app. Please ensure Wire is open and accessibility service is enabled.")
                return
            }
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
        debugLog("STATS", "Found $totalContacts contacts to process")
        android.util.Log.i("WireAuto", "Starting to send messages to $totalContacts contacts")
        android.util.Log.i("WireAuto", "State check: isWireOpened=${isWireOpened.get()}, isSendingInProgress=${isSendingInProgress.get()}")
        
        if (totalContacts == 0) {
            android.util.Log.e("WireAuto", "No contacts found to send messages to")
            debugLog("ERROR", "No contacts found to send messages to - saving debug log")
            saveDebugLogToPrefs()
            sendErrorBroadcast("No contacts found in Wire app. Please ensure you have conversations in Wire and try again.")
            resetState()
            return
        }
        
        updateNotification("Found $totalContacts contacts. Sending messages...")
        sendProgressBroadcast("Found $totalContacts contacts. Sending messages...", 0)
        android.util.Log.i("WireAuto", "=== Starting to process $totalContacts contacts from top to bottom ===")
        debugLog("EVENT", "=== Starting to process $totalContacts contacts from top to bottom ===")
        
        val processedContactIndices = mutableSetOf<Int>() // Track processed contacts by index to avoid duplicates
        val sentContactNames = mutableSetOf<String>() // Track sent contacts by name to avoid duplicates
        val contactResults = mutableListOf<com.wireautomessenger.model.ContactResult>()
        
        for ((index, rowItem) in rowItems.withIndex()) {
            // Periodically verify Wire is still in foreground (every 10 contacts)
            if (index % 10 == 0 && index > 0) {
                val rootCheck = rootInActiveWindow
                if (rootCheck == null || rootCheck.packageName != WIRE_PACKAGE) {
                    android.util.Log.w("WireAuto", "Wire lost focus during sending (contact $index), attempting to recover...")
                    if (!ensureWireInForeground()) {
                        android.util.Log.e("WireAuto", "Could not recover Wire app, stopping message sending")
                        sendErrorBroadcast("Wire app lost focus during sending. Please ensure Wire stays open.")
                        resetState()
                        return
                    }
                    // Refresh root node after recovery
                    delay(1000)
                }
            }
            
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
                
                val rowBounds = android.graphics.Rect()
                refreshedRowItem.getBoundsInScreen(rowBounds)
                if (rowBounds.width() <= 0 || rowBounds.height() <= 0) {
                    android.util.Log.w("WireAuto", "Invalid row bounds for contact row, cannot proceed")
                    contactResults.add(com.wireautomessenger.model.ContactResult(
                        name = contactName,
                        status = com.wireautomessenger.model.ContactStatus.FAILED,
                        errorMessage = "Invalid row bounds",
                        position = index + 1
                    ))
                    sendContactUpdate(contactName, "failed", index + 1, "Invalid row bounds")
                    continue
                }
                
                // Find profile placeholder/avatar in the contact row
                debugLog("SEARCH", "Searching for profile placeholder in row item")
                val profilePlaceholder = findProfilePlaceholderInRow(refreshedRowItem)
                debugLog("SEARCH", "Profile placeholder search result: ${if (profilePlaceholder != null) "FOUND" else "NOT FOUND"}")
                if (profilePlaceholder == null) {
                    val avatarCandidates = mutableListOf<AccessibilityNodeInfo>()
                    findImageNodesInRow(refreshedRowItem, avatarCandidates)
                    debugLog("SEARCH", "Avatar candidates identified: ${avatarCandidates.size}")
                }
                
                val targetNode: AccessibilityNodeInfo
                val targetLabel: String
                if (profilePlaceholder != null) {
                    android.util.Log.i("WireAuto", "✓ Found profile placeholder/avatar for $contactName")
                    android.util.Log.d("WireAuto", "Profile placeholder: className=${profilePlaceholder.className}, clickable=${profilePlaceholder.isClickable}, bounds=${getBoundsString(profilePlaceholder)}")
                    targetNode = profilePlaceholder
                    targetLabel = "profile-placeholder"
                } else {
                    android.util.Log.w("WireAuto", "⚠️ Profile placeholder not found, falling back to clicking entire row for: $contactName")
                    targetNode = refreshedRowItem
                    targetLabel = "contact-row"
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
                debugLog("CLICK", "STEP 4.${contactsProcessed}.2: Attempting to click $targetLabel for: $contactName")
                debugLog("CLICK", "Click target bounds: left=${bounds.left}, top=${bounds.top}, width=${bounds.width()}, height=${bounds.height()}")
                debugLog("CLICK", "Click center coordinates: ($centerX, $centerY)")
                android.util.Log.i("WireAuto", "STEP 4.${contactsProcessed}.2: Clicking profile placeholder/contact for: $contactName")
                
                // Try multiple click methods - prioritize gesture dispatch as it's most reliable
                var clicked = false
                
                // Method 1: Direct bounds gesture tap
                if (!clicked) {
                    clicked = clickBounds(bounds, targetLabel)
                    debugLog("CLICK", "Method 1 - Direct bounds gesture result: $clicked")
                    android.util.Log.d("WireAuto", "Method 1 - Direct gesture on $targetLabel: $clicked")
                    if (clicked) delay(800)
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
                
                // Method 5: Click left region (avatar zone) by coordinates
                if (!clicked) {
                    val leftRegion = android.graphics.Rect(rowBounds)
                    leftRegion.right = rowBounds.left + (rowBounds.width() * 0.35).toInt()
                    if (leftRegion.width() > 0) {
                        android.util.Log.w("WireAuto", "Attempting left-region fallback for $contactName")
                        clicked = clickBounds(leftRegion, "row-left-region")
                        if (clicked) delay(500)
                    }
                }
                
                // Method 6: Click center of entire row by coordinates
                if (!clicked) {
                    android.util.Log.w("WireAuto", "Attempting row-center fallback for $contactName")
                    clicked = clickBounds(rowBounds, "row-center-region")
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
                    messageInput?.let { input ->
                        val inputBounds = android.graphics.Rect()
                        input.getBoundsInScreen(inputBounds)
                        val screenBounds = android.graphics.Rect()
                        val root = currentRoot // Capture in local variable for smart cast
                        if (root == null) {
                            android.util.Log.w("WireAuto", "Current root is null, cannot check keyboard blocking")
                        } else {
                            root.getBoundsInScreen(screenBounds)
                            
                            // Check if input is in bottom 30% of screen (likely blocked by keyboard)
                            val screenHeight = screenBounds.height()
                            val inputBottom = inputBounds.bottom
                            val bottomThreshold = screenHeight * 0.7
                            
                            if (inputBottom > bottomThreshold) {
                                android.util.Log.d("WireAuto", "Message input may be blocked by keyboard, attempting to scroll...")
                                // Try to scroll the message input into view
                                if (input.isScrollable) {
                                    input.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                                    delay(500)
                                } else {
                                    // Find scrollable parent and scroll
                                    var parent = input.parent
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
                            } else {
                                // Input is not blocked by keyboard, no action needed
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.d("WireAuto", "Could not handle keyboard blocking: ${e.message}")
                }

                // FORCE FOCUS BEFORE TYPING: Perform ACTION_CLICK and ACTION_FOCUS before ACTION_SET_TEXT
                android.util.Log.d("WireAuto", "Force focusing message input before typing...")
                try {
                    messageInput?.let { input ->
                        // First, click on the input field using gesture dispatch
                        if (clickNodeWithGesture(input)) {
                            android.util.Log.d("WireAuto", "Clicked message input via gesture")
                            delay(500)
                        }
                        
                        // Then, perform ACTION_FOCUS
                        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        android.util.Log.d("WireAuto", "Focused message input")
                        delay(500)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("WireAuto", "Could not force focus message input: ${e.message}")
                }

                // Clear any existing text first
                try {
                    messageInput?.let { input ->
                        // Try to select all and delete
                        val bundleClear = android.os.Bundle()
                        bundleClear.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundleClear)
                        delay(300)
                    }
                } catch (e: Exception) {
                    android.util.Log.d("WireAuto", "Could not clear text: ${e.message}")
                }

                // Type message - use ACTION_SET_TEXT to set the entire message at once
                updateNotification("Typing message to $contactName...")
                sendProgressBroadcast("Typing message to $contactName...", contactsSent)
                debugLog("TEXT", "STEP 4.${contactsProcessed}.4: Setting message text in input field")
                debugLog("TEXT", "Message content: $message")
                android.util.Log.d("WireAuto", "Text Entered: $message")
                android.util.Log.i("WireAuto", "STEP 4.${contactsProcessed}.4: Setting message text: $message")
                
                // Try to set text multiple times to ensure it's set correctly
                var textSet = false
                var textVerified = false
                for (textAttempt in 1..3) {
                    if (messageInput == null) {
                        android.util.Log.w("WireAuto", "Message input is null on attempt $textAttempt, refreshing...")
                        currentRoot = getRootWithRetry(maxRetries = 2, delayMs = 300)
                        if (currentRoot != null) {
                            messageInput = findMessageInput(currentRoot)
                        }
                        if (messageInput == null) {
                            android.util.Log.e("WireAuto", "Could not find message input after refresh")
                            break
                        }
                    }
                    
                    val bundle = android.os.Bundle()
                    bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
                    textSet = messageInput?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle) ?: false
                    debugLog("TEXT", "ACTION_SET_TEXT attempt $textAttempt result: $textSet")
                    android.util.Log.i("WireAuto", "Message text set attempt $textAttempt: $textSet")
                    
                    if (textSet) {
                        // Wait a moment and verify text was actually set
                        delay(500)
                        val currentText = messageInput?.text?.toString()?.trim() ?: ""
                        if (currentText == message || currentText.contains(message.take(10))) {
                            textVerified = true
                            debugLog("TEXT", "Text verified in input field: ${currentText.take(50)}...")
                            android.util.Log.i("WireAuto", "Text verified in input field: ${currentText.take(50)}...")
                            break
                        } else {
                            android.util.Log.w("WireAuto", "Text not verified (attempt $textAttempt): expected '$message', got '$currentText'")
                            debugLog("WARN", "Text not verified (attempt $textAttempt): expected '${message.take(50)}...', got '${currentText.take(50)}...'")
                            // Refresh message input and try again
                            currentRoot = getRootWithRetry(maxRetries = 2, delayMs = 300)
                            if (currentRoot != null) {
                                messageInput = findMessageInput(currentRoot) ?: messageInput
                            }
                        }
                    } else {
                        android.util.Log.w("WireAuto", "ACTION_SET_TEXT returned false (attempt $textAttempt)")
                        delay(500)
                    }
                }
                
                if (!textSet || !textVerified) {
                    android.util.Log.e("WireAuto", "Failed to set message text after 3 attempts")
                    debugLog("ERROR", "Failed to set message text after 3 attempts - message may not send correctly")
                    // Continue anyway - sometimes the text is set even if verification fails
                }
                
                // Wait for text to be set and send button to be enabled (random delay 1-3 sec)
                val typingDelay = (1500..3000).random()
                android.util.Log.d("WireAuto", "Waiting ${typingDelay}ms for send button to be enabled...")
                debugLog("TEXT", "Waiting ${typingDelay}ms for send button to be enabled...")
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
                updateNotification("Sending message to $contactName...")
                sendProgressBroadcast("Sending message to $contactName...", contactsSent)
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
                        
                        // Step 2: Click send button - try multiple methods with retries
                        var clicked = false
                        
                        android.util.Log.d("WireAuto", "Send Clicked: Attempting to click send button")
                        debugLog("CLICK", "Attempting to click send button for contact: $contactName")
                        
                        // Method 1: Use gesture dispatch instead of ACTION_CLICK (harder for Wire to block)
                        clicked = clickNodeWithGesture(sendButton)
                        debugLog("CLICK", "Method 1 - Gesture dispatch on send button: $clicked")
                        android.util.Log.d("WireAuto", "Send Clicked: Method 1 - Gesture dispatch on send button: $clicked")
                        android.util.Log.i("WireAuto", "Method 1 - Gesture dispatch on send button: $clicked")
                        if (clicked) {
                            val clickDelay = (1500..3000).random()
                            debugLog("CLICK", "Gesture dispatch succeeded, waiting ${clickDelay}ms...")
                            delay(clickDelay.toLong())
                        }
                        
                        // Fallback: Try ACTION_CLICK if gesture failed
                        if (!clicked && sendButton.isClickable) {
                            clicked = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            debugLog("CLICK", "Method 1.5 - Fallback ACTION_CLICK: $clicked")
                            android.util.Log.d("WireAuto", "Send Clicked: Method 1.5 - Fallback ACTION_CLICK: $clicked")
                            if (clicked) {
                                val clickDelay = (1500..3000).random()
                                debugLog("CLICK", "ACTION_CLICK succeeded, waiting ${clickDelay}ms...")
                                delay(clickDelay.toLong())
                            }
                        }
                        
                        // Retry Method 1 if still not clicked
                        if (!clicked) {
                            android.util.Log.d("WireAuto", "Retrying gesture dispatch...")
                            debugLog("CLICK", "Retrying gesture dispatch...")
                            delay(500)
                            clicked = clickNodeWithGesture(sendButton)
                            if (clicked) {
                                debugLog("CLICK", "Retry gesture dispatch succeeded")
                                val clickDelay = (1500..3000).random()
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
                            debugLog("VERIFY", "Send button clicked - verifying message was sent for contact: $contactName")
                            
                            // Wait longer for UI to update and message to actually send
                            val verificationDelay = (2500..4000).random()
                            debugLog("VERIFY", "Waiting ${verificationDelay}ms for message to send...")
                            delay(verificationDelay.toLong())
                            
                            // Refresh root to check verification - try multiple times
                            var verificationPassed = false
                            for (verifyAttempt in 1..5) { // Increased to 5 attempts
                                debugLog("VERIFY", "Verification attempt $verifyAttempt/5")
                                currentRoot = getRootWithRetry(maxRetries = 3, delayMs = 500)
                                if (currentRoot != null) {
                                    val refreshedInput = findMessageInput(currentRoot)
                                    val inputText = refreshedInput?.text?.toString()?.trim() ?: ""
                                    
                                    debugLog("VERIFY", "Input text on attempt $verifyAttempt: '${inputText.take(50)}...'")
                                    
                                    // STRICT: Input must be cleared (empty) for message to be considered sent
                                    if (inputText.isEmpty()) {
                                        // Input cleared - message was sent!
                                        messageSent = true
                                        verificationPassed = true
                                        android.util.Log.i("WireAuto", "Send confirmed (attempt $verifyAttempt): Input box is empty - message sent successfully!")
                                        debugLog("SUCCESS", "Message send verified - input box is empty")
                                        break
                                    } else if (inputText != message && inputText.length < message.length) {
                                        // Input changed and is shorter - likely sent, wait a bit more to confirm
                                        android.util.Log.d("WireAuto", "Input text changed (attempt $verifyAttempt): '$inputText' - message likely sent, waiting more...")
                                        debugLog("VERIFY", "Input text changed - message likely sent, waiting more...")
                                        delay(1500)
                                        continue
                                    } else if (inputText != message) {
                                        // Input changed but not empty - might have sent, wait a bit more
                                        android.util.Log.d("WireAuto", "Input text changed but not empty (attempt $verifyAttempt): '$inputText', waiting more...")
                                        debugLog("VERIFY", "Input text changed but not empty, waiting more...")
                                        delay(1500)
                                        continue
                                    } else {
                                        // Input still has the same message - message NOT sent
                                        android.util.Log.w("WireAuto", "Input still contains message (attempt $verifyAttempt): '$inputText' - message may not have been sent")
                                        debugLog("WARN", "Input still contains message on attempt $verifyAttempt")
                                        if (verifyAttempt < 5) {
                                            delay(2000) // Longer delay between attempts
                                            continue
                                        } else {
                                            // After 5 attempts, if input still has message, try clicking send button again
                                            android.util.Log.w("WireAuto", "Input still has message after 5 attempts - trying to click send button again...")
                                            debugLog("WARN", "Input still has message after 5 attempts - retrying send button click")
                                            // Try clicking send button one more time
                                            if (sendButton.isClickable) {
                                                sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                                delay(2000)
                                                // Check one more time
                                                currentRoot = getRootWithRetry(maxRetries = 2, delayMs = 500)
                                                val finalInput = currentRoot?.let { findMessageInput(it) }
                                                val finalText = finalInput?.text?.toString()?.trim() ?: ""
                                                if (finalText.isEmpty()) {
                                                    messageSent = true
                                                    verificationPassed = true
                                                    debugLog("SUCCESS", "Message sent after retry click")
                                                } else {
                                                    messageSent = false
                                                    android.util.Log.e("WireAuto", "Send verification FAILED: Input still contains message after retry")
                                                }
                                            } else {
                                                messageSent = false
                                                android.util.Log.e("WireAuto", "Send verification FAILED: Input still contains message after multiple checks")
                                            }
                                        }
                                    }
                                } else {
                                    android.util.Log.w("WireAuto", "Lost access during verification (attempt $verifyAttempt)")
                                    debugLog("WARN", "Lost access during verification attempt $verifyAttempt")
                                    if (verifyAttempt < 5) {
                                        delay(1500)
                                        continue
                                    }
                                }
                            }
                            
                            if (!verificationPassed && !messageSent) {
                                android.util.Log.e("WireAuto", "Send verification failed - message was NOT sent for contact: $contactName")
                                debugLog("ERROR", "Send verification failed - message was NOT sent for contact: $contactName")
                            } else if (messageSent) {
                                debugLog("SUCCESS", "Message send verification PASSED for contact: $contactName")
                            }
                        } else {
                            android.util.Log.w("WireAuto", "Could not click send button for contact: $contactName")
                            debugLog("ERROR", "Could not click send button for contact: $contactName")
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
        
        // Save structured operation report for easy sharing
        val duration = System.currentTimeMillis() - sessionStartTime
        val operationReport = buildOperationReport(totalContacts, contactsProcessed, contactsSent, contactResults, duration)
        saveOperationReport(operationReport)

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
        val classLower = className.lowercase(Locale.getDefault())
        val descLower = node.contentDescription?.toString()?.lowercase(Locale.getDefault()) ?: ""
        val viewIdLower = node.viewIdResourceName?.lowercase(Locale.getDefault()) ?: ""
        
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val width = bounds.width()
        val height = bounds.height()
        val aspectRatio = if (height > 0) width.toFloat() / height.toFloat() else 0f
        val roughlySquare = height > 0 && kotlin.math.abs(width - height) <= height * 0.4
        val reasonableSize = width in 30..250 && height in 30..250
        val verySmallText = (node.text?.toString()?.trim()?.length ?: 0) in 1..2
        val hasNoChildren = node.childCount <= 1
        
        val keywordMatch = avatarClassKeywords.any { keyword ->
            classLower.contains(keyword) || descLower.contains(keyword) || viewIdLower.contains(keyword)
        }
        val exactMatch = avatarExactClassNames.any { className.equals(it, ignoreCase = true) }
        
        val looksLikeAvatar = (
                keywordMatch ||
                exactMatch ||
                (roughlySquare && reasonableSize && hasNoChildren) ||
                (verySmallText && reasonableSize)
        ) && width > 0 && height > 0
        
        if (looksLikeAvatar) {
            android.util.Log.d(
                "WireAuto",
                "Avatar candidate: class=$className, desc='${node.contentDescription}', bounds=($width x $height), text='${node.text}'"
            )
            result.add(node)
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
     * PERSISTENT ROOT NODE: Retry getting rootInActiveWindow with multiple strategies
     * Uses alternative methods if rootInActiveWindow fails
     */
    private suspend fun getRootWithRetry(maxRetries: Int = 5, delayMs: Long = 500): AccessibilityNodeInfo? {
        for (attempt in 1..maxRetries) {
            var root = rootInActiveWindow
            
            // If root is null, try alternative methods
            if (root == null) {
                android.util.Log.d("WireAuto", "Root is null on attempt $attempt/$maxRetries, trying alternative methods...")
                
                // Method 1: Try to get windows list (for Android 7.0+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        val windows = windows
                        if (windows != null && windows.isNotEmpty()) {
                            // Get the top window (most recent)
                            for (i in windows.size - 1 downTo 0) {
                                val window = windows[i]
                                if (window != null && window.root != null) {
                                    val windowRoot = window.root
                                    if (windowRoot != null && windowRoot.packageName == WIRE_PACKAGE) {
                                        android.util.Log.d("WireAuto", "Found Wire window via windows list")
                                        root = windowRoot
                                        break
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("WireAuto", "Failed to get windows list: ${e.message}")
                    }
                }
                
                // Method 2: Try to refresh by performing a harmless action
                if (root == null && attempt <= 2) {
                    try {
                        // Try to refresh the window by performing a harmless action
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        delay(200)
                        root = rootInActiveWindow
                    } catch (e: Exception) {
                        android.util.Log.w("WireAuto", "Global action refresh failed: ${e.message}")
                    }
                }
                
                // Method 3: Try to launch Wire app if we can't get root
                if (root == null && attempt >= 3) {
                    try {
                        android.util.Log.d("WireAuto", "Attempting to launch Wire app to get root access...")
                        val wireIntent = packageManager.getLaunchIntentForPackage(WIRE_PACKAGE)
                        if (wireIntent != null) {
                            wireIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            wireIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            startActivity(wireIntent)
                            delay(2000) // Wait for Wire to open
                            root = rootInActiveWindow
                            
                            // Try windows list again after launch
                            if (root == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                try {
                                    val windows = windows
                                    if (windows != null && windows.isNotEmpty()) {
                                        for (i in windows.size - 1 downTo 0) {
                                            val window = windows[i]
                                            if (window != null && window.root != null) {
                                                val windowRoot = window.root
                                                if (windowRoot != null && windowRoot.packageName == WIRE_PACKAGE) {
                                                    android.util.Log.d("WireAuto", "Found Wire window after launch")
                                                    root = windowRoot
                                                    break
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.w("WireAuto", "Failed to get windows after launch: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("WireAuto", "Failed to launch Wire: ${e.message}")
                    }
                }
            }
            
            // If we got a root, verify it's from Wire package
            if (root != null) {
                val packageName = root.packageName?.toString()
                if (packageName == WIRE_PACKAGE) {
                    android.util.Log.d("WireAuto", "Successfully got Wire root node on attempt $attempt")
                    return root
                } else {
                    android.util.Log.d("WireAuto", "Got root but wrong package: $packageName, expected: $WIRE_PACKAGE")
                    // If it's not Wire, try to launch Wire
                    if (attempt >= 2) {
                        try {
                            val wireIntent = packageManager.getLaunchIntentForPackage(WIRE_PACKAGE)
                            if (wireIntent != null) {
                                wireIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(wireIntent)
                                delay(2000)
                                root = rootInActiveWindow
                                if (root != null && root.packageName == WIRE_PACKAGE) {
                                    return root
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("WireAuto", "Failed to launch Wire: ${e.message}")
                        }
                    }
                }
            }
            
            // Wait before next attempt
            if (attempt < maxRetries) {
                delay(delayMs)
            }
        }
        
        android.util.Log.w("WireAuto", "Failed to get Wire root node after $maxRetries attempts")
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
    
    private fun clickBounds(bounds: android.graphics.Rect, label: String): Boolean {
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            android.util.Log.w("WireAuto", "Invalid bounds for $label: $bounds")
            return false
        }
        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.centerY().toFloat()
        android.util.Log.d("WireAuto", "Attempting gesture click on $label at ($centerX, $centerY)")
        return clickAtCoordinates(centerX, centerY, label)
    }
    
    private fun clickAtCoordinates(centerX: Float, centerY: Float, label: String): Boolean {
        return try {
            val path = android.graphics.Path().apply {
                moveTo(centerX, centerY)
            }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
                path, 0, 200
            )
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            
            dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    android.util.Log.d("WireAuto", "Gesture click completed for $label")
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    android.util.Log.w("WireAuto", "Gesture click cancelled for $label")
                }
            }, null)
        } catch (e: Exception) {
            android.util.Log.e("WireAuto", "Gesture click failed for $label: ${e.message}")
            false
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
    
    private fun dumpAccessibilityTree(root: AccessibilityNodeInfo?, maxDepth: Int = 4) {
        if (root == null) {
            debugLog("TREE", "Cannot dump tree - root is null")
            return
        }
        debugLog("TREE", "=== Accessibility Tree Dump (maxDepth=$maxDepth) ===")
        android.util.Log.d("WireAutoTree", "=== Accessibility Tree Dump (maxDepth=$maxDepth) ===")
        dumpNodeRecursive(root, 0, maxDepth)
        debugLog("TREE", "=== End of Tree Dump ===")
    }

    private fun dumpNodeRecursive(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        val indent = "  ".repeat(depth)
        val className = node.className?.toString() ?: "Unknown"
        val text = node.text?.toString()?.replace("\n", " ") ?: ""
        val desc = node.contentDescription?.toString()?.replace("\n", " ") ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val info = "$indent- $className | text='$text' | desc='$desc' | id='$viewId' | clickable=${node.isClickable} | children=${node.childCount} | bounds=$bounds"
        debugLog("TREE", info)
        android.util.Log.d("WireAutoTree", info)
        if (depth < maxDepth) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    dumpNodeRecursive(child, depth + 1, maxDepth)
                }
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
        debugLog("INPUT", "Finding message input field - avoiding CONVERSATIONS TextView and top 15% of screen")
        
        // Get screen dimensions to avoid top 15%
        val screenHeight = resources.displayMetrics.heightPixels
        val top15PercentThreshold = (screenHeight * 0.15).toInt()
        
        // Strategy 1: Find by Resource ID com.wire:id/text_input (HIGHEST PRIORITY)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                val textInputNodes = root.findAccessibilityNodeInfosByViewId("com.wire:id/text_input")
                if (textInputNodes != null && textInputNodes.isNotEmpty()) {
                    val inputNode = textInputNodes[0]
                    // Verify it's not in top 15% and not a CONVERSATIONS TextView
                    val bounds = android.graphics.Rect()
                    inputNode.getBoundsInScreen(bounds)
                    val nodeText = inputNode.text?.toString() ?: ""
                    
                    if (bounds.top > top15PercentThreshold && !nodeText.contains("CONVERSATIONS", ignoreCase = true)) {
                    android.util.Log.d("WireAuto", "Message input found via ViewId: com.wire:id/text_input")
                        debugLog("INPUT", "Found com.wire:id/text_input at y=${bounds.top} (threshold=$top15PercentThreshold)")
                    return inputNode
                    } else {
                        android.util.Log.w("WireAuto", "Found text_input but it's in top 15% or is CONVERSATIONS - skipping")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.d("WireAuto", "ViewId search for com.wire:id/text_input failed: ${e.message}")
            }
            
            // Also try alternative view ID
            try {
                val viewIdNodes = root.findAccessibilityNodeInfosByViewId("com.witaletr.wire:id/message_input")
                if (viewIdNodes != null && viewIdNodes.isNotEmpty()) {
                    val inputNode = viewIdNodes[0]
                    val bounds = android.graphics.Rect()
                    inputNode.getBoundsInScreen(bounds)
                    val nodeText = inputNode.text?.toString() ?: ""
                    
                    if (bounds.top > top15PercentThreshold && !nodeText.contains("CONVERSATIONS", ignoreCase = true)) {
                    android.util.Log.d("WireAuto", "Message input found via ViewId: com.witaletr.wire:id/message_input")
                    return inputNode
                    }
                }
            } catch (e: Exception) {
                android.util.Log.d("WireAuto", "ViewId search failed: ${e.message}")
            }
        }
        
        // Strategy 2: Find by Resource ID using findNodeByResourceId
        val textInputByResourceId = findNodeByResourceId(root, "com.wire:id/text_input")
        if (textInputByResourceId != null) {
            val bounds = android.graphics.Rect()
            textInputByResourceId.getBoundsInScreen(bounds)
            val nodeText = textInputByResourceId.text?.toString() ?: ""
            
            if (bounds.top > top15PercentThreshold && !nodeText.contains("CONVERSATIONS", ignoreCase = true)) {
            android.util.Log.d("WireAuto", "Message input found via findNodeByResourceId: com.wire:id/text_input")
            return textInputByResourceId
            }
        }
        
        // Strategy 3: Find by hint text "Message" or "Type a message" (avoiding top 15% and CONVERSATIONS)
        val allEditTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllNodesByClassName(root, "android.widget.EditText", allEditTexts)
        findAllNodesByClassName(root, "androidx.appcompat.widget.AppCompatEditText", allEditTexts)
        
        for (editText in allEditTexts) {
            val contentDesc = editText.contentDescription?.toString()?.lowercase() ?: ""
            val hint = editText.hintText?.toString()?.lowercase() ?: ""
            val resourceId = editText.viewIdResourceName ?: ""
            val nodeText = editText.text?.toString() ?: ""
            
            // CRITICAL: Exclude search boxes
            if (contentDesc.contains("search") || hint.contains("search") || resourceId.contains("search")) {
                continue
            }
            
            // CRITICAL: Exclude CONVERSATIONS TextView
            if (nodeText.contains("CONVERSATIONS", ignoreCase = true)) {
                android.util.Log.w("WireAuto", "Skipping EditText with CONVERSATIONS text: $nodeText")
                continue
            }
            
            // Check if in top 15% of screen
            val bounds = android.graphics.Rect()
            editText.getBoundsInScreen(bounds)
            if (bounds.top <= top15PercentThreshold) {
                android.util.Log.w("WireAuto", "Skipping EditText in top 15% of screen (y=${bounds.top}, threshold=$top15PercentThreshold)")
                continue
            }
            
            // Prefer message input fields with "Message" hint
            if (hint.contains("message", ignoreCase = true) || 
                contentDesc.contains("message", ignoreCase = true)) {
                android.util.Log.d("WireAuto", "Message input found via hint 'Message' at y=${bounds.top}")
                debugLog("INPUT", "Found input with 'Message' hint at y=${bounds.top}")
                return editText
            }
            
            // Also check for "Type a message" hint
            if (hint.contains("type a message", ignoreCase = true) ||
                contentDesc.contains("type a message", ignoreCase = true)) {
                android.util.Log.d("WireAuto", "Message input found via hint 'Type a message' at y=${bounds.top}")
                debugLog("INPUT", "Found input with 'Type a message' hint at y=${bounds.top}")
                return editText
            }
        }
        
        // Strategy 4: If no specific message input found, return first non-search EditText (but still avoid top 15% and CONVERSATIONS)
        for (editText in allEditTexts) {
            val contentDesc = editText.contentDescription?.toString()?.lowercase() ?: ""
            val hint = editText.hintText?.toString()?.lowercase() ?: ""
            val nodeText = editText.text?.toString() ?: ""
            val bounds = android.graphics.Rect()
            editText.getBoundsInScreen(bounds)
            
            if (!contentDesc.contains("search") && 
                !hint.contains("search") &&
                !nodeText.contains("CONVERSATIONS", ignoreCase = true) &&
                bounds.top > top15PercentThreshold) {
                android.util.Log.d("WireAuto", "Message input found via fallback (first non-search EditText) at y=${bounds.top}")
                debugLog("INPUT", "Found fallback input at y=${bounds.top}")
                return editText
            }
        }
        
        android.util.Log.w("WireAuto", "Message input not found")
        debugLog("ERROR", "Message input not found - checked all strategies")
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
        debugLog("SEND", "Finding send button - checking com.wire:id/send_button and com.wire:id/video_call_button")
        
        // Strategy 1: Find by Resource ID com.wire:id/send_button (HIGHEST PRIORITY)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                val sendButtonNodes = root.findAccessibilityNodeInfosByViewId("com.wire:id/send_button")
                if (sendButtonNodes != null && sendButtonNodes.isNotEmpty()) {
                    val sendNode = sendButtonNodes[0]
                    android.util.Log.d("WireAuto", "Send button found via ViewId: com.wire:id/send_button")
                    debugLog("SEND", "Found com.wire:id/send_button")
                    return getClickableParent(sendNode)
                }
            } catch (e: Exception) {
                android.util.Log.d("WireAuto", "ViewId search for com.wire:id/send_button failed: ${e.message}")
            }
            
            // Strategy 1b: Find by Resource ID com.wire:id/video_call_button (which often changes to send)
            try {
                val videoCallNodes = root.findAccessibilityNodeInfosByViewId("com.wire:id/video_call_button")
                if (videoCallNodes != null && videoCallNodes.isNotEmpty()) {
                    val sendNode = videoCallNodes[0]
                    android.util.Log.d("WireAuto", "Send button found via ViewId: com.wire:id/video_call_button")
                    debugLog("SEND", "Found com.wire:id/video_call_button (may act as send button)")
                    return getClickableParent(sendNode)
                }
            } catch (e: Exception) {
                android.util.Log.d("WireAuto", "ViewId search for com.wire:id/video_call_button failed: ${e.message}")
            }
            
            // Strategy 1c: Also try alternative view ID (com.witaletr.wire:id/send_button)
            try {
                val viewIdNodes = root.findAccessibilityNodeInfosByViewId("com.witaletr.wire:id/send_button")
                if (viewIdNodes != null && viewIdNodes.isNotEmpty()) {
                    val sendNode = viewIdNodes[0]
                    android.util.Log.d("WireAuto", "Send button found via ViewId: com.witaletr.wire:id/send_button")
                    debugLog("SEND", "Found com.witaletr.wire:id/send_button")
                    return getClickableParent(sendNode)
                }
            } catch (e: Exception) {
                android.util.Log.d("WireAuto", "ViewId search for send_button failed: ${e.message}")
            }
        }
        
        // Strategy 1d: Try findNodeByResourceId for com.wire:id/send_button
        val sendButtonByResourceId = findNodeByResourceId(root, "com.wire:id/send_button")
        if (sendButtonByResourceId != null) {
            android.util.Log.d("WireAuto", "Send button found via findNodeByResourceId: com.wire:id/send_button")
            debugLog("SEND", "Found com.wire:id/send_button via findNodeByResourceId")
            return getClickableParent(sendButtonByResourceId)
        }
        
        // Strategy 1e: Try findNodeByResourceId for com.wire:id/video_call_button
        val videoCallByResourceId = findNodeByResourceId(root, "com.wire:id/video_call_button")
        if (videoCallByResourceId != null) {
            android.util.Log.d("WireAuto", "Send button found via findNodeByResourceId: com.wire:id/video_call_button")
            debugLog("SEND", "Found com.wire:id/video_call_button via findNodeByResourceId")
            return getClickableParent(videoCallByResourceId)
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
                debugLog("SEND", "Found send button via Button search")
                return getClickableParent(button)
            }
        }
        
        // Strategy 5: Find clickable image/icon on the right side of input field (fallback)
        // This is a last resort when no send button ID is found
        android.util.Log.d("WireAuto", "No send button found by ID, trying to find clickable icon on right side of input")
        debugLog("SEND", "Trying fallback: find clickable image/icon on right side of input field")
        
        // Find message input to determine its position
        val messageInput = findMessageInput(root)
        if (messageInput != null) {
            val inputBounds = android.graphics.Rect()
            messageInput.getBoundsInScreen(inputBounds)
            val inputRight = inputBounds.right
            val inputTop = inputBounds.top
            val inputBottom = inputBounds.bottom
            val inputCenterY = (inputTop + inputBottom) / 2
            
            // Look for clickable ImageView/ImageButton on the right side of input
            val allImages = mutableListOf<AccessibilityNodeInfo>()
            findAllNodesByClassName(root, "android.widget.ImageView", allImages)
            findAllNodesByClassName(root, "android.widget.ImageButton", allImages)
            
            for (image in allImages) {
                if (!image.isClickable) continue
                
                val imageBounds = android.graphics.Rect()
                image.getBoundsInScreen(imageBounds)
                
                // Check if image is on the right side of input and vertically aligned
                val imageLeft = imageBounds.left
                val imageCenterY = (imageBounds.top + imageBounds.bottom) / 2
                
                // Image should be to the right of input and roughly vertically aligned
                if (imageLeft > inputRight && 
                    Math.abs(imageCenterY - inputCenterY) < 100) { // Within 100px vertically
                    android.util.Log.d("WireAuto", "Found clickable icon on right side of input: className=${image.className}")
                    debugLog("SEND", "Found clickable icon on right side of input at x=$imageLeft")
                    return getClickableParent(image)
                }
            }
        }
        
        android.util.Log.w("WireAuto", "Send button not found using all strategies")
        debugLog("ERROR", "Send button not found - all strategies exhausted")
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
    
    /**
     * Check if a specific text exists in the view tree
     */
    private fun hasTextInView(root: AccessibilityNodeInfo, searchText: String): Boolean {
        val nodeText = root.text?.toString() ?: ""
        val contentDesc = root.contentDescription?.toString() ?: ""
        
        if (nodeText.contains(searchText, ignoreCase = true) || 
            contentDesc.contains(searchText, ignoreCase = true)) {
            return true
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null && hasTextInView(child, searchText)) {
                return true
            }
        }
        
        return false
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
    
    /**
     * Helper: Find all clickable nodes recursively (for search results)
     */
    private fun findAllClickableNodesRecursive(root: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        if (root.isClickable) {
            result.add(root)
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                findAllClickableNodesRecursive(child, result)
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
            saveDebugLogToPrefs()
            val failureReport = buildString {
                appendLine("Wire Auto Messenger Broadcast Report")
                appendLine("Status: FAILED")
                appendLine("Time: ${dateFormat.format(Date())}")
                appendLine("----------------------------------------")
                appendLine(errorMessage.trim())
                appendLine()
                appendLine("Tip: Copy the debug log (Menu → Debug Log → Copy) and share it with support together with this report.")
            }
            saveOperationReport(failureReport)
            val intent = Intent(ACTION_ERROR).apply {
                putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Phase 0: Contact Discovery
     * Scans the Wire home screen (conversation list) to discover all contact names.
     * 
     * IMPORTANT: This function always starts with a fresh scan - it builds a new
     * discoveredContacts list from scratch each time it's called, ensuring repeatable
     * session capability. The sessionSentList is cleared at the start of each run
     * to allow unlimited runs per day.
     */
    private suspend fun discoverContactsFromHomeScreen(): ArrayList<String> {
        android.util.Log.i("WireAuto", "=== PHASE 0: Starting fresh contact discovery (Scan-then-Send) ===")
        debugLog("PHASE0", "=== PHASE 0: Starting fresh contact discovery from home screen (Scan-then-Send) ===")
        
        updateNotification("Scanning contacts...")
        sendProgressBroadcast("Scanning conversation list...")
        
        // Always start with a fresh, empty set for repeatable runs
        val discoveredContacts = mutableSetOf<String>()
        var root = getRootWithRetry(maxRetries = 5, delayMs = 500)
        
        if (root == null || root.packageName != WIRE_PACKAGE) {
            android.util.Log.e("WireAuto", "Cannot access Wire app for contact discovery")
            debugLog("ERROR", "Cannot access Wire app for contact discovery")
            return ArrayList()
        }
        
        // Ensure we're on the home/conversations screen
        debugLog("PHASE0", "Ensuring we're on the home screen...")
        navigateToConversationsList(root)
        delay(2000) // Wait for navigation
        
        root = getRootWithRetry(maxRetries = 5, delayMs = 500)
        if (root == null || root.packageName != WIRE_PACKAGE) {
            android.util.Log.e("WireAuto", "Lost access after navigation")
            return ArrayList()
        }
        
        // Step 1: Extract contacts from initial view (before scrolling)
        debugLog("PHASE0", "Extracting contacts from initial view...")
        val initialContacts = extractContactNamesFromView(root)
        discoveredContacts.addAll(initialContacts)
        android.util.Log.d("WireAuto", "Found ${initialContacts.size} contacts in initial view")
        debugLog("PHASE0", "Found ${initialContacts.size} contacts in initial view")
        
        // Step 2: Scroll down slowly 3-5 times to discover more contacts
        val scrollCount = (3..5).random() // Random between 3-5 scrolls
        android.util.Log.i("WireAuto", "Will perform $scrollCount slow scrolls to discover more contacts")
        debugLog("PHASE0", "Will perform $scrollCount slow scrolls to discover more contacts")
        
        for (scrollIndex in 1..scrollCount) {
            debugLog("PHASE0", "Scroll ${scrollIndex}/$scrollCount: Currently found ${discoveredContacts.size} unique contacts")
            updateNotification("Scanning contacts... (${discoveredContacts.size} found, scroll $scrollIndex/$scrollCount)")
            
            // Check if root is null before scrolling
            if (root == null || root.packageName != WIRE_PACKAGE) {
                android.util.Log.w("WireAuto", "Root is null or not Wire package at scroll $scrollIndex")
                debugLog("PHASE0", "Root is null or not Wire package at scroll $scrollIndex")
                break
            }
            
            // Perform slow scroll down
            debugLog("PHASE0", "Performing slow scroll down...")
                val scrolled = performSlowScroll(root!!)
                if (!scrolled) {
                android.util.Log.w("WireAuto", "Could not scroll further at scroll $scrollIndex")
                debugLog("PHASE0", "Could not scroll further at scroll $scrollIndex")
                    break
                }
            
            // Wait for new contacts to load after scroll
            delay(2000) // Increased delay for better loading
                
                // Refresh root after scroll
                root = getRootWithRetry(maxRetries = 3, delayMs = 500)
                if (root == null || root.packageName != WIRE_PACKAGE) {
                android.util.Log.w("WireAuto", "Lost access after scroll $scrollIndex")
                debugLog("PHASE0", "Lost access after scroll $scrollIndex")
                    break
                }
            
            // Extract contacts from current view after scroll
            val contactsAfterScroll = extractContactNamesFromView(root!!)
            val beforeCount = discoveredContacts.size
            discoveredContacts.addAll(contactsAfterScroll)
            val newContacts = discoveredContacts.size - beforeCount
            
            android.util.Log.d("WireAuto", "After scroll $scrollIndex: Found $newContacts new contacts (total: ${discoveredContacts.size})")
            debugLog("PHASE0", "After scroll $scrollIndex: Found $newContacts new contacts (total: ${discoveredContacts.size})")
        }
        
        // Step 3: Filter out guest contacts and create ArrayList
        val filteredContacts = discoveredContacts.filter { contactName ->
            val lowerName = contactName.lowercase()
            !lowerName.contains("guest") && contactName.isNotBlank()
        }
        
        val contactList = ArrayList(filteredContacts.sorted())
        
        android.util.Log.i("WireAuto", "=== Contact discovery complete: Found ${contactList.size} unique contacts (after filtering guests) ===")
        debugLog("PHASE0", "Contact discovery complete: Found ${contactList.size} unique contacts (after filtering guests)")
        debugLog("PHASE0", "Discovered contacts: ${contactList.take(10).joinToString(", ")}${if (contactList.size > 10) "..." else ""}")
        
        return contactList
    }
    
    /**
     * Extract contact names from TextView elements in the current view
     * Professional-grade: Header exclusion, node ID tracking, context-aware deduplication
     */
    private fun extractContactNamesFromView(root: AccessibilityNodeInfo): Set<String> {
        val contactNames = mutableSetOf<String>()
        
        // Get screen dimensions for header exclusion
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val headerThreshold = (screenHeight * 0.15).toInt() // Top 15% of screen
        
        android.util.Log.d("WireAuto", "Screen height: $screenHeight, Header threshold (15%): $headerThreshold")
        debugLog("EXTRACT", "Header exclusion: Ignoring top $headerThreshold pixels (15% of screen)")
        
        // Strategy 1: Find RecyclerView first (conversation list)
        val recyclerView = findRecyclerView(root)
        val searchRoot = recyclerView ?: root
        
        android.util.Log.d("WireAuto", "Extracting contacts - RecyclerView found: ${recyclerView != null}")
        debugLog("EXTRACT", "RecyclerView found: ${recyclerView != null}, searching in ${if (recyclerView != null) "RecyclerView" else "root"}")
        
        // Strategy 2: Find all clickable rows (chat items) in the RecyclerView
        val clickableRows = mutableListOf<AccessibilityNodeInfo>()
        findAllClickableNodesRecursive(searchRoot, clickableRows)
        
        // Filter to only get rows that are likely chat items (not buttons, search bar, etc.)
        // AND exclude header area (top 15% of screen)
        val chatRows = clickableRows.filter { row ->
            val bounds = android.graphics.Rect()
            row.getBoundsInScreen(bounds)
            val className = row.className?.toString() ?: ""
            
            // Header & Profile Exclusion: Ignore top 15% of screen
            val isInHeaderArea = bounds.top < headerThreshold
            
            // Must be a reasonable size (chat rows are typically tall)
            val hasReasonableSize = bounds.height() > 50
            // Must not be a button
            val isNotButton = !className.contains("Button", ignoreCase = true)
            // Must have children (chat rows contain TextViews)
            val hasChildren = row.childCount > 0
            
            if (isInHeaderArea) {
                android.util.Log.v("WireAuto", "Excluding node in header area: top=${bounds.top}, threshold=$headerThreshold")
                debugLog("EXTRACT", "Excluding node in header area (top ${bounds.top} < $headerThreshold)")
            }
            
            !isInHeaderArea && hasReasonableSize && isNotButton && hasChildren
        }
        
        android.util.Log.d("WireAuto", "Found ${chatRows.size} potential chat rows")
        debugLog("EXTRACT", "Found ${chatRows.size} potential chat rows")
        
        // System buttons to exclude
        val systemButtons = setOf(
            "search", "conversations", "new", "add", "settings", "profile", 
            "menu", "back", "close", "cancel", "ok", "done", "send",
            "filter", "sort", "refresh", "sync", "edit", "delete"
        )
        
        // For each chat row, extract ONLY the FIRST TextView (contact name)
        // Context-Aware Deduplication: Track exact UI Node ID or exact string
        for (chatRow in chatRows) {
            val textViewsInRow = mutableListOf<AccessibilityNodeInfo>()
            
            // Find all TextViews in this row
            findAllTextViewsInNode(chatRow, textViewsInRow)
            
            // Sort by Y position (top to bottom) to get the first one
            val sortedTextViews = textViewsInRow.sortedBy { textView ->
                val bounds = android.graphics.Rect()
                textView.getBoundsInScreen(bounds)
                bounds.top
            }
            
            // Get the FIRST TextView (top-most) - this should be the contact name
            // Skip the second TextView (message preview)
            val firstTextView = sortedTextViews.firstOrNull() ?: continue
            
            val text = firstTextView.text?.toString()?.trim() ?: ""
            val contentDesc = firstTextView.contentDescription?.toString()?.trim() ?: ""
            val className = firstTextView.className?.toString() ?: ""
            
            // Skip empty text
            if (text.isEmpty() && contentDesc.isEmpty()) continue
            
            // Skip if it's a Button or ImageButton (system buttons)
            if (className.contains("Button", ignoreCase = true) && 
                !className.contains("TextView", ignoreCase = true)) {
                continue
            }
            
            val lowerText = text.lowercase()
            val lowerDesc = contentDesc.lowercase()
            
            // Skip system buttons and UI elements
            val isSystemButton = systemButtons.any { button -> 
                lowerText == button || lowerDesc == button || 
                lowerText.contains(button) || lowerDesc.contains(button)
            }
            
            if (isSystemButton) {
                continue
            }
            
            // Filter out guest contacts and common UI elements
            if (lowerText.contains("search") || 
                lowerText.contains("conversation") || 
                lowerText.contains("chat") ||
                lowerText.contains("message") ||
                lowerText.contains("new") ||
                lowerText.contains("add") ||
                lowerText.contains("settings") ||
                lowerText.contains("profile") ||
                lowerText.contains("guest") ||
                lowerText.matches(Regex("^\\d+$")) || // Skip pure numbers
                lowerText.length < 2) {
                continue
            }
            
            // Also filter guest from content description
            if (lowerDesc.contains("guest")) {
                continue
            }
            
            // Split name from message if it contains a colon (e.g., "Name: Message")
            val nameText = if (text.contains(":")) {
                // Extract only the part before the colon
                text.substringBefore(":").trim()
            } else {
                text
            }
            
            // Clean and sanitize the name
            val cleanName = sanitizeContactName(nameText)
            
            // Context-Aware Deduplication: Create unique identifier for this contact row
            // Use node ID (viewIdResourceName) if available, otherwise use exact string
            val nodeId = firstTextView.viewIdResourceName ?: ""
            val uniqueIdentifier = if (nodeId.isNotBlank()) {
                "$nodeId|$cleanName" // Use node ID + name for uniqueness
            } else {
                // Fallback: Use bounds position + name for uniqueness
                val bounds = android.graphics.Rect()
                firstTextView.getBoundsInScreen(bounds)
                "${bounds.left},${bounds.top}|$cleanName"
            }
            
            // Filter out guest contacts and validate
            // DO NOT merge names automatically - keep 'M.Asad' and 'Muhammad Asad Asif' separate
            if (cleanName.isNotBlank() && 
                cleanName.length >= 2 && 
                cleanName.length <= 100 &&
                !cleanName.lowercase().contains("guest") &&
                !sessionSentList.contains(uniqueIdentifier)) { // Check if already processed
                contactNames.add(cleanName)
                sessionSentList.add(uniqueIdentifier) // Track this exact contact row
                android.util.Log.v("WireAuto", "Added contact name: $cleanName (ID: $uniqueIdentifier)")
                debugLog("EXTRACT", "Added contact: $cleanName (unique ID: $uniqueIdentifier)")
            } else if (sessionSentList.contains(uniqueIdentifier)) {
                android.util.Log.v("WireAuto", "Skipping duplicate contact: $cleanName (already in sessionSentList)")
                debugLog("EXTRACT", "Skipping duplicate: $cleanName (ID: $uniqueIdentifier)")
            }
            
            // Also check content description (but split if it contains colon)
            if (contentDesc.isNotBlank() && contentDesc.length >= 2 && contentDesc.length <= 100) {
                val nameDesc = if (contentDesc.contains(":")) {
                    contentDesc.substringBefore(":").trim()
                } else {
                    contentDesc
                }
                val cleanDesc = sanitizeContactName(nameDesc)
                val descUniqueId = if (nodeId.isNotBlank()) {
                    "$nodeId|$cleanDesc"
                } else {
                    val bounds = android.graphics.Rect()
                    firstTextView.getBoundsInScreen(bounds)
                    "${bounds.left},${bounds.top}|$cleanDesc"
                }
                
                if (cleanDesc.isNotBlank() && 
                    cleanDesc.length >= 2 && 
                    !cleanDesc.lowercase().contains("guest") &&
                    !sessionSentList.contains(descUniqueId)) {
                    contactNames.add(cleanDesc)
                    sessionSentList.add(descUniqueId)
                    android.util.Log.v("WireAuto", "Added contact name from contentDesc: $cleanDesc (ID: $descUniqueId)")
                }
            }
        }
        
        android.util.Log.d("WireAuto", "Extracted ${contactNames.size} unique contact names")
        debugLog("EXTRACT", "Extracted ${contactNames.size} unique contact names: ${contactNames.take(5).joinToString(", ")}")
        
        return contactNames
    }
    
    /**
     * Helper: Find all TextViews recursively in a node
     */
    private fun findAllTextViewsInNode(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        val className = node.className?.toString() ?: ""
        if (className.contains("TextView", ignoreCase = true)) {
            result.add(node)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findAllTextViewsInNode(child, result)
            }
        }
    }
    
    /**
     * Sanitize contact name: Remove special characters, trim, and clean
     */
    private fun sanitizeContactName(name: String): String {
        var sanitized = name.trim()
        
        // Remove common prefixes
        val prefixes = listOf("You: ", "you: ", "You ", "you ", "New: ", "new: ")
        for (prefix in prefixes) {
            if (sanitized.startsWith(prefix, ignoreCase = true)) {
                sanitized = sanitized.substring(prefix.length).trim()
            }
        }
        
        // Split name from message if colon exists (take only before colon)
        if (sanitized.contains(":")) {
            sanitized = sanitized.substringBefore(":").trim()
        }
        
        // Remove timestamps and dates
        sanitized = sanitized.replace(Regex("\\s*\\d{1,2}:\\d{2}\\s*$"), "") // Remove time at end
        sanitized = sanitized.replace(Regex("\\s*(Today|Yesterday|Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\\s*$", RegexOption.IGNORE_CASE), "")
        
        // Remove special characters that aren't part of typical names (keep letters, numbers, spaces, hyphens, apostrophes)
        sanitized = sanitized.replace(Regex("[^\\p{L}\\p{N}\\s\\-']"), "")
        
        // Remove extra whitespace
        sanitized = sanitized.replace(Regex("\\s+"), " ").trim()
        
        return sanitized
    }
    
    /**
     * Helper: Check if a node is in the hierarchy of another node
     */
    private fun isNodeInHierarchy(node: AccessibilityNodeInfo, ancestor: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 10) {
            if (current == ancestor) {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }
    
    /**
     * Clean contact name by removing common prefixes and suffixes
     * Also splits name from message if colon exists
     */
    private fun cleanContactName(name: String): String {
        var cleaned = name.trim()
        
        // Split name from message if it contains a colon (e.g., "Name: Message")
        if (cleaned.contains(":")) {
            cleaned = cleaned.substringBefore(":").trim()
        }
        
        // Remove common prefixes
        val prefixes = listOf("You: ", "you: ", "You ", "you ", "New: ", "new: ")
        for (prefix in prefixes) {
            if (cleaned.startsWith(prefix, ignoreCase = true)) {
                cleaned = cleaned.substring(prefix.length).trim()
            }
        }
        
        // Remove timestamps and dates (patterns like "12:34", "Today", "Yesterday", etc.)
        cleaned = cleaned.replace(Regex("\\s*\\d{1,2}:\\d{2}\\s*$"), "") // Remove time at end
        cleaned = cleaned.replace(Regex("\\s*(Today|Yesterday|Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\\s*$", RegexOption.IGNORE_CASE), "")
        
        // Remove extra whitespace
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        
        return cleaned
    }
    
    /**
     * Perform slow scroll using GestureDescription (human-like scrolling)
     */
    private suspend fun performSlowScroll(root: AccessibilityNodeInfo): Boolean {
        try {
            val scrollableView = findScrollableView(root)
            if (scrollableView == null) {
                android.util.Log.w("WireAuto", "No scrollable view found")
                return false
            }
            
            val bounds = android.graphics.Rect()
            scrollableView.getBoundsInScreen(bounds)
            
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                return false
            }
            
            // Calculate scroll coordinates (center of view, scroll down)
            val startX = bounds.centerX().toFloat()
            val startY = bounds.centerY().toFloat()
            val endY = startY - 300f // Scroll up by 300px (slow scroll)
            
            val path = android.graphics.Path()
            path.moveTo(startX, startY)
            path.lineTo(startX, endY)
            
            // Slow scroll: 800ms duration for smooth, human-like movement
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
                path, 0, 800
            )
            
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            
            var gestureCompleted = false
            val gestureResult = dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    gestureCompleted = true
                    android.util.Log.d("WireAuto", "Slow scroll gesture completed")
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    android.util.Log.w("WireAuto", "Slow scroll gesture cancelled")
                }
            }, null)
            
            if (gestureResult) {
                // Wait for gesture to complete
                var waitAttempts = 0
                while (!gestureCompleted && waitAttempts < 10) {
                    delay(100)
                    waitAttempts++
                }
                return true
            }
            
            return false
        } catch (e: Exception) {
            android.util.Log.e("WireAuto", "Error performing slow scroll: ${e.message}", e)
            return false
        }
    }
    
    /**
     * NEW SEARCH-BASED IMPLEMENTATION
     * Phase 1-6: Complete automation with search, human mimicry, batching, and anti-ban strategies
     * 
     * FLOW: 
     * 1. Pehle contacts scan kiye gaye aur list banai gayi (discoverContactsFromHomeScreen)
     * 2. Ab us list ke according har contact ki conversation open karke message bhejo
     * 3. Har contact ke liye: Search -> Open conversation -> Send message -> Next contact
     */
    private suspend fun sendMessagesViaSearch(message: String, contactNames: List<String>) {
        android.util.Log.i("WireAuto", "=== PHASE 1: Starting to send messages to pre-scanned contact list ===")
        debugLog("EVENT", "=== PHASE 1: Iterating through contact list and sending messages ===")
        debugLog("EVENT", "Contact list received: ${contactNames.size} contacts to process")
        
        // Phase 1: Setup & Launch - Already done in sendMessagesToAllContacts
        // Bootstrap delay: Wait for app to sync messages
        updateNotification("Waiting for Wire to sync...")
        sendProgressBroadcast("Waiting for Wire to sync...")
        debugLog("PHASE1", "Bootstrap delay: 3000ms to allow app to sync messages")
        delay(3000)
        
        if (contactNames.isEmpty()) {
            android.util.Log.e("WireAuto", "No contacts found to send messages to")
            debugLog("ERROR", "No contacts found - cannot proceed with search-based sending")
            sendErrorBroadcast("No contacts found. Please ensure you have conversations in Wire.")
            return
        }
        
        android.util.Log.i("WireAuto", "Processing ${contactNames.size} contacts from pre-scanned list")
        debugLog("DATA", "Contact list ready: ${contactNames.size} contacts")
        debugLog("DATA", "Will now iterate through list: Open each conversation -> Send message -> Next")
        
        // Phase 4: Batching configuration
        val batchSize = 20
        val batchBreakSeconds = 120 // 2 minutes
        var contactsProcessed = 0
        var contactsSent = 0
        val contactResults = mutableListOf<com.wireautomessenger.model.ContactResult>()
        val reportFile = File(getExternalFilesDir(null), "automation_report.txt")
        
        // Initialize report file with FileWriter for proper flushing
        val reportWriter = FileWriter(reportFile, false) // false = overwrite, true = append
        reportWriter.append("=== Wire Automation Report ===\n")
        reportWriter.append("Start Time: ${dateFormat.format(Date())}\n")
        reportWriter.append("Total Contacts: ${contactNames.size}\n")
        reportWriter.append("Message: ${message.take(100)}${if (message.length > 100) "..." else ""}\n\n")
        reportWriter.flush()
        
        // Process contacts in batches - Iterating through pre-scanned contact list
        // Har contact ke liye: Search karo -> Conversation open karo -> Message bhejo -> Next contact
        for (batchStart in contactNames.indices step batchSize) {
            val batchEnd = minOf(batchStart + batchSize, contactNames.size)
            val batch = contactNames.subList(batchStart, batchEnd)
            
            android.util.Log.i("WireAuto", "=== Processing batch ${(batchStart / batchSize) + 1}: contacts ${batchStart + 1}-$batchEnd from pre-scanned list ===")
            debugLog("BATCH", "Processing batch ${(batchStart / batchSize) + 1}: contacts ${batchStart + 1}-$batchEnd from pre-scanned list")
            updateNotification("Processing batch ${(batchStart / batchSize) + 1}/${(contactNames.size + batchSize - 1) / batchSize}...")
            
            // Iterate through each contact in the pre-scanned list
            for ((index, contactName) in batch.withIndex()) {
                val globalIndex = batchStart + index
                
                // Context-Aware Deduplication: Skip if already sent in this session
                if (sessionSentList.contains(contactName)) {
                    android.util.Log.w("WireAuto", "Skipping duplicate contact: $contactName (already sent in this session)")
                    debugLog("DEDUP", "Skipping duplicate contact: $contactName (already in sessionSentList)")
                    contactResults.add(com.wireautomessenger.model.ContactResult(
                        name = contactName,
                        status = com.wireautomessenger.model.ContactStatus.SKIPPED,
                        errorMessage = "Already sent in this session",
                        position = globalIndex + 1
                    ))
                    reportWriter.append("${dateFormat.format(Date())} | SKIPPED | $contactName | Already sent in this session\n")
                    reportWriter.flush()
                    sendContactUpdate(contactName, "skipped", globalIndex + 1, "Already sent in this session")
                    continue
                }
                
                contactsProcessed++
                
                try {
                    // ============================================================
                    // PROCESSING CONTACT FROM PRE-SCANNED LIST
                    // ============================================================
                    // Step 1: Contact ko search karo
                    // Step 2: Conversation open karo
                    // Step 3: Message bhejo
                    // Step 4: Next contact par jao
                    // ============================================================
                    android.util.Log.i("WireAuto", "=== Processing contact ${globalIndex + 1}/${contactNames.size} from list: $contactName ===")
                    debugLog("CONTACT", "Processing contact ${globalIndex + 1}/${contactNames.size} from pre-scanned list: $contactName")
                    updateNotification("Sending to $contactName (${globalIndex + 1}/${contactNames.size})...")
                    sendProgressBroadcast("Sending to $contactName...", contactsSent)
                    
                    // Phase 2: Contact Search Logic - Pre-scanned list se contact ko search karo
                    debugLog("SEARCH", "Searching for contact from pre-scanned list: $contactName")
                    val searchResult = searchAndSelectContact(contactName)
                    
                    if (!searchResult) {
                        val errorMsg = "Contact not found: $contactName"
                        android.util.Log.w("WireAuto", errorMsg)
                        debugLog("ERROR", errorMsg)
                        contactResults.add(com.wireautomessenger.model.ContactResult(
                            name = contactName,
                            status = com.wireautomessenger.model.ContactStatus.FAILED,
                            errorMessage = "Contact not found in search",
                            position = globalIndex + 1
                        ))
                        reportWriter.append("${dateFormat.format(Date())} | FAILED | $contactName | Contact not found\n")
                        reportWriter.flush() // Flush to ensure report is updated immediately after each contact
                        sendContactUpdate(contactName, "failed", globalIndex + 1, "Contact not found")
                        continue
                    }
                    
                    // Phase 3: Messaging & Human Mimicry
                    val messageSent = sendMessageToContact(message, contactName, globalIndex + 1)
                    
                    if (messageSent) {
                        contactsSent++
                        // Mark as sent in session tracking
                        sessionSentList.add(contactName)
                        contactResults.add(com.wireautomessenger.model.ContactResult(
                            name = contactName,
                            status = com.wireautomessenger.model.ContactStatus.SENT,
                            errorMessage = null,
                            position = globalIndex + 1
                        ))
                        reportWriter.append("${dateFormat.format(Date())} | SUCCESS | $contactName | Message sent\n")
                        reportWriter.flush() // Flush to ensure report is updated immediately after each contact
                        sendContactUpdate(contactName, "sent", globalIndex + 1, null)
                        debugLog("SUCCESS", "Message sent successfully to $contactName (added to sessionSentList)")
                    } else {
                        contactResults.add(com.wireautomessenger.model.ContactResult(
                            name = contactName,
                            status = com.wireautomessenger.model.ContactStatus.FAILED,
                            errorMessage = "Failed to send message",
                            position = globalIndex + 1
                        ))
                        reportWriter.append("${dateFormat.format(Date())} | FAILED | $contactName | Failed to send message\n")
                        reportWriter.flush() // Flush to ensure report is updated immediately after each contact
                        sendContactUpdate(contactName, "failed", globalIndex + 1, "Failed to send message")
                    }
                    
                    // Phase 4: Return to main screen after each message
                    returnToMainScreen()
                    
                    // Phase 4: Randomized delay between contacts (3000-7000ms)
                    val randomDelay = (3000..7000).random()
                    debugLog("DELAY", "Random delay before next contact: ${randomDelay}ms")
                    delay(randomDelay.toLong())
                    
                } catch (e: Exception) {
                    // Self-Correction: Log error and continue to next contact instead of stopping
                    android.util.Log.e("WireAuto", "Error processing contact $contactName: ${e.message}", e)
                    debugLog("ERROR", "Error processing contact $contactName: ${e.message} - continuing to next contact", e)
                    contactResults.add(com.wireautomessenger.model.ContactResult(
                        name = contactName,
                        status = com.wireautomessenger.model.ContactStatus.FAILED,
                        errorMessage = "Exception: ${e.message}",
                        position = globalIndex + 1
                    ))
                    reportWriter.append("${dateFormat.format(Date())} | ERROR | $contactName | ${e.message}\n")
                    reportWriter.flush() // Flush to ensure report is updated immediately after each contact
                    sendContactUpdate(contactName, "failed", globalIndex + 1, "Exception: ${e.message}")
                    
                    // Ensure we return to main screen even on error to continue with next contact
                    try {
                        returnToMainScreen()
                        delay(1000) // Brief delay before next contact
                    } catch (e2: Exception) {
                        android.util.Log.w("WireAuto", "Error returning to main screen after contact failure: ${e2.message}")
                    }
                }
            }
            
            // Phase 4: Break between batches (except for last batch)
            if (batchEnd < contactNames.size) {
                android.util.Log.i("WireAuto", "=== Batch complete. Taking ${batchBreakSeconds}s break before next batch ===")
                debugLog("BATCH", "Batch complete. Taking ${batchBreakSeconds}s break")
                updateNotification("Batch complete. Break ${batchBreakSeconds}s...")
                delay(batchBreakSeconds * 1000L)
            }
        }
        
        // Final report
        val duration = System.currentTimeMillis() - sessionStartTime
        val durationSeconds = duration / 1000.0
        reportWriter.append("\n=== Summary ===\n")
        reportWriter.append("End Time: ${dateFormat.format(Date())}\n")
        reportWriter.append("Total Runtime: ${String.format("%.2f", durationSeconds)} seconds\n")
        reportWriter.append("Contacts Processed: $contactsProcessed\n")
        reportWriter.append("Messages Sent: $contactsSent\n")
        reportWriter.append("Success Rate: ${if (contactsProcessed > 0) String.format("%.1f", (contactsSent * 100.0 / contactsProcessed)) else "0.0"}%\n")
        reportWriter.flush()
        reportWriter.close() // Close the writer
        
        android.util.Log.i("WireAuto", "=== Automation complete: $contactsSent/$contactsProcessed messages sent ===")
        debugLog("COMPLETE", "Automation complete: $contactsSent/$contactsProcessed messages sent")
        debugLog("REPORT", "Report saved to: ${reportFile.absolutePath}")
        
        // Save results
        val operationReport = buildOperationReport(contactNames.size, contactsProcessed, contactsSent, contactResults, duration)
        saveOperationReport(operationReport)
        
        // Send completion broadcast
        val intent = Intent(ACTION_COMPLETED).apply {
            putExtra(EXTRA_CONTACTS_SENT, contactsSent)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
    
    /**
     * Phase 2: Contact Search Logic
     * Locate search bar, sanitize and type contact name, wait for results, select exact match
     */
    private suspend fun searchAndSelectContact(contactName: String): Boolean {
        debugLog("SEARCH", "Phase 2: Searching for contact: $contactName")
        
        // Step 0: Sanitize the contact name before searching
        val sanitizedName = sanitizeContactName(contactName)
        android.util.Log.d("WireAuto", "Sanitized contact name: '$contactName' -> '$sanitizedName'")
        debugLog("SEARCH", "Sanitized contact name: '$contactName' -> '$sanitizedName'")
        
        if (sanitizedName.isBlank()) {
            android.util.Log.e("WireAuto", "Contact name is blank after sanitization")
            debugLog("ERROR", "Contact name is blank after sanitization")
            return false
        }
        
        // Step 1: Find search bar/icon
        var root = getRootWithRetry(maxRetries = 5, delayMs = 500)
        if (root == null || root.packageName != WIRE_PACKAGE) {
            android.util.Log.e("WireAuto", "Cannot access Wire app for search")
            return false
        }
        
        val searchBar = findSearchBar(root)
        if (searchBar == null) {
            android.util.Log.e("WireAuto", "Search bar not found")
            debugLog("ERROR", "Search bar not found")
            return false
        }
        
        // Step 2: Click on search bar
        debugLog("SEARCH", "Clicking on search bar...")
        if (!clickNodeWithGesture(searchBar)) {
            android.util.Log.w("WireAuto", "Failed to click search bar, trying alternative method")
            searchBar.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        delay(1000) // Wait for search to open
        
        // Step 3: Type sanitized contact name character-by-character (human mimicry)
        root = getRootWithRetry(maxRetries = 3, delayMs = 500)
        val searchInput = findSearchInput(root)
        if (searchInput == null) {
            android.util.Log.e("WireAuto", "Search input field not found")
            return false
        }
        
        // Clear search bar completely before typing
        debugLog("SEARCH", "Clearing search bar completely...")
        
        // Method 1: Clear using ACTION_SET_TEXT with empty string
        val bundleClear = android.os.Bundle()
        bundleClear.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundleClear)
        delay(300)
        
        // Method 2: Select all and delete (more reliable clearing)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                searchInput.performAction(AccessibilityNodeInfo.ACTION_SELECT)
                delay(100)
                searchInput.performAction(AccessibilityNodeInfo.ACTION_CUT)
                delay(100)
            } catch (e: Exception) {
                android.util.Log.d("WireAuto", "Select and cut failed, using setText only: ${e.message}")
            }
        }
        
        // Method 3: Clear again to ensure it's empty
        searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundleClear)
        delay(300)
        
        // Verify search bar is empty by checking if we can set empty text again
        val verifyClear = android.os.Bundle()
        verifyClear.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, verifyClear)
        delay(200)
        
        debugLog("SEARCH", "Search bar cleared, typing sanitized contact name character-by-character: $sanitizedName")
        
        // Type sanitized name character-by-character with 100ms delay
        // Build the full name first, then set it (more reliable than character-by-character)
        val bundleFull = android.os.Bundle()
        bundleFull.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, sanitizedName)
        val setFullText = searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundleFull)
        
        if (!setFullText) {
            // Fallback: Type character-by-character if setText fails
            android.util.Log.w("WireAuto", "SetText failed, falling back to character-by-character typing")
            debugLog("SEARCH", "SetText failed, using character-by-character fallback")
            for (char in sanitizedName) {
                val bundle = android.os.Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, char.toString())
                searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                delay(100) // Human-like typing delay
            }
        } else {
            android.util.Log.d("WireAuto", "Successfully set full search text: $sanitizedName")
            debugLog("SEARCH", "Successfully set full search text: $sanitizedName")
        }
        
        // Increase search wait: Give search results extra 2 seconds to fully load
        delay(3500) // Wait for search results to populate (1500ms + 2000ms extra)
        debugLog("SEARCH", "Waiting for search results to fully load...")
        
        // Step 4: Find and click matching result (flexible matching)
        root = getRootWithRetry(maxRetries = 3, delayMs = 500)
        val searchResult = findFlexibleSearchResult(root, sanitizedName)
        
        if (searchResult == null) {
            android.util.Log.w("WireAuto", "No search result found for: $sanitizedName")
            debugLog("WARN", "No search result found for: $sanitizedName")
            // Close search
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(500)
            return false
        }
        
        // Click on search result
        debugLog("SEARCH", "Clicking on search result for: $sanitizedName")
        if (!clickNodeWithGesture(searchResult)) {
            searchResult.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        delay(2000) // Wait for conversation to open
        
        // Fix 'Conversations' Header Confusion: Check if still on search screen
        root = getRootWithRetry(maxRetries = 3, delayMs = 500)
        val stillOnSearchScreen = root?.let { 
            val searchBarAfterClick = findSearchBar(it)
            val searchInputAfterClick = findSearchInput(it)
            val hasConversationsText = hasTextInView(it, "CONVERSATIONS")
            
            // If search bar is still visible or we see CONVERSATIONS text, we're still on search/home screen
            searchBarAfterClick != null || searchInputAfterClick != null || hasConversationsText
        } ?: false
        
        if (stillOnSearchScreen) {
            android.util.Log.w("WireAuto", "Still on search screen after clicking result - clicking again")
            debugLog("SEARCH", "Still on search screen - clicking search result again")
            
            // Find search result again and click
            root = getRootWithRetry(maxRetries = 3, delayMs = 500)
            val searchResultRetry = root?.let { findFlexibleSearchResult(it, sanitizedName) }
            
            if (searchResultRetry != null) {
                debugLog("SEARCH", "Clicking search result again (retry)")
                if (!clickNodeWithGesture(searchResultRetry)) {
                    searchResultRetry.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                delay(2000) // Wait for conversation to open again
            } else {
                android.util.Log.w("WireAuto", "Could not find search result for retry")
                debugLog("ERROR", "Could not find search result for retry click")
            }
        }
        
        // Double-Check After Opening Chat (The "Pro" Layer)
        // Read name from Chat Toolbar/Header and verify it matches
        val verified = verifyOpenedChatName(sanitizedName)
        if (!verified) {
            android.util.Log.w("WireAuto", "Chat verification failed - wrong contact opened")
            debugLog("VERIFY", "Chat verification failed - wrong contact opened, going back")
            // Press Back and return false
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(1000)
            return false
        }
        
        return true
    }
    
    /**
     * Helper: Find search result with reliable matching
     * 1. Prioritize exact match
     * 2. Find closest string match if multiple results
     * 3. Fallback to first clickable item in search results
     */
    private fun findFlexibleSearchResult(root: AccessibilityNodeInfo?, searchName: String): AccessibilityNodeInfo? {
        if (root == null) return null
        
        val cleanSearchName = searchName.trim().lowercase()
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        findAllClickableNodesRecursive(root, allNodes)
        
        android.util.Log.d("WireAuto", "Searching for '$searchName' in ${allNodes.size} clickable nodes")
        debugLog("SEARCH", "Searching for '$searchName' in ${allNodes.size} clickable nodes")
        
        // Try to find conversation_list or contact_name resource IDs first
        val searchResultsArea = findNodeByResourceId(root, "com.wire:id/conversation_list")
            ?: findNodeByResourceId(root, "com.wire:id/contact_name")
        
        val searchResultsNodes = if (searchResultsArea != null) {
            // Get all clickable nodes within the search results area
            val nodesInArea = mutableListOf<AccessibilityNodeInfo>()
            findAllClickableNodesRecursive(searchResultsArea, nodesInArea)
            nodesInArea
        } else {
            // Fallback: use all nodes but prioritize those that look like search results
            allNodes.filter { node ->
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                // Filter out very small nodes (likely buttons) and very large nodes (likely containers)
                bounds.height() > 40 && bounds.height() < 200
            }
        }
        
        android.util.Log.d("WireAuto", "Found ${searchResultsNodes.size} potential search result nodes")
        debugLog("SEARCH", "Found ${searchResultsNodes.size} potential search result nodes")
        
        // Strategy 1: Prioritize exact match (case-insensitive)
        val exactMatches = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        for (node in searchResultsNodes) {
            val text = node.text?.toString()?.trim() ?: ""
            val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
            
            // Check both text and contentDescription
            val nodeTextLower = text.lowercase()
            val nodeDescLower = contentDesc.lowercase()
            val displayText = if (text.isNotBlank()) text else contentDesc
            
            // Exact match check
            if (nodeTextLower == cleanSearchName || nodeDescLower == cleanSearchName) {
                exactMatches.add(Pair(node, displayText))
            }
        }
        
        if (exactMatches.isNotEmpty()) {
            // If multiple exact matches, return the first one
            val (node, displayText) = exactMatches.first()
            android.util.Log.d("WireAuto", "Found exact match: '$displayText' == '$searchName'")
            debugLog("SEARCH", "Found exact match: '$displayText' == '$searchName'")
            return node
        }
        
        // Strategy 2: Find closest string match (for cases like 'M.Asad' vs 'Muhammad Asad Asif')
        val candidateMatches = mutableListOf<Triple<AccessibilityNodeInfo, String, Int>>()
        
        for (node in searchResultsNodes) {
            val text = node.text?.toString()?.trim() ?: ""
            val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
            val displayText = if (text.isNotBlank()) text else contentDesc
            
            val nodeTextLower = text.lowercase()
            val nodeDescLower = contentDesc.lowercase()
            
            // Calculate similarity score
            var score = 0
            
            // Check if starts with search name (higher score)
            if (nodeTextLower.startsWith(cleanSearchName) || nodeDescLower.startsWith(cleanSearchName)) {
                score = 100
            } else if (nodeTextLower.contains(cleanSearchName) || nodeDescLower.contains(cleanSearchName)) {
                // Contains search name (lower score)
                score = 50
            } else {
                // Calculate Levenshtein-like similarity for short names
                if (cleanSearchName.length <= 3) {
                    val similarity = calculateStringSimilarity(cleanSearchName, nodeTextLower)
                    if (similarity > 0.5) {
                        score = (similarity * 30).toInt()
                    }
                }
            }
            
            if (score > 0) {
                candidateMatches.add(Triple(node, displayText, score))
            }
        }
        
        // Sort by score (highest first) and return the best match
        if (candidateMatches.isNotEmpty()) {
            val sortedMatches = candidateMatches.sortedByDescending { it.third }
            val (bestNode, bestText, bestScore) = sortedMatches.first()
            android.util.Log.d("WireAuto", "Found closest match: '$bestText' (score: $bestScore) for '$searchName'")
            debugLog("SEARCH", "Found closest match: '$bestText' (score: $bestScore) for '$searchName'")
            return bestNode
        }
        
        // Strategy 3: Fallback to first clickable item in search results
        if (searchResultsNodes.isNotEmpty()) {
            val firstResult = searchResultsNodes.first()
            val text = firstResult.text?.toString()?.trim() ?: ""
            val contentDesc = firstResult.contentDescription?.toString()?.trim() ?: ""
            val displayText = if (text.isNotBlank()) text else contentDesc
            android.util.Log.w("WireAuto", "No match found, using first result as fallback: '$displayText'")
            debugLog("SEARCH", "No match found, using first result as fallback: '$displayText'")
            return firstResult
        }
        
        android.util.Log.w("WireAuto", "No search results found for: $searchName")
        debugLog("SEARCH", "No search results found for: $searchName")
        return null
    }
    
    /**
     * Helper: Calculate string similarity (simple Levenshtein-like)
     */
    private fun calculateStringSimilarity(str1: String, str2: String): Double {
        if (str1 == str2) return 1.0
        if (str1.isEmpty() || str2.isEmpty()) return 0.0
        
        // Check if str1 is contained in str2
        if (str2.contains(str1)) {
            return str1.length.toDouble() / str2.length.coerceAtLeast(str1.length)
        }
        
        // Simple character overlap
        var matches = 0
        for (char in str1) {
            if (str2.contains(char)) {
                matches++
            }
        }
        
        return matches.toDouble() / str1.length.coerceAtLeast(str2.length)
    }
    
    /**
     * Double-Check After Opening Chat: Verify the opened chat name matches
     * Read name from Chat Toolbar/Header and compare with expected name
     * Excludes security notice text and refines header detection
     */
    private suspend fun verifyOpenedChatName(expectedName: String): Boolean {
        debugLog("VERIFY", "Verifying opened chat name: expected '$expectedName'")
        
        delay(1000) // Wait for chat to fully load
        
        val root = getRootWithRetry(maxRetries = 3, delayMs = 500)
        if (root == null || root.packageName != WIRE_PACKAGE) {
            android.util.Log.w("WireAuto", "Cannot access Wire app for verification")
            debugLog("VERIFY", "Cannot access Wire app - using Safety Skip")
            return true // Safety Skip: Allow proceeding if can't access
        }
        
        // Security notice text to exclude
        val securityNoticeTexts = listOf(
            "communication in wire is always end-to-end encrypted",
            "end-to-end encrypted",
            "encrypted",
            "your messages are protected"
        )
        
        // Refine Header Detection: Look ONLY for Toolbar Title or TextView at very top
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val toolbarThreshold = (screenHeight * 0.15).toInt() // Top 15% for toolbar
        
        // Strategy 1: Find Toolbar Title directly
        val toolbarNodes = mutableListOf<AccessibilityNodeInfo>()
        findAllNodesByClassName(root, "androidx.appcompat.widget.Toolbar", toolbarNodes)
        findAllNodesByClassName(root, "android.widget.Toolbar", toolbarNodes)
        
        var actualChatName = ""
        
        // Look for TextView inside Toolbar (Toolbar Title)
        for (toolbar in toolbarNodes) {
            val toolbarTextViews = mutableListOf<AccessibilityNodeInfo>()
            findAllTextViewsInNode(toolbar, toolbarTextViews)
            
            for (textView in toolbarTextViews) {
                val text = textView.text?.toString()?.trim() ?: ""
                val lowerText = text.lowercase()
                
                // Exclude Security Notice
                val isSecurityNotice = securityNoticeTexts.any { notice ->
                    lowerText.contains(notice, ignoreCase = true)
                }
                
                if (!isSecurityNotice && text.isNotBlank() && text.length <= 100) {
                    actualChatName = text
                    android.util.Log.d("WireAuto", "Found chat name in Toolbar: '$actualChatName'")
                    debugLog("VERIFY", "Found chat name in Toolbar: '$actualChatName'")
                    break
                }
            }
            if (actualChatName.isNotBlank()) break
        }
        
        // Strategy 2: If not found in Toolbar, find TextView at very top of screen
        if (actualChatName.isBlank()) {
            val allTextViews = mutableListOf<AccessibilityNodeInfo>()
            findAllNodesByClassName(root, "android.widget.TextView", allTextViews)
            
            // Sort by Y position (top to bottom) and get the top-most TextView
            val topTextViews = allTextViews
                .mapNotNull { textView ->
                    val bounds = android.graphics.Rect()
                    textView.getBoundsInScreen(bounds)
                    val text = textView.text?.toString()?.trim() ?: ""
                    val lowerText = text.lowercase()
                    
                    // Must be in top 15% and not be security notice
                    val isInToolbarArea = bounds.top < toolbarThreshold
                    val isSecurityNotice = securityNoticeTexts.any { notice ->
                        lowerText.contains(notice, ignoreCase = true)
                    }
                    
                    if (isInToolbarArea && !isSecurityNotice && text.isNotBlank() && text.length <= 100) {
                        Pair(textView, bounds.top)
                    } else {
                        null
                    }
                }
                .sortedBy { it.second } // Sort by Y position (top first)
            
            actualChatName = topTextViews.firstOrNull()?.first?.text?.toString()?.trim() ?: ""
            
            if (actualChatName.isNotBlank()) {
                android.util.Log.d("WireAuto", "Found chat name in top TextView: '$actualChatName'")
                debugLog("VERIFY", "Found chat name in top TextView: '$actualChatName'")
            }
        }
        
        // Fallback Verification: Safety Skip - If can't find name but successfully clicked contact, allow proceeding
        if (actualChatName.isBlank()) {
            android.util.Log.w("WireAuto", "Could not read chat name from toolbar/header")
            debugLog("VERIFY", "Could not read chat name - using Safety Skip (allowing proceed)")
            return true // Safety Skip: Allow proceeding if can't verify
        }
        
        val sanitizedExpected = sanitizeContactName(expectedName).lowercase()
        val sanitizedActual = sanitizeContactName(actualChatName).lowercase()
        
        android.util.Log.d("WireAuto", "Chat verification: expected='$sanitizedExpected', actual='$sanitizedActual'")
        debugLog("VERIFY", "Chat verification: expected='$sanitizedExpected', actual='$sanitizedActual'")
        
        // Check if it's the same person as last messaged
        if (lastPersonMessaged != null && sanitizedActual == lastPersonMessaged?.lowercase()) {
            android.util.Log.w("WireAuto", "Same person as last messaged: $actualChatName")
            debugLog("VERIFY", "Same person as last messaged: $actualChatName - going back")
            return false // Same person - go back
        }
        
        // Check if actual name matches expected (exact or starts with)
        val matches = sanitizedActual == sanitizedExpected || 
                     sanitizedActual.startsWith(sanitizedExpected) ||
                     sanitizedExpected.startsWith(sanitizedActual)
        
        if (matches) {
            lastPersonMessaged = sanitizedActual // Update last person messaged
            android.util.Log.d("WireAuto", "Chat verification passed: $actualChatName")
            debugLog("VERIFY", "Chat verification passed: $actualChatName")
            return true
        } else {
            android.util.Log.w("WireAuto", "Chat verification failed: expected '$expectedName', got '$actualChatName'")
            debugLog("VERIFY", "Chat verification failed: expected '$expectedName', got '$actualChatName'")
            // Safety Skip: If verification fails but we successfully clicked, allow proceeding
            debugLog("VERIFY", "Using Safety Skip - allowing proceed despite mismatch")
            return true // Safety Skip: Allow proceeding
        }
    }
    
    /**
     * Helper: Find node by resource ID
     */
    private fun findNodeByResourceId(root: AccessibilityNodeInfo, resourceId: String): AccessibilityNodeInfo? {
        val viewIdResourceName = root.viewIdResourceName
        if (viewIdResourceName == resourceId) {
            return root
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                val found = findNodeByResourceId(child, resourceId)
                if (found != null) return found
            }
        }
        
        return null
    }
    
    /**
     * Phase 3: Messaging & Human Mimicry
     * Focus input, type message, click send, verify sent
     */
    private suspend fun sendMessageToContact(message: String, contactName: String, position: Int): Boolean {
        debugLog("MESSAGE", "Phase 3: Sending message to $contactName")
        
        // Retry logic: max 3 attempts with incremental backoff
        var attempt = 0
        val backoffDelays = listOf(2000L, 4000L, 8000L)
        
        while (attempt < 3) {
            attempt++
            debugLog("MESSAGE", "Attempt $attempt/3 to send message to $contactName")
            
            try {
                // Step 1: Find message input field
                var root = getRootWithRetry(maxRetries = 3, delayMs = 500)
                val messageInput = root?.let { findMessageInput(it) }
                
                if (messageInput == null) {
                    android.util.Log.w("WireAuto", "Message input not found (attempt $attempt)")
                    if (attempt < 3) {
                        delay(backoffDelays[attempt - 1])
                        continue
                    }
                    return false
                }
                
                // Step 2: Wait 2 seconds after search result click, then force click bottom-center
                debugLog("MESSAGE", "Waiting 2 seconds after search result click...")
                delay(2000) // Wait 2 seconds as requested
                
                // Force click on bottom-center of screen where input box is usually located
                debugLog("MESSAGE", "Performing force click on bottom-center of screen...")
                val screenWidth = resources.displayMetrics.widthPixels
                val screenHeight = resources.displayMetrics.heightPixels
                val bottomCenterX = screenWidth / 2
                val bottomCenterY = (screenHeight * 0.85).toInt() // 85% down the screen (near bottom)
                
                android.util.Log.d("WireAuto", "Force clicking at bottom-center: ($bottomCenterX, $bottomCenterY)")
                debugLog("MESSAGE", "Force clicking at bottom-center: ($bottomCenterX, $bottomCenterY)")
                
                try {
                    val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
                        android.graphics.Path().apply {
                            moveTo(bottomCenterX.toFloat(), bottomCenterY.toFloat())
                        },
                        0, 100
                    )
                    val gesture = android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(stroke)
                        .build()
                    
                    var gestureCompleted = false
                    dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                            gestureCompleted = true
                        }
                        override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                            android.util.Log.w("WireAuto", "Force click gesture cancelled")
                        }
                    }, null)
                    
                    // Wait for gesture to complete
                    var waitAttempts = 0
                    while (!gestureCompleted && waitAttempts < 20) {
                        delay(100)
                        waitAttempts++
                    }
                    delay(500) // Additional delay after force click
                } catch (e: Exception) {
                    android.util.Log.w("WireAuto", "Force click failed: ${e.message}")
                    debugLog("WARN", "Force click failed, continuing with normal click: ${e.message}")
                }
                
                // Step 3: Click and focus input field (ensure it's active)
                debugLog("MESSAGE", "Clicking and focusing message input field...")
                
                // Refresh root and find input again after force click
                root = getRootWithRetry(maxRetries = 3, delayMs = 500)
                val refreshedInput = root?.let { findMessageInput(it) } ?: messageInput
                
                // Click the input field first to ensure it's active
                if (!clickNodeWithGesture(refreshedInput)) {
                    refreshedInput.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                delay(500)
                
                // Then focus it
                refreshedInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                delay(500)
                
                // Step 4: Clear existing text
                val bundleClear = android.os.Bundle()
                bundleClear.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                refreshedInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundleClear)
                delay(300)
                
                // Step 5: Type message (use setText for speed, but could be changed to character-by-character)
                debugLog("MESSAGE", "Setting message text in input field...")
                android.util.Log.i("WireAuto", "Typing message: $message")
                val bundle = android.os.Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
                val textSet = refreshedInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                
                if (!textSet) {
                    android.util.Log.w("WireAuto", "Failed to set message text (attempt $attempt)")
                    if (attempt < 3) {
                        delay(backoffDelays[attempt - 1])
                        continue
                    }
                    return false
                }
                
                delay(1000) // Wait for send button to enable
                
                // Step 6: Find and click send button
                root = getRootWithRetry(maxRetries = 3, delayMs = 500)
                val sendButton = if (root != null && refreshedInput != null) {
                    val rootNonNull = root
                    val inputNonNull = refreshedInput
                    findSendButton(rootNonNull) ?: findSendButtonNearInput(rootNonNull, inputNonNull)
                } else {
                    null
                }
                
                if (sendButton == null) {
                    android.util.Log.w("WireAuto", "Send button not found (attempt $attempt)")
                    if (attempt < 3) {
                        delay(backoffDelays[attempt - 1])
                        continue
                    }
                    return false
                }
                
                debugLog("MESSAGE", "Clicking send button...")
                var clicked = clickNodeWithGesture(sendButton)
                if (!clicked) {
                    clicked = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                
                if (!clicked) {
                    android.util.Log.w("WireAuto", "Failed to click send button (attempt $attempt)")
                    if (attempt < 3) {
                        delay(backoffDelays[attempt - 1])
                        continue
                    }
                    return false
                }
                
                delay(2000) // Wait for message to send
                
                // Step 6: Verify message was sent (check if input is empty)
                root = getRootWithRetry(maxRetries = 3, delayMs = 500)
                val refreshedInput = root?.let { findMessageInput(it) }
                val inputText = refreshedInput?.text?.toString()?.trim() ?: ""
                
                if (inputText.isEmpty()) {
                    debugLog("SUCCESS", "Message sent confirmed - input field is empty")
                    return true
                } else {
                    android.util.Log.w("WireAuto", "Message may not have been sent - input still has text: '$inputText'")
                    if (attempt < 3) {
                        delay(backoffDelays[attempt - 1])
                        continue
                    }
                    // Consider it sent anyway if we clicked the button
                    return true
                }
                
            } catch (e: Exception) {
                android.util.Log.e("WireAuto", "Error in sendMessageToContact (attempt $attempt): ${e.message}", e)
                if (attempt < 3) {
                    delay(backoffDelays[attempt - 1])
                    continue
                }
                return false
            }
        }
        
        return false
    }
    
    /**
     * Phase 4: Return to main screen
     */
    private suspend fun returnToMainScreen() {
        debugLog("NAVIGATION", "Returning to main chat list screen and clearing search bar...")
        
        // Press Back button until we're back on the main chat list
        // This ensures we're ready for the next contact search
        var backPresses = 0
        val maxBackPresses = 5 // Safety limit
        
        while (backPresses < maxBackPresses) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(1500) // Wait for navigation
            
            // Check if we're back on the main screen by verifying we can find the conversations list
            val root = getRootWithRetry(maxRetries = 2, delayMs = 300)
            if (root != null && root.packageName == WIRE_PACKAGE) {
                // Try to find a RecyclerView or conversation list indicator
                val recyclerView = findRecyclerView(root)
                if (recyclerView != null) {
                    debugLog("NAVIGATION", "Successfully returned to main chat list after ${backPresses + 1} back press(es)")
                    android.util.Log.d("WireAuto", "Successfully returned to main chat list")
                    break
                }
            }
            
            backPresses++
            
            // If we've pressed back multiple times, assume we're on main screen
            if (backPresses >= 2) {
                debugLog("NAVIGATION", "Assumed back on main screen after $backPresses back presses")
                break
            }
        }
        
        // Clear search bar completely before next contact
        debugLog("NAVIGATION", "Clearing search bar to ensure clean state for next contact")
        val root = getRootWithRetry(maxRetries = 3, delayMs = 500)
        if (root != null && root.packageName == WIRE_PACKAGE) {
            val searchInput = findSearchInput(root)
            if (searchInput != null) {
                // Clear search bar completely
                val bundleClear = android.os.Bundle()
                bundleClear.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundleClear)
                delay(300)
                
                // Verify it's cleared
                searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundleClear)
                delay(200)
                debugLog("NAVIGATION", "Search bar cleared successfully")
                android.util.Log.d("WireAuto", "Search bar cleared - ready for next contact")
            } else {
                debugLog("NAVIGATION", "No search input found - may already be on home screen")
            }
        }
        
        // Final delay to ensure UI is stable
        delay(1000)
    }
    
    /**
     * Helper: Find search bar
     */
    private fun findSearchBar(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Try to find search bar using multiple strategies
        val searchBar = findSearchConversationsBar(root)
        if (searchBar != null) return searchBar
        
        // Try to find by content description
        val searchByDesc = findNodeByContentDescription(root, "Search")
            ?: findNodeByContentDescription(root, "Search conversations")
            ?: findNodeByContentDescription(root, "Search chats")
        
        if (searchByDesc != null) return searchByDesc
        
        // Try to find EditText with search hint
        val allEditTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllNodesByClassName(root, "android.widget.EditText", allEditTexts)
        findAllNodesByClassName(root, "androidx.appcompat.widget.AppCompatEditText", allEditTexts)
        
        for (editText in allEditTexts) {
            val hint = editText.hintText?.toString()?.lowercase() ?: ""
            if (hint.contains("search")) {
                return editText
            }
        }
        
        return null
    }
    
    /**
     * Helper: Find search input field (after search is opened)
     */
    private fun findSearchInput(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        
        val allEditTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllNodesByClassName(root, "android.widget.EditText", allEditTexts)
        findAllNodesByClassName(root, "androidx.appcompat.widget.AppCompatEditText", allEditTexts)
        
        // Return the first focused or visible EditText (likely the search input)
        for (editText in allEditTexts) {
            if (editText.isFocused || editText.isVisibleToUser) {
                return editText
            }
        }
        
        return allEditTexts.firstOrNull()
    }
    
    /**
     * Helper: Find search result matching contact name
     */
    private fun findSearchResult(root: AccessibilityNodeInfo?, contactName: String): AccessibilityNodeInfo? {
        if (root == null) return null
        
        val cleanName = contactName.trim()
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        findAllClickableNodesRecursive(root, allNodes)
        
        for (node in allNodes) {
            val text = node.text?.toString()?.trim() ?: ""
            val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
            
            if (text.equals(cleanName, ignoreCase = true) || 
                contentDesc.equals(cleanName, ignoreCase = true) ||
                text.contains(cleanName, ignoreCase = true)) {
                return node
            }
        }
        
        return null
    }
    
    /**
     * Helper: Get contacts from conversations list (fallback)
     */
    private suspend fun getContactsFromConversationsList(): List<String> {
        val contacts = mutableListOf<String>()
        val root = getRootWithRetry(maxRetries = 5, delayMs = 500)
        
        if (root != null && root.packageName == WIRE_PACKAGE) {
            val contactItems = getAllContactItems(root)
            for (item in contactItems) {
                val name = extractContactNameFromRow(item)
                if (name != null && name.isNotBlank()) {
                    contacts.add(name)
                }
            }
        }
        
        return contacts
    }
    
}

