package com.sakuravillager.manga_translator.translation.translator.common

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MTPEAdapterTest {

    @Test
    fun `NoOpMTPEAdapter returns translations unchanged`() = runBlocking {
        val queries = listOf("hello", "world")
        val translations = listOf("こんにちは", "世界")
        val result = NoOpMTPEAdapter.dispatch(queries, translations)
        assertEquals(translations, result)
    }

    @Test
    fun `NoOpMTPEAdapter preserves ordering`() = runBlocking {
        val queries = listOf("a", "b", "c")
        val translations = listOf("1", "2", "3")
        val result = NoOpMTPEAdapter.dispatch(queries, translations)
        assertEquals(listOf("1", "2", "3"), result)
    }

    @Test
    fun `NoOpMTPEAdapter handles empty lists`() = runBlocking {
        val result = NoOpMTPEAdapter.dispatch(emptyList(), emptyList())
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `NoOpMTPEAdapter returns the same list instance`() = runBlocking {
        val queries = listOf("test")
        val translations = listOf("translated")
        val result = NoOpMTPEAdapter.dispatch(queries, translations)
        assertSame(translations, result)
    }
}
