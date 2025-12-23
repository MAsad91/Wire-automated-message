package com.wireautomessenger

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkAccessibilityService()
        loadSavedMessage()
        setupListeners()
        updateScheduleStatus()
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
        
        val isEnabled = enabledServices.any { service ->
            service.resolveInfo.serviceInfo.packageName == packageName
        }

        updateAccessibilityStatus(isEnabled)
    }

    private fun updateAccessibilityStatus(isEnabled: Boolean) {
        if (isEnabled) {
            tvAccessibilityStatus.text = "✓ Enabled"
            tvAccessibilityStatus.setTextColor(getColor(R.color.on_success))
            tvAccessibilityStatus.setBackgroundResource(R.drawable.status_badge_success)
            btnEnableAccessibility.text = "Service Enabled"
            btnEnableAccessibility.isEnabled = false
        } else {
            tvAccessibilityStatus.text = "✗ Not Enabled"
            tvAccessibilityStatus.setTextColor(getColor(R.color.on_error))
            tvAccessibilityStatus.setBackgroundResource(R.drawable.status_badge_error)
            btnEnableAccessibility.text = "Enable Accessibility Service"
            btnEnableAccessibility.isEnabled = true
        }
    }

    private fun loadSavedMessage() {
        val savedMessage = prefs.getString("saved_message", "")
        if (!savedMessage.isNullOrEmpty()) {
            etMessage.setText(savedMessage)
        }
    }

    private fun saveMessage() {
        val message = etMessage.text?.toString() ?: ""
        prefs.edit().putString("saved_message", message).apply()
    }

    private fun setupListeners() {
        btnEnableAccessibility.setOnClickListener {
            openAccessibilitySettings()
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
        
        return enabledServices.any { service ->
            service.resolveInfo.serviceInfo.packageName == packageName
        }
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
