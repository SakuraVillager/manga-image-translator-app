package com.sakuravillager.manga_translator.translation.dict

import org.junit.Test
import java.io.File
import org.junit.Assert.*

class DictionaryLoaderTest {

    private fun createTempDict(content: String): File {
        val file = File.createTempFile("dict", ".txt")
        file.writeText(content)
        file.deleteOnExit()
        return file
    }

    @Test
    fun `load parses replacement rules correctly`() {
        val content = """
            hello 你好
            world 世界
            # comment
            // also comment
            
            badtext
        """.trimIndent()
        val file = createTempDict(content)
        val entries = DictionaryLoader.load(file.absolutePath)

        assertEquals(3, entries.size)
        assertEquals("你好", entries[0].replacement)
        assertTrue(entries[0].pattern.matches("hello"))
        assertEquals("", entries[2].replacement)
        assertTrue(entries[2].pattern.matches("badtext"))
    }

    @Test
    fun `apply replaces text using loaded dictionary`() {
        val content = "hello 你好\nworld 世界"
        val file = createTempDict(content)
        val entries = DictionaryLoader.load(file.absolutePath)
        val result = DictionaryLoader.apply("hello world", entries)
        assertEquals("你好 世界", result)
    }

    @Test
    fun `apply deletes text for pattern-only entries`() {
        val content = "badtext"
        val file = createTempDict(content)
        val entries = DictionaryLoader.load(file.absolutePath)
        val result = DictionaryLoader.apply("this is badtext content", entries)
        assertEquals("this is  content", result)
    }

    @Test
    fun `empty file returns empty dictionary`() {
        val file = createTempDict("")
        val entries = DictionaryLoader.load(file.absolutePath)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `empty dictionary leaves text unchanged`() {
        val result = DictionaryLoader.apply("hello world", emptyList<DictionaryLoader.DictEntry>())
        assertEquals("hello world", result)
    }
}
