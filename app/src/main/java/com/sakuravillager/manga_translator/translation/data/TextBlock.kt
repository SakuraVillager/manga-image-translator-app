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
    val minRect: RectF get() {
        val allPoints = lines.flatten()
        if (allPoints.isEmpty()) return RectF()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        for (p in allPoints) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return RectF(minX, minY, maxX, maxY)
    }
    val center: PointF get() = PointF()
}
