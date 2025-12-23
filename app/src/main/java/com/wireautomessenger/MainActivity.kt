package com.wireautomessenger

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.wireautomessenger.service.WireAutomationService
import com.wireautomessenger.work.MessageSendingWorker
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var etMessage: TextInputEditText
    private lateinit var btnSendNow: MaterialButton
    private lateinit var btnEnableAccessibility: MaterialButton
    private lateinit var switchSchedule: SwitchMaterial
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvNextSend: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var llProgress: LinearLayout

    private val prefs by lazy {
        getSharedPreferences("WireAutoMessenger", Context.MODE_PRIVATE)
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        requestPermissions()
        checkAccessibilityService()
        loadSavedMessage()
        setupListeners()
        updateScheduleStatus()
    }

    private fun requestPermissions() {
        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                showNotificationPermissionDialog()
            }
        }

        // Check if Accessibility Service is enabled, if not show dialog on first launch
        if (!isAccessibilityServiceEnabled()) {
            val isFirstLaunch = prefs.getBoolean("first_launch", true)
            if (isFirstLaunch) {
                prefs.edit().putBoolean("first_launch", false).apply()
                showAccessibilityPermissionDialog()
            }
        }
    }

    private fun showNotificationPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notification_permission_title)
            .setMessage(R.string.notification_permission_message)
            .setPositiveButton(R.string.notification_permission_allow) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        NOTIFICATION_PERMISSION_REQUEST_CODE
                    )
                }
            }
            .setNegativeButton(R.string.notification_permission_skip, null)
            .setCancelable(true)
            .show()
    }

    private fun showAccessibilityPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_dialog_title)
            .setMessage(getString(R.string.permission_dialog_message) + "\n\n" +
                    "📱 For Redmi/Xiaomi Devices:\n\n" +
                    "1. Tap 'Enable Now' below\n" +
                    "2. You'll see Accessibility Settings\n" +
                    "3. Look for 'Downloaded apps' or 'Installed services'\n" +
                    "4. Find 'Wire Auto Messenger' in that list\n" +
                    "5. Tap on 'Wire Auto Messenger'\n" +
                    "6. Toggle the switch ON\n" +
                    "7. If you see 'Restricted Settings' blocking access:\n" +
                    "   → Go to Settings → Apps → Restricted Settings\n" +
                    "   → Allow 'Wire Auto Messenger'\n" +
                    "   → Then enable Accessibility Service again\n" +
                    "8. Tap 'Allow' or 'OK' on the warning\n" +
                    "9. Return to this app\n\n" +
                    "⚠️ Important: If blocked by Restricted Settings, allow it first!")
            .setPositiveButton(R.string.permission_dialog_positive) { _, _ ->
                openAccessibilityServiceSettings()
            }
            .setNegativeButton(R.string.permission_dialog_negative, null)
            .setCancelable(false)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun initViews() {
        etMessage = findViewById(R.id.etMessage)
        btnSendNow = findViewById(R.id.btnSendNow)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        switchSchedule = findViewById(R.id.switchSchedule)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvStatus = findViewById(R.id.tvStatus)
        tvNextSend = findViewById(R.id.tvNextSend)
        progressBar = findViewById(R.id.progressBar)
        llProgress = findViewById(R.id.llProgress)
    }

    private fun checkAccessibilityService() {
        val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        
        // Check by package name
        var isEnabled = enabledServices.any { service ->
            service.resolveInfo.serviceInfo.packageName == packageName
        }
        
        // Also check by service name for better compatibility
        if (!isEnabled) {
            isEnabled = enabledServices.any { service ->
                service.resolveInfo.serviceInfo.name == "com.wireautomessenger.service.WireAutomationService" ||
                service.resolveInfo.serviceInfo.name.contains("WireAutomationService", ignoreCase = true)
            }
        }
        
        // Check if service is malfunctioning (enabled but not working)
        var isMalfunctioning = false
        if (isEnabled) {
            try {
                // Try to get service info to check if it's actually working
                val serviceInfo = enabledServices.firstOrNull { service ->
                    service.resolveInfo.serviceInfo.packageName == packageName ||
                    service.resolveInfo.serviceInfo.name.contains("WireAutomationService", ignoreCase = true)
                }
                
                // If service is enabled but we can't access it properly, it might be malfunctioning
                if (serviceInfo == null) {
                    isMalfunctioning = true
                }
            } catch (e: Exception) {
                // If we get an error checking the service, it might be malfunctioning
                isMalfunctioning = true
            }
        }
        
        // Debug: Log all enabled services (for troubleshooting)
        if (!isEnabled && enabledServices.isNotEmpty()) {
            android.util.Log.d("AccessibilityCheck", "Enabled services: ${enabledServices.map { it.resolveInfo.serviceInfo.packageName + "/" + it.resolveInfo.serviceInfo.name }}")
        }

        updateAccessibilityStatus(isEnabled, isMalfunctioning)
    }

    private fun updateAccessibilityStatus(isEnabled: Boolean, isMalfunctioning: Boolean = false) {
        if (isEnabled && !isMalfunctioning) {
            tvAccessibilityStatus.text = "✓ Enabled"
            tvAccessibilityStatus.setTextColor(getColor(R.color.on_success))
            tvAccessibilityStatus.setBackgroundResource(R.drawable.status_badge_success)
            btnEnableAccessibility.text = "Service Enabled"
            btnEnableAccessibility.isEnabled = false
        } else if (isMalfunctioning) {
            tvAccessibilityStatus.text = "⚠ Malfunctioning"
            tvAccessibilityStatus.setTextColor(getColor(R.color.on_error))
            tvAccessibilityStatus.setBackgroundResource(R.drawable.status_badge_error)
            btnEnableAccessibility.text = "Fix Service"
            btnEnableAccessibility.isEnabled = true
            // Show helpful dialog
            showMalfunctioningDialog()
        } else {
            tvAccessibilityStatus.text = "✗ Not Enabled"
            tvAccessibilityStatus.setTextColor(getColor(R.color.on_error))
            tvAccessibilityStatus.setBackgroundResource(R.drawable.status_badge_error)
            btnEnableAccessibility.text = "Enable Accessibility Service"
            btnEnableAccessibility.isEnabled = true
        }
    }
    
    private fun showMalfunctioningDialog() {
        // Only show once per session to avoid annoying the user
        val key = "malfunction_dialog_shown_${System.currentTimeMillis() / 1000 / 60}" // Show once per minute
        if (prefs.getBoolean(key, false)) {
            return
        }
        prefs.edit().putBoolean(key, true).apply()
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Service Malfunctioning")
            .setMessage("The Accessibility Service is enabled but not working properly.\n\n" +
                    "To fix this:\n\n" +
                    "1. Go to Settings → Accessibility\n" +
                    "2. Find 'Wire Auto Messenger'\n" +
                    "3. Toggle it OFF, wait 5 seconds\n" +
                    "4. Toggle it ON again\n" +
                    "5. Return to this app\n\n" +
                    "This usually fixes the issue.")
            .setPositiveButton("Open Settings") { _, _ ->
                openAccessibilityServiceSettings()
            }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }

    private fun loadSavedMessage() {
        val savedMessage = prefs.getString("saved_message", "")
        if (!savedMessage.isNullOrEmpty()) {
            etMessage.setText(savedMessage)
            // Move cursor to top
            etMessage.setSelection(0)
        } else {
            // Ensure cursor starts at top for new messages
            etMessage.requestFocus()
            etMessage.setSelection(0)
        }
    }

    private fun saveMessage() {
        val message = etMessage.text?.toString() ?: ""
        prefs.edit().putString("saved_message", message).apply()
    }

    private fun setupListeners() {
        btnEnableAccessibility.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                showAccessibilityPermissionDialog()
            }
        }

        btnSendNow.setOnClickListener {
            sendMessagesNow()
        }

        switchSchedule.setOnCheckedChangeListener { _, isChecked ->
            handleScheduleToggle(isChecked)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Please enable 'Wire Auto Messenger' in the accessibility settings", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open accessibility settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilityServiceSettings() {
        try {
            // Try to open directly to our service settings
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            
            // Show helpful toast
            Toast.makeText(this, 
                "Look for 'Wire Auto Messenger' in the list and toggle it ON", 
                Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // Fallback to general accessibility settings
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "Could not open accessibility settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendMessagesNow() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, getString(R.string.accessibility_not_enabled), Toast.LENGTH_LONG).show()
            return
        }

        val message = etMessage.text?.toString()?.trim() ?: ""
        if (message.isEmpty()) {
            etMessage.error = getString(R.string.message_empty)
            Toast.makeText(this, getString(R.string.message_empty), Toast.LENGTH_SHORT).show()
            return
        }

        saveMessage()
        startMessageSending(message)
    }

    private fun startMessageSending(message: String) {
        // Save message for the service to use
        prefs.edit().putString("pending_message", message).apply()
        
        // Start the automation service
        val intent = Intent(this, WireAutomationService::class.java)
        intent.action = WireAutomationService.ACTION_SEND_MESSAGES
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        llProgress.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.sending_messages)
        tvStatus.visibility = View.VISIBLE
        btnSendNow.isEnabled = false
        switchSchedule.isEnabled = false
    }

    private fun handleScheduleToggle(isChecked: Boolean) {
        if (isChecked) {
            val message = etMessage.text?.toString()?.trim() ?: ""
            if (message.isEmpty()) {
                switchSchedule.isChecked = false
                etMessage.error = getString(R.string.message_empty)
                Toast.makeText(this, "Please enter a message first", Toast.LENGTH_SHORT).show()
                return
            }
            saveMessage()
            enableScheduledSending()
        } else {
            disableScheduledSending()
        }
    }

    private fun enableScheduledSending() {
        if (!isAccessibilityServiceEnabled()) {
            switchSchedule.isChecked = false
            Toast.makeText(this, getString(R.string.accessibility_not_enabled), Toast.LENGTH_LONG).show()
            return
        }

        // Schedule work every 3 days
        val workRequest = PeriodicWorkRequestBuilder<MessageSendingWorker>(
            3, TimeUnit.DAYS,
            1, TimeUnit.HOURS // Flex interval
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "wire_message_schedule",
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )

        prefs.edit().putLong("schedule_start_time", System.currentTimeMillis()).apply()
        prefs.edit().putBoolean("schedule_enabled", true).apply()
        updateScheduleStatus()
        Toast.makeText(this, getString(R.string.schedule_enabled), Toast.LENGTH_SHORT).show()
    }

    private fun disableScheduledSending() {
        WorkManager.getInstance(this).cancelUniqueWork("wire_message_schedule")
        prefs.edit().putBoolean("schedule_enabled", false).apply()
        updateScheduleStatus()
        Toast.makeText(this, getString(R.string.schedule_disabled), Toast.LENGTH_SHORT).show()
    }

    private fun updateScheduleStatus() {
        val isEnabled = prefs.getBoolean("schedule_enabled", false)
        switchSchedule.isChecked = isEnabled
        
        if (isEnabled) {
            // Calculate next send time (3 days from schedule start or last send)
            val scheduleStartTime = prefs.getLong("schedule_start_time", System.currentTimeMillis())
            val lastSendTime = prefs.getLong("last_send_time", scheduleStartTime)
            val nextSendTime = if (lastSendTime > scheduleStartTime) {
                lastSendTime + (3 * 24 * 60 * 60 * 1000L)
            } else {
                scheduleStartTime + (3 * 24 * 60 * 60 * 1000L)
            }
            val nextSendDate = java.text.SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(nextSendTime))
            tvNextSend.text = "Next send: $nextSendDate"
            tvNextSend.visibility = View.VISIBLE
        } else {
            tvNextSend.visibility = View.GONE
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        
        // Check by package name
        var isEnabled = enabledServices.any { service ->
            service.resolveInfo.serviceInfo.packageName == packageName
        }
        
        // Also check by service name for better compatibility
        if (!isEnabled) {
            isEnabled = enabledServices.any { service ->
                service.resolveInfo.serviceInfo.name == "com.wireautomessenger.service.WireAutomationService" ||
                service.resolveInfo.serviceInfo.name.contains("WireAutomationService", ignoreCase = true)
            }
        }
        
        return isEnabled
    }

    override fun onResume() {
        super.onResume()
        checkAccessibilityService()
        updateScheduleStatus()
        
        // Check if sending completed
        val sendingComplete = prefs.getBoolean("sending_complete", false)
        if (sendingComplete) {
            llProgress.visibility = View.GONE
            tvStatus.text = getString(R.string.messages_sent)
            tvStatus.visibility = View.VISIBLE
            btnSendNow.isEnabled = true
            switchSchedule.isEnabled = true
            prefs.edit().putBoolean("sending_complete", false).apply()
        }
    }
}
