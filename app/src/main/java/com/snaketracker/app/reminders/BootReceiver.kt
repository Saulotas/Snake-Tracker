package com.snaketracker.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.snaketracker.app.logging.FileLogger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            FileLogger.i("BootReceiver", "BOOT_COMPLETED received")
            launchGoAsync(context) { ReminderArming.refresh(it, source = "BootReceiver") }
        }
    }
}
