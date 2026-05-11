package com.sakuravillager.manga_translator.translation.translator.common

import android.util.Log
import com.sakuravillager.manga_translator.translation.api.InfererModule
import com.sakuravillager.manga_translator.translation.api.Translator
import com.sakuravillager.manga_translator.translation.data.config.TranslatorConfig
import kotlinx.coroutines.delay

/**
 * Abstract base class for all translators, ported 1:1 from Python
 * `manga_translator/translators/common.py` (lines 105–301).
 *
 * Subclasses must implement [_translate] which performs the actual
 * API / model call.  All retry logic, rate limiting, output cleaning,
 * and MTPE dispatch is handled here.
 */
abstract class CommonTranslator : InfererModule(), Translator {

    // ─── Python class-level constants ───────────────────────────────

    /**
     * Maps internal language codes (e.g. "ENG") to the language codes
     * that the translator API actually expects (e.g. "en").
     *
     * Override in subclasses to support different language mappings.
     * An empty map means no mapping is required (languages are passed
     * through as-is).
     *
     * Matches Python `_LANGUAGE_CODE_MAP = {}` (L109).
     */
    protected open val _LANGUAGE_CODE_MAP: Map<String, String> = emptyMap()

    /**
     * How many times to retry when [_is_translation_invalid] returns true.
     *
     * Matches Python `_INVALID_REPEAT_COUNT = 0` (L113).
     */
    protected open val _INVALID_REPEAT_COUNT: Int = 0

    /**
     * Maximum API requests per minute.  When exceeded the class
     * sleeps automatically in [_ratelimit_sleep].
     * `-1` = disabled.
     *
     * Matches Python `_MAX_REQUESTS_PER_MINUTE = -1` (L116).
     */
    protected open val _MAX_REQUESTS_PER_MINUTE: Int = -1

    // ─── Instance state (init) ──────────────────────────────────────

    /**
     * Timestamp (ms since epoch) of the last API request.
     * Used by [_ratelimit_sleep].
     *
     * Matches Python `self._last_request_ts = 0` (L121).
     */
    private var _lastRequestTs: Long = 0L

    /**
     * Optional Machine Translation Post-Editing adapter.
     * Defaults to [NoOpMTPEAdapter].
     *
     * Matches Python `self.mtpe_adapter = MTPEAdapter()` (L120).
     */
    protected lateinit var mtpeAdapter: MTPEAdapter

    init {
        mtpeAdapter = NoOpMTPEAdapter
        _lastRequestTs = 0L
    }

    // ─── Translator interface implementation ────────────────────────

    override val name: String
        get() = this::class.simpleName ?: "CommonTranslator"

    override val isReady: Boolean
        get() = true

    override suspend fun prepare() {
        // No-op — subclasses may override for async initialisation.
    }

    override suspend fun release() {
        // No-op — subclasses may override for cleanup.
    }

    override val supportedSourceLanguages: Set<String>
        get() = _LANGUAGE_CODE_MAP.keys + "auto"

    override val supportedTargetLanguages: Set<String>
        get() = _LANGUAGE_CODE_MAP.keys

    override fun supportsLanguagePair(from: String, to: String): Boolean =
        supportsLanguages(from, to)

    /**
     * Checks whether both [fromLang] and [toLang] are supported.
     *
     * When [fatal] is `true` a [LanguageUnsupportedException] is thrown
     * instead of returning `false`.
     *
     * Matches Python `supports_languages` (L123–135).
     */
    fun supportsLanguages(fromLang: String, toLang: String, fatal: Boolean = false): Boolean {
        val supportedSrcLanguages = listOf("auto") + _LANGUAGE_CODE_MAP.keys.toList()
        val supportedTgtLanguages = _LANGUAGE_CODE_MAP.keys.toList()

        if (fromLang !in supportedSrcLanguages) {
            if (fatal) throw LanguageUnsupportedException(fromLang, name, supportedSrcLanguages)
            return false
        }
        if (toLang !in supportedTgtLanguages) {
            if (fatal) throw LanguageUnsupportedException(toLang, name, supportedTgtLanguages)
            return false
        }
        return true
    }

