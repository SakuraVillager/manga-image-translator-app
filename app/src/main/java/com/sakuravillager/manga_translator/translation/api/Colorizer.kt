package com.sakuravillager.manga_translator.translation.api

import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.data.config.ColorizerConfig

interface Colorizer : PipelineModule {
    override val name: String

    suspend fun colorize(
        bitmap: Bitmap,
        config: ColorizerConfig,
    ): Bitmap
}