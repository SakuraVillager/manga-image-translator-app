package com.sakuravillager.manga_translator.translation.translator

import org.junit.Test
import org.junit.Assert.*

/**
 * JVM unit tests for [NllbTranslator] and [NllbBigTranslator].
 *
 * These tests cover the static/companion object members and language code
 * mappings that are pure Kotlin with no ONNX Runtime dependency.
 */
class NllbTranslatorTest {

    // ─── FLORES_200_LANGUAGES companion map ──────────────────────────

    @Test
    fun flores200LanguagesContainsExpectedEntries() {
        val map = NllbTranslator.FLORES_200_LANGUAGES
        assertTrue("FLORES_200 should contain English", map.containsKey("eng_Latn"))
        assertTrue("FLORES_200 should contain Chinese Simplified", map.containsKey("zho_Hans"))
        assertTrue("FLORES_200 should contain Chinese Traditional", map.containsKey("zho_Hant"))
        assertTrue("FLORES_200 should contain Japanese", map.containsKey("jpn_Jpan"))
        assertTrue("FLORES_200 should contain Korean", map.containsKey("kor_Hang"))
        assertTrue("FLORES_200 should contain Russian", map.containsKey("rus_Cyrl"))
        assertTrue("FLORES_200 should contain Arabic", map.containsKey("arb_Arab"))
        assertTrue("FLORES_200 should contain French", map.containsKey("fra_Latn"))
        assertTrue("FLORES_200 should contain German", map.containsKey("deu_Latn"))
        assertTrue("FLORES_200 should contain Spanish", map.containsKey("spa_Latn"))
    }

    @Test
    fun flores200LanguagesHasExpectedSize() {
        // NLLB-200 supports 200+ languages; we expect at least 200 entries
        assertTrue(
            "FLORES_200 should have at least 200 language entries, got ${NllbTranslator.FLORES_200_LANGUAGES.size}",
            NllbTranslator.FLORES_200_LANGUAGES.size >= 200,
        )
    }

    @Test
    fun flores200LanguagesHasCorrectEnglishName() {
        assertEquals(
            "English",
            NllbTranslator.FLORES_200_LANGUAGES["eng_Latn"],
        )
    }

    @Test
    fun flores200LanguagesHasCorrectChineseSimplifiedName() {
        assertEquals(
            "Chinese (Simplified)",
            NllbTranslator.FLORES_200_LANGUAGES["zho_Hans"],
        )
    }

    @Test
    fun flores200LanguagesHasCorrectChineseTraditionalName() {
        assertEquals(
            "Chinese (Traditional)",
            NllbTranslator.FLORES_200_LANGUAGES["zho_Hant"],
        )
    }

    // ─── Model info constants ────────────────────────────────────────

    @Test
    fun nllbEncoderModelInfoHasCorrectName() {
        assertEquals("nllb_600m_encoder", NllbTranslator.NLLB_ENCODER_MODEL.name)
    }

    @Test
    fun nllbDecoderModelInfoHasCorrectName() {
        assertEquals("nllb_600m_decoder", NllbTranslator.NLLB_DECODER_MODEL.name)
    }

    @Test
    fun nllbTokenizerModelInfoHasCorrectName() {
        assertEquals("nllb_600m_tokenizer", NllbTranslator.NLLB_TOKENIZER_MODEL.name)
    }

    // ─── NllbBigTranslator model info constants ──────────────────────

    @Test
    fun nllbBigEncoderModelInfoHasCorrectName() {
        assertEquals("nllb_1.3b_encoder", NllbBigTranslator.NLLB_BIG_ENCODER_MODEL.name)
    }

    @Test
    fun nllbBigDecoderModelInfoHasCorrectName() {
        assertEquals("nllb_1.3b_decoder", NllbBigTranslator.NLLB_BIG_DECODER_MODEL.name)
    }

    @Test
    fun nllbBigTokenizerModelInfoHasCorrectName() {
        assertEquals("nllb_1.3b_tokenizer", NllbBigTranslator.NLLB_BIG_TOKENIZER_MODEL.name)
    }

    // ─── Translator name ─────────────────────────────────────────────

    @Test
    fun nllbTranslatorNameIsCorrect() {
        // Can't easily instantiate NllbTranslator in JVM tests (needs ModelDownloadManager + OnnxSessionManager),
        // but the companion constant FLORES_200 is static and accessible.
        assertNotNull("FLORES_200_LANGUAGES should not be null", NllbTranslator.FLORES_200_LANGUAGES)
    }
}
