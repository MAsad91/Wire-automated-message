package com.wireautomessenger.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.wireautomessenger.work.MessageSendingWorker
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            // Restore scheduled sending if it was enabled
            val prefs: SharedPreferences = 
                context.getSharedPreferences("WireAutoMessenger", Context.MODE_PRIVATE)
            
            val scheduleEnabled = prefs.getBoolean("schedule_enabled", false)
            
            if (scheduleEnabled) {
                // Re-schedule the work
                val workRequest = PeriodicWorkRequestBuilder<MessageSendingWorker>(
                    3, TimeUnit.DAYS,
                    1, TimeUnit.HOURS
                ).build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "wire_message_schedule",
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
            }
        }
    }
}

