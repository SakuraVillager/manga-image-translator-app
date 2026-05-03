package com.sakuravillager.manga_translator.translation.api

import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.data.config.InpainterConfig

interface Inpainter : PipelineModule {
    override val name: String
    suspend fun inpaint(
        bitmap: Bitmap,
        mask: Bitmap,
        config: InpainterConfig,
    ): Bitmap
}
