package com.sakuravillager.manga_translator.translation.glossary

import java.io.File

object GlossaryLoader {
    data class GlossaryEntry(val pattern: Regex, val replacement: String)

    fun load(filePath: String): List<GlossaryEntry> {
        // Same mit format as DictionaryLoader: "pattern replacement" per line
        return try {
            val file = File(filePath)
            if (!file.exists()) return emptyList()
            file.readLines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("//") }
                .mapNotNull { line ->
                    val parts = line.split("\\s+".toRegex(), 2)
                    if (parts.isEmpty()) null
                    else GlossaryEntry(Regex(parts[0]), parts.getOrElse(1) { "" })
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun extractRelevantTerms(entries: List<GlossaryEntry>, queries: List<String>): Map<String, String> {
        if (entries.isEmpty() || queries.isEmpty()) return emptyMap()
        return entries.filter { entry ->
            queries.any { query -> entry.pattern.containsMatchIn(query) }
        }.associate { it.pattern.pattern to it.replacement }
    }
}
