package com.sakuravillager.manga_translator.translation.data

import android.graphics.Bitmap

data class DetectionResult(
    val textlines: List<Quadrilateral>,
    val rawMask: Bitmap? = null,
    val mask: Bitmap? = null,
)