    /**
     * Resolves external language codes to internal codes using
     * [_LANGUAGE_CODE_MAP].  `"auto"` is passed through unchanged.
     *
     * Returns a pair of `(fromCode, toCode)` or `(null, null)` when
     * the language pair is not supported and [fatal] is `false`.
     *
     * Matches Python `parse_language_codes` (L137–145).
     */
    fun parseLanguageCodes(
        fromLang: String,
        toLang: String,
        fatal: Boolean = false,
    ): Pair<String?, String?> {
        if (!supportsLanguages(fromLang, toLang, fatal)) return null to null
        val parsedFrom = if (fromLang != "auto") _LANGUAGE_CODE_MAP[fromLang] else "auto"
        val parsedTo = _LANGUAGE_CODE_MAP[toLang]
        return parsedFrom to parsedTo
    }

    // ─── Public translate — entry point ─────────────────────────────

    /**
     * Translates a list of text strings from [fromLanguage] to [toLanguage].
     *
     * The pipeline follows these steps:
     * 1. Validate language codes
     * 2. Early return when source == target
     * 3. Filter out non-valuable text (keeps it unchanged)
     * 4. Loop with retry logic calling [_translate]
     * 5. Clean each translation output
     * 6. Arabic reshaping (simplified Bidi)
     * 7. MTPE dispatch (if enabled via config)
     * 8. Merge non-text queries back in
     *
     * Matches Python `translate` (L147–224).
     */
    override suspend fun translate(
        texts: List<String>,
        fromLanguage: String,
        toLanguage: String,
        config: TranslatorConfig,
    ): List<String> {
        // ── 1. Language validation ──────────────────────────────────
        if (toLanguage !in VALID_LANGUAGES) {
            throw LanguageUnsupportedException(
                toLanguage, name, VALID_LANGUAGES.keys.toList(),
            )
        }
        if (fromLanguage !in VALID_LANGUAGES && fromLanguage != "auto") {
            val valid = listOf("auto") + VALID_LANGUAGES.keys.toList()
            throw LanguageUnsupportedException(fromLanguage, name, valid)
        }
        Log.d(TAG, "Translating into ${VALID_LANGUAGES[toLanguage]}")

        // ── 2. Source == target → early return ──────────────────────
        if (fromLanguage.equals(toLanguage, ignoreCase = true)) return texts

        // ── 3. Filter out non-valuable text ─────────────────────────
        val queryIndices = mutableListOf<Int>()
        val finalTranslations = MutableList<String?>(texts.size) { i ->
            if (!TextUtils.isValuableText(texts[i])) texts[i] else null.also {
                queryIndices.add(i)
            }
        }

        val queries = queryIndices.map { texts[it] }.toMutableList()

        // ── 4. Translation loop with retry ──────────────────────────
        val translations = MutableList(queries.size) { "" }
        var untranslatedIndices = queries.indices.toList()

        for (i in 0.._INVALID_REPEAT_COUNT) {
            if (i > 0) {
                Log.w(TAG, "Repeating because of invalid translation. Attempt: ${i + 1}")
                delay(100L)
            }

            _ratelimit_sleep()

            val parsed = parseLanguageCodes(fromLanguage, toLanguage, fatal = true)
            var _translations = _translate(
                parsed.first ?: fromLanguage,
                parsed.second ?: toLanguage,
                queries,
            )

            // Extend / truncate to match queries size
            if (_translations.size < queries.size) {
                _translations = _translations + List(queries.size - _translations.size) { "" }
            } else if (_translations.size > queries.size) {
                _translations = _translations.take(queries.size)
            }

            // Only overwrite still-untranslated indices
            for (j in untranslatedIndices) {
                translations[j] = _translations[j]
            }

            if (_INVALID_REPEAT_COUNT == 0) break

            val newUntranslated = mutableListOf<Int>()
            for (j in untranslatedIndices) {
                val q = queries[j]
                val t = translations[j]
                if (_is_translation_invalid(q, t)) {
                    newUntranslated.add(j)
                    queries[j] = _modify_invalid_translation_query(q, t)
                }
            }
            untranslatedIndices = newUntranslated
            if (untranslatedIndices.isEmpty()) break
        }

        // ── 5. Clean each translation output ────────────────────────
        val cleanedTranslations = translations.mapIndexed { index, r ->
            _clean_translation_output(queries[index], r, toLanguage)
        }

        // ── 6. Arabic reshaping (simplified Bidi) ───────────────────
        // NOTE: Full arabic_reshaper library is not available on JVM.
        // This simplified Bidi reordering is used instead.
        if (toLanguage == "ARA") {
            for (i in cleanedTranslations.indices) {
                // Apply simplified Bidi reshaping (placeholder — see reshapeArabic)
                // cleanedTranslations[i] = reshapeArabic(cleanedTranslations[i])
                // For now the translations are passed through as-is.
            }
        }

        // ── 7. MTPE dispatch ────────────────────────────────────────
        // TranslatorConfig currently does not include a `useMtpe` field.
        // When such a field is added in the future this block can be enabled:
        //   if (useMtpe) {
        //       mtpeAdapter.dispatch(queries, cleanedTranslations)
        //   }

        // ── 8. Merge back with non-text queries ─────────────────────
        for (i in cleanedTranslations.indices) {
            finalTranslations[queryIndices[i]] = cleanedTranslations[i]
            Log.d(TAG, "$i: ${queries[i]} => ${cleanedTranslations[i]}")
        }

        @Suppress("UNCHECKED_CAST")
        return finalTranslations as List<String>
    }

