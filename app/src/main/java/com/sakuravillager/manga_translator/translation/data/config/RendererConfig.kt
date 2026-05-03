package com.sakuravillager.manga_translator.translation.data.config

import com.sakuravillager.manga_translator.translation.data.TextAlignment
import com.sakuravillager.manga_translator.translation.data.TextDirection

data class RendererConfig(
    val renderer: RendererType = RendererType.DEFAULT,
    val alignment: TextAlignment = TextAlignment.AUTO,
    val fontSizeOffset: Int = 0,
    val fontSizeMinimum: Int = -1,
    val direction: TextDirection = TextDirection.AUTO,
    val disableFontBorder: Boolean = false,
    val fontColor: String? = null,
    val lineSpacing: Float? = null,
    val rtl: Boolean = true,
)
