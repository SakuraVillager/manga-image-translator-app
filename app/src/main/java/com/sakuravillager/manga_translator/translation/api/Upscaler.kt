package com.sakuravillager.manga_translator.translation.api

import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.data.config.UpscaleConfig

interface Upscaler : PipelineModule {
    override val name: String

    suspend fun upscale(
        bitmap: Bitmap,
        config: UpscaleConfig,
    ): Bitmap
}