package com.sakuravillager.manga_translator.translation.glossary

import org.junit.Test
import java.io.File
import org.junit.Assert.*

class GlossaryLoaderTest {

    private fun createTempGlossary(content: String): File {
        val file = File.createTempFile("glossary", ".txt")
        file.writeText(content)
        file.deleteOnExit()
        return file
    }

    @Test
    fun `load parses glossary entries correctly`() {
        val content = """
            龍 dragon
            桜 sakura
            # comment
            // also comment
            
            hero
        """.trimIndent()
        val file = createTempGlossary(content)
        val entries = GlossaryLoader.load(file.absolutePath)

        assertEquals(3, entries.size)
        assertEquals("dragon", entries[0].replacement)
        assertTrue(entries[0].pattern.matches("龍"))
        assertEquals("", entries[2].replacement)
        assertTrue(entries[2].pattern.matches("hero"))
    }

    @Test
    fun `empty file returns empty list`() {
        val file = createTempGlossary("")
        val entries = GlossaryLoader.load(file.absolutePath)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `load returns empty list when file does not exist`() {
        val entries = GlossaryLoader.load("/nonexistent/path/glossary.txt")
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `extractRelevantTerms returns matching entries`() {
        val entries = listOf(
            GlossaryLoader.GlossaryEntry(Regex("龍"), "dragon"),
            GlossaryLoader.GlossaryEntry(Regex("桜"), "sakura"),
            GlossaryLoader.GlossaryEntry(Regex("忍者"), "ninja"),
        )
        val queries = listOf("龍が", "桜の木")
        val result = GlossaryLoader.extractRelevantTerms(entries, queries)

        assertEquals(2, result.size)
        assertTrue(result.containsKey("龍"))
        assertTrue(result.containsKey("桜"))
        assertEquals("dragon", result["龍"])
        assertEquals("sakura", result["桜"])
    }

    @Test
    fun `extractRelevantTerms returns empty for no matching terms`() {
        val entries = listOf(
            GlossaryLoader.GlossaryEntry(Regex("忍者"), "ninja"),
        )
        val queries = listOf("龍", "桜")
        val result = GlossaryLoader.extractRelevantTerms(entries, queries)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `extractRelevantTerms returns empty for empty entries`() {
        val result = GlossaryLoader.extractRelevantTerms(emptyList(), listOf("test"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extractRelevantTerms returns empty for empty queries`() {
        val entries = listOf(
            GlossaryLoader.GlossaryEntry(Regex("test"), "replacement"),
        )
        val result = GlossaryLoader.extractRelevantTerms(entries, emptyList())
        assertTrue(result.isEmpty())
    }
}
