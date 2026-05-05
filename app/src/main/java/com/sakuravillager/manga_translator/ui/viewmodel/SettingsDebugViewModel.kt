package com.sakuravillager.manga_translator.ui.viewmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import com.sakuravillager.manga_translator.data.logging.AppLogger
import com.sakuravillager.manga_translator.data.logging.LogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class SettingsDebugViewModel : ViewModel() {

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun refreshLogs() {
        _logs.value = AppLogger.getPersistedLogs()
    }

    fun clearLogs() {
        AppLogger.clear()
        refreshLogs()
    }

    fun copyLogsToClipboard(context: Context): Boolean {
        val text = AppLogger.exportAsText()
        if (text.isEmpty()) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("App Logs", text)
        clipboard.setPrimaryClip(clip)
        return true
    }

    fun shareLogs(context: Context): Intent {
        val text = AppLogger.exportAsText()
        val logDir = File(context.cacheDir, "logs")
        logDir.mkdirs()
        val file = File(logDir, "manga-translator-logs.txt")
        file.writeText(text)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, "Share Logs") }
    }
}
