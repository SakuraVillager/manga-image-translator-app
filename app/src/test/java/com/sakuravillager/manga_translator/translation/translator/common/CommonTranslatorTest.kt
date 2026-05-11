package com.sakuravillager.manga_translator.translation.translator.common

import com.sakuravillager.manga_translator.translation.data.config.TranslatorConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonTranslatorTest {

    // ─── Mock subclasses for testing ───────────────────────────────

    /**
     * A concrete [CommonTranslator] subclass that records calls to
     * [_translate] and returns pre-configured results.
     *
     * By default has language mappings for ENG, CHS, JPN so that
     * [translate] pipeline tests work out of the box.
     */
    private class MockTranslator(
        override val _LANGUAGE_CODE_MAP: Map<String, String> = mapOf(
            "ENG" to "en",
            "CHS" to "zh",
            "JPN" to "ja",
        ),
        override val _INVALID_REPEAT_COUNT: Int = 0,
        override val _MAX_REQUESTS_PER_MINUTE: Int = -1,
        private val mockResults: (List<String>) -> List<String> = { it },
    ) : CommonTranslator() {

        val translateCalls = mutableListOf<Triple<String, String, List<String>>>()

        override suspend fun _translate(
            fromLang: String,
            toLang: String,
            queries: List<String>,
        ): List<String> {
            translateCalls.add(Triple(fromLang, toLang, queries))
            return mockResults(queries)
        }

        // ── Expose protected methods for testing ──

        fun call_is_translation_invalid(query: String, trans: String): Boolean =
            _is_translation_invalid(query, trans)

        fun call_modify_invalid_translation_query(query: String, trans: String): String =
            _modify_invalid_translation_query(query, trans)

        fun call_clean_translation_output(query: String, trans: String, toLang: String): String =
            _clean_translation_output(query, trans, toLang)

        suspend fun call_ratelimit_sleep() = _ratelimit_sleep()
    }

    /**
     * A translator that uses a custom mapping, mimicking Deepl etc.
     */
    private class MappedTranslator(
        override val _LANGUAGE_CODE_MAP: Map<String, String> = mapOf(
            "ENG" to "en",
            "CHS" to "zh",
            "JPN" to "ja",
            "KOR" to "ko",
        ),
    ) : CommonTranslator() {
        override suspend fun _translate(
            fromLang: String,
            toLang: String,
            queries: List<String>,
        ): List<String> = queries  // pass-through
    }

    // ═══════════════════════════════════════════════════════════════
    //  supportsLanguages
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun supportsLanguages_returnsTrue_forValidLanguagePair() {
        val t = MappedTranslator()
        assertTrue(t.supportsLanguages("ENG", "CHS"))
    }

    @Test
    fun supportsLanguages_returnsTrue_whenFromIsAuto() {
        val t = MappedTranslator()
        assertTrue(t.supportsLanguages("auto", "ENG"))
    }

    @Test
    fun supportsLanguages_returnsFalse_forUnknownFromLanguage() {
        val t = MappedTranslator()
        assertFalse(t.supportsLanguages("FRA", "ENG"))
    }

    @Test
    fun supportsLanguages_returnsFalse_forUnknownToLanguage() {
        val t = MappedTranslator()
        assertFalse(t.supportsLanguages("ENG", "FRA"))
    }

    @Test
    fun supportsLanguages_throws_whenFatalAndFromLanguageIsInvalid() {
        val t = MappedTranslator()
        val ex = assertThrows(LanguageUnsupportedException::class.java) {
            t.supportsLanguages("FRA", "ENG", fatal = true)
        }
        assertTrue(ex.message!!.contains("FRA"))
    }

    @Test
    fun supportsLanguages_throws_whenFatalAndToLanguageIsInvalid() {
        val t = MappedTranslator()
        val ex = assertThrows(LanguageUnsupportedException::class.java) {
            t.supportsLanguages("ENG", "FRA", fatal = true)
        }
        assertTrue(ex.message!!.contains("FRA"))
    }

    @Test
    fun supportsLanguages_works_withEmptyLanguageCodeMap() {
        val t = MockTranslator(_LANGUAGE_CODE_MAP = emptyMap())
        assertFalse(t.supportsLanguages("ENG", "CHS"))
        assertFalse(t.supportsLanguages("auto", "nonexistent"))
    }

    // ═══════════════════════════════════════════════════════════════
    //  parseLanguageCodes
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun parseLanguageCodes_mapsCodesCorrectly() {
        val t = MappedTranslator()
        val (from, to) = t.parseLanguageCodes("ENG", "CHS")
        assertEquals("en", from)
        assertEquals("zh", to)
    }

    @Test
    fun parseLanguageCodes_passesAutoThroughUnchanged() {
        val t = MappedTranslator()
        val (from, to) = t.parseLanguageCodes("auto", "ENG")
        assertEquals("auto", from)
        assertEquals("en", to)
    }

    @Test
    fun parseLanguageCodes_returnsNullPair_forUnsupportedLanguages() {
        val t = MappedTranslator()
        val (from, to) = t.parseLanguageCodes("FRA", "ENG")
        assertEquals(null, from)
        assertEquals(null, to)
    }

    @Test
    fun parseLanguageCodes_returnsNullPair_forUnsupportedTarget() {
        val t = MappedTranslator()
        val (from, to) = t.parseLanguageCodes("ENG", "FRA")
        assertEquals(null, from)
        assertEquals(null, to)
    }

    @Test
    fun parseLanguageCodes_throws_whenFatalAndLanguageUnsupported() {
        val t = MappedTranslator()
        assertThrows(LanguageUnsupportedException::class.java) {
            t.parseLanguageCodes("FRA", "ENG", fatal = true)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  _is_translation_invalid
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun isTranslationInvalid_returnsTrue_whenTranslationEmptyAndQueryNotEmpty() {
        val t = MockTranslator()
        assertTrue(t.call_is_translation_invalid("hello", ""))
    }

    @Test
    fun isTranslationInvalid_returnsFalse_whenBothEmpty() {
        val t = MockTranslator()
        assertFalse(t.call_is_translation_invalid("", ""))
    }

    @Test
    fun isTranslationInvalid_returnsFalse_whenQueryEmptyButTranslationNotEmpty() {
        val t = MockTranslator()
        assertFalse(t.call_is_translation_invalid("", "hello"))
    }

    @Test
    fun isTranslationInvalid_returnsTrue_whenQueryHasManyValuableCharsAndTransHasFew() {
        val t = MockTranslator()
        // query = "abcdefghij" -> 10 valuable chars (> 6)
        // trans = "ab!!!!!!!!!!!!!!" -> 2 valuable chars (< 6) and 2 < 0.25 * 16 = 4
        assertTrue(t.call_is_translation_invalid("abcdefghij", "ab!!!!!!!!!!!!!!"))
    }

    @Test
    fun isTranslationInvalid_returnsFalse_whenQueryHasFewValuableChars() {
        val t = MockTranslator()
        // query = "ab" -> 2 valuable chars (NOT > 6)
        assertFalse(t.call_is_translation_invalid("ab", "ab!!!!!!!!!!!!!!"))
    }

    @Test
    fun isTranslationInvalid_returnsFalse_whenTranslationHasEnoughValuableChars() {
        val t = MockTranslator()
        // query = "abcdefghij" -> 10 valuable chars (> 6)
        // trans = "abcdef" -> 6 valuable chars (NOT < 6)
        assertFalse(t.call_is_translation_invalid("abcdefghij", "abcdef"))
    }

    @Test
    fun isTranslationInvalid_returnsFalse_whenTranslationValuableCharRatioIsHighEnough() {
        val t = MockTranslator()
        // query = "abcdefghij" -> 10 valuable chars (> 6)
        // trans = "ab" -> 2 valuable chars (< 6) but 2 >= 0.25 * 2 = 0.5
        assertFalse(t.call_is_translation_invalid("abcdefghij", "ab"))
    }

    // ═══════════════════════════════════════════════════════════════
    //  _modify_invalid_translation_query
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun modifyInvalidTranslationQuery_returnsQueryUnchangedByDefault() {
        val t = MockTranslator()
        assertEquals("hello", t.call_modify_invalid_translation_query("hello", "world"))
    }

    // ═══════════════════════════════════════════════════════════════
    //  _clean_translation_output - Rule 1: whitespace collapse
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun cleanOutput_collapsesMultipleSpaces() {
        val t = MockTranslator()
        val result = t.call_clean_translation_output("a b", "hello   world", "ENG")
        assertEquals("hello world", result)
    }

    @Test
    fun cleanOutput_collapsesTabsAndNewlines() {
        val t = MockTranslator()
        val result = t.call_clean_translation_output("a b", "hello\t\nworld", "ENG")
        assertEquals("hello world", result)
    }

    // ═══════════════════════════════════════════════════════════════
    //  _clean_translation_output - Rule 2: punctuation spacing
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun cleanOutput_addsSpaceAfterPeriodBeforeWord() {
        val t = MockTranslator()
        val result = t.call_clean_translation_output("hello.world", "hello.world", "ENG")
        assertEquals("hello. world", result)
    }

    @Test
    fun cleanOutput_addsSpaceAfterCommaBeforeWord() {
        val t = MockTranslator()
        val result = t.call_clean_translation_output("a,b", "hello,world", "ENG")
        assertEquals("hello, world", result)
    }

    // ═══════════════════════════════════════════════════════════════
    //  _clean_translation_output - Rule 3: consecutive punctuation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun cleanOutput_removesSpaceBetweenConsecutivePunctuation() {
        val t = MockTranslator()
        // Rule 3: "hello ! ! world" -> "hello !! world"
        // Rule 4: "hello !! world"  -> "hello!! world" (space before ! removed by rule 4)
        val result = t.call_clean_translation_output("a b", "hello ! ! world", "ENG")
        assertEquals("hello!! world", result)
    }

    @Test
    fun cleanOutput_removesTrailingSpaceAfterPunctuation() {
        val t = MockTranslator()
        // Rule 3 removes trailing space: "hello world! " -> "hello world!"
        val result = t.call_clean_translation_output("a b", "hello world! ", "ENG")
        assertEquals("hello world!", result)
    }

    // ═══════════════════════════════════════════════════════════════
    //  _clean_translation_output - Rule 4: space before punctuation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun cleanOutput_removesSpaceBeforePeriod() {
        val t = MockTranslator()
        val result = t.call_clean_translation_output("a b", "hello .", "ENG")
        assertEquals("hello.", result)
    }

    @Test
    fun cleanOutput_removesSpaceBeforeExclamation() {
        val t = MockTranslator()
        val result = t.call_clean_translation_output("a b", "hello !", "ENG")
        assertEquals("hello!", result)
    }

    // ═══════════════════════════════════════════════════════════════
    //  _clean_translation_output - Rule 5: ellipsis spacing
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun cleanOutput_removesSpaceAfterEllipsisBeforeWord() {
        val t = MockTranslator()
        // NOTE: This rule was missing from TranslationValidator.cleanTranslation()
        val result = t.call_clean_translation_output("...a", "... hello", "ENG")
        assertEquals("...hello", result)
    }

    // ═══════════════════════════════════════════════════════════════
    //  _clean_translation_output - ARA skips rules 4 and 5
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun cleanOutput_skipsRules4And5_forArabic() {
        val t = MockTranslator()
        val result = t.call_clean_translation_output("a b", "hello .", "ARA")
        assertEquals("hello .", result) // unchanged because ARA skips rule 4
    }

    @Test
    fun cleanOutput_skipsEllipsisRule_forArabic() {
        val t = MockTranslator()
        val result = t.call_clean_translation_output("...a", "... hello", "ARA")
        assertEquals("... hello", result) // unchanged because ARA skips rule 5
    }

    // ═══════════════════════════════════════════════════════════════
    //  _clean_translation_output - Rule 6: repeating sequence
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun cleanOutput_compressesRepeatingSequences_allUpper() {
        val t = MockTranslator()
        // Query: "ABCDEFGH" (8 chars, all upper)
        // Trans: "ababab" (6 chars, seq="ab", len=2)
        // Conditions: 2 < 0.5*6=3, 6 < 8 -> both true
        // repeatCount = maxOf(1, 8/2) = 4
        // shrunken = "ab".repeat(4) = "abababab" (8 chars)
        // All query chars are upper -> all shrunken chars upper: "ABABABAB"
        val query = "ABCDEFGH"
        val trans = "ababab"
        val result = t.call_clean_translation_output(query, trans, "ENG")
        assertEquals("ABABABAB", result)
    }

    @Test
    fun cleanOutput_compressesRepeatingSequences_mixedCase() {
        val t = MockTranslator()
        // Query: "Abcdefgh" (8 chars, first char upper)
        // Trans: "ababab" (6 chars, seq="ab", len=2)
        // Only index 0 'A' is upper -> shrunken[0]='a'.uppercaseChar()='A'
        // Rest are lower -> keep as-is: 'b','a','b','a','b','a','b'
        val query = "Abcdefgh"
        val trans = "ababab"
        val result = t.call_clean_translation_output(query, trans, "ENG")
        assertEquals("Abababab", result)
    }

    @Test
    fun cleanOutput_doesNotCompress_whenResultNotShorterThanQuery() {
        val t = MockTranslator()
        // trans length (4) !< query length (1), so no compression
        val result = t.call_clean_translation_output("a", "aaaa", "ENG")
        assertEquals("aaaa", result)
    }

    @Test
    fun cleanOutput_doesNotCompress_whenSeqNotShortEnough() {
        val t = MockTranslator()
        // query = "ABCDEFGH" (8), trans = "abcabc" (6)
        // seq="abc" len=3, 3 < 0.5*6=3? No -> no compression
        val result = t.call_clean_translation_output("ABCDEFGH", "abcabc", "ENG")
        assertEquals("abcabc", result)
    }

    // ═══════════════════════════════════════════════════════════════
    //  _clean_translation_output - empty inputs
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun cleanOutput_returnsEmpty_forEmptyQuery() {
        val t = MockTranslator()
        assertEquals("", t.call_clean_translation_output("", "hello", "ENG"))
    }

    @Test
    fun cleanOutput_returnsEmpty_forEmptyTrans() {
        val t = MockTranslator()
        assertEquals("", t.call_clean_translation_output("hello", "", "ENG"))
    }

    @Test
    fun cleanOutput_combinedRulesWorkTogether() {
        val t = MockTranslator()
        // Input: "hello  .  world  !  !  test"
        // Rule 1: "hello . world ! ! test"
        // Rule 2: no change (punctuation already has space around)
        // Rule 3: "hello . world !! test"  (space between !! removed)
        // Rule 4:
        //   - "o ." -> "o."       (space before . removed)
        //   - "d !!" -> "d!!"     (space before !! removed)
        // Result: "hello. world!! test"
        val result = t.call_clean_translation_output(
            "a b", "hello  .  world  !  !  test", "ENG",
        )
        assertEquals("hello. world!! test", result)
    }

    // ═══════════════════════════════════════════════════════════════
    //  _ratelimit_sleep
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun ratelimitSleep_returnsImmediately_whenDisabled() = runTest {
        val t = MockTranslator(_MAX_REQUESTS_PER_MINUTE = -1)
        t.call_ratelimit_sleep()
    }

    @Test
    fun ratelimitSleep_returnsImmediately_whenZero() = runTest {
        val t = MockTranslator(_MAX_REQUESTS_PER_MINUTE = 0)
        t.call_ratelimit_sleep()
    }

    // ═══════════════════════════════════════════════════════════════
    //  translate - full pipeline with mock
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun translate_returnsOriginalTexts_whenFromEqualsTo() = runTest {
        val t = MockTranslator()
        val texts = listOf("hello", "world")
        val result = t.translate(texts, "ENG", "ENG", TranslatorConfig())
        assertEquals(texts, result)
    }

    @Test
    fun translate_validatesTargetLanguage() {
        val t = MockTranslator()
        val ex = assertThrows(LanguageUnsupportedException::class.java) {
            runTest {
                t.translate(listOf("hello"), "ENG", "INVALID", TranslatorConfig())
            }
        }
        assertTrue(ex.message!!.contains("INVALID"))
    }

    @Test
    fun translate_validatesSourceLanguage() {
        val t = MockTranslator()
        val ex = assertThrows(LanguageUnsupportedException::class.java) {
            runTest {
                t.translate(listOf("hello"), "INVALID", "ENG", TranslatorConfig())
            }
        }
        assertTrue(ex.message!!.contains("INVALID"))
    }

    @Test
    fun translate_passesThroughNonValuableTextUnchanged() = runTest {
        val mockResults: (List<String>) -> List<String> = { queries ->
            queries.map { "translated_$it" }
        }
        val t = MockTranslator(mockResults = mockResults)
        val texts = listOf("123", "hello", "!@#")
        val result = t.translate(texts, "ENG", "CHS", TranslatorConfig())
        assertEquals(listOf("123", "translated_hello", "!@#"), result)
    }

    @Test
    fun translate_callsTranslateWithCorrectArguments() = runTest {
        val mockResults: (List<String>) -> List<String> = { queries ->
            queries.map { "translated_$it" }
        }
        val t = MockTranslator(mockResults = mockResults)
        val texts = listOf("hello", "world")
        t.translate(texts, "ENG", "CHS", TranslatorConfig())

        assertEquals(1, t.translateCalls.size)
        val (from, to, queries) = t.translateCalls[0]
        assertEquals("en", from)  // mapped by _LANGUAGE_CODE_MAP
        assertEquals("zh", to)
        assertEquals(listOf("hello", "world"), queries)
    }

    @Test
    fun translate_callsTranslateWithCodeMappedArguments() = runTest {
        val mockResults: (List<String>) -> List<String> = { queries ->
            queries.map { "translated_$it" }
        }
        val t = MockTranslator(
            _LANGUAGE_CODE_MAP = mapOf("ENG" to "en", "CHS" to "zh"),
            mockResults = mockResults,
        )
        val texts = listOf("hello", "world")
        t.translate(texts, "ENG", "CHS", TranslatorConfig())

        assertEquals(1, t.translateCalls.size)
        val (from, to, queries) = t.translateCalls[0]
        assertEquals("en", from)
        assertEquals("zh", to)
        assertEquals(listOf("hello", "world"), queries)
    }

    @Test
    fun translate_padsShortTranslateResults() = runTest {
        val mockResults: (List<String>) -> List<String> = { listOf("only_one") }
        val t = MockTranslator(mockResults = mockResults)
        val texts = listOf("hello", "world")
        val result = t.translate(texts, "ENG", "CHS", TranslatorConfig())
        assertEquals(2, result.size)
        assertEquals("only_one", result[0])
        assertEquals("", result[1])
    }

    @Test
    fun translate_truncatesLongTranslateResults() = runTest {
        val mockResults: (List<String>) -> List<String> = {
            listOf("a", "b", "c", "d")
        }
        val t = MockTranslator(mockResults = mockResults)
        val texts = listOf("hello", "world")
        val result = t.translate(texts, "ENG", "CHS", TranslatorConfig())
        assertEquals(2, result.size)
    }

    @Test
    fun translate_retriesOnInvalidTranslation_whenRepeatCountPositive() = runTest {
        var callCount = 0
        val mockResults: (List<String>) -> List<String> = { queries ->
            callCount++
            if (callCount == 1) {
                listOf("ab!!!!!!!!!!!!!!") // invalid per _is_translation_invalid
            } else {
                listOf("valid translation")
            }
        }
        val t = MockTranslator(
            _INVALID_REPEAT_COUNT = 1,
            mockResults = mockResults,
        )
        val texts = listOf("abcdefghij") // 10 valuable chars
        val result = t.translate(texts, "ENG", "CHS", TranslatorConfig())
        assertEquals(1, result.size)
        assertTrue(result[0].contains("valid translation"))
        assertEquals(2, callCount)
    }

    @Test
    fun translate_filtersNonValuableTextAndMergesCorrectly() = runTest {
        val mockResults: (List<String>) -> List<String> = { queries ->
            queries.map { "TR($it)" }
        }
        val t = MockTranslator(mockResults = mockResults)
        val texts = listOf("hello", "123", "world", "...", "test")
        val result = t.translate(texts, "ENG", "CHS", TranslatorConfig())
        assertEquals(listOf("TR(hello)", "123", "TR(world)", "...", "TR(test)"), result)
    }

    @Test
    fun translate_cleansOutputWithAllRules() = runTest {
        val mockResults: (List<String>) -> List<String> = { queries ->
            queries.map { "hello  world  .  test" }
        }
        val t = MockTranslator(mockResults = mockResults)
        val result = t.translate(listOf("input"), "ENG", "CHS", TranslatorConfig())
        assertEquals(1, result.size)
        // After rule 1: "hello world . test"
        // After rule 3: "hello world . test" (no consecutive punctuation)
        // After rule 4: "hello world. test" (space before period removed)
        assertEquals("hello world. test", result[0])
    }

    // ═══════════════════════════════════════════════════════════════
    //  Translator interface properties
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun name_returnsClassSimpleName() {
        val t = MockTranslator()
        assertEquals("MockTranslator", t.name)
    }

    @Test
    fun isReady_returnsTrueByDefault() {
        val t = MockTranslator()
        assertTrue(t.isReady)
    }

    @Test
    fun supportedSourceLanguages_includesAuto() {
        val t = MappedTranslator()
        assertTrue(t.supportedSourceLanguages.contains("auto"))
        assertTrue(t.supportedSourceLanguages.contains("ENG"))
        assertTrue(t.supportedSourceLanguages.contains("CHS"))
        assertEquals(5, t.supportedSourceLanguages.size) // auto + ENG, CHS, JPN, KOR
    }

    @Test
    fun supportedTargetLanguages_doesNotIncludeAuto() {
        val t = MappedTranslator()
        assertFalse(t.supportedTargetLanguages.contains("auto"))
        assertTrue(t.supportedTargetLanguages.contains("ENG"))
        assertEquals(4, t.supportedTargetLanguages.size) // ENG, CHS, JPN, KOR
    }

    @Test
    fun supportsLanguagePair_delegatesToSupportsLanguages() {
        val t = MappedTranslator()
        assertTrue(t.supportsLanguagePair("ENG", "CHS"))
        assertFalse(t.supportsLanguagePair("FRA", "ENG"))
    }

    @Test
    fun prepareAndRelease_areNoOps() = runTest {
        val t = MockTranslator()
        t.prepare()
        t.release()
    }
}
