package com.sakuravillager.manga_translator.data.logging

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object AppLogger {
    private const val MAX_ENTRIES = 500
    private const val LOG_DIR = "logs"
    private val buffer = ArrayDeque<LogEntry>()
    private var logDir: File? = null

    /** Initialize file logging. Call from Application.onCreate(). */
    fun init(context: Context) {
        logDir = File(context.filesDir, LOG_DIR)
        logDir?.mkdirs()
    }

    @Synchronized
    fun d(tag: String, message: String) {
        log(LogLevel.DEBUG, tag, message)
    }

    @Synchronized
    fun i(tag: String, message: String) {
        log(LogLevel.INFO, tag, message)
    }

    @Synchronized
    fun w(tag: String, message: String) {
        log(LogLevel.WARN, tag, message)
    }

    @Synchronized
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null) "$message\n${throwable.stackTraceToString()}" else message
        log(LogLevel.ERROR, tag, msg)
    }

    private fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(level = level, tag = tag, message = message)
        buffer.addLast(entry)
        if (buffer.size > MAX_ENTRIES) {
            buffer.removeFirst()
        }

        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }

        // Persist to file (best-effort, never crash)
        persist(entry)
    }

    private fun persist(entry: LogEntry) {
        val dir = logDir ?: return
        val datePart = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val file = File(dir, "app.$datePart.log")
        try {
            // Use RandomAccessFile in "rwd" mode for crash-safe writes.
            // "rwd" = synchronous writes: each write() is committed to disk immediately.
            // Without this, OS-buffered writes can be lost if the process crashes.
            RandomAccessFile(file, "rwd").use { raf ->
                raf.seek(file.length())  // append
                raf.write((entry.formatted() + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (_: Exception) {
            // silent — logging must never crash the app
        }
    }

    @Synchronized
    fun getLogs(): List<LogEntry> = buffer.toList()

    /** Read today's persisted log file. Returns the last N lines. */
    fun getPersistedLogs(maxLines: Int = 2000): String {
        val datePart = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val dir = logDir ?: return ""
        val file = File(dir, "app.$datePart.log")
        if (!file.exists()) return ""
        return try {
            val lines = mutableListOf<String>()
            BufferedReader(InputStreamReader(file.inputStream())).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    lines.add(line)
                    if (lines.size > maxLines) lines.removeFirst()
                    line = reader.readLine()
                }
            }
            lines.joinToString("\n")
        } catch (_: Exception) { "" }
    }

    /** Get the log directory file handle. */
    fun getLogDirectory(): File? = logDir

    @Synchronized
    fun getExportText(): String = buffer.joinToString("\n") { it.formatted() }

    /**
     * Full export: in-memory buffer + today's persisted file.
     * Survives crashes — file content is retained across process restarts.
     */
    @Synchronized
    fun exportAsText(): String {
        val persisted = getPersistedLogs()
        val memory = buffer.joinToString("\n") { it.formatted() }
        return when {
            persisted.isEmpty() -> memory
            memory.isEmpty() -> persisted
            else -> "$persisted\n$memory"
        }
    }

    @Synchronized
    fun clear() = buffer.clear()
}
