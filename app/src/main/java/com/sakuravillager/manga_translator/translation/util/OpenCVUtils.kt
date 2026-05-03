package com.sakuravillager.manga_translator.translation.util

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat

/**
 * Attempts to initialize OpenCV using [OpenCVLoader.initLocal] (preferred),
 * falling back to the deprecated [OpenCVLoader.initDebug] if needed.
 *
 * @return `true` if OpenCV was successfully loaded, `false` otherwise.
 */
fun ensureOpenCVLoaded(): Boolean {
    return try {
        OpenCVLoader.initLocal()
    } catch (e: Exception) {
        try {
            OpenCVLoader.initDebug()
        } catch (e2: Exception) {
            false
        }
    }
}

/**
 * Converts an Android [Bitmap] to an OpenCV [Mat] in-place.
 *
 * @param bitmap Source bitmap (ARGB_8888).
 * @return A new [Mat] containing the bitmap pixel data.
 */
fun bitmapToMat(bitmap: Bitmap): Mat {
    val mat = Mat()
    Utils.bitmapToMat(bitmap, mat)
    return mat
}

/**
 * Converts an OpenCV [Mat] to an Android [Bitmap].
 *
 * @param mat Source matrix (preferably CV_8UC4 for ARGB output).
 * @return A new ARGB_8888 [Bitmap] containing the matrix pixel data.
 */
fun matToBitmap(mat: Mat): Bitmap {
    val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(mat, bitmap)
    return bitmap
}
