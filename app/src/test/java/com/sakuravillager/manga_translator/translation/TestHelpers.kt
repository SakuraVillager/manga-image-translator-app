package com.sakuravillager.manga_translator.translation

import android.graphics.PointF

/**
 * Creates a [PointF] with the given coordinates using reflection.
 *
 * Required because the mockable Android jar (`isReturnDefaultValues = true`) does not support
 * the `PointF(Float, Float)` constructor — it returns a zero-valued point. Reflection bypasses
 * this limitation in JVM unit tests.
 *
 * Shared across [DefaultTextlineMergerTest], [QuadrilateralGeometryTest], [GeometryUtilsTest]
 * and new parity tests to avoid duplicating the same helper in every file.
 */
fun pt(x: Float, y: Float): PointF {
    val p = PointF()
    PointF::class.java.getField("x").setFloat(p, x)
    PointF::class.java.getField("y").setFloat(p, y)
    return p
}

/**
 * Creates a rectangular quadrilateral with the given bounding box corners:
 * TL = (left, top), TR = (right, top), BR = (right, bottom), BL = (left, bottom).
 */
fun rectQuad(left: Float, top: Float, right: Float, bottom: Float): List<PointF> = listOf(
    pt(left, top), pt(right, top), pt(right, bottom), pt(left, bottom),
)
