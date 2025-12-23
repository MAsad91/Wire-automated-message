package com.wireautomessenger.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
        const val WIRE_PACKAGE = "ch.wire"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "wire_automation_channel"
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
                isRunning.set(false)
                return
            }

            updateNotification("Opening Wire app...")
            
            // Launch Wire app
            val wireIntent = packageManager.getLaunchIntentForPackage(WIRE_PACKAGE)
            if (wireIntent != null) {
                wireIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(wireIntent)
                delay(3000) // Wait for app to open
            } else {
                updateNotification("Wire app not found")
                isRunning.set(false)
                return
            }

            // Navigate to contacts and send messages
            navigateAndSendMessages(message)

        } catch (e: Exception) {
            updateNotification("Error: ${e.message}")
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
        val maxContacts = 500 // Safety limit
        
        updateNotification("Navigating to contacts...")
        delay(2000)

        // Try to find and click on contacts/conversations tab
        // This will vary based on Wire's UI structure
        val rootNode = rootInActiveWindow ?: return

        // Look for contacts list or conversations
        val contactsList = findContactsList(rootNode)
        
        if (contactsList == null) {
            // Try to navigate to contacts
            navigateToContacts(rootNode)
            delay(2000)
        }

        // Get all contact items
        val contactItems = getAllContactItems(rootInActiveWindow ?: return)
        
        updateNotification("Found ${contactItems.size} contacts. Sending messages...")

        for ((index, contactNode) in contactItems.withIndex()) {
            if (contactsProcessed >= maxContacts) break
            
            try {
                // Click on contact
                if (contactNode.isClickable) {
                    contactNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    delay(1500) // Wait for conversation to open
                } else {
                    // Try to find clickable parent
                    var parent = contactNode.parent
                    var attempts = 0
                    while (parent != null && attempts < 5) {
                        if (parent.isClickable) {
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            delay(1500)
                            break
                        }
                        parent = parent.parent
                        attempts++
                    }
                }

                // Find message input field
                val messageInput = findMessageInput(rootInActiveWindow ?: continue)
                if (messageInput != null) {
                    // Type message
                    val bundle = android.os.Bundle()
                    bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
                    messageInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                    delay(500)

                    // Find and click send button
                    val sendButton = findSendButton(rootInActiveWindow ?: continue)
                    if (sendButton != null) {
                        sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        delay(1000)
                        contactsProcessed++
                        
                        updateNotification("Sent to $contactsProcessed contacts...")
                    }
                }

                // Go back to contacts list
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(1000)

            } catch (e: Exception) {
                e.printStackTrace()
                // Continue with next contact
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(1000)
            }
        }

        // Save last send time and completion status
        prefs.edit()
            .putLong("last_send_time", System.currentTimeMillis())
            .putBoolean("sending_complete", true)
            .apply()
        
        updateNotification("Completed! Sent to $contactsProcessed contacts.")
        
        // Show toast
        scope.launch(Dispatchers.Main) {
            Toast.makeText(this@WireAutomationService, 
                "Messages sent to $contactsProcessed contacts", 
                Toast.LENGTH_LONG).show()
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
        
        // Look for RecyclerView or ListView containing contacts
        val recyclerView = findRecyclerView(root)
        if (recyclerView != null) {
            for (i in 0 until recyclerView.childCount) {
                val child = recyclerView.getChild(i)
                if (child != null && isContactItem(child)) {
                    contacts.add(child)
                }
            }
        } else {
            // Fallback: search for all clickable items that might be contacts
            findContactItemsRecursive(root, contacts)
        }
        
        return contacts
    }

    private fun findRecyclerView(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNodeByClassName(root, "androidx.recyclerview.widget.RecyclerView")
            ?: findNodeByClassName(root, "android.widget.ListView")
    }

    private fun isContactItem(node: AccessibilityNodeInfo): Boolean {
        // A contact item typically has text (name) and is clickable
        return node.isClickable && 
               (node.text != null && node.text.isNotEmpty() ||
                node.contentDescription != null && node.contentDescription.isNotEmpty())
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
        return findNodeByContentDescription(root, "Send")
            ?: findNodeByContentDescription(root, "Send message")
            ?: findNodeByText(root, "Send")
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
}

