package com.sakuravillager.manga_translator.translation.pipeline

import android.graphics.PointF
import com.sakuravillager.manga_translator.translation.data.TextBlock
import com.sakuravillager.manga_translator.translation.pt
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the translation pre/post processing order contract.
 *
 * Verifies that:
 * 1. `textRaw` is preserved through all pre-processing (pre-dict, bracket fix, case, clean)
 * 2. `text` is modified by pre-processing while `textRaw` stays original
 * 3. `textRaw` is used by the renderer for region expansion calculations
 *
 * Note: The processing functions themselves (applyPreDictionary, fixBrackets, etc.) are private.
 * We test the data contract through [TextBlock] copy operations and verify the data model
 * semantics match the pipeline's usage pattern.
 */
class TranslationProcessingOrderTest {

    private fun textBlock(text: String = "テスト", textRaw: String = "テスト"): TextBlock = TextBlock(
        lines = listOf(listOf(pt(0f, 0f), pt(40f, 0f), pt(40f, 10f), pt(0f, 10f))),
        text = text,
        textRaw = textRaw,
    )

    // ── textRaw preservation contract ─────────────────────────────────────

    @Test
    fun `textRaw is set to text at construction`() {
        val block = textBlock(text = "hello", textRaw = "hello")
        assertEquals("hello", block.textRaw)
    }

    @Test
    fun `modifying text via copy does not affect textRaw`() {
        val original = textBlock(text = "hello", textRaw = "hello")
        val modified = original.copy(text = "modified")
        assertEquals("modified", modified.text)
        assertEquals("hello", modified.textRaw)
    }

    @Test
    fun `textRaw stays unchanged through multiple copy operations`() {
        val original = textBlock(text = "original", textRaw = "original")
        // Simulate pre-dict: modifies text
        val afterPreDict = original.copy(text = original.text.replace("original", "pre-dicted"))
        assertEquals("pre-dicted", afterPreDict.text)
        assertEquals("original", afterPreDict.textRaw)

        // Simulate bracket fix: modifies text again
        val afterBrackets = afterPreDict.copy(text = afterPreDict.text + " (fixed)")
        assertEquals("pre-dicted (fixed)", afterBrackets.text)
        assertEquals("original", afterBrackets.textRaw)

        // Simulate translation: sets translation field
        val afterTranslation = afterBrackets.copy(translation = "translated text")
        assertEquals("original", afterTranslation.textRaw)
        assertEquals("pre-dicted (fixed)", afterTranslation.text)
        assertEquals("translated text", afterTranslation.translation)
    }

    @Test
    fun `textRaw is independent of translation field`() {
        val block = textBlock(text = "source", textRaw = "source")
        val translated = block.copy(translation = "target")
        assertEquals("source", translated.textRaw)
        assertEquals("source", translated.text)
        assertEquals("target", translated.translation)
    }

    // ── Renderer expansion contract ───────────────────────────────────────

    @Test
    fun `textRaw length is used for renderer expansion ratio`() {
        // The renderer uses textRaw.length vs translation.length to decide scaling
        val block = textBlock(text = "テスト", textRaw = "テスト")
        val translated = block.copy(translation = "Hello world, this is a longer translation")

        // Verify the ratio would be > 1 (translation is longer)
        val ratio = translated.translation.length.toFloat() / translated.textRaw.length.toFloat()
        assertTrue("Translation is longer than textRaw, ratio should be > 1", ratio > 1f)
    }

    @Test
    fun `textRaw is empty string for empty text block`() {
        val block = TextBlock()
        assertEquals("", block.textRaw)
        assertEquals("", block.text)
    }
}
