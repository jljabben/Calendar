package org.fossify.calendar.caldav

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Writes sync failures with full stack traces to a file in the app's
 * private files directory, so crashes can be diagnosed from the device
 * even without adb: the log is read out via the new "Send sync logs"
 * settings entry.
 */
object SyncLogger {

    private const val TAG = "DirectCalDavSync"
    private const val LOG_FILE = "direct_caldav_sync.log"
    private const val MAX_LOG_BYTES = 256 * 1024

    @Suppress("TooGenericExceptionCaught")
    fun logError(context: Context, message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        try {
            val entry = buildString {
                append("\n==== ")
                append(System.currentTimeMillis())
                append(" ====\n")
                append(message)
                append('\n')
                if (throwable != null) {
                    append(throwable.toString())
                    append('\n')
                    val writer = StringWriter()
                    throwable.printStackTrace(PrintWriter(writer))
                    append(writer.toString())
                }
            }
            val file = logFile(context)
            if (file.length() > MAX_LOG_BYTES) {
                file.delete()
            }
            file.appendText(entry)
        } catch (t: Throwable) {
            Log.w(TAG, "Writing the sync log itself failed", t)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun readLog(context: Context): String = try {
        logFile(context).readText()
    } catch (t: Throwable) {
        Log.w(TAG, "Reading the sync log failed", t)
        "No sync log available."
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun clearLog(context: Context) {
        try {
            logFile(context).delete()
        } catch (t: Throwable) {
            Log.w(TAG, "Clearing the sync log failed", t)
        }
    }

    private fun logFile(context: Context): File =
        File(context.filesDir, LOG_FILE)
}
