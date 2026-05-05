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

/**
 * ONNX-based AOT-GAN inpainter for manga image translation.
 *
 * AOT-GAN (Generator for Any-to-One Translation) takes a 4-channel input
 * (MASK + RGB, each normalized to [-1, 1]) and produces a 3-channel RGB
 * output in the same range.
 *
 * Architecture: AOTGenerator(4, 3) — 4 input channels, 3 output channels.
 * Normalization: /127.5 - 1.0 → range [-1, 1] (no FFT/LamaFourier normalization).
 * MASK concat order: cat([mask, img], dim=1) — mask channel first.
 *
 * Model file: aot_inpainting.onnx — bundled in assets/models/.
 * Fallback download URL: ModelRegistry.AOT_INPAINTING_MODEL (future GitHub Release).
 */
class AotInpainter(
    private val modelDownloadManager: ModelDownloadManager,
    private val sessionManager: OnnxSessionManager,
    private val context: Context,
) : Inpainter {

    override val name: String = "AotInpainter"

    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    private var session: OrtSession? = null

    companion object {
        private const val TAG = "AotInpainter"
        private const val ASSET_PATH = "models/aot_inpainting.onnx"
    }

    override suspend fun prepare() {
        Log.d(TAG, "Preparing AotInpainter...")
        // Priority 1: Load from bundled assets
        val modelBytes = try {
            context.assets.open(ASSET_PATH).use { it.readBytes() }.also {
                Log.d(TAG, "Loaded from assets (${it.size} bytes)")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Asset not found, trying download: ${e.message}")
            // Priority 2: Download from remote URL
            modelDownloadManager.ensureModel(ModelRegistry.AOT_INPAINTING_MODEL).readBytes().also {
                Log.d(TAG, "Downloaded model (${it.size} bytes)")
            }
        }
        Log.d(TAG, "Creating ONNX session...")
        session = sessionManager.createSession(modelBytes)
        _isReady = true
        Log.d(TAG, "AotInpainter ready")
    }

    override suspend fun release() {
        Log.d(TAG, "Releasing AotInpainter...")
        session?.let { sessionManager.closeSession(it) }
        session = null
        _isReady = false
        Log.d(TAG, "AotInpainter released")
    }

    override suspend fun inpaint(
        bitmap: Bitmap,
        mask: Bitmap,
        config: InpainterConfig,
    ): Bitmap = withContext(Dispatchers.Default) {
        val sess = session ?: error("AotInpainter not prepared. Call prepare() first.")

        val srcW = bitmap.width
        val srcH = bitmap.height

        // ---- 1. Ensure mask matches bitmap dimensions -------------------------
        val alignedMask = if (mask.width != srcW || mask.height != srcH) {
            Bitmap.createScaledBitmap(mask, srcW, srcH, true)
        } else {
            mask
        }

        // ---- 2. Determine processing size -------------------------------------
        // Scale down if the largest dimension exceeds inpaintingSize.
        val maxDim = maxOf(srcW, srcH)
        val scale = if (maxDim > config.inpaintingSize) {
            config.inpaintingSize.toFloat() / maxDim
        } else {
            1f
        }
        val procW = (srcW * scale).toInt().coerceAtLeast(1)
        val procH = (srcH * scale).toInt().coerceAtLeast(1)

        Log.d(TAG, "inpaint(${srcW}x${srcH}) → scale=$scale → (${procW}x${procH})")

        // ---- 3. Create working image and mask at processing size --------------
        val workingBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, procW, procH, true)
        } else {
            bitmap
        }
        val workingMask = if (scale < 1f) {
            Bitmap.createScaledBitmap(alignedMask, procW, procH, true)
        } else {
            alignedMask
        }

        val toRecycle = mutableListOf<Bitmap>()

        try {
            // ---- 4. Build NCHW float32 tensor [1, 4, H, W] --------------------
            // Channel 0: MASK (-1.0 or 1.0, threshold at 127)
            //   Python source: mask/127.5-1.0 → [-1,1]; mask>0 → 1.0; bg stays -1.0
            // Channel 1: R (normalized to [-1, 1])
            // Channel 2: G (normalized to [-1, 1])
            // Channel 3: B (normalized to [-1, 1])
            val area = procW * procH
            val floatArray = FloatArray(4 * area)
            val pixels = IntArray(area)
            val maskPixels = IntArray(area)

            workingBitmap.getPixels(pixels, 0, procW, 0, 0, procW, procH)
            workingMask.getPixels(maskPixels, 0, procW, 0, 0, procW, procH)

            for (i in 0 until area) {
                val px = pixels[i]
                val mx = maskPixels[i]

                // MASK channel: 1.0 (fg) / -1.0 (bg), threshold at 127
                floatArray[i] = if (((mx shr 16) and 0xFF) > 127) 1.0f else -1.0f

                // RGB normalized to [-1, 1]: (value / 127.5) - 1.0
                floatArray[1 * area + i] = (((px shr 16) and 0xFF) - 127.5f) / 127.5f
                floatArray[2 * area + i] = (((px shr 8) and 0xFF) - 127.5f) / 127.5f
                floatArray[3 * area + i] = ((px and 0xFF) - 127.5f) / 127.5f
            }

            val shape = longArrayOf(1L, 4L, procH.toLong(), procW.toLong())
            val inputTensor = OnnxTensor.createTensor(
                sessionManager.environment, FloatBuffer.wrap(floatArray), shape,
            )

            try {
                // ---- 5. ONNX inference --------------------------------------------
                val inputName = sess.inputNames.iterator().next()
                val inputs = mapOf(inputName to inputTensor)
                val results = sess.run(inputs)

                try {
                    // ---- 6. Extract output tensor --------------------------------
                    val outputTensor = results.get(0) as OnnxTensor
                    val outputBuf = outputTensor.floatBuffer
                    val outputShape = outputTensor.info.shape
                    val outH = outputShape[2].toInt()
                    val outW = outputShape[3].toInt()
                    val outArea = outW * outH

                    Log.d(TAG, "Output shape: ${outputShape.contentToString()}")

                    // ---- 7. Clip to [-1, 1] and denormalize to [0, 255] --------
                    // Denormalize: (val + 1.0) * 127.5
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

                    // ---- 8. Resize output to original dimensions if scaled -----
                    val resultAtOrigSize: Bitmap
                    val needsResize = scale < 1f || outW != srcW || outH != srcH
                    if (needsResize) {
                        resultAtOrigSize = Bitmap.createScaledBitmap(
                            outBitmap, srcW, srcH, true,
                        )
                        toRecycle.add(outBitmap)
                    } else {
                        resultAtOrigSize = outBitmap
                    }

                    // ---- 9. Blend with original image using mask ---------------
                    // Where mask pixel > 127, use inpainted result;
                    // otherwise keep original.
                    val origPixels = IntArray(srcW * srcH)
                    val alignedMaskPixels = IntArray(srcW * srcH)
                    bitmap.getPixels(origPixels, 0, srcW, 0, 0, srcW, srcH)
                    alignedMask.getPixels(
                        alignedMaskPixels, 0, srcW, 0, 0, srcW, srcH,
                    )

                    val resPixels = IntArray(srcW * srcH)
                    resultAtOrigSize.getPixels(resPixels, 0, srcW, 0, 0, srcW, srcH)

                    val finalPixels = IntArray(srcW * srcH)
                    for (i in 0 until srcW * srcH) {
                        val maskVal = (alignedMaskPixels[i] shr 16) and 0xFF
                        finalPixels[i] =
                            if (maskVal > 127) resPixels[i] else origPixels[i]
                    }

                    val finalBitmap = Bitmap.createBitmap(
                        srcW, srcH, Bitmap.Config.ARGB_8888,
                    )
                    finalBitmap.setPixels(finalPixels, 0, srcW, 0, 0, srcW, srcH)

                    // Cleanup intermediate bitmaps
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
            // Recycle scaled working copies only (not originals)
            if (scale < 1f) {
                workingBitmap.recycle()
                workingMask.recycle()
            }
            // Clean up alignedMask if it's a copy
            if (alignedMask !== mask) {
                alignedMask.recycle()
            }
            toRecycle.forEach { it.recycle() }
        }
    }
}
