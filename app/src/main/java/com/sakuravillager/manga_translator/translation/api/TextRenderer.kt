package com.sakuravillager.manga_translator.translation.api

import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.data.TextBlock
import com.sakuravillager.manga_translator.translation.data.config.RendererConfig

interface TextRenderer : PipelineModule {
    override val name: String
    suspend fun render(
        bitmap: Bitmap,
        textRegions: List<TextBlock>,
        config: RendererConfig,
    ): Bitmap
}
