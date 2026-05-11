package com.sakuravillager.manga_translator.translation.translator

import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.model.ModelInfo
import com.sakuravillager.manga_translator.translation.model.ModelRegistry
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager

/**
 * JParaCrawl Big ONNX translator — larger variant of [JparacrawlTranslator].
 *
 * JParaCrawl Big uses a larger transformer architecture (more layers and wider
 * hidden dimensions) compared to the base JParaCrawl model, providing better
 * translation quality at the cost of higher memory usage and slower inference.
 *
 * ### Differences from JParaCrawl Base
 * | Property           | JParaCrawl Base | JParaCrawl Big  |
 * |--------------------|-----------------|-----------------|
 * | Parameters         | ~300M           | ~1.2B           |
 * | Vocabulary         | ~32k            | ~32k            |
 *
 * ### ONNX Model Files
 * - `jparacrawl_big.onnx` — combined encoder-decoder ONNX model
 * - `jparacrawl_big_tokenizer.spm` — SentencePiece tokenizer model (shared vocab)
 *
 * ### Supported Language Pairs
 * - JPN ↔ ENG (bidirectional)
 *
 * @param modelDownloadManager Used to download and verify model files.
 * @param onnxSessionManager  Singleton ONNX session factory.
 */
class JparacrawlBigTranslator(
    modelDownloadManager: ModelDownloadManager,
    onnxSessionManager: OnnxSessionManager,
) : JparacrawlTranslator(modelDownloadManager, onnxSessionManager) {

    // ─── Overrides ────────────────────────────────────────────────────────

    /** Model name for JParaCrawl Big (used for file lookups). */
    override val modelName: String = "jparacrawl_big"

    /**
     * ONNX model metadata for JParaCrawl Big.
     */
    override val modelInfo: ModelInfo
        get() = ModelRegistry.JPARACRAWL_BIG_MODEL
}
