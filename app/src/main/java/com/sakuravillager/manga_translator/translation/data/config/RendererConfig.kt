package com.sakuravillager.manga_translator.translation.data.config

import com.sakuravillager.manga_translator.translation.data.TextAlignment
import com.sakuravillager.manga_translator.translation.data.TextDirection

data class RendererConfig(
    val renderer: RendererType = RendererType.DEFAULT,
    val alignment: TextAlignment = TextAlignment.AUTO,
    val fontSizeOffset: Int = 0,
    val fontSizeMinimum: Int = -1,
    val fontSize: Int? = null,
    val direction: TextDirection = TextDirection.AUTO,
    val disableFontBorder: Boolean = false,
    val fontColor: String? = null,
    val lineSpacing: Float? = null,
    val uppercase: Boolean = false,
    val lowercase: Boolean = false,
    val noHyphenation: Boolean = false,
    val rtl: Boolean = true,
    /** Optional render mask: when non-null, only regions inside the mask are rendered. Matches Python `render_mask`. */
    val renderMask: android.graphics.Bitmap? = null,
)
