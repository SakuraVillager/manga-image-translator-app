package com.sakuravillager.manga_translator.translation.translator

import com.sakuravillager.manga_translator.translation.api.Translator
import com.sakuravillager.manga_translator.translation.data.config.TranslatorConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [CompositeTranslator].
 *
 * These tests use [NoOpTranslator] and [OriginalTranslator] which have no
 * external dependencies and work in pure JVM unit tests.
 */
class CompositeTranslatorTest {

    // ─── Empty / single-step chains ───────────────────────────────────

    @Test
    fun emptyStepsReturnsInputUnchanged() = runTest {
        val translator = CompositeTranslator(emptyList())
        val result = translator.translate(
            listOf("hello", "world"),
            "auto", "ENG", TranslatorConfig(),
        )
        assertEquals(listOf("hello", "world"), result)
    }

    @Test
    fun singleOriginalStepPassesTextThrough() = runTest {
        val steps = listOf(
            TranslatorStep(OriginalTranslator(), "ENG"),
        )
        val translator = CompositeTranslator(steps)
        val result = translator.translate(
            listOf("hello", "world"),
            "auto", "ENG", TranslatorConfig(),
        )
        assertEquals(listOf("hello", "world"), result)
    }

    @Test
    fun singleNoOpStepReturnsEmptyStrings() = runTest {
        val steps = listOf(
            TranslatorStep(NoOpTranslator(), "ENG"),
        )
        val translator = CompositeTranslator(steps)
        val result = translator.translate(
            listOf("hello", "world"),
            "auto", "ENG", TranslatorConfig(),
        )
        assertEquals(listOf("", ""), result)
    }

    // ─── Multi-step chains ───────────────────────────────────────────

    @Test
    fun originalThenNoOpReturnsEmptyStrings() = runTest {
        val steps = listOf(
            TranslatorStep(OriginalTranslator(), "ENG"),  // passes through
            TranslatorStep(NoOpTranslator(), "CHS"),       // empties
        )
        val translator = CompositeTranslator(steps)
        val result = translator.translate(
            listOf("hello", "world"),
            "auto", "CHS", TranslatorConfig(),
        )
        assertEquals(listOf("", ""), result)
    }

    @Test
    fun noOpThenOriginalReturnsEmptyStrings() = runTest {
        val steps = listOf(
            TranslatorStep(NoOpTranslator(), "ENG"),       // empties
            TranslatorStep(OriginalTranslator(), "CHS"),   // passes through empty
        )
        val translator = CompositeTranslator(steps)
        val result = translator.translate(
            listOf("hello", "world"),
            "auto", "CHS", TranslatorConfig(),
        )
        assertEquals(listOf("", ""), result)
    }

    @Test
    fun originalThenOriginalPassesThrough() = runTest {
        val steps = listOf(
            TranslatorStep(OriginalTranslator(), "ENG"),
            TranslatorStep(OriginalTranslator(), "CHS"),
        )
        val translator = CompositeTranslator(steps)
        val result = translator.translate(
            listOf("hello", "world", "test"),
            "auto", "CHS", TranslatorConfig(),
        )
        assertEquals(listOf("hello", "world", "test"), result)
    }

    // ─── Edge cases ──────────────────────────────────────────────────

    @Test
    fun emptyInputReturnsEmpty() = runTest {
        val steps = listOf(
            TranslatorStep(OriginalTranslator(), "ENG"),
        )
        val translator = CompositeTranslator(steps)
        val result = translator.translate(
            emptyList(),
            "auto", "ENG", TranslatorConfig(),
        )
        assertTrue("Empty input should return empty list", result.isEmpty())
    }

    @Test
    fun blankInputReturnsUnchanged() = runTest {
        val steps = listOf(
            TranslatorStep(NoOpTranslator(), "ENG"),
        )
        val translator = CompositeTranslator(steps)
        val result = translator.translate(
            listOf("", "  "),
            "auto", "ENG", TranslatorConfig(),
        )
        assertEquals(listOf("", "  "), result)
    }

    // ─── Metadata ────────────────────────────────────────────────────

    @Test
    fun nameIsCompositeTranslator() {
        val translator = CompositeTranslator(emptyList())
        assertEquals("CompositeTranslator", translator.name)
    }

    @Test
    fun emptyStepsNotReady() {
        val translator = CompositeTranslator(emptyList())
        assertFalse(translator.isReady)
    }

    @Test
    fun supportsLanguagePairReturnsFalseForEmptySteps() {
        val translator = CompositeTranslator(emptyList())
        assertFalse(translator.supportsLanguagePair("ENG", "CHS"))
    }

    @Test
    fun supportsLanguagePairReturnsTrueWhenAllStepsSupport() {
        val steps = listOf(
            TranslatorStep(OriginalTranslator(), "ENG"),
        )
        val translator = CompositeTranslator(steps)
        // OriginalTranslator passes auto through supportsLanguagePair
        assertTrue(translator.supportsLanguagePair("ENG", "CHS"))
    }
}
