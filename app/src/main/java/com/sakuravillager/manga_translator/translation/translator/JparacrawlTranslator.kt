package com.sakuravillager.manga_translator.translation.translator

import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.model.ModelInfo
import com.sakuravillager.manga_translator.translation.model.ModelRegistry
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager

/**
 * JParaCrawl ONNX translator — Japanese↔English neural machine translation.
 *
 * JParaCrawl is a transformer-based translation model trained on the
 * JParaCrawl corpus (large-scale Japanese-English parallel data).  It shares
 * the same T5-based encoder-decoder architecture as [SugoiTranslator], with
 * a single ONNX model for simplified inference.
 *
 * ### Differences from Sugoi V4
 * | Aspect         | Sugoi V4                    | JParaCrawl                    |
 * |----------------|-----------------------------|-------------------------------|
 * | Training data  | Manga translation pairs     | JParaCrawl corpus (web data)  |
 * | Primary use    | JPN→ENG manga-specialized   | General JPN↔ENG translation   |
 * | Vocabulary     | ~32k (manga-optimized)      | ~32k (general-purpose)        |
 *
 * ### ONNX Model Files
 * - `jparacrawl.onnx` — combined encoder-decoder ONNX model
 * - `jparacrawl_tokenizer.spm` — SentencePiece tokenizer model
 *
 * ### Supported Language Pairs
 * - JPN ↔ ENG (bidirectional)
 *
 * @param modelDownloadManager Used to download and verify model files.
 * @param onnxSessionManager  Singleton ONNX session factory.
 */
open class JparacrawlTranslator(
    modelDownloadManager: ModelDownloadManager,
    onnxSessionManager: OnnxSessionManager,
) : SugoiTranslator(modelDownloadManager, onnxSessionManager) {

    // ─── Overrides ────────────────────────────────────────────────────────

    /** Model name for JParaCrawl (used for file lookups). */
    override val modelName: String = "jparacrawl"

    /**
     * ONNX model metadata for JParaCrawl.
     */
    override val modelInfo: ModelInfo
        get() = ModelRegistry.JPARACRAWL_MODEL

    /**
     * JParaCrawl supports JPN ↔ ENG bidirectional translation.
     */
    override val _LANGUAGE_CODE_MAP: Map<String, String> = mapOf(
        "JPN" to "ja",
        "ENG" to "en",
    )
}
