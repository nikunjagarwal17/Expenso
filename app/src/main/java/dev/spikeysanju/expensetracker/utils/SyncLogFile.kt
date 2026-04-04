package dev.spikeysanju.expensetracker.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SyncLogFile {
    private const val FILE_NAME = "sync.log"
    private const val MAX_LOG_SIZE_BYTES = 512 * 1024L
    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun append(context: Context, message: String) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                file.writeText("")
            }

            val line = "${tsFormat.format(Date())} | $message\n"
            file.appendText(line)
        }
    }

    fun path(context: Context): String {
        return File(context.filesDir, FILE_NAME).absolutePath
    }
}