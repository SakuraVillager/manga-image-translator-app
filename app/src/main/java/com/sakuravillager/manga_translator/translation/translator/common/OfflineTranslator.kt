package com.sakuravillager.manga_translator.translation.translator.common

/**
 * Abstract base class for offline / local-model translators.
 *
 * Ported 1:1 from Python `manga_translator/translators/common.py` (lines 303–328).
 *
 * [OfflineTranslator] extends [CommonTranslator] and provides a standard
 * lifecycle for managing local models (load / reload / unload).
 *
 * Subclasses must implement:
 * - [_infer]  – the actual model inference
 * - [_load]   – model initialisation (called by [load])
 * - [_unload] – model cleanup (called by [unload])
 */
abstract class OfflineTranslator : CommonTranslator() {

    companion object {
        /**
         * Subdirectory within the models folder where translator models
         * are stored.
         *
         * Matches Python `_MODEL_SUB_DIR = 'translators'` (L304).
         */
        const val _MODEL_SUB_DIR = "translators"
    }

    // ─── Translation ────────────────────────────────────────────────

    /**
     * Delegates translation to [_infer].
     *
     * Matches Python `_translate(self, *args, **kwargs)`
     * → `await self.infer(*args, **kwargs)` (L306–307).
     */
    override suspend fun _translate(
        fromLang: String,
        toLang: String,
        queries: List<String>,
    ): List<String> = _infer(fromLang, toLang, queries)

    /**
     * Runs the model inference on the given [queries].
     *
     * Must be implemented by subclasses.
     *
     * Matches Python `async def _infer(self, from_lang, to_lang, queries)` (L309–311).
     *
     * @param fromLang Source language code.
     * @param toLang   Target language code.
     * @param queries  Source text segments to translate.
     * @return Translated text segments, one per input query.
     */
    protected abstract suspend fun _infer(
        fromLang: String,
        toLang: String,
        queries: List<String>,
    ): List<String>

    // ─── Lifecycle ──────────────────────────────────────────────────

    /**
     * Loads the model for the given language pair on the specified [device].
     *
     * Calls [parseLanguageCodes] to resolve language codes, then delegates
     * to [_load].
     *
     * Matches Python `async def load(self, from_lang, to_lang, device)` (L313–314).
     */
    suspend fun load(fromLang: String, toLang: String, device: String) {
        val (parsedFrom, parsedTo) = parseLanguageCodes(fromLang, toLang, fatal = true)
        @Suppress("UNCHECKED_CAST")
        _load(parsedFrom as String, parsedTo as String, device)
    }

    /**
     * Initialises the model for the given language pair on the specified [device].
     *
     * Must be implemented by subclasses.
     *
     * Matches Python `async def _load(self, from_lang, to_lang, device)` (L317–318).
     */
    protected abstract suspend fun _load(
        fromLang: String,
        toLang: String,
        device: String,
    )

    /**
     * Reloads the model by unloading and then loading it again.
     *
     * Matches Python `async def reload(self, from_lang, to_lang, device)` (L320–321).
     */
    suspend fun reload(fromLang: String, toLang: String, device: String) {
        unload()
        load(fromLang, toLang, device)
    }

    /**
     * Unloads the model.
     *
     * Delegates to [_unload].
     *
     * Matches Python `async def unload(self, device)` (L327–328).
     */
    suspend fun unload() {
        _unload()
    }

    /**
     * Releases / cleans up the model resources.
     *
     * Must be implemented by subclasses.
     */
    protected abstract suspend fun _unload()
}
