package com.sakuravillager.manga_translator

import org.junit.Test
import java.io.File
import org.junit.Assert.*
import java.io.FileOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader

class LoggerTest {
    @Test
    fun testLogs() {
        val dir = File("test_logs").apply { mkdirs() }
        val f = File(dir, "app.2024.log").apply { writeText("Hello") }
        try {
            val sorted = arrayOf(f)
            val lines = mutableListOf<String>()
            for (file in sorted) {
                BufferedReader(InputStreamReader(file.inputStream())).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        lines.add(line)
                        if (lines.size > 5) lines.removeFirst()
                        line = reader.readLine()
                    }
                }
            }
            println(lines.joinToString("\n"))
        }catch(e: Exception) {
            e.printStackTrace()
        }
    }
}