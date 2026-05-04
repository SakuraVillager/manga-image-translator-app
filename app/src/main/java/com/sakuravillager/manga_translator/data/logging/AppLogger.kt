package com.sakuravillager.manga_translator.data.logging

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
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
        val dir = logDir
        if (dir == null) {
            Log.e("AppLogger", "logDir is null — AppLogger.init() was never called")
            return
        }
        val datePart = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val file = File(dir, "app.$datePart.log")
        try {
            val bytes = (entry.formatted() + "\n").toByteArray(Charsets.UTF_8)
            FileOutputStream(file, true).use { fos ->
                fos.write(bytes)
                fos.flush()                       // flush Java buffer → OS
                @Suppress("DEPRECATION")
                fos.fd.sync()                     // fsync → physical storage
            }
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to persist log: ${e.message}", e)
        }
    }

    @Synchronized
    fun getLogs(): List<LogEntry> = buffer.toList()

    /**
     * Read ALL persisted log files in the directory, newest first.
     * Unlike the old getPersistedLogs() which only reads today's file,
     * this reads every app.*.log file → handles crashes that span multiple days.
     */
    fun getAllPersistedLogs(maxLines: Int = 5000): String {
        val dir = logDir ?: return ""
        val logFiles = dir.listFiles { file -> file.name.startsWith("app.") && file.name.endsWith(".log") }
            ?: emptyArray()
        if (logFiles.isEmpty()) return ""
        // Newest first
        val sorted = logFiles.sortedByDescending { it.name }
        return try {
            val lines = mutableListOf<String>()
            for (file in sorted) {
                BufferedReader(InputStreamReader(file.inputStream())).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        lines.add(line)
                        if (lines.size > maxLines) lines.removeFirst()
                        line = reader.readLine()
                    }
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
     * Full export: all persisted log files + current in-memory buffer.
     * Reads EVERY app.*.log file in the directory — survives crashes
     * and day transitions where old getPersistedLogs() would lose data.
     */
    @Synchronized
    fun exportAsText(): String {
        val fileContent = getAllPersistedLogs()
        val memory = buffer.joinToString("\n") { it.formatted() }
        return when {
            fileContent.isEmpty() -> memory
            memory.isEmpty() -> fileContent
            else -> "$fileContent\n$memory"
        }
    }

    @Synchronized
    fun clear() = buffer.clear()
}
