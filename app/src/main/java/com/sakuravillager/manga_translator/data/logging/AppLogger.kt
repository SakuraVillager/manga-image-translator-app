package com.sakuravillager.manga_translator.data.logging

import android.util.Log

object AppLogger {
    private const val MAX_ENTRIES = 500
    private val buffer = ArrayDeque<LogEntry>()

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
    }

    @Synchronized
    fun getLogs(): List<LogEntry> = buffer.toList()

    @Synchronized
    fun clear() = buffer.clear()

    @Synchronized
    fun exportAsText(): String = buffer.joinToString("\n") { it.formatted() }
}
