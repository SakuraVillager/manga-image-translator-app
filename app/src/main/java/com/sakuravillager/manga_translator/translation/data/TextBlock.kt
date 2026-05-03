package com.sakuravillager.manga_translator.translation.data

import android.graphics.PointF
import android.graphics.RectF

data class TextBlock(
    val lines: List<List<PointF>> = emptyList(),
    val texts: List<String> = emptyList(),
    val text: String = "",
    val translation: String = "",
    val language: String? = null,
    val fontSize: Float = 0f,
    val angle: Float = 0f,
    val fgColor: Int? = null,
    val bgColor: Int? = null,
    val direction: TextDirection = TextDirection.AUTO,
    val alignment: TextAlignment = TextAlignment.AUTO,
    val lineSpacing: Float = 0f,
) {
    val isHorizontal: Boolean get() = true
    val isVertical: Boolean get() = false
    val minRect: RectF get() = RectF()
    val center: PointF get() = PointF()
}
