package com.sakuravillager.manga_translator.translation.inpaint

import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.api.Inpainter
import com.sakuravillager.manga_translator.translation.data.config.InpainterConfig
import com.sakuravillager.manga_translator.translation.util.bitmapToMat
import com.sakuravillager.manga_translator.translation.util.ensureOpenCVLoaded
import com.sakuravillager.manga_translator.translation.util.matToBitmap
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

class SimpleFillInpainter : Inpainter {

    override val name: String = "simple_fill"
    override var isReady: Boolean = false
        private set

    override suspend fun prepare() {
        if (!ensureOpenCVLoaded()) {
            throw IllegalStateException("Failed to load OpenCV")
        }
        isReady = true
    }

    override suspend fun inpaint(
        bitmap: Bitmap,
        mask: Bitmap,
        config: InpainterConfig,
    ): Bitmap {
        // Step 1: if dimensions don't match, resize mask to bitmap dimensions
        val workingMask = if (mask.width != bitmap.width || mask.height != bitmap.height) {
            Bitmap.createScaledBitmap(mask, bitmap.width, bitmap.height, true)
        } else {
            mask
        }

        // Step 2: convert bitmaps to Mats
        val bitmapMat = bitmapToMat(bitmap)
        val maskMat = bitmapToMat(workingMask)

        // Step 3: convert mask to grayscale and binarize
        val grayMask = Mat()
        Imgproc.cvtColor(maskMat, grayMask, Imgproc.COLOR_RGBA2GRAY)

        val binaryMask = Mat()
        Imgproc.threshold(grayMask, binaryMask, 127.0, 255.0, Imgproc.THRESH_BINARY)

        // Step 4: check if mask has any white pixels
        val whitePixels = Core.countNonZero(binaryMask)

        if (whitePixels == 0) {
            // No white pixels in mask — return original bitmap
            bitmapMat.release()
            maskMat.release()
            grayMask.release()
            binaryMask.release()
            return bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        }

        // Step 5: create all-white Mat and copy to bitmap where mask is non-zero
        val whiteMat = Mat(
            bitmapMat.rows(),
            bitmapMat.cols(),
            CvType.CV_8UC4,
            Scalar(255.0, 255.0, 255.0, 255.0),
        )
        whiteMat.copyTo(bitmapMat, binaryMask)

        // Step 6: convert back to Bitmap
        val result = matToBitmap(bitmapMat)

        // Cleanup
        bitmapMat.release()
        maskMat.release()
        grayMask.release()
        binaryMask.release()
        whiteMat.release()

        return result
    }

    override suspend fun release() {
        isReady = false
    }
}
