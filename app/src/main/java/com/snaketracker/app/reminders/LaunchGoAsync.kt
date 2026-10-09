package com.snaketracker.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import com.snaketracker.app.logging.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The one goAsync scaffold for the reminder receivers: goes async synchronously
 * on the receiver's main thread, runs [block] off-thread, always finishes the
 * receiver's result, and catches failures so a background error (e.g. a DB
 * error while re-arming) is logged instead of crashing the process.
 */
internal fun BroadcastReceiver.launchGoAsync(
    context: Context,
    block: suspend (Context) -> Unit
) {
    val tag = javaClass.simpleName
    val pendingResult = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
        try {
            block(context.applicationContext)
        } catch (e: Exception) {
            Log.e(tag, "Receiver work failed", e)
            FileLogger.e(tag, "Receiver work failed", e)
        } finally {
            pendingResult.finish()
        }
    }
}
