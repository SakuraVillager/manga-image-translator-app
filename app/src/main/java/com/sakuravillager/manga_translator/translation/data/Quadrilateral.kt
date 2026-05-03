package com.sakuravillager.manga_translator.translation.data

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF

data class Quadrilateral(
    val points: List<PointF>,
    val text: String = "",
    val probability: Float = 0f,
    val direction: TextDirection = TextDirection.AUTO,
    val fgColor: Int? = null,
    val bgColor: Int? = null,
) {
    val boundingBox: RectF get() = RectF()
    val center: PointF get() = PointF()
    val angle: Float get() = 0f
    val area: Float get() = 0f
    val aspectRatio: Float get() = 0f
    val fontSize: Float get() = 0f
    val structure: List<PointF> get() = emptyList()

    fun distance(other: Quadrilateral, rho: Float = 0.5f): Float = 0f

    fun getTransformedRegion(
        bitmap: Bitmap,
        direction: TextDirection,
        textHeight: Int,
    ): Bitmap = bitmap

    companion object {
        fun sortPoints(pts: List<PointF>): List<PointF> = pts
    }
}
