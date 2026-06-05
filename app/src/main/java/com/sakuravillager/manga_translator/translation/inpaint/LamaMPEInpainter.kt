package com.sakuravillager.manga_translator.translation.inpaint

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.sakuravillager.manga_translator.translation.api.Inpainter
import com.sakuravillager.manga_translator.translation.data.config.InpainterConfig
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.model.ModelRegistry
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.FloatBuffer

open class LamaMPEInpainter(
    protected val modelDownloadManager: ModelDownloadManager,
    protected val sessionManager: OnnxSessionManager,
    protected val context: Context,
) : Inpainter {

    override val name: String = "LamaMPEInpainter"

    protected open val logTag: String = "LamaMPEInpainter"
    protected open val assetPath: String = "models/aot_inpainting.onnx"
    protected open val modelRegistryInfo = ModelRegistry.AOT_INPAINTING_MODEL

    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    private var session: OrtSession? = null

    override suspend fun prepare() {
        Log.d(logTag, "Preparing $name...")
        val modelBytes = try {
            context.assets.open(assetPath).use { it.readBytes() }.also {
                Log.d(logTag, "Loaded from assets (${it.size} bytes)")
            }
        } catch (e: IOException) {
            Log.w(logTag, "Asset not found, trying download: ${e.message}")
            modelDownloadManager.ensureModel(modelRegistryInfo).readBytes().also {
                Log.d(logTag, "Downloaded model (${it.size} bytes)")
            }
        }
        Log.d(logTag, "Creating ONNX session...")
        session = sessionManager.createSession(modelBytes)
        _isReady = true
        Log.d(logTag, "$name ready")
    }

    override suspend fun release() {
        Log.d(logTag, "Releasing $name...")
        session?.let { sessionManager.closeSession(it) }
        session = null
        _isReady = false
        Log.d(logTag, "$name released")
    }

    override suspend fun inpaint(
        bitmap: Bitmap,
        mask: Bitmap,
        config: InpainterConfig,
    ): Bitmap = withContext(Dispatchers.Default) {
        val sess = session ?: error("$name not prepared. Call prepare() first.")

        val srcW = bitmap.width
        val srcH = bitmap.height

        val alignedMask = if (mask.width != srcW || mask.height != srcH) {
            Bitmap.createScaledBitmap(mask, srcW, srcH, true)
        } else {
            mask
        }

        val maxDim = maxOf(srcW, srcH)
        val scale = if (maxDim > config.inpaintingSize) {
            config.inpaintingSize.toFloat() / maxDim
        } else {
            1f
        }
        // Scale down if needed
        val scaledW = (srcW * scale).toInt().coerceAtLeast(1)
        val scaledH = (srcH * scale).toInt().coerceAtLeast(1)

        // Pad to multiple of 8 (matches Python pad_size=8 in inpainting_lama_mpe.py L67-79)
        val padSize = 8
        val procW = if (scaledW % padSize != 0) scaledW + (padSize - scaledW % padSize) else scaledW
        val procH = if (scaledH % padSize != 0) scaledH + (padSize - scaledH % padSize) else scaledH
        val needsPad = procW != scaledW || procH != scaledH

        Log.d(logTag, "inpaint(${srcW}x${srcH}) → scale=$scale → (${scaledW}x${scaledH}) → pad → (${procW}x${procH})")

        val scaledBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        } else {
            bitmap
        }
        val scaledMask = if (scale < 1f) {
            Bitmap.createScaledBitmap(alignedMask, scaledW, scaledH, true)
        } else {
            alignedMask
        }
        val workingBitmap = if (needsPad) {
            Bitmap.createScaledBitmap(scaledBitmap, procW, procH, true)
        } else {
            scaledBitmap
        }
        val workingMask = if (needsPad) {
            Bitmap.createScaledBitmap(scaledMask, procW, procH, true)
        } else {
            scaledMask
        }

        val toRecycle = mutableListOf<Bitmap>()

        try {
            val area = procW * procH
            val floatArray = FloatArray(4 * area)
            val pixels = IntArray(area)
            val maskPixels = IntArray(area)

            workingBitmap.getPixels(pixels, 0, procW, 0, 0, procW, procH)
            workingMask.getPixels(maskPixels, 0, procW, 0, 0, procW, procH)

            for (i in 0 until area) {
                val px = pixels[i]
                val mx = maskPixels[i]
                val maskValue = if (((mx shr 16) and 0xFF) > 127) 1.0f else 0.0f

                floatArray[i] = maskValue

                val r = (((px shr 16) and 0xFF) - 127.5f) / 127.5f
                val g = (((px shr 8) and 0xFF) - 127.5f) / 127.5f
                val b = ((px and 0xFF) - 127.5f) / 127.5f
                if (maskValue > 0f) {
                    floatArray[1 * area + i] = 0f
                    floatArray[2 * area + i] = 0f
                    floatArray[3 * area + i] = 0f
                } else {
                    floatArray[1 * area + i] = r
                    floatArray[2 * area + i] = g
                    floatArray[3 * area + i] = b
                }
            }

            val shape = longArrayOf(1L, 4L, procH.toLong(), procW.toLong())
            val inputTensor = OnnxTensor.createTensor(
                sessionManager.environment, FloatBuffer.wrap(floatArray), shape,
            )

            try {
                val inputName = sess.inputNames.iterator().next()
                val inputs = mapOf(inputName to inputTensor)
                val results = sess.run(inputs)

                try {
                    val outputTensor = results.get(0) as OnnxTensor
                    val outputBuf = outputTensor.floatBuffer
                    val outputShape = outputTensor.info.shape
                    val outH = outputShape[2].toInt()
                    val outW = outputShape[3].toInt()
                    val outArea = outW * outH

                    Log.d(logTag, "Output shape: ${outputShape.contentToString()}")

                    val outPixels = IntArray(outArea)
                    for (i in 0 until outArea) {
                        val r = ((outputBuf.get(i).coerceIn(-1f, 1f) + 1f) * 127.5f)
                            .toInt().coerceIn(0, 255)
                        val g = ((outputBuf.get(1 * outArea + i).coerceIn(-1f, 1f) + 1f) * 127.5f)
                            .toInt().coerceIn(0, 255)
                        val b = ((outputBuf.get(2 * outArea + i).coerceIn(-1f, 1f) + 1f) * 127.5f)
                            .toInt().coerceIn(0, 255)
                        outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }

                    val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                    outBitmap.setPixels(outPixels, 0, outW, 0, 0, outW, outH)

                    val resultAtOrigSize: Bitmap
                    val needsResize = scale < 1f || outW != srcW || outH != srcH
                    if (needsResize) {
                        resultAtOrigSize = Bitmap.createScaledBitmap(outBitmap, srcW, srcH, true)
                        toRecycle.add(outBitmap)
                    } else {
                        resultAtOrigSize = outBitmap
                    }

                    val origPixels = IntArray(srcW * srcH)
                    val alignedMaskPixels = IntArray(srcW * srcH)
                    bitmap.getPixels(origPixels, 0, srcW, 0, 0, srcW, srcH)
                    alignedMask.getPixels(alignedMaskPixels, 0, srcW, 0, 0, srcW, srcH)

                    val resPixels = IntArray(srcW * srcH)
                    resultAtOrigSize.getPixels(resPixels, 0, srcW, 0, 0, srcW, srcH)

                    // Hard binary compositing (matches Python L117: ans = inpainted * mask + original * (1-mask))
                    val finalPixels = IntArray(srcW * srcH)
                    for (i in 0 until srcW * srcH) {
                        val maskVal = (alignedMaskPixels[i] shr 16) and 0xFF
                        finalPixels[i] = if (maskVal >= 127) resPixels[i] else origPixels[i]
                    }

                    val finalBitmap = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
                    finalBitmap.setPixels(finalPixels, 0, srcW, 0, 0, srcW, srcH)

                    if (needsResize) {
                        resultAtOrigSize.recycle()
                    }

                    finalBitmap
                } finally {
                    results.close()
                }
            } finally {
                inputTensor.close()
            }
        } finally {
            if (needsPad) {
                if (workingBitmap !== scaledBitmap) workingBitmap.recycle()
                if (workingMask !== scaledMask) workingMask.recycle()
            }
            if (scale < 1f) {
                scaledBitmap.recycle()
                scaledMask.recycle()
            }
            if (alignedMask !== mask) {
                alignedMask.recycle()
            }
            toRecycle.forEach { it.recycle() }
        }
    }
}