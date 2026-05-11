package com.sakuravillager.manga_translator.translation.api

import android.util.Log

/**
 * Abstract base for all inference modules (detectors, OCR, translators, etc.).
 * Matches Python's InfererModule from utils/inference.py (L24-60).
 */
abstract class InfererModule : PipelineModule {
    protected val loggerTag: String
        get() = this::class.simpleName ?: "InfererModule"

    protected fun log(level: Int, message: String) {
        when (level) {
            Log.DEBUG -> Log.d(loggerTag, message)
            Log.INFO -> Log.i(loggerTag, message)
            Log.WARN -> Log.w(loggerTag, message)
            Log.ERROR -> Log.e(loggerTag, message)
        }
    }

    /**
     * Optional configuration parsing from module-specific config.
     * Subclasses override to extract settings from config objects.
     * Matches Python: parse_args(self, args: TranslatorConfig) (L61).
     */
    open fun parseArgs(config: Any) {
        // Default no-op
    }
}
