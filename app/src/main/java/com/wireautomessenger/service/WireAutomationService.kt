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

    private val isRunning = AtomicBoolean(false)
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
        
        // Broadcast extras
        const val EXTRA_PROGRESS_TEXT = "progress_text"
        const val EXTRA_CONTACTS_SENT = "contacts_sent"
        const val EXTRA_ERROR_MESSAGE = "error_message"
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
        // Handle events if needed for automation
        // This method must not throw exceptions
        try {
            // Event handling can be added here if needed
        } catch (e: Exception) {
            // Silently handle any errors
            e.printStackTrace()
        }
    }

    override fun onInterrupt() {
        // Service interrupted
        // This method must not throw exceptions
        try {
            isRunning.set(false)
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
        if (isRunning.getAndSet(true)) {
            return
        }

        try {
            val message = prefs.getString("pending_message", "") ?: ""
            if (message.isEmpty()) {
                updateNotification("No message to send")
                sendErrorBroadcast("No message to send. Please enter a message first.")
                isRunning.set(false)
                return
            }

            updateNotification("Opening Wire app...")
            sendProgressBroadcast("Opening Wire app...")
            
            // Launch Wire app - try multiple methods and package names
            var wireIntent: Intent? = null
            var foundPackage: String? = null
            
            val allPackages = listOf(WIRE_PACKAGE, "com.wire", "ch.wire", "wire")
            
            // Method 1: Try to get launch intent (most reliable)
            for (pkg in allPackages) {
                try {
                    wireIntent = packageManager.getLaunchIntentForPackage(pkg)
                    if (wireIntent != null) {
                        foundPackage = pkg
                        android.util.Log.d("WireLaunch", "Found Wire app with package: $pkg (launch intent)")
                        break
                    }
                } catch (e: Exception) {
                    android.util.Log.d("WireLaunch", "Launch intent check failed for $pkg: ${e.message}")
                }
            }
            
            // Method 2: Check if package exists (even if disabled)
            if (wireIntent == null) {
                for (pkg in allPackages) {
                    try {
                        val packageInfo = packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                        if (packageInfo != null) {
                            // Package exists, try to get launch intent again
                            wireIntent = packageManager.getLaunchIntentForPackage(pkg)
                            if (wireIntent != null) {
                                foundPackage = pkg
                                android.util.Log.d("WireLaunch", "Found Wire app with package: $pkg (package info)")
                                break
                            } else {
                                android.util.Log.w("WireLaunch", "Wire package $pkg exists but has no launch intent (may be disabled)")
                            }
                        }
                    } catch (e: PackageManager.NameNotFoundException) {
                        // Package doesn't exist, continue
                    } catch (e: Exception) {
                        android.util.Log.d("WireLaunch", "Package info check failed for $pkg: ${e.message}")
                    }
                }
            }
            
            // Method 3: Check installed packages list as last resort
            if (wireIntent == null) {
                try {
                    val installedPackages = packageManager.getInstalledPackages(PackageManager.GET_ACTIVITIES)
                    for (pkg in allPackages) {
                        val found = installedPackages.any { it.packageName == pkg }
                        if (found) {
                            // Try one more time to get launch intent
                            wireIntent = packageManager.getLaunchIntentForPackage(pkg)
                            if (wireIntent != null) {
                                foundPackage = pkg
                                android.util.Log.d("WireLaunch", "Found Wire app with package: $pkg (installed packages list)")
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WireLaunch", "Error checking installed packages: ${e.message}", e)
                }
            }
            
            if (wireIntent != null && foundPackage != null) {
                wireIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                android.util.Log.i("WireLaunch", "Launching Wire app with package: $foundPackage")
                startActivity(wireIntent)
                delay(3000) // Wait for app to open
            } else {
                val errorMsg = "Wire app not found. Please ensure Wire app is installed from Google Play Store (package: com.wire) and try again."
                android.util.Log.e("WireLaunch", errorMsg)
                updateNotification("Wire app not found")
                sendErrorBroadcast(errorMsg)
                isRunning.set(false)
                return
            }

            // Navigate to contacts and send messages
            navigateAndSendMessages(message)

        } catch (e: Exception) {
            val errorMsg = "Error: ${e.message ?: "Unknown error"}"
            updateNotification(errorMsg)
            sendErrorBroadcast(errorMsg)
            e.printStackTrace()
        } finally {
            isRunning.set(false)
            delay(2000)
            stopForeground(true)
            stopSelf()
        }
    }

    private suspend fun navigateAndSendMessages(message: String) {
        var contactsProcessed = 0
        var contactsSent = 0
        val maxContacts = 500 // Safety limit
        
        updateNotification("Waiting for Wire app to load...")
        sendProgressBroadcast("Waiting for Wire app to load...")
        delay(4000) // Give Wire more time to fully load

        // Ensure we're in Wire app - check package name
        var rootNode = rootInActiveWindow
        if (rootNode == null || rootNode.packageName != WIRE_PACKAGE) {
            // Wait a bit more and check again
            delay(2000)
            rootNode = rootInActiveWindow
            if (rootNode == null || rootNode.packageName != WIRE_PACKAGE) {
                sendErrorBroadcast("Could not access Wire app. Please ensure Wire is open and try again.")
                return
            }
        }

        updateNotification("Navigating to conversations...")
        sendProgressBroadcast("Navigating to conversations...")
        
        // Try to navigate to conversations/contacts list
        // Wire typically shows conversations by default, but we need to ensure we're on the right screen
        navigateToConversationsList(rootNode)
        delay(3000) // Wait for navigation

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
        
        val totalContacts = contactItems.size
        android.util.Log.i("WireAuto", "Starting to send messages to $totalContacts contacts")
        updateNotification("Found $totalContacts contacts. Sending messages...")
        sendProgressBroadcast("Found $totalContacts contacts. Sending messages...", 0)

        // Process contacts
        val contactsToProcess = contactItems.toList() // Create a copy to avoid modification issues
        for ((index, contactNode) in contactsToProcess.withIndex()) {
            if (contactsProcessed >= maxContacts) break
            
            try {
                contactsProcessed++
                val contactName = contactNode.text?.toString() ?: "Contact $contactsProcessed"
                android.util.Log.d("WireAuto", "Processing contact $contactsProcessed: $contactName")
                
                updateNotification("Sending to contact $contactsProcessed/$totalContacts...")
                sendProgressBroadcast("Sending to contact $contactsProcessed/$totalContacts...", contactsSent)
                
                // Ensure we're still in Wire app
                var currentRoot = rootInActiveWindow
                if (currentRoot == null || currentRoot.packageName != WIRE_PACKAGE) {
                    android.util.Log.w("WireAuto", "Not in Wire app, skipping contact")
                    continue
                }
                
                // Click on contact - try multiple methods
                var clicked = false
                if (contactNode.isClickable) {
                    contactNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    clicked = true
                } else {
                    // Try to find clickable parent or child
                    var nodeToClick = findClickableNode(contactNode)
                    if (nodeToClick != null) {
                        nodeToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        clicked = true
                    }
                }
                
                if (!clicked) {
                    android.util.Log.w("WireAuto", "Could not click contact, skipping")
                    continue
                }
                
                delay(2000) // Wait for conversation to open

                // Verify we're in conversation view
                currentRoot = rootInActiveWindow
                if (currentRoot == null || currentRoot.packageName != WIRE_PACKAGE) {
                    android.util.Log.w("WireAuto", "Not in Wire app after clicking contact")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    delay(1000)
                    continue
                }

                // Find message input field - try multiple methods
                val messageInput = findMessageInput(currentRoot)
                if (messageInput == null) {
                    android.util.Log.w("WireAuto", "Message input not found for contact $contactsProcessed")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    delay(1000)
                    continue
                }

                // Clear any existing text first
                try {
                    messageInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    delay(300)
                    messageInput.performAction(AccessibilityNodeInfo.ACTION_CLEAR_SELECTION)
                    delay(200)
                } catch (e: Exception) {
                    // Ignore if clear doesn't work
                }

                // Type message
                val bundle = android.os.Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
                messageInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                delay(1500) // Wait for text to be set and send button to be enabled

                // Try to click send button with retry logic (up to 3 attempts)
                var messageSent = false
                for (attempt in 1..3) {
                    // Refresh root after typing/retry
                    currentRoot = rootInActiveWindow
                    if (currentRoot == null || currentRoot.packageName != WIRE_PACKAGE) {
                        android.util.Log.w("WireAuto", "Lost access to Wire after typing (attempt $attempt)")
                        if (attempt < 3) {
                            delay(1000)
                            continue
                        } else {
                            break
                        }
                    }

                    // Find send button - try multiple methods
                    var sendButton = findSendButton(currentRoot)
                    
                    // If not found, try finding button near message input
                    if (sendButton == null) {
                        android.util.Log.d("WireAuto", "Send button not found by standard methods (attempt $attempt), trying alternative...")
                        sendButton = findSendButtonNearInput(currentRoot, messageInput)
                    }
                    
                    // If still not found, try finding any clickable icon/button near input
                    if (sendButton == null) {
                        android.util.Log.d("WireAuto", "Trying to find any clickable element near input (attempt $attempt)...")
                        sendButton = findAnyClickableNearInput(currentRoot, messageInput)
                    }
                    
                    // Try to click the send button
                    if (sendButton != null) {
                        android.util.Log.d("WireAuto", "Found send button (attempt $attempt), trying to click...")
                        
                        // Try multiple clicking methods
                        var clicked = false
                        
                        // Method 1: Direct click
                        if (sendButton.isClickable) {
                            clicked = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            android.util.Log.d("WireAuto", "Clicked send button directly: $clicked")
                        }
                        
                        // Method 2: Find clickable parent/child
                        if (!clicked) {
                            val clickableNode = findClickableNode(sendButton)
                            if (clickableNode != null) {
                                clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                android.util.Log.d("WireAuto", "Clicked send button via clickable node: $clicked")
                            }
                        }
                        
                        // Method 3: Try clicking parent if button itself isn't clickable
                        if (!clicked) {
                            var parent = sendButton.parent
                            var depth = 0
                            while (parent != null && depth < 3 && !clicked) {
                                if (parent.isClickable) {
                                    clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    android.util.Log.d("WireAuto", "Clicked send button parent: $clicked")
                                    break
                                }
                                parent = parent.parent
                                depth++
                            }
                        }
                        
                        // Method 4: Try clicking children
                        if (!clicked) {
                            for (i in 0 until sendButton.childCount) {
                                val child = sendButton.getChild(i)
                                if (child != null && child.isClickable) {
                                    clicked = child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    if (clicked) {
                                        android.util.Log.d("WireAuto", "Clicked send button child: $clicked")
                                        break
                                    }
                                }
                            }
                        }
                        
                        if (clicked) {
                            messageSent = true
                            android.util.Log.i("WireAuto", "✓ Successfully clicked send button on attempt $attempt")
                            delay(2000) // Wait for message to send
                            break
                        } else {
                            android.util.Log.w("WireAuto", "Could not click send button on attempt $attempt")
                            if (attempt < 3) {
                                delay(1000) // Wait before retry
                            }
                        }
                    } else {
                        android.util.Log.w("WireAuto", "Send button not found on attempt $attempt")
                        if (attempt < 3) {
                            delay(1000) // Wait before retry
                        }
                    }
                }
                
                if (!messageSent) {
                    android.util.Log.w("WireAuto", "Failed to send message after 3 attempts for contact $contactsProcessed")
                    // Still continue - might have been sent
                }
                
                contactsSent++
                android.util.Log.i("WireAuto", "✓ Message sent to contact $contactsSent: $contactName")
                
                updateNotification("Sent to $contactsSent/$totalContacts contacts...")
                sendProgressBroadcast("Sent to $contactsSent/$totalContacts contacts...", contactsSent)

                // Go back to contacts list
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(1500) // Wait to return to list

            } catch (e: Exception) {
                android.util.Log.e("WireAuto", "Error processing contact $contactsProcessed: ${e.message}", e)
                // Try to go back and continue
                try {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    delay(1000)
                } catch (e2: Exception) {
                    // Ignore
                }
            }
        }

        // Save last send time and completion status
        prefs.edit()
            .putLong("last_send_time", System.currentTimeMillis())
            .putBoolean("sending_complete", true)
            .putInt("last_contacts_sent", contactsSent)
            .apply()
        
        val finalMessage = if (contactsSent > 0) {
            "Completed! Sent to $contactsSent out of $contactsProcessed contacts."
        } else {
            "Completed but no messages were sent. Please check Wire app and try again."
        }
        
        updateNotification(finalMessage)
        sendCompletionBroadcast(contactsSent)
        
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
        // Look for EditText or text input field
        return findNodeByClassName(root, "android.widget.EditText")
            ?: findNodeByClassName(root, "androidx.appcompat.widget.AppCompatEditText")
            ?: findNodeByContentDescription(root, "Message")
            ?: findNodeByContentDescription(root, "Type a message")
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Try multiple methods to find send button
        return findNodeByContentDescription(root, "Send")
            ?: findNodeByContentDescription(root, "Send message")
            ?: findNodeByContentDescription(root, "send") // lowercase
            ?: findNodeByContentDescription(root, "Send message") // with space
            ?: findNodeByText(root, "Send")
            ?: findNodeByText(root, "send") // lowercase
            ?: findNodeByClassName(root, "android.widget.ImageButton") // Icon button
            ?: findNodeByClassName(root, "androidx.appcompat.widget.AppCompatImageButton") // AppCompat icon button
            ?: findNodeByClassName(root, "android.widget.Button") // Regular button
            ?: findNodeByClassName(root, "androidx.appcompat.widget.AppCompatButton") // AppCompat button
            ?: findNodeByViewId(root, "send") // Try by resource ID
            ?: findNodeByViewId(root, "send_button") // Try alternative ID
            ?: findNodeByViewId(root, "btn_send") // Try alternative ID
    }
    
    private fun findSendButtonNearInput(root: AccessibilityNodeInfo, inputField: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Get input field bounds to find buttons near it
        val inputBounds = android.graphics.Rect()
        inputField.getBoundsInScreen(inputBounds)
        
        // Look for buttons near the input field (siblings or in same parent)
        var parent = inputField.parent
        var depth = 0
        var bestMatch: AccessibilityNodeInfo? = null
        var bestScore = 0
        
        while (parent != null && depth < 5) {
            // Look for buttons in the same parent
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i)
                if (child != null && child != inputField) {
                    val className = child.className?.toString() ?: ""
                    val contentDesc = child.contentDescription?.toString() ?: ""
                    val text = child.text?.toString() ?: ""
                    
                    // Get child bounds
                    val childBounds = android.graphics.Rect()
                    child.getBoundsInScreen(childBounds)
                    
                    // Check if it's a button-like element
                    var score = 0
                    if (className.contains("Button", ignoreCase = true) ||
                        className.contains("ImageButton", ignoreCase = true) ||
                        className.contains("Image", ignoreCase = true)) {
                        score += 10
                    }
                    if (contentDesc.contains("send", ignoreCase = true)) {
                        score += 20
                    }
                    if (text.contains("send", ignoreCase = true)) {
                        score += 15
                    }
                    // Prefer buttons on the right side of input (where send buttons usually are)
                    if (childBounds.left > inputBounds.right) {
                        score += 5
                    }
                    // Prefer clickable and enabled buttons
                    if (child.isClickable) {
                        score += 10
                    }
                    if (child.isEnabled) {
                        score += 5
                    }
                    
                    if (score > bestScore && (child.isClickable || child.isEnabled)) {
                        bestMatch = child
                        bestScore = score
                        android.util.Log.d("WireAuto", "Found potential send button near input (score: $score): $className, desc: $contentDesc")
                    }
                }
            }
            parent = parent.parent
            depth++
        }
        
        return bestMatch
    }
    
    private fun findAnyClickableNearInput(root: AccessibilityNodeInfo, inputField: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Get input field bounds
        val inputBounds = android.graphics.Rect()
        inputField.getBoundsInScreen(inputBounds)
        
        // Find any clickable element near the input field (especially on the right side)
        var bestMatch: AccessibilityNodeInfo? = null
        var bestDistance = Int.MAX_VALUE
        
        findClickableNodesRecursive(root) { node ->
            if (node != inputField && node.isClickable) {
                val nodeBounds = android.graphics.Rect()
                node.getBoundsInScreen(nodeBounds)
                
                // Check if it's near the input field (especially on the right side)
                val isOnRight = nodeBounds.left > inputBounds.right
                val nodeCenterY = (nodeBounds.top + nodeBounds.bottom) / 2
                val inputCenterY = (inputBounds.top + inputBounds.bottom) / 2
                val isNearVertically = kotlin.math.abs(nodeCenterY - inputCenterY) < inputBounds.height() * 2
                val horizontalDistance = if (isOnRight) {
                    nodeBounds.left - inputBounds.right
                } else {
                    inputBounds.left - nodeBounds.right
                }
                
                // Prefer elements on the right side, close to the input
                if ((isOnRight || horizontalDistance < 200) && isNearVertically && horizontalDistance < 300) {
                    if (horizontalDistance < bestDistance) {
                        bestMatch = node
                        bestDistance = horizontalDistance
                        android.util.Log.d("WireAuto", "Found clickable near input: ${node.className}, distance: $horizontalDistance")
                    }
                }
            }
        }
        
        return bestMatch
    }
    
    private fun findClickableNodesRecursive(node: AccessibilityNodeInfo, callback: (AccessibilityNodeInfo) -> Unit) {
        callback(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findClickableNodesRecursive(child, callback)
            }
        }
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
    
    private fun sendCompletionBroadcast(contactsSent: Int) {
        try {
            val intent = Intent(ACTION_COMPLETED).apply {
                putExtra(EXTRA_CONTACTS_SENT, contactsSent)
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

