package com.wireautomessenger

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.wireautomessenger.api.WireApiManager
import com.wireautomessenger.service.WireAutomationService
import com.wireautomessenger.work.MessageSendingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    
    private val messageBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WireAutomationService.ACTION_PROGRESS_UPDATE -> {
                    val progressText = intent.getStringExtra(WireAutomationService.EXTRA_PROGRESS_TEXT) ?: ""
                    val contactsSent = intent.getIntExtra(WireAutomationService.EXTRA_CONTACTS_SENT, 0)
                    updateProgress(progressText, contactsSent)
                }
                WireAutomationService.ACTION_COMPLETED -> {
                    val contactsSent = intent.getIntExtra(WireAutomationService.EXTRA_CONTACTS_SENT, 0)
                    onSendingCompleted(contactsSent)
                }
                WireAutomationService.ACTION_ERROR -> {
                    val errorMessage = intent.getStringExtra(WireAutomationService.EXTRA_ERROR_MESSAGE) ?: "Unknown error"
                    onSendingError(errorMessage)
                }
            }
        }
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
        registerBroadcastReceiver()
    }
    
    override fun onResume() {
        super.onResume()
        checkAccessibilityService()
        checkWireAppInstalled()
        updateScheduleStatus()
        
        // Check if sending was completed while app was in background
        val sendingComplete = prefs.getBoolean("sending_complete", false)
        if (sendingComplete && llProgress.visibility == View.VISIBLE) {
            // If progress is showing but sending is complete, reset UI
            val contactsSent = prefs.getInt("last_contacts_sent", 0)
            if (contactsSent > 0) {
                onSendingCompleted(contactsSent)
            } else {
                resetSendingUI()
            }
        }
    }
    
    private fun checkWireAppInstalled() {
        // Check Wire app installation status
        val isInstalled = isWireAppInstalled()
        
        // Only show warning once per session to avoid annoying the user
        val key = "wire_check_shown_${System.currentTimeMillis() / 1000 / 60}" // Once per minute
        if (!prefs.getBoolean(key, false)) {
            if (!isInstalled) {
                prefs.edit().putBoolean(key, true).apply()
                // Show a subtle warning in the UI
                Toast.makeText(this, 
                    "⚠ Wire app not detected. Please ensure Wire is installed from Play Store.", 
                    Toast.LENGTH_SHORT).show()
            } else {
                // Log success for debugging
                android.util.Log.d("WireCheck", "Wire app detected successfully")
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Don't unregister here, keep listening in background
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterBroadcastReceiver()
    }
    
    private fun registerBroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction(WireAutomationService.ACTION_PROGRESS_UPDATE)
            addAction(WireAutomationService.ACTION_COMPLETED)
            addAction(WireAutomationService.ACTION_ERROR)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(messageBroadcastReceiver, filter)
    }
    
    private fun unregisterBroadcastReceiver() {
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(messageBroadcastReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
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
        MaterialAlertDialogBuilder(this, R.style.Theme_WireAutoMessenger_Dialog)
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
        MaterialAlertDialogBuilder(this, R.style.Theme_WireAutoMessenger_Dialog)
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
        
        MaterialAlertDialogBuilder(this, R.style.Theme_WireAutoMessenger_Dialog)
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
        val message = etMessage.text?.toString()?.trim() ?: ""
        if (message.isEmpty()) {
            etMessage.error = getString(R.string.message_empty)
            Toast.makeText(this, getString(R.string.message_empty), Toast.LENGTH_SHORT).show()
            return
        }

        saveMessage()
        
        // Check if API credentials are configured
        val appId = prefs.getString("wire_app_id", "")?.trim()
        val apiToken = prefs.getString("wire_api_token", "")?.trim()
        
        if (!appId.isNullOrEmpty() && !apiToken.isNullOrEmpty()) {
            // Use API mode
            sendMessagesViaApi(appId, apiToken, message)
        } else {
            // Use Accessibility Service mode (fallback)
            if (!isAccessibilityServiceEnabled()) {
                showApiConfigurationDialog()
                return
            }
            
            // Check if Wire app is installed
            if (!isWireAppInstalled()) {
                showWireNotInstalledDialog()
                return
            }
            
            startMessageSending(message)
        }
    }
    
    private fun sendMessagesViaApi(appId: String, apiToken: String, message: String) {
        llProgress.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        btnSendNow.isEnabled = false
        switchSchedule.isEnabled = false
        
        tvStatus.text = "Connecting to Wire API..."
        tvStatus.visibility = View.VISIBLE
        
        val apiManager = WireApiManager(this)
        val scope = CoroutineScope(Dispatchers.Main)
        
        scope.launch {
            try {
                // Step 1: Get team members
                runOnUiThread {
                    tvStatus.text = "Getting team members..."
                }
                sendProgressBroadcast("Getting team members...", 0)
                
                val membersResult = apiManager.getTeamMembers(appId, apiToken)
                
                if (membersResult.isFailure) {
                    val error = membersResult.exceptionOrNull()?.message ?: "Unknown error"
                    onSendingError("Failed to get team members: $error")
                    return@launch
                }
                
                val userIds = membersResult.getOrNull() ?: emptyList()
                
                if (userIds.isEmpty()) {
                    onSendingError("No team members found. Please ensure your Wire team has members.")
                    return@launch
                }
                
                // Step 2: Send broadcast message
                runOnUiThread {
                    tvStatus.text = "Sending messages to ${userIds.size} team members..."
                }
                sendProgressBroadcast("Sending messages to ${userIds.size} team members...", 0)
                
                val broadcastResult = apiManager.sendBroadcastMessage(
                    appId = appId,
                    apiToken = apiToken,
                    userIds = userIds,
                    message = message,
                    onProgress = { current, total ->
                        runOnUiThread {
                            tvStatus.text = "Sending to $current/$total team members..."
                            sendProgressBroadcast("Sending to $current/$total team members...", current - 1)
                        }
                    }
                )
                
                if (broadcastResult.isSuccess) {
                    val result = broadcastResult.getOrNull()!!
                    onSendingCompleted(result.success, result.total)
                } else {
                    val error = broadcastResult.exceptionOrNull()?.message ?: "Unknown error"
                    onSendingError("Failed to send messages: $error")
                }
                
            } catch (e: Exception) {
                android.util.Log.e("WireAPI", "Error in API send", e)
                onSendingError("Error: ${e.message ?: "Unknown error"}")
            }
        }
    }
    
    private fun showApiConfigurationDialog() {
        MaterialAlertDialogBuilder(this, R.style.Theme_WireAutoMessenger_Dialog)
            .setTitle("Wire API Configuration")
            .setMessage("You can use Wire API (Team accounts) or Accessibility Service (Personal accounts).\n\n" +
                    "For Team accounts:\n" +
                    "1. Get App ID and Token from Wire Team Settings → Apps\n" +
                    "2. Configure them in Settings\n\n" +
                    "For Personal accounts:\n" +
                    "Enable Accessibility Service to continue.")
            .setPositiveButton("Configure API") { _, _ ->
                showApiSettingsDialog()
            }
            .setNegativeButton("Use Accessibility") { _, _ ->
                if (!isAccessibilityServiceEnabled()) {
                    showAccessibilityPermissionDialog()
                } else {
                    sendMessagesNow()
                }
            }
            .setNeutralButton("Cancel", null)
            .show()
    }
    
    private fun showApiSettingsDialog() {
        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_2, null)
        // Create a custom dialog with input fields
        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }
        
        val etAppId = TextInputEditText(this).apply {
            hint = "App ID"
            setText(prefs.getString("wire_app_id", ""))
        }
        
        val etApiToken = TextInputEditText(this).apply {
            hint = "API Token"
            setText(prefs.getString("wire_api_token", ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        
        inputLayout.addView(etAppId)
        inputLayout.addView(etApiToken)
        
        MaterialAlertDialogBuilder(this, R.style.Theme_WireAutoMessenger_Dialog)
            .setTitle("Wire API Configuration")
            .setMessage("Enter your Wire App credentials:\n\n" +
                    "1. Go to Wire Team Settings → Apps\n" +
                    "2. Create a new app or use existing\n" +
                    "3. Copy App ID and API Token\n" +
                    "4. Paste them below")
            .setView(inputLayout)
            .setPositiveButton("Save") { _, _ ->
                val appId = etAppId.text?.toString()?.trim() ?: ""
                val apiToken = etApiToken.text?.toString()?.trim() ?: ""
                
                if (appId.isEmpty() || apiToken.isEmpty()) {
                    Toast.makeText(this, "Please enter both App ID and API Token", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit()
                        .putString("wire_app_id", appId)
                        .putString("wire_api_token", apiToken)
                        .apply()
                    Toast.makeText(this, "API credentials saved!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun isWireAppInstalled(): Boolean {
        return try {
            val primaryPackage = "com.wire"
            val alternativePackages = listOf("ch.wire", "wire")
            val allPackages = listOf(primaryPackage) + alternativePackages
            
            android.util.Log.i("WireCheck", "Starting Wire app detection...")
            android.util.Log.i("WireCheck", "Checking packages: $allPackages")
            
            // Method 1: Try to get launch intent (most reliable - works if app is enabled)
            for (pkg in allPackages) {
                try {
                    val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        android.util.Log.i("WireCheck", "✓ Wire app FOUND via launch intent: $pkg")
                        return true
                    } else {
                        android.util.Log.d("WireCheck", "Launch intent is null for: $pkg")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("WireCheck", "Launch intent check failed for $pkg: ${e.message}")
                }
            }
            
            // Method 2: Try to get package info (works even if app is disabled)
            for (pkg in allPackages) {
                try {
                    val packageInfo = packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                    if (packageInfo != null) {
                        android.util.Log.i("WireCheck", "✓ Wire app FOUND via package info: $pkg")
                        android.util.Log.d("WireCheck", "Package version: ${packageInfo.versionName}, versionCode: ${packageInfo.versionCode}")
                        return true
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    // Package not found, continue
                    android.util.Log.d("WireCheck", "Package not found (NameNotFoundException): $pkg")
                } catch (e: Exception) {
                    android.util.Log.w("WireCheck", "Package info check failed for $pkg: ${e.message}")
                }
            }
            
            // Method 3: Check installed packages list (most comprehensive)
            try {
                android.util.Log.d("WireCheck", "Checking installed packages list...")
                val installedPackages = packageManager.getInstalledPackages(PackageManager.GET_ACTIVITIES)
                android.util.Log.d("WireCheck", "Total installed packages: ${installedPackages.size}")
                
                // Log first few package names for debugging
                if (installedPackages.isNotEmpty()) {
                    val samplePackages = installedPackages.take(10).map { it.packageName }
                    android.util.Log.d("WireCheck", "Sample packages: $samplePackages")
                }
                
                for (pkg in allPackages) {
                    val found = installedPackages.any { it.packageName == pkg }
                    if (found) {
                        android.util.Log.i("WireCheck", "✓ Wire app FOUND in installed packages list: $pkg")
                        return true
                    } else {
                        android.util.Log.d("WireCheck", "Package $pkg not in installed packages list")
                    }
                }
            } catch (e: SecurityException) {
                android.util.Log.e("WireCheck", "SecurityException: Missing <queries> declaration in manifest? ${e.message}")
            } catch (e: Exception) {
                android.util.Log.e("WireCheck", "Error getting installed packages: ${e.message}", e)
            }
            
            // Method 4: Try with PackageInfoFlags (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                for (pkg in allPackages) {
                    try {
                        val packageInfo = packageManager.getPackageInfo(
                            pkg, 
                            PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong())
                        )
                        if (packageInfo != null) {
                            android.util.Log.i("WireCheck", "✓ Wire app FOUND via PackageInfoFlags: $pkg")
                            return true
                        }
                    } catch (e: Exception) {
                        android.util.Log.d("WireCheck", "PackageInfoFlags check failed for $pkg: ${e.message}")
                    }
                }
            }
            
            android.util.Log.w("WireCheck", "✗ Wire app NOT FOUND using any method")
            android.util.Log.w("WireCheck", "Please ensure:")
            android.util.Log.w("WireCheck", "1. Wire app is installed from Play Store")
            android.util.Log.w("WireCheck", "2. <queries> declaration is in AndroidManifest.xml")
            android.util.Log.w("WireCheck", "3. App has proper permissions")
            
            false
        } catch (e: Exception) {
            android.util.Log.e("WireCheck", "Critical error checking Wire app: ${e.message}", e)
            e.printStackTrace()
            false
        }
    }
    
    private fun showWireNotInstalledDialog() {
        MaterialAlertDialogBuilder(this, R.style.Theme_WireAutoMessenger_Dialog)
            .setTitle("Wire App Not Found")
            .setMessage("Wire app is not installed on your device.\n\n" +
                    "To use this app:\n\n" +
                    "1. Install Wire app from Google Play Store\n" +
                    "2. Open Wire and create/login to your account\n" +
                    "3. Add contacts in Wire\n" +
                    "4. Return to this app and try again\n\n" +
                    "The app requires Wire to be installed and logged in.")
            .setPositiveButton("Open Play Store") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = android.net.Uri.parse("market://details?id=com.wire")
                        setPackage("com.android.vending")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to web browser
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.wire")
                        }
                        startActivity(intent)
                    } catch (e2: Exception) {
                        Toast.makeText(this, "Please install Wire app from Google Play Store", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(true)
            .show()
    }

    private fun startMessageSending(message: String) {
        // Save message for the service to use
        prefs.edit().putString("pending_message", message).apply()
        
        // Reset completion flag
        prefs.edit().putBoolean("sending_complete", false).putInt("last_contacts_sent", 0).apply()
        
        // Start the automation service
        val intent = Intent(this, WireAutomationService::class.java)
        intent.action = WireAutomationService.ACTION_SEND_MESSAGES
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        llProgress.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.sending_messages)
        tvStatus.visibility = View.VISIBLE
        btnSendNow.isEnabled = false
        switchSchedule.isEnabled = false
    }
    
    private fun updateProgress(progressText: String, contactsSent: Int) {
        runOnUiThread {
            tvStatus.text = progressText
            if (contactsSent > 0) {
                tvStatus.text = "$progressText ($contactsSent sent)"
            }
        }
    }
    
    private fun onSendingCompleted(contactsSent: Int, totalContacts: Int = contactsSent) {
        runOnUiThread {
            // Save completion status
            prefs.edit()
                .putBoolean("sending_complete", true)
                .putInt("last_contacts_sent", contactsSent)
                .apply()
            
            // Update UI
            progressBar.visibility = View.GONE
            val statusText = if (totalContacts > contactsSent) {
                "✓ Completed! Sent to $contactsSent out of $totalContacts contacts"
            } else {
                "✓ Completed! Sent to $contactsSent contacts"
            }
            tvStatus.text = statusText
            tvStatus.setTextColor(getColor(R.color.on_success))
            
            // Show success message
            val toastMessage = if (totalContacts > contactsSent) {
                "Messages sent to $contactsSent out of $totalContacts contacts successfully!"
            } else {
                "Messages sent to $contactsSent contacts successfully!"
            }
            Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show()
            
            // Reset UI after 3 seconds
            btnSendNow.postDelayed({
                resetSendingUI()
            }, 3000)
        }
    }
    
    private fun onSendingError(errorMessage: String) {
        runOnUiThread {
            // Update UI
            llProgress.visibility = View.GONE
            progressBar.visibility = View.GONE
            btnSendNow.isEnabled = true
            switchSchedule.isEnabled = true
            
            // Show short status
            tvStatus.text = "✗ Error occurred - see details"
            tvStatus.setTextColor(getColor(R.color.on_error))
            tvStatus.visibility = View.VISIBLE
            
            // Show detailed error dialog with scrollable text
            showDetailedErrorDialog(errorMessage)
        }
    }
    
    private fun showDetailedErrorDialog(errorMessage: String) {
        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(32, 16, 32, 16)
        }
        
        val textView = TextView(this).apply {
            text = errorMessage
            textSize = 14f
            setTextColor(getColor(R.color.on_surface))
            setPadding(0, 0, 0, 16)
        }
        
        scrollView.addView(textView)
        
        MaterialAlertDialogBuilder(this, R.style.Theme_WireAutoMessenger_Dialog)
            .setTitle("⚠️ Error Details")
            .setView(scrollView)
            .setPositiveButton("OK") { _, _ ->
                resetSendingUI()
            }
            .setNegativeButton("Copy Info") { _, _ ->
                // Copy error message to clipboard
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Error Details", errorMessage)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Error details copied to clipboard", Toast.LENGTH_SHORT).show()
                resetSendingUI()
            }
            .setCancelable(true)
            .setOnCancelListener {
                resetSendingUI()
            }
            .show()
    }
    
    private fun resetSendingUI() {
        runOnUiThread {
            llProgress.visibility = View.GONE
            progressBar.visibility = View.GONE
            tvStatus.visibility = View.GONE
            btnSendNow.isEnabled = true
            switchSchedule.isEnabled = true
            tvStatus.setTextColor(getColor(R.color.on_surface))
        }
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

    private fun sendProgressBroadcast(text: String, contactsSent: Int = 0) {
        try {
            val intent = Intent(WireAutomationService.ACTION_PROGRESS_UPDATE).apply {
                putExtra(WireAutomationService.EXTRA_PROGRESS_TEXT, text)
                putExtra(WireAutomationService.EXTRA_CONTACTS_SENT, contactsSent)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error sending progress broadcast", e)
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

}
