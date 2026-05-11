package com.sakuravillager.manga_translator.translation.data.config

data class InpainterConfig(
    val inpainter: InpainterType = InpainterType.LAMA_LARGE,
    val inpaintingSize: Int = 2048,
)
