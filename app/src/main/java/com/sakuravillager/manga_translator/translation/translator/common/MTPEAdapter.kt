package com.sakuravillager.manga_translator.translation.translator.common

/**
 * Coroutine-compatible callback interface for Machine Translation Post-Editing (MTPE).
 *
 * Mirrors the Python [MTPEAdapter](https://github.com/kanaad-v/manga-image-translator/blob/main/manga_translator/translators/common.py#L85)
 * which interactively prompts the user to edit each translation.
 *
 * On Android, the callback is expected to dispatch the (query, translation) pairs
 * to a UI dialog or notification and return the user-edited results.
 */
interface MTPEAdapter {
    /**
     * @param queries      The original source-language text segments.
     * @param translations The raw machine-translated text segments.
     * @return The post-edited translations (one per input pair).
     */
    suspend fun dispatch(queries: List<String>, translations: List<String>): List<String>
}

/**
 * No-op implementation that returns the original translations unchanged.
 * Used when MTPE is disabled or not supported.
 */
object NoOpMTPEAdapter : MTPEAdapter {
    override suspend fun dispatch(
        queries: List<String>,
        translations: List<String>,
    ): List<String> = translations
}
