package com.sakuravillager.manga_translator.translation.pipeline

/**
 * Can be thrown from within a progress callback to prematurely
 * terminate the translation.
 *
 * Matches Python's TranslationInterrupt
 * (manga_translator/manga_translator.py L56-61).
 */
class TranslationInterrupt(message: String? = null) : Exception(message)
