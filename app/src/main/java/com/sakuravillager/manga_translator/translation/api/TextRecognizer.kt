package com.sakuravillager.manga_translator.translation.api

import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.config.OcrConfig

interface TextRecognizer : PipelineModule {
    override val name: String
    suspend fun recognize(
        bitmap: Bitmap,
        textlines: List<Quadrilateral>,
        config: OcrConfig,
    ): List<Quadrilateral>
}
