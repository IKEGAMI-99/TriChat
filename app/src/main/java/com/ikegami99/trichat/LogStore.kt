package com.ikegami99.trichat

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogStore(private val context: Context) {
    private val file = File(context.filesDir, "trichat.log")
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    init {
        if (!file.exists() || file.length() == 0L) {
            file.parentFile?.mkdirs()
            file.writeText("TriChat log\nversion=${BuildConfig.VERSION_NAME}\ncreated=${fmt.format(Date())}\n")
        }
    }

    @Synchronized fun i(tag: String, message: String) {
        runCatching {
            file.appendText("${fmt.format(Date())} [$tag] $message\n")
            if (file.length() > 2_000_000L) {
                val tail = file.readText().takeLast(1_000_000)
                file.writeText("TriChat log (trimmed)\n$tail")
            }
        }
    }

    @Synchronized fun exportText(): String {
        if (!file.exists() || file.length() == 0L) {
            file.writeText("TriChat log\nversion=${BuildConfig.VERSION_NAME}\nexported=${fmt.format(Date())}\n(no prior events)\n")
        }
        val body = file.readText()
        return if (body.isBlank()) "TriChat log\nversion=${BuildConfig.VERSION_NAME}\n(no prior events)\n" else body
    }
}
