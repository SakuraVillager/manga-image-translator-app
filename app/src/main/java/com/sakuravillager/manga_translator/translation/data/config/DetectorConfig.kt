package com.sakuravillager.manga_translator.translation.data.config

data class DetectorConfig(
    val detector: DetectorType = DetectorType.CTD,
    val detectionSize: Int = 1024,
    val textThreshold: Float = 0.5f,
    val boxThreshold: Float = 0.6f,
    val unclipRatio: Float = 2.3f,
    val detRotate: Boolean = false,
    val detAutoRotate: Boolean = false,
    val detInvert: Boolean = false,
    val detGammaCorrect: Boolean = false,
)
