package com.sakuravillager.manga_translator.translation.data.config

data class UpscaleConfig(
    val upscaler: UpscalerType = UpscalerType.BASIC,
    val upscaleRatio: Int? = null,
    val revertUpscaling: Boolean = false,
)