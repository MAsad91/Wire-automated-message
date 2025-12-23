package com.wireautomessenger.work

import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.wireautomessenger.service.WireAutomationService

class MessageSendingWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            // Start the automation service to send messages
            val intent = Intent(applicationContext, WireAutomationService::class.java)
            intent.action = WireAutomationService.ACTION_SEND_MESSAGES
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