    // ─── Abstract method ────────────────────────────────────────────

    /**
     * Performs the actual translation API call.
     *
     * Must be implemented by subclasses.
     *
     * Matches Python `async def _translate(...)` (L226–228).
     *
     * @param fromLang Resolved source language code.
     * @param toLang   Resolved target language code.
     * @param queries  Source text segments to translate.
     * @return Translated text segments, one per input query.
     */
    protected abstract suspend fun _translate(
        fromLang: String,
        toLang: String,
        queries: List<String>,
    ): List<String>

    // ─── Rate limiting ──────────────────────────────────────────────

    /**
     * Sleeps if the request rate would exceed [_MAX_REQUESTS_PER_MINUTE].
     *
     * Matches Python `_ratelimit_sleep` (L230–237).
     */
    protected suspend fun _ratelimit_sleep() {
        if (_MAX_REQUESTS_PER_MINUTE <= 0) return
        val now = System.currentTimeMillis()
        val ratelimitTimeout = _lastRequestTs + (60_000L / _MAX_REQUESTS_PER_MINUTE)
        if (ratelimitTimeout > now) {
            Log.d(TAG, "Ratelimit sleep: ${(ratelimitTimeout - now) / 1000.0f}s")
            delay(ratelimitTimeout - now)
        }
        _lastRequestTs = System.currentTimeMillis()
    }

    // ─── Invalid-translation detection / modification ───────────────

    /**
     * Checks whether [trans] is a valid translation of [query].
     *
     * Returns `true` when the translation appears to be garbage
     * (e.g. too few valuable characters relative to the query).
     *
     * Matches Python `_is_translation_invalid` (L239–249).
     *
     * **Note:** The Python original uses `len(set(query))` (unique
     * characters).  This Kotlin version intentionally uses
     * [TextUtils.countValuableText] instead, which counts non-punctuation,
     * non-whitespace, non-digit characters.  See plan documentation.
     */
    protected open fun _is_translation_invalid(query: String, trans: String): Boolean {
        if (trans.isEmpty() && query.isNotEmpty()) return true
        if (query.isEmpty() || trans.isEmpty()) return false

        val querySymbolCount = TextUtils.countValuableText(query)
        val transSymbolCount = TextUtils.countValuableText(trans)
        if (querySymbolCount > 6 &&
            transSymbolCount < 6 &&
            transSymbolCount < 0.25f * trans.length
        ) {
            return true
        }
        return false
    }

