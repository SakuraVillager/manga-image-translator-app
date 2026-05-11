package com.sakuravillager.manga_translator.translation.translator

import com.sakuravillager.manga_translator.translation.translator.common.CommonTranslator
import com.sakuravillager.manga_translator.translation.translator.common.VALID_LANGUAGES

/**
 * A translator that returns empty strings for all queries.
 *
 * Ported 1:1 from Python `manga_translator/translators/none.py` (`NoneTranslator`).
 *
 * This is useful for testing or when translations should be hidden/removed.
 */
class NoOpTranslator : CommonTranslator() {

    override val _LANGUAGE_CODE_MAP: Map<String, String> =
        VALID_LANGUAGES.keys.associateWith { it }

    override suspend fun _translate(
        fromLang: String,
        toLang: String,
        queries: List<String>,
    ): List<String> = queries.map { "" }
}
