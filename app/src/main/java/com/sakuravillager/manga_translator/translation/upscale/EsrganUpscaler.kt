package com.sakuravillager.manga_translator.translation.upscale

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.sakuravillager.manga_translator.translation.api.Upscaler
import com.sakuravillager.manga_translator.translation.data.config.UpscaleConfig
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.model.ModelInfo
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.FloatBuffer

/**
 * ONNX-based ESRGAN 4× upscaler for manga images.
 *
 * ESRGAN (Enhanced Super-Resolution Generative Adversarial Network) takes a
 * 3-channel RGB input normalized to [0, 1] and produces a 4× larger RGB output
 * in the same range.
 *
 * Model file: esrgan_4x.onnx — bundled in assets/models/ or downloaded on demand.
 *
 * @property modelDownloadManager used to fetch the model from remote if not bundled.
 * @property sessionManager ONNX runtime session manager.
 * @property context Android context for loading bundled assets.
 */
class EsrganUpscaler(
    private val modelDownloadManager: ModelDownloadManager,
    private val sessionManager: OnnxSessionManager,
    private val context: Context,
) : Upscaler {

    override val name: String = "EsrganUpscaler"

    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    private var session: OrtSession? = null

    companion object {
        private const val TAG = "EsrganUpscaler"
        private const val ASSET_PATH = "models/esrgan_4x.onnx"
        private const val SCALE = 4

        /** Model descriptor for fallback download when the asset is missing. */
        val MODEL_INFO = ModelInfo(
            name = "esrgan_4x",
            url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/esrgan_4x.onnx",
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            sizeBytes = 8_000_000L,
        )
    }

    override suspend fun prepare() {
        Log.d(TAG, "Preparing EsrganUpscaler...")
        val modelBytes = try {
            context.assets.open(ASSET_PATH).use { it.readBytes() }.also {
                Log.d(TAG, "Loaded from assets (${it.size} bytes)")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Asset not found, trying download: ${e.message}")
            modelDownloadManager.ensureModel(MODEL_INFO).readBytes().also {
                Log.d(TAG, "Downloaded model (${it.size} bytes)")
            }
        }
        Log.d(TAG, "Creating ONNX session...")
        session = sessionManager.createSession(modelBytes)
        _isReady = true
        Log.d(TAG, "EsrganUpscaler ready")
    }

    override suspend fun release() {
        Log.d(TAG, "Releasing EsrganUpscaler...")
        session?.let { sessionManager.closeSession(it) }
        session = null
        _isReady = false
        Log.d(TAG, "EsrganUpscaler released")
    }

    override suspend fun upscale(
        bitmap: Bitmap,
        config: UpscaleConfig,
    ): Bitmap = withContext(Dispatchers.Default) {
        val sess = session ?: error("EsrganUpscaler not prepared. Call prepare() first.")

        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW <= 0 || srcH <= 0) return@withContext bitmap

        Log.d(TAG, "upscale(${srcW}x${srcH}) → 4x")

        // ── 1. Preprocess: Bitmap → NCHW float32 tensor [1, 3, H, W] in [0, 1] ──
        val area = srcW * srcH
        val floatArray = FloatArray(3 * area)
        val pixels = IntArray(area)
        bitmap.getPixels(pixels, 0, srcW, 0, 0, srcW, srcH)

        for (i in 0 until area) {
            val px = pixels[i]
            floatArray[i] = ((px shr 16) and 0xFF) / 255.0f           // R
            floatArray[area + i] = ((px shr 8) and 0xFF) / 255.0f     // G
            floatArray[2 * area + i] = (px and 0xFF) / 255.0f          // B
        }

        val shape = longArrayOf(1L, 3L, srcH.toLong(), srcW.toLong())
        val inputTensor = OnnxTensor.createTensor(
            sessionManager.environment,
            FloatBuffer.wrap(floatArray),
            shape,
        )

        try {
            // ── 2. ONNX inference ──
            val inputName = sess.inputNames.iterator().next()
            val results = sess.run(mapOf(inputName to inputTensor))

            try {
                // ── 3. Extract output tensor [1, 3, H*4, W*4] ──
                val outputTensor = results.get(0) as OnnxTensor
                val outputBuf = outputTensor.floatBuffer
                val outputShape = outputTensor.info.shape
                val outH = outputShape[2].toInt()
                val outW = outputShape[3].toInt()
                val outArea = outW * outH

                Log.d(TAG, "Output shape: ${outputShape.contentToString()}")

                // ── 4. Postprocess: clamp [0, 1] → [0, 255] → Bitmap ──
                val outPixels = IntArray(outArea)
                for (i in 0 until outArea) {
                    val r = (outputBuf.get(i).coerceIn(0f, 1f) * 255f)
                        .toInt().coerceIn(0, 255)
                    val g = (outputBuf.get(1 * outArea + i).coerceIn(0f, 1f) * 255f)
                        .toInt().coerceIn(0, 255)
                    val b = (outputBuf.get(2 * outArea + i).coerceIn(0f, 1f) * 255f)
                        .toInt().coerceIn(0, 255)
                    outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }

                val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                outBitmap.setPixels(outPixels, 0, outW, 0, 0, outW, outH)

                // ── 5. Ensure exact 4x size (defensive resize) ──
                val expectedW = srcW * SCALE
                val expectedH = srcH * SCALE
                if (outW != expectedW || outH != expectedH) {
                    Log.w(TAG, "Output size ${outW}x${outH} ≠ expected ${expectedW}x${expectedH}, resizing")
                    return@withContext Bitmap.createScaledBitmap(
                        outBitmap, expectedW, expectedH, true,
                    ).also { outBitmap.recycle() }
                }

                outBitmap
            } finally {
                results.close()
            }
        } finally {
            inputTensor.close()
        }
    }
}
