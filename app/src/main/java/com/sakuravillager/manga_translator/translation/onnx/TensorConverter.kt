package com.sakuravillager.manga_translator.translation.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.nio.FloatBuffer

object TensorConverter {

    fun bitmapToNCHWTensor01(
        env: OrtEnvironment,
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
    ): OnnxTensor {
        val resized = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        val pixels = IntArray(targetWidth * targetHeight)
        resized.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)

        val area = targetWidth * targetHeight
        val size = 1 * 3 * area
        val floatArray = FloatArray(size)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            // R channel
            floatArray[i] = ((pixel shr 16) and 0xFF) / 255.0f
            // G channel
            floatArray[area + i] = ((pixel shr 8) and 0xFF) / 255.0f
            // B channel
            floatArray[2 * area + i] = (pixel and 0xFF) / 255.0f
        }

        val shape = longArrayOf(1L, 3L, targetHeight.toLong(), targetWidth.toLong())
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArray), shape)
    }

    fun bitmapToNCHWTensorMinusOneOne(
        env: OrtEnvironment,
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
    ): OnnxTensor {
        val resized = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        val pixels = IntArray(targetWidth * targetHeight)
        resized.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)

        val area = targetWidth * targetHeight
        val size = 1 * 3 * area
        val floatArray = FloatArray(size)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            // R channel: (R - 127.5) / 127.5
            floatArray[i] = (((pixel shr 16) and 0xFF) - 127.5f) / 127.5f
            // G channel: (G - 127.5) / 127.5
            floatArray[area + i] = (((pixel shr 8) and 0xFF) - 127.5f) / 127.5f
            // B channel: (B - 127.5) / 127.5
            floatArray[2 * area + i] = ((pixel and 0xFF) - 127.5f) / 127.5f
        }

        val shape = longArrayOf(1L, 3L, targetHeight.toLong(), targetWidth.toLong())
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArray), shape)
    }

    fun extractFloatArray(result: OrtSession.Result, index: Int): Array<FloatArray> {
        val tensor = result.get(index) as OnnxTensor
        val buffer = tensor.floatBuffer
        val shape = tensor.shape
        val rows = shape[0].toInt()
        val cols = shape[1].toInt()
        return Array(rows) { i ->
            FloatArray(cols) { j ->
                buffer.get(i * cols + j)
            }
        }
    }

    fun extractFloatBuffer(result: OrtSession.Result, index: Int): FloatBuffer {
        val tensor = result.get(index) as OnnxTensor
        return tensor.floatBuffer
    }
}
