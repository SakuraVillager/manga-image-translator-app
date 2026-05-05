package com.sakuravillager.manga_translator.data.logging

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

object AppLogger {
    private const val MAX_ENTRIES = 500
    private const val LOG_DIR = "logs"
    private val persistedLogPattern = Pattern.compile("^\\[(.+?)] \\[(DEBUG|INFO|WARN|ERROR)] (.+?): (.*)$", Pattern.DOTALL)
    private val buffer = ArrayDeque<LogEntry>()
    private var logDir: File? = null
    private var crashHandlerInstalled = false
    private var previousCrashHandler: Thread.UncaughtExceptionHandler? = null

    /** Initialize file logging. Call from Application.onCreate(). */
    fun init(context: Context) {
        logDir = File(context.filesDir, LOG_DIR)
        logDir?.mkdirs()

        if (!crashHandlerInstalled) {
            previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                e("UncaughtException", "Thread ${thread.name} crashed", throwable)
                previousCrashHandler?.uncaughtException(thread, throwable)
            }
            crashHandlerInstalled = true
            i("AppLogger", "Uncaught exception handler installed")
        }
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

    fun getPersistedLogs(maxLines: Int = 5000): List<LogEntry> {
        return try {
            readPersistedLogBlocks(maxLines).mapNotNull { block ->
                parsePersistedEntry(block) ?: LogEntry(
                    timestamp = System.currentTimeMillis(),
                    level = LogLevel.INFO,
                    tag = "AppLogger",
                    message = block
                )
            }
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to read persisted logs", e)
            emptyList()
        }
    }

    /**
     * Read ALL persisted log files in the directory, newest first.
     * Unlike the old getPersistedLogs() which only reads today's file,
     * this reads every app.*.log file → handles crashes that span multiple days.
     */
    fun getAllPersistedLogs(maxLines: Int = 5000): String {
        return readPersistedLogText(maxLines)
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
        val fileContent = readPersistedLogText()
        return if (fileContent.isNotEmpty()) fileContent else buffer.joinToString("\n") { it.formatted() }
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        clearPersistedLogs()
    }

    private fun clearPersistedLogs() {
        val dir = logDir
        if (dir == null) {
            Log.e("AppLogger", "logDir is null — AppLogger.init() was never called")
            return
        }
        try {
            val logFiles = dir.listFiles { file -> file.name.startsWith("app.") && file.name.endsWith(".log") }
                ?: return
            for (file in logFiles) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to clear persisted logs", e)
        }
    }

    private fun readPersistedLogText(maxLines: Int = 5000): String {
        val dir = logDir ?: return ""
        val logFiles = dir.listFiles { file -> file.name.startsWith("app.") && file.name.endsWith(".log") }
            ?: emptyArray()
        if (logFiles.isEmpty()) return ""

        val sorted = logFiles.sortedBy { it.name }
        val lines = ArrayDeque<String>()

        return try {
            for (file in sorted) {
                BufferedReader(InputStreamReader(file.inputStream())).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        lines.addLast(line)
                        if (lines.size > maxLines) lines.removeFirst()
                        line = reader.readLine()
                    }
                }
            }
            lines.joinToString("\n")
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to read persisted log text", e)
            ""
        }
    }

    private fun readPersistedLogBlocks(maxLines: Int = 5000): List<String> {
        val text = readPersistedLogText(maxLines)
        if (text.isEmpty()) return emptyList()

        val blocks = ArrayDeque<String>()
        val current = StringBuilder()

        for (line in text.lineSequence()) {
            if (persistedLogPattern.matcher(line).matches()) {
                if (current.isNotEmpty()) {
                    blocks.addLast(current.toString())
                    if (blocks.size > maxLines) blocks.removeFirst()
                    current.setLength(0)
                }
                current.append(line)
            } else if (current.isNotEmpty()) {
                current.append('\n').append(line)
            } else {
                current.append(line)
            }
        }

        if (current.isNotEmpty()) {
            blocks.addLast(current.toString())
        }

        return blocks.toList()
    }

    private fun parsePersistedEntry(line: String): LogEntry? {
        val matcher = persistedLogPattern.matcher(line)
        if (!matcher.matches()) return null

        val timestampText = matcher.group(1) ?: return null
        val levelText = matcher.group(2) ?: return null
        val tag = matcher.group(3) ?: return null
        val message = matcher.group(4) ?: return null

        val timestamp = try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
            sdf.parse(timestampText)?.time ?: return null
        } catch (_: Exception) {
            return null
        }

        val level = try {
            LogLevel.valueOf(levelText)
        } catch (_: Exception) {
            return null
        }

        return LogEntry(timestamp = timestamp, level = level, tag = tag, message = message)
    }
}
