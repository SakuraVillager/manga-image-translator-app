package com.sakuravillager.manga_translator.translation.api

import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.data.DetectionResult
import com.sakuravillager.manga_translator.translation.data.config.DetectorConfig

interface TextDetector : PipelineModule {
    override val name: String
    suspend fun detect(
        bitmap: Bitmap,
        config: DetectorConfig,
    ): DetectionResult
}
