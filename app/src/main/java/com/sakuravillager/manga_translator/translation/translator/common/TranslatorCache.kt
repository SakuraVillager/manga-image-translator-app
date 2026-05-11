package com.sakuravillager.manga_translator.translation.translator.common

import com.sakuravillager.manga_translator.translation.data.config.TranslatorType
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton cache for translator instances.
 * Mirrors Python's `translator_cache` in `manga_translator/translators/__init__.py`.
 *
 * Thread-safe via [ConcurrentHashMap] — [getTranslator] uses `computeIfAbsent`
 * internally so the factory is invoked at most once per key under concurrent access.
 */
val TRANSLATOR_CACHE = ConcurrentHashMap<TranslatorType, Any>()

/**
 * Returns a cached translator instance for the given [key], or creates one
 * using the provided [factory] lambda and stores it in the cache.
 *
 * Equivalent to Python's `get_translator()` in `__init__.py:71-77`:
 * ```
 * if not translator_cache.get(key):
 *     translator = TRANSLATORS[key]
 *     translator_cache[key] = translator(*args, **kwargs)
 * return translator_cache[key]
 * ```
 *
 * @param key     The [TranslatorType] used as the cache key.
 * @param factory A lambda that creates the translator instance when invoked.
 * @return The cached (or newly created) translator instance.
 */
fun getTranslator(key: TranslatorType, factory: () -> Any): Any {
    return TRANSLATOR_CACHE.getOrPut(key) { factory() }
}

/**
 * Clears all cached translator instances.
 *
 * Equivalent to resetting Python's `translator_cache = {}`.
 */
fun clearTranslatorCache() {
    TRANSLATOR_CACHE.clear()
}
