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
            val lineNumber = index + 1 // 1-based line numbers
            val stripped = rawLine.trim()
            // Skip empty lines and comments
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("//")) return@forEachIndexed

            val line = stripped
                .replace(Regex("\\s+#.*$"), "")
                .replace(Regex("\\s+//.*$"), "")
                .trim()

            if (line.isEmpty()) return@forEachIndexed

            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size == 2) {
                val pattern = parts[0]
                val replacement = parts[1]
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
                Log.i(TAG, "Line ${entry.lineNumber}: Replaced \"$before\" with \"$result\" using pattern \"${entry.pattern.pattern}\"")
            }
        }
        return result
    }
}
