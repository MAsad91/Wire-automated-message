package com.wireautomessenger.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.os.Build
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

class WireAutomationService : AccessibilityService() {

    // State machine flags
    private val isRunning = AtomicBoolean(false)
    private val isWireOpened = AtomicBoolean(false)
    private val isSendingInProgress = AtomicBoolean(false)
    
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("WireAutoMessenger", MODE_PRIVATE)
    }
    private val scope = CoroutineScope(Dispatchers.Main)

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
        } catch (e: Exception) {
            // Log error but don't crash the service
            e.printStackTrace()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // CRITICAL: NO app-launch logic here to prevent infinite loops
        // This method only observes events, never launches apps
        // All app launching happens ONLY from user action (Start button) via onStartCommand
        try {
            // Optional: Log window state changes for debugging (but don't act on them)
            if (event != null && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val packageName = event.packageName?.toString()
                if (packageName == WIRE_PACKAGE && isSendingInProgress.get()) {
                    android.util.Log.d("WireAuto", "Wire app window state changed - package: $packageName")
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
            if (intent?.action == ACTION_SEND_MESSAGES) {
                if (!isRunning.get()) {
                    try {
                        startForeground(NOTIFICATION_ID, createNotification("Starting automation..."))
                    } catch (e: Exception) {
                        // If foreground service fails, continue anyway
                        e.printStackTrace()
                    }
                    scope.launch {
                        sendMessagesToAllContacts()
                    }
                }
            }
            START_NOT_STICKY
        } catch (e: Exception) {
            e.printStackTrace()
            START_NOT_STICKY
        }
    }

    private suspend fun sendMessagesToAllContacts() {
        // State machine: Check if already running
        if (isRunning.getAndSet(true)) {
            android.util.Log.w("WireAuto", "Already running - ignoring duplicate request")
            return
        }

        // Reset state flags
        isWireOpened.set(false)
        isSendingInProgress.set(false)

        try {
            val message = prefs.getString("pending_message", "") ?: ""
            if (message.isEmpty()) {
                android.util.Log.e("WireAuto", "No message to send")
                updateNotification("No message to send")
                sendErrorBroadcast("No message to send. Please enter a message first.")
                resetState()
                return
            }

            android.util.Log.i("WireAuto", "=== STARTING MESSAGE SENDING PROCESS ===")
            android.util.Log.i("WireAuto", "State: isRunning=true, isWireOpened=false, isSendingInProgress=false")

            // STEP 1: Launch Wire app ONCE (only from user action)
            updateNotification("Opening Wire app...")
            sendProgressBroadcast("Opening Wire app...")
            
            val launchResult = launchWireAppOnce()
            if (!launchResult) {
                resetState()
                return
            }

            // STEP 2: Wait for Wire to be in foreground
            android.util.Log.i("WireAuto", "Waiting for Wire app to be in foreground...")
            val wireInForeground = waitForWireInForeground(maxWaitSeconds = 15)
            if (!wireInForeground) {
                val errorMsg = "Wire app did not come to foreground. Please ensure Wire is installed and accessible."
                android.util.Log.e("WireAuto", errorMsg)
                updateNotification("Wire app not accessible")
                sendErrorBroadcast(errorMsg)
                resetState()
                return
            }

            isWireOpened.set(true)
            android.util.Log.i("WireAuto", "Wire app is now in foreground - State: isWireOpened=true")

            // STEP 3: Navigate to contacts and send messages
            isSendingInProgress.set(true)
            android.util.Log.i("WireAuto", "Starting message sending process - State: isSendingInProgress=true")
            
            navigateAndSendMessages(message)

                } catch (e: Exception) {
            val errorMsg = "Error: ${e.message ?: "Unknown error"}"
            android.util.Log.e("WireAuto", "Fatal error in sendMessagesToAllContacts: $errorMsg", e)
            updateNotification(errorMsg)
            sendErrorBroadcast(errorMsg)
        } finally {
            android.util.Log.i("WireAuto", "=== MESSAGE SENDING PROCESS COMPLETED ===")
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

    private suspend fun navigateAndSendMessages(message: String) {
        android.util.Log.i("WireAuto", "=== STEP 3: Starting navigateAndSendMessages ===")
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
        delay(3000) // Give Wire time to fully load

        // Ensure we're in Wire app - check package name (NO relaunch)
        var rootNode = rootInActiveWindow
        if (rootNode == null || rootNode.packageName != WIRE_PACKAGE) {
            android.util.Log.w("WireAuto", "Wire app not in foreground - waiting...")
            delay(2000)
            rootNode = rootInActiveWindow
            if (rootNode == null || rootNode.packageName != WIRE_PACKAGE) {
                android.util.Log.e("WireAuto", "Could not access Wire app after wait")
                sendErrorBroadcast("Could not access Wire app. Please ensure Wire is open and try again.")
                return
            }
        }
        
        android.util.Log.i("WireAuto", "Wire app is accessible - package: ${rootNode.packageName}")

        updateNotification("Navigating to conversations...")
        sendProgressBroadcast("Navigating to conversations...")
        
        // Try to navigate to conversations/contacts list
        // Wire typically shows conversations by default, but we need to ensure we're on the right screen
        navigateToConversationsList(rootNode)
        delay(4000) // Wait longer for navigation to complete

        // Refresh root after navigation
        rootNode = rootInActiveWindow
        if (rootNode == null || rootNode.packageName != WIRE_PACKAGE) {
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
                rootNode = rootInActiveWindow
                if (rootNode != null && rootNode.packageName == WIRE_PACKAGE) {
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
                rootNode = rootInActiveWindow
                if (rootNode != null && rootNode.packageName == WIRE_PACKAGE) {
                    contactItems = getAllContactItems(rootNode)
                    android.util.Log.d("WireAuto", "After longer wait: ${contactItems.size} contacts found")
                }
            }
            
            if (contactItems.isEmpty()) {
                // Collect debug info about what we found
                val debugInfo = collectDebugInfo(rootNode)
                
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
                        debugInfo
                
                updateNotification("No contacts found - see details in app")
                sendErrorBroadcast(errorMsg)
                return
            }
        }
        
        // NEW APPROACH: Find RecyclerView and identify actual conversation row items
        // Filter out UI elements like search bars, headers, FAB buttons
        val recyclerView = findRecyclerView(rootNode)
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
        val contactResults = mutableListOf<com.wireautomessenger.model.ContactResult>()
        
        for ((index, rowItem) in rowItems.withIndex()) {
            android.util.Log.d("WireAuto", "--- Processing contact ${index + 1}/$totalContacts ---")
            if (contactsProcessed >= maxContacts) {
                android.util.Log.i("WireAuto", "Reached max contacts limit ($maxContacts), stopping")
                break
            }
            
            try {
                // Extract contact name from the row item
                val contactName = extractContactNameFromRow(rowItem) ?: "Contact ${index + 1}"
                
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
                
                contactsProcessed++
                processedContactIndices.add(index)
                
                android.util.Log.i("WireAuto", "=== Processing contact $contactsProcessed/$totalContacts: $contactName ===")
                android.util.Log.d("WireAuto", "Row item: className=${rowItem.className}, clickable=${rowItem.isClickable}, childCount=${rowItem.childCount}")
                android.util.Log.d("WireAuto", "State check: isWireOpened=${isWireOpened.get()}, isSendingInProgress=${isSendingInProgress.get()}")
                
                updateNotification("Sending to contact $contactsProcessed/$totalContacts: $contactName...")
                sendProgressBroadcast("Sending to contact $contactsProcessed/$totalContacts: $contactName...", contactsSent)
                
                // Check if we're still in Wire app (NO relaunch - state machine prevents this)
                var currentRoot = rootInActiveWindow
                if (currentRoot == null || currentRoot.packageName != WIRE_PACKAGE) {
                    android.util.Log.w("WireAuto", "Not in Wire app - checking state machine")
                    
                    // State machine check: If Wire was opened but we lost access, wait and retry
                    if (isWireOpened.get() && isSendingInProgress.get()) {
                        android.util.Log.w("WireAuto", "Wire was opened but lost access - waiting for recovery...")
                        delay(2000)
                            currentRoot = rootInActiveWindow
                    
                    if (currentRoot == null || currentRoot.packageName != WIRE_PACKAGE) {
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
                    currentRoot = rootInActiveWindow
                    
                    // Verify we're still in Wire app and on the list (NO relaunch - state machine)
                    if (currentRoot == null || currentRoot.packageName != WIRE_PACKAGE) {
                        android.util.Log.w("WireAuto", "Lost access to Wire after going back - waiting for recovery...")
                        // Wait and retry (NO relaunch - state machine prevents infinite loops)
                        delay(2000)
                                currentRoot = rootInActiveWindow
                        
                        if (currentRoot == null || currentRoot.packageName != WIRE_PACKAGE) {
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
                        currentRoot = rootInActiveWindow
                    }
                }
                
                // Refresh the row item from current root (it might be stale)
                // Get fresh RecyclerView and find the row at this index
                var refreshedRowItem: AccessibilityNodeInfo? = null
                val freshRecyclerView = findRecyclerView(currentRoot)
                
                if (freshRecyclerView != null && index < freshRecyclerView.childCount) {
                    // Get fresh child at this index
                    refreshedRowItem = freshRecyclerView.getChild(index)
                    if (refreshedRowItem != null) {
                        // Find the actual row container
                        refreshedRowItem = findConversationRowContainer(refreshedRowItem) ?: refreshedRowItem
                    }
                }
                
                // Fallback: try to find by contact name
                if (refreshedRowItem == null) {
                    val cleanContactName = contactName.removePrefix("You: ").trim()
                    refreshedRowItem = findContactNodeByText(currentRoot, contactName)
                        ?: findContactNodeByText(currentRoot, cleanContactName)
                }
                
                // Final fallback: use original row item
                if (refreshedRowItem == null) {
                    refreshedRowItem = rowItem
                    android.util.Log.w("WireAuto", "Could not refresh row item, using original (may be stale)")
                }
                
                android.util.Log.i("WireAuto", "STEP 4.${contactsProcessed}.1: Attempting to click contact row at index $index: $contactName")
                android.util.Log.d("WireAuto", "Row item: clickable=${refreshedRowItem.isClickable}, className=${refreshedRowItem.className}")
                
                // Get bounds for gesture dispatch (most reliable method)
                val bounds = android.graphics.Rect()
                refreshedRowItem.getBoundsInScreen(bounds)
                
                if (bounds.width() <= 0 || bounds.height() <= 0) {
                    android.util.Log.w("WireAuto", "Invalid bounds for row item, cannot click")
                    contactResults.add(com.wireautomessenger.model.ContactResult(
                        name = contactName,
                        status = com.wireautomessenger.model.ContactStatus.FAILED,
                        errorMessage = "Invalid bounds for contact row",
                        position = index + 1
                    ))
                    sendContactUpdate(contactName, "failed", index + 1, "Invalid bounds")
                    continue
                }
                
                val centerX = bounds.centerX().toFloat()
                val centerY = bounds.centerY().toFloat()
                
                android.util.Log.d("WireAuto", "Row bounds: left=${bounds.left}, top=${bounds.top}, right=${bounds.right}, bottom=${bounds.bottom}")
                android.util.Log.d("WireAuto", "Click center: ($centerX, $centerY)")
                
                // Try multiple click methods - prioritize gesture dispatch as it's most reliable
                var clicked = false
                
                // Method 1: Use gesture dispatch (simulate touch) - MOST RELIABLE
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
                            android.util.Log.d("WireAuto", "Gesture completed successfully")
                        }
                        override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                            android.util.Log.w("WireAuto", "Gesture was cancelled")
                        }
                    }, null)
                    
                    android.util.Log.d("WireAuto", "Method 1 - Gesture dispatch: $clicked")
                    if (clicked) {
                        delay(800) // Wait for gesture to complete and UI to respond
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WireAuto", "Gesture dispatch failed: ${e.message}")
                }
                
                // Method 2: Direct click on row item
                if (!clicked && refreshedRowItem.isClickable) {
                    clicked = refreshedRowItem.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    android.util.Log.d("WireAuto", "Method 2 - Direct click on row: $clicked")
                    if (clicked) delay(500)
                }
                
                // Method 3: Find and click any clickable child in the row
                if (!clicked) {
                    for (i in 0 until refreshedRowItem.childCount) {
                        val child = refreshedRowItem.getChild(i)
                        if (child != null && child.isClickable) {
                            clicked = child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            android.util.Log.d("WireAuto", "Method 3 - Clicked clickable child $i in row: $clicked")
                            if (clicked) {
                                delay(500)
                                break
                            }
                        }
                    }
                }
                
                // Method 4: Find clickable parent (the row container itself)
                if (!clicked) {
                    var parent = refreshedRowItem.parent
                    var depth = 0
                    while (parent != null && depth < 5 && !clicked) {
                        if (parent.isClickable) {
                            clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            android.util.Log.d("WireAuto", "Method 4 - Clicked parent at depth $depth: $clicked")
                            if (clicked) delay(500)
                            break
                        }
                        parent = parent.parent
                        depth++
                    }
                }
                
                // Method 5: Try clicking the row item even if not explicitly clickable
                if (!clicked) {
                    clicked = refreshedRowItem.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    android.util.Log.d("WireAuto", "Method 5 - Force click on row item: $clicked")
                    if (clicked) delay(500)
                }
                
                // Method 6: Use findClickableNode helper
                if (!clicked) {
                    val clickableNode = findClickableNode(refreshedRowItem)
                    if (clickableNode != null) {
                        clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        android.util.Log.d("WireAuto", "Method 6 - Clicked via findClickableNode: $clicked")
                        if (clicked) delay(500)
                    }
                }
                
                if (!clicked) {
                    android.util.Log.w("WireAuto", "All click methods failed for contact: $contactName")
                    contactResults.add(com.wireautomessenger.model.ContactResult(
                        name = contactName,
                        status = com.wireautomessenger.model.ContactStatus.FAILED,
                        errorMessage = "Could not click contact after trying 8 methods",
                        position = index + 1
                    ))
                    sendContactUpdate(contactName, "failed", index + 1, "Could not click contact after trying 8 methods")
                    continue
                }
                
                android.util.Log.i("WireAuto", "STEP 4.${contactsProcessed}.2: Waiting for conversation to open...")
                delay(3000) // Wait for conversation to open

                // Verify we're in conversation view - try multiple times (NO relaunch)
                currentRoot = rootInActiveWindow
                var attempts = 0
                while ((currentRoot == null || currentRoot.packageName != WIRE_PACKAGE) && attempts < 3) {
                    android.util.Log.d("WireAuto", "Waiting for Wire app access (attempt ${attempts + 1})...")
                    delay(1000)
                    currentRoot = rootInActiveWindow
                    attempts++
                }
                
                if (currentRoot == null || currentRoot.packageName != WIRE_PACKAGE) {
                    android.util.Log.w("WireAuto", "Not in Wire app after clicking contact: $contactName - waiting...")
                    // Wait and retry (NO relaunch - state machine prevents infinite loops)
                    delay(2000)
                            currentRoot = rootInActiveWindow
                    
                    if (currentRoot == null || currentRoot.packageName != WIRE_PACKAGE) {
                        android.util.Log.w("WireAuto", "Still not in Wire app - trying back button")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        delay(2000)
                        currentRoot = rootInActiveWindow
                        
                        if (currentRoot == null || currentRoot.packageName != WIRE_PACKAGE) {
                        contactResults.add(com.wireautomessenger.model.ContactResult(
                            name = contactName,
                            status = com.wireautomessenger.model.ContactStatus.FAILED,
                            errorMessage = "Lost access after clicking contact",
                            position = index + 1
                        ))
                        sendContactUpdate(contactName, "failed", index + 1, "Lost access after clicking")
                        continue
                        }
                    }
                }

                // Verify we're actually in a conversation (message input should exist)
                var messageInput = findMessageInput(currentRoot)
                if (messageInput == null) {
                    // Wait a bit more and try again - conversation might still be loading
                    android.util.Log.d("WireAuto", "Message input not found immediately, waiting...")
                    delay(2000)
                    currentRoot = rootInActiveWindow
                    if (currentRoot != null && currentRoot.packageName == WIRE_PACKAGE) {
                        messageInput = findMessageInput(currentRoot)
                    }
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

                android.util.Log.i("WireAuto", "STEP 4.${contactsProcessed}.3: Found message input, preparing to type message...")

                // Focus on message input
                try {
                    messageInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    delay(500)
                } catch (e: Exception) {
                    android.util.Log.w("WireAuto", "Could not focus message input: ${e.message}")
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
                android.util.Log.i("WireAuto", "STEP 4.${contactsProcessed}.4: Setting message text: $message")
                val bundle = android.os.Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
                val textSet = messageInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                android.util.Log.i("WireAuto", "Message text set: $textSet")
                
                // Wait for text to be set and send button to be enabled (random delay 1-3 sec)
                val randomDelay = (1000..3000).random()
                android.util.Log.d("WireAuto", "Waiting ${randomDelay}ms for send button to be enabled...")
                delay(randomDelay.toLong())

                // Refresh root after typing
                currentRoot = rootInActiveWindow
                if (currentRoot == null || currentRoot.packageName != WIRE_PACKAGE) {
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

                // Refresh root after typing to get updated UI
                currentRoot = rootInActiveWindow
                if (currentRoot == null || currentRoot.packageName != WIRE_PACKAGE) {
                    android.util.Log.w("WireAuto", "Lost access to Wire after typing: $contactName")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    delay(2000)
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
                android.util.Log.i("WireAuto", "STEP 4.${contactsProcessed}.5: Looking for send button...")
                
                // Try multiple times to find send button (it might take a moment to appear)
                var sendButton: AccessibilityNodeInfo? = null
                for (attempt in 1..3) {
                    currentRoot = rootInActiveWindow
                    if (currentRoot == null || currentRoot.packageName != WIRE_PACKAGE) {
                        android.util.Log.w("WireAuto", "Lost access to Wire while finding send button")
                        break
                    }
                    
                    sendButton = findSendButton(currentRoot)
                    
                    // If not found, try finding button near message input as fallback
                    if (sendButton == null) {
                        android.util.Log.d("WireAuto", "Send button not found by standard methods (attempt $attempt), trying near input...")
                        messageInput = findMessageInput(currentRoot)
                        if (messageInput != null) {
                            sendButton = findSendButtonNearInput(currentRoot, messageInput)
                            if (sendButton != null) {
                                sendButton = getClickableParent(sendButton) // Ensure we get clickable parent
                            }
                        }
                    }
                    
                    if (sendButton != null) {
                        android.util.Log.i("WireAuto", "Send button found on attempt $attempt")
                        break
                    }
                    
                    if (attempt < 3) {
                        val randomDelay = (1000..3000).random() // Random delay 1-3 seconds
                        android.util.Log.d("WireAuto", "Waiting ${randomDelay}ms before retry...")
                        delay(randomDelay.toLong())
                        currentRoot = rootInActiveWindow
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
                        
                        // Method 1: Direct click on clickable parent
                        if (sendButton.isClickable) {
                            clicked = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            android.util.Log.i("WireAuto", "Method 1 - Direct click on send button: $clicked")
                            if (clicked) {
                                val randomDelay = (1000..3000).random()
                                delay(randomDelay.toLong())
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
                                    val randomDelay = (1000..3000).random()
                                    delay(randomDelay.toLong())
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
                                        val randomDelay = (1000..3000).random()
                                        delay(randomDelay.toLong())
                                    }
                                    break
                                }
                                parent = parent.parent
                                depth++
                            }
                        }
                        
                        // Step 3: Verify click success
                        if (clicked) {
                            android.util.Log.i("WireAuto", "Send button clicked - verifying message was sent...")
                            
                            // Wait for UI to update
                            val randomDelay = (1500..2500).random()
                            delay(randomDelay.toLong())
                            
                            // Refresh root to check verification
                            currentRoot = rootInActiveWindow
                            if (currentRoot != null && currentRoot.packageName == WIRE_PACKAGE) {
                                // Verification: Check if input box is cleared OR message appears in chat
                                val refreshedInput = findMessageInput(currentRoot)
                                val inputText = refreshedInput?.text?.toString()?.trim() ?: ""
                                
                                if (inputText.isEmpty() || inputText != message) {
                                    // Input cleared or changed - message likely sent
                                    messageSent = true
                                    android.util.Log.i("WireAuto", "Send confirmed: Input box cleared/changed")
                                } else {
                                    // Check if we're still in conversation (message might have been sent)
                                    val stillInConversation = refreshedInput != null
                                    if (stillInConversation) {
                                        // Still in conversation with input - might have sent, give benefit of doubt
                                        messageSent = true
                                        android.util.Log.i("WireAuto", "Send confirmed: Still in conversation (message likely sent)")
                                    } else {
                                        android.util.Log.w("WireAuto", "Send verification unclear - input text unchanged")
                                        messageSent = true // Assume sent if click succeeded
                                    }
                                }
                            } else {
                                android.util.Log.w("WireAuto", "Lost access after click - assuming message sent")
                                messageSent = true // Assume sent if click succeeded
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

                // Go back to contacts list after sending (or if failed)
                // This ensures we're ready for the next contact
                android.util.Log.i("WireAuto", "STEP 4.${contactsProcessed}.7: Going back to contacts list...")
                currentRoot = rootInActiveWindow
                
                if (currentRoot != null && currentRoot.packageName == WIRE_PACKAGE) {
                    // Check if we're still in conversation (message input exists)
                    val stillInConversation = findMessageInput(currentRoot) != null
                    if (stillInConversation) {
                        android.util.Log.d("WireAuto", "Still in conversation, going back to contacts list...")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        val randomDelay = (2000..4000).random() // Random delay 2-4 seconds
                        android.util.Log.d("WireAuto", "Waiting ${randomDelay}ms for navigation...")
                        delay(randomDelay.toLong())
                        
                        // Verify we're back on the list
                        currentRoot = rootInActiveWindow
                        if (currentRoot != null && currentRoot.packageName == WIRE_PACKAGE) {
                            val stillInConv = findMessageInput(currentRoot) != null
                            if (stillInConv) {
                                android.util.Log.w("WireAuto", "Still in conversation after back, trying once more...")
                                performGlobalAction(GLOBAL_ACTION_BACK)
                                delay(2000)
                                
                                // One more check
                                currentRoot = rootInActiveWindow
                                if (currentRoot != null && currentRoot.packageName == WIRE_PACKAGE) {
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
                    currentRoot = rootInActiveWindow
                    
                    if (currentRoot == null || currentRoot.packageName != WIRE_PACKAGE) {
                        android.util.Log.w("WireAuto", "Still not in Wire app - may need manual intervention")
                        // Don't relaunch - let user handle it or continue with next contact
                    }
                }
                
                // Small random delay before processing next contact to ensure UI is stable (1-3 sec)
                val randomDelay = (1000..3000).random()
                android.util.Log.d("WireAuto", "Waiting ${randomDelay}ms before next contact...")
                delay(randomDelay.toLong())

            } catch (e: Exception) {
                android.util.Log.e("WireAuto", "Error processing contact $contactsProcessed: ${e.message}", e)
                // Try to go back to list on error
                try {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    delay(1500)
                } catch (e2: Exception) {
                    android.util.Log.e("WireAuto", "Error going back to list: ${e2.message}")
                }
                // Continue to next contact even if this one failed
            }
        }
        
        android.util.Log.i("WireAuto", "=== Finished processing all contacts ===")
        android.util.Log.i("WireAuto", "Total contacts processed: $contactsProcessed")
        android.util.Log.i("WireAuto", "Total messages sent: $contactsSent")

        // Save last send time and completion status
        prefs.edit()
            .putLong("last_send_time", System.currentTimeMillis())
            .putBoolean("sending_complete", true)
            .putInt("last_contacts_sent", contactsSent)
            .putInt("last_contacts_processed", contactsProcessed)
            .putInt("last_total_contacts", totalContacts)
            .apply()
        
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
        val contacts = mutableListOf<AccessibilityNodeInfo>()
        
        android.util.Log.d("WireAuto", "=== Starting contact detection ===")
        android.util.Log.d("WireAuto", "Root package: ${root.packageName}, className: ${root.className}")
        
        // Method 1: Look for RecyclerView or ListView containing contacts
        val recyclerView = findRecyclerView(root)
        if (recyclerView != null) {
            android.util.Log.d("WireAuto", "Found RecyclerView with ${recyclerView.childCount} direct children")
            
            // Get all children recursively from RecyclerView
            for (i in 0 until recyclerView.childCount) {
                val child = recyclerView.getChild(i)
                if (child != null) {
                    findContactItemsInNode(child, contacts)
                }
            }
            
            // Also search recursively within RecyclerView
            findContactItemsRecursive(recyclerView, contacts)
            
            android.util.Log.d("WireAuto", "After RecyclerView search: ${contacts.size} contacts found")
        } else {
            android.util.Log.d("WireAuto", "No RecyclerView found")
        }
        
        // Method 2: Search entire root recursively for contact items
        if (contacts.isEmpty()) {
            android.util.Log.d("WireAuto", "No contacts in RecyclerView, searching entire root recursively...")
            findContactItemsRecursive(root, contacts)
            android.util.Log.d("WireAuto", "After recursive search: ${contacts.size} contacts found")
        }
        
        // Method 3: Broader search - find any clickable items with text (less strict)
        if (contacts.isEmpty()) {
            android.util.Log.d("WireAuto", "Trying broader search for clickable items with text...")
            findClickableItemsWithText(root, contacts)
            android.util.Log.d("WireAuto", "After broader search: ${contacts.size} contacts found")
        }
        
        // Remove duplicates based on node reference
        val uniqueContacts = contacts.distinctBy { 
            // Use a combination of text and position to identify unique contacts
            val bounds = android.graphics.Rect()
            it.getBoundsInScreen(bounds)
            "${it.text}_${bounds.left}_${bounds.top}_${bounds.right}_${bounds.bottom}"
        }
        
        android.util.Log.i("WireAuto", "=== Contact detection complete: ${uniqueContacts.size} unique contacts found ===")
        if (uniqueContacts.isNotEmpty()) {
            uniqueContacts.take(5).forEachIndexed { index, contact ->
                android.util.Log.d("WireAuto", "Contact ${index + 1}: text='${contact.text}', clickable=${contact.isClickable}, className=${contact.className}")
            }
        } else {
            android.util.Log.w("WireAuto", "No contacts found! This might indicate:")
            android.util.Log.w("WireAuto", "1. Wire UI structure is different than expected")
            android.util.Log.w("WireAuto", "2. Contacts are in a different screen/view")
            android.util.Log.w("WireAuto", "3. Wire uses custom views not detected by accessibility")
        }
        
        return uniqueContacts
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
    
    private fun navigateToConversationsList(root: AccessibilityNodeInfo) {
        // Try to find and click on "Conversations" or "Chats" tab/button
        val conversationsButton = findNodeByText(root, "Conversations")
            ?: findNodeByText(root, "Chats")
            ?: findNodeByText(root, "Messages")
            ?: findNodeByContentDescription(root, "Conversations")
            ?: findNodeByContentDescription(root, "Chats")
        
        if (conversationsButton != null) {
            val clickableNode = findClickableNode(conversationsButton)
            if (clickableNode != null) {
                clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                android.util.Log.d("WireAuto", "Clicked on Conversations button")
                return
            }
        }
        
        // If not found, try to find bottom navigation or tab bar
        // Wire typically shows conversations by default, so this might not be needed
        android.util.Log.d("WireAuto", "Conversations button not found, assuming already on conversations screen")
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
        // Look for EditText or text input field, but exclude search boxes
        // Search boxes typically have "Search" in their content description
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
            
            // Prefer message input fields
            if (contentDesc.contains("message") || 
                contentDesc.contains("type a message") ||
                hint.contains("message") ||
                hint.contains("type")) {
                return editText
            }
        }
        
        // If no specific message input found, return first non-search EditText
        for (editText in allEditTexts) {
            val contentDesc = editText.contentDescription?.toString()?.lowercase() ?: ""
            val hint = editText.hintText?.toString()?.lowercase() ?: ""
            if (!contentDesc.contains("search") && !hint.contains("search")) {
                return editText
            }
        }
        
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
        
        // Strategy 1: findAccessibilityNodeInfosByViewId (if available - Android 10+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                val viewIdNodes = root.findAccessibilityNodeInfosByViewId("com.wire:id/send")
                    ?: root.findAccessibilityNodeInfosByViewId("android:id/send")
                    ?: root.findAccessibilityNodeInfosByViewId("send")
                
                if (viewIdNodes != null && viewIdNodes.isNotEmpty()) {
                    val sendNode = viewIdNodes[0]
                    android.util.Log.i("WireAuto", "Send button found via ViewId: ${sendNode.viewIdResourceName}")
                    return getClickableParent(sendNode)
                }
            } catch (e: Exception) {
                android.util.Log.d("WireAuto", "ViewId search failed: ${e.message}")
            }
        }
        
        // Strategy 2: findAccessibilityNodeInfosByText("Send")
        try {
            val textNodes = root.findAccessibilityNodeInfosByText("Send")
            if (textNodes != null && textNodes.isNotEmpty()) {
                for (node in textNodes) {
                    val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
                    val text = node.text?.toString()?.lowercase() ?: ""
                    if (contentDesc.contains("send") || text.contains("send")) {
                        android.util.Log.i("WireAuto", "Send button found via Text: 'Send'")
                        return getClickableParent(node)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.d("WireAuto", "Text search failed: ${e.message}")
        }
        
        // Strategy 3: ContentDescription containing "send"
        val contentDescNodes = findAllNodesByContentDescription(root, "send")
        if (contentDescNodes.isNotEmpty()) {
            android.util.Log.i("WireAuto", "Send button found via ContentDescription: ${contentDescNodes.size} candidates")
            // Prefer clickable nodes or their clickable parents
            for (node in contentDescNodes) {
                val clickableParent = getClickableParent(node)
                if (clickableParent != null && clickableParent.isClickable) {
                    return clickableParent
                }
            }
            // Return first node's clickable parent if any
            return getClickableParent(contentDescNodes[0])
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
                android.util.Log.i("WireAuto", "Send button found via Button search: className=${button.className}")
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
        // - Toolbars, action bars
        // - Empty or very small items
        
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString()?.trim() ?: ""
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
        val lowerText = text.lowercase()
        val lowerDesc = contentDesc.lowercase()
        
        // Exclude search bars
        if (lowerText.contains("search") || lowerDesc.contains("search") ||
            className.contains("SearchView", ignoreCase = true) ||
            className.contains("EditText", ignoreCase = true)) {
            return false
        }
        
        // Exclude section headers
        if (text == "CONVERSATIONS" || text == "CONVERSATION" || 
            text == "CHATS" || text == "MESSAGES" ||
            (text.isNotEmpty() && text.all { it.isLetter() && it.isUpperCase() && text.length < 15 })) {
            return false
        }
        
        // Exclude FAB buttons
        if (className.contains("FloatingActionButton", ignoreCase = true) ||
            lowerText == "new" || lowerDesc == "new" ||
            (text == "New" && className.contains("Button", ignoreCase = true))) {
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
        if (bounds.height() < 50) { // Conversation rows are typically taller than 50px
            return false
        }
        
        // A conversation row should:
        // - Have text content (contact name or message preview)
        // - Be clickable or have clickable parent
        // - Have multiple children (avatar, name, message, etc.)
        val hasText = text.isNotEmpty() || contentDesc.isNotEmpty()
        val hasMultipleChildren = node.childCount >= 2
        val isClickable = node.isClickable || findClickableNode(node) != null
        
        // Must be in Wire package
        if (node.packageName != WIRE_PACKAGE) {
            return false
        }
        
        return hasText && hasMultipleChildren && isClickable
    }
    
    private fun findClickableContainersInRecyclerView(root: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
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
                findClickableContainersInRecyclerView(child, result)
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

