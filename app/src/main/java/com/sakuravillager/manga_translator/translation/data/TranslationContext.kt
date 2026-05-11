package com.sakuravillager.manga_translator.translation.data

import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.data.config.TranslationConfig

data class TranslationContext(
    val inputBitmap: Bitmap,
    val config: TranslationConfig,
    var originalBitmap: Bitmap? = null,  // Store original for final output comparison
    var imgColorized: Bitmap? = null,
    var imgUpscaled: Bitmap? = null,
    var imgRgb: Bitmap? = null,
    var imgAlpha: Bitmap? = null,
    var textlines: MutableList<Quadrilateral> = mutableListOf(),
    var rawMask: Bitmap? = null,
    var refinedMask: Bitmap? = null,
    var gimpMask: Bitmap? = null,
    var textRegions: MutableList<TextBlock> = mutableListOf(),
    var imgInpainted: Bitmap? = null,
    var imgRendered: Bitmap? = null,
    var resultBitmap: Bitmap? = null,
    var fromLanguage: String? = null,
    var debugFolder: String? = null,
    var usePlaceholder: Boolean = false,
    var debugImages: MutableMap<String, Bitmap> = mutableMapOf(),
)
