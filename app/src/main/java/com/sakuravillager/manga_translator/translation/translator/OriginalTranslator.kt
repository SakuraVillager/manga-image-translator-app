package com.sakuravillager.manga_translator.translation.translator

import com.sakuravillager.manga_translator.translation.translator.common.CommonTranslator
import com.sakuravillager.manga_translator.translation.translator.common.VALID_LANGUAGES

/**
 * A translator that returns queries unchanged (identity / passthrough).
 *
 * Ported 1:1 from Python `manga_translator/translators/original.py` (`OriginalTranslator`).
 *
 * This is useful for debugging or when the original text should be preserved
 * as the "translation" (e.g. source language is already the target language).
 */
class OriginalTranslator : CommonTranslator() {

    override val _LANGUAGE_CODE_MAP: Map<String, String> =
        VALID_LANGUAGES.keys.associateWith { it }

    override suspend fun _translate(
        fromLang: String,
        toLang: String,
        queries: List<String>,
    ): List<String> = queries
}
