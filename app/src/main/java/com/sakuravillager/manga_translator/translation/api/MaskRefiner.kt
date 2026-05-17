package com.sakuravillager.manga_translator.translation.api

import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.data.TextBlock

interface MaskRefiner : PipelineModule {
    override val name: String
    suspend fun refine(
        textRegions: List<TextBlock>,
        bitmap: Bitmap,
        rawMask: Bitmap?,
        kernelSize: Int = 3,
        dilationOffset: Int = 20,
        ignoreBubble: Int = 0,
    ): Bitmap
}
