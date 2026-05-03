package com.sakuravillager.manga_translator.translation.api

import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.TextBlock

interface TextlineMerger : PipelineModule {
    override val name: String
    suspend fun merge(
        textlines: List<Quadrilateral>,
        imageWidth: Int,
        imageHeight: Int,
    ): List<TextBlock>
}
