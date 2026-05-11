package com.sakuravillager.manga_translator.translation.data.config

data class OcrConfig(
    val ocrEngine: OcrEngineType = OcrEngineType.MODEL_48PX,
    val minTextLength: Int = 0,
    val ignoreBubble: Int = 0,
    val prob: Float? = null,
    val useMocrMerge: Boolean = false,
    val debugSaveCrops: Boolean = false,
    val debugSaveTokens: Boolean = false,
)
