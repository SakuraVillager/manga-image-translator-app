package com.sakuravillager.manga_translator.translation.data

import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.data.config.TranslationConfig

data class TranslationContext(
    val input_bitmap: Bitmap,
    val config: TranslationConfig,
    var original_bitmap: Bitmap? = null,  // Store original for final output comparison
    var img_colorized: Bitmap? = null,
    var img_upscaled: Bitmap? = null,
    var img_rgb: Bitmap? = null,
    var img_alpha: Bitmap? = null,
    var textlines: MutableList<Quadrilateral> = mutableListOf(),
    var raw_mask: Bitmap? = null,
    var refined_mask: Bitmap? = null,
    var gimp_mask: Bitmap? = null,
    var text_regions: MutableList<TextBlock> = mutableListOf(),
    var img_inpainted: Bitmap? = null,
    var img_rendered: Bitmap? = null,
    var result_bitmap: Bitmap? = null,
    var from_language: String? = null,
    var render_mask: Bitmap? = null,
    var debug_folder: String? = null,
    var use_placeholder: Boolean = false,
    var debug_images: MutableMap<String, Bitmap> = mutableMapOf(),
)
