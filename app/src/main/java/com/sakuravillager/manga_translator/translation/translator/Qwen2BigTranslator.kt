package com.sakuravillager.manga_translator.translation.translator

import android.util.Log
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.model.ModelInfo
import com.sakuravillager.manga_translator.translation.model.ModelRegistry
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager

/**
 * Qwen2 7B ONNX-based LLM translator (large variant).
 *
 * Extends [Qwen2Translator] with a larger model (7B vs 1.5B parameters)
 * for higher translation quality at the cost of increased memory and
 * compute requirements.
 *
 * ### ⚠️ Resource Warning
 * Qwen2-7B requires ~14 GB of RAM in fp32 and ~4 GB in int8.  On devices
 * with less than 6 GB of RAM this translator should not be used — consider
 * [Qwen2Translator] (1.5B) with int8 quantization instead.
 *
 * ### Differences from Qwen2Translator (1.5B)
 * - [modelInfo] points to [ModelRegistry.QWEN2_BIG_MODEL]
 * - Int8 quantization is NOT enabled by default (the model is already
 *   very large and quantisation may significantly degrade quality)
 * - Uses the same generation parameters as the base class
 *
 * @param modelDownloadManager Used to download and verify the ONNX model.
 * @param onnxSessionManager   Singleton ONNX session factory.
 */
class Qwen2BigTranslator(
    modelDownloadManager: ModelDownloadManager,
    onnxSessionManager: OnnxSessionManager,
) : Qwen2Translator(modelDownloadManager, onnxSessionManager) {

    companion object {
        private const val TAG = "Qwen2BigTranslator"
    }

    init {
        // Do NOT enable int8 by default for the 7B model — quantisation would
        // significantly degrade the model's already-expensive quality advantage.
        // Users who need int8 can enable it via configuration.
        useInt8 = false

        Log.d(TAG, "Qwen2BigTranslator initialised (model: ${ModelRegistry.QWEN2_BIG_MODEL.name})")
    }

    /**
     * Points to the 7B model entry in [ModelRegistry].
     */
    override val modelInfo: ModelInfo
        get() = ModelRegistry.QWEN2_BIG_MODEL
}
