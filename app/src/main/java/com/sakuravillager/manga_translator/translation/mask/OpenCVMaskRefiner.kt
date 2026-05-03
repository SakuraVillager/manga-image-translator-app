package com.sakuravillager.manga_translator.translation.mask

import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.api.MaskRefiner
import com.sakuravillager.manga_translator.translation.data.TextBlock
import com.sakuravillager.manga_translator.translation.util.bitmapToMat
import com.sakuravillager.manga_translator.translation.util.ensureOpenCVLoaded
import com.sakuravillager.manga_translator.translation.util.matToBitmap
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class OpenCVMaskRefiner : MaskRefiner {

    override val name: String = "OpenCVMaskRefiner"
    override var isReady: Boolean = false
        private set

    override suspend fun prepare() {
        if (!ensureOpenCVLoaded()) {
            throw IllegalStateException("Failed to load OpenCV")
        }
        isReady = true
    }

    override suspend fun release() {
        isReady = false
    }

    override suspend fun refine(
        textRegions: List<TextBlock>,
        bitmap: Bitmap,
        rawMask: Bitmap?,
        kernelSize: Int,
        dilationOffset: Int,
    ): Bitmap {
        // Step 1: create mask bitmap if rawMask is null
        val maskBitmap = rawMask ?: Bitmap.createBitmap(
            bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888,
        ).apply { eraseColor(android.graphics.Color.BLACK) }

        var maskMat: Mat? = null
        var kernel: Mat? = null
        try {
            // Step 2: convert mask Bitmap to Mat
            maskMat = bitmapToMat(maskBitmap)

            // Step 3: binarize the mask
            Imgproc.threshold(maskMat, maskMat, 127.0, 255.0, Imgproc.THRESH_BINARY)

            // Step 4: draw white filled rectangles for each text region
            for (region in textRegions) {
                val rect = region.minRect
                Imgproc.rectangle(
                    maskMat,
                    Point(rect.left.toDouble(), rect.top.toDouble()),
                    Point(rect.right.toDouble(), rect.bottom.toDouble()),
                    Scalar.all(255.0),
                    Imgproc.FILLED,
                )
            }

            // Step 5: create elliptical kernel
            kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                Size(kernelSize.toDouble(), kernelSize.toDouble()),
            )

            // Step 6: dilate
            val iterations = maxOf(1, dilationOffset / kernelSize)
            Imgproc.dilate(maskMat, maskMat, kernel, Point(-1.0, -1.0), iterations)

            // Step 7: convert Mat back to Bitmap
            return matToBitmap(maskMat)
        } finally {
            maskMat?.release()
            kernel?.release()
        }
    }
}
