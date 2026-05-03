package com.sakuravillager.manga_translator.translation.dict

import android.util.Log
import java.io.File

object DictionaryLoader {
    private const val TAG = "DictionaryLoader"

    data class DictEntry(
        val pattern: Regex,
        val replacement: String,
        val lineNumber: Int,
    )

    fun load(path: String): List<DictEntry> {
        val entries = mutableListOf<DictEntry>()
        val lines = File(path).readLines()

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            // Skip empty lines and comments
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEachIndexed

            val lineNumber = index + 1 // 1-based line numbers

            // Split into pattern and optional replacement
            val spaceIndex = line.indexOf(' ')
            if (spaceIndex > 0) {
                val pattern = line.substring(0, spaceIndex)
                val replacement = line.substring(spaceIndex + 1).trim()
                entries.add(DictEntry(Regex(pattern), replacement, lineNumber))
                Log.d(TAG, "Loaded rule #$lineNumber: /$pattern/ -> '$replacement'")
            } else {
                // pattern-only: delete matched content
                entries.add(DictEntry(Regex(line), "", lineNumber))
                Log.d(TAG, "Loaded rule #$lineNumber: /$line/ -> (delete)")
            }
        }

        Log.d(TAG, "Loaded ${entries.size} dictionary rules from $path")
        return entries
    }

    fun apply(text: String, dictionary: List<DictEntry>): String {
        var result = text
        for (entry in dictionary) {
            val before = result
            result = entry.pattern.replace(result, entry.replacement)
            if (result != before) {
                Log.d(TAG, "Applied rule #${entry.lineNumber}: '$before' -> '$result'")
            }
        }
        return result
    }
}