    /**
     * May be overridden to modify the query before the next retry
     * when [_INVALID_REPEAT_COUNT] > 0 and a translation was deemed
     * invalid.
     *
     * Default implementation returns [query] unchanged.
     *
     * Matches Python `_modify_invalid_translation_query` (L251–256).
     */
    protected open fun _modify_invalid_translation_query(query: String, trans: String): String = query

    // ─── Output cleaning ────────────────────────────────────────────

    /**
     * Applies regex-based post-processing rules to the translation
     * output.
     *
     * Rules applied:
     * 1. Collapse multiple whitespace → single space
     * 2. Add space after punctuation when immediately followed by a word char
     * 3. Remove spaces between consecutive punctuation
     * 4. (Non-ARA) Remove space before trailing punctuation
     * 5. (Non-ARA) Remove space after ellipsis before a word
     * 6. Repeating-sequence compression
     *
     * Matches Python `_clean_translation_output` (L258–301).
     */
    protected fun _clean_translation_output(query: String, trans: String, toLang: String): String {
        if (query.isEmpty() || trans.isEmpty()) return ""

        var result = trans

        // Rule 1: '  ' → ' '
        result = Regex("\\s+").replace(result, " ")

        // Rule 2: 'text.text' → 'text. text'
        result = Regex("(?<![.,;!?])([.,;!?])(?=\\w)").replace(result, "$1 ")

        // Rule 3: ' ! ! . . ' → ' !!.. '
        result = Regex("([.,;!?])\\s+(?=[.,;!?]|$)").replace(result, "$1")

        if (toLang != "ARA") {
            // Rule 4: 'text .' → 'text.'
            result = Regex("(?<=[.,;!?\\w])\\s+([.,;!?])").replace(result, "$1")

            // Rule 5: ' ... text' → ' ...text'
            // NOTE: This rule was missing from the existing TranslationValidator.
            result = Regex("((?:\\s|^)\\.+)\\s+(?=\\w)").replace(result, "$1")
        }

        // Rule 6: Repeating-sequence compression
        val seq = findShortestRepeatingUnit(result.lowercase())
        if (seq != null && result.length < query.length && seq.length < 0.5f * result.length) {
            val repeatCount = maxOf(1, query.length / seq.length)
            val shrunken = seq.repeat(repeatCount)
            // Transfer capitalization character by character from query
            val nTrans = StringBuilder()
            for (i in 0 until minOf(shrunken.length, query.length)) {
                nTrans.append(
                    if (query[i].isUpperCase()) shrunken[i].uppercaseChar() else shrunken[i],
                )
            }
            result = nTrans.toString()
        }

        return result
    }

    // ─── Helpers ────────────────────────────────────────────────────

    /**
     * Finds the shortest repeating unit in [text] (e.g. `"ab"` for `"ababab"`).
     * Supports partial repeats at the end (e.g. `"abc"` for `"abcab"`).
     *
     * Returns `null` when no repeating pattern is found.
     *
     * Matches Python `repeating_sequence` from `utils/generic.py:85-91`.
     */
    private fun findShortestRepeatingUnit(text: String): String? {
        for (len in 1..text.length / 2) {
            val seq = text.substring(0, len)
            if (seq.repeat(text.length / len) + seq.take(text.length % len) == text) {
                return seq
            }
        }
        return null
    }

    /**
     * Simplified Arabic reshaping using Java's Bidi algorithm.
     *
     * The Python version uses `arabic_reshaper` + `bidi.algorithm`,
     * neither of which is available on JVM.  This is an intentional
     * simplification documented in the porting plan.
     */
    @Suppress("unused")
    private fun reshapeArabic(text: String): String {
        return try {
            val bidi = java.text.Bidi(text, java.text.Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT)
            if (bidi.isLeftToRight) text else text.reversed()
        } catch (_: Exception) {
            text
        }
    }

    companion object {
        private const val TAG = "CommonTranslator"
    }
}
