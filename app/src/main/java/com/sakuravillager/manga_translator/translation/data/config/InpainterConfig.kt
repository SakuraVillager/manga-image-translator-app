package com.sakuravillager.manga_translator.translation.data.config

data class InpainterConfig(
    val inpainter: InpainterType = InpainterType.AOT,
    val inpaintingSize: Int = 2048,
    /** Floating-point precision for inpainting model inference. Matches Python `--inpainting_precision`. */
    val inpaintingPrecision: String? = null,
)
