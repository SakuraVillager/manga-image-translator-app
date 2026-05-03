package com.sakuravillager.manga_translator.translation.data.config

data class OcrConfig(
    val ocrEngine: OcrEngineType = OcrEngineType.MODEL_48PX,
    val minTextLength: Int = 0,
    val ignoreBubble: Int = 0,
)
