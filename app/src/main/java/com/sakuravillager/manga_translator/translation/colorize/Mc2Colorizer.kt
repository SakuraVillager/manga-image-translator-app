package com.sakuravillager.manga_translator.translation.colorize

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.sakuravillager.manga_translator.translation.api.Colorizer
import com.sakuravillager.manga_translator.translation.data.config.ColorizerConfig
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.model.ModelInfo
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.FloatBuffer

/**
 * ONNX-based Manga Colorization (MC²) colorizer.
 *
 * MC² uses a generator + denoiser architecture to colorize grayscale manga
 * pages. The generator takes a 1-channel grayscale input normalized to [0, 1]
 * and produces a 3-channel RGB output in the same range. An optional denoising
 * step can be applied after generation to reduce artifacts.
 *
 * Processing pipeline:
 *   1. Resize input to [colorizeSize] (preserving aspect ratio).
 *   2. Convert to grayscale NCHW tensor [1, 1, H, W] normalized to [0, 1].
 *   3. Run generator ONNX inference → [1, 3, H, W] RGB output.
 *   4. Optionally run denoiser if [denoiseSigma] > 0.
 *   5. Resize output back to the original image dimensions.
 *
 * Model files:
 *   - mc2_generator.onnx — bundled in assets/models/ or downloaded.
 *   - mc2_denoiser.onnx  — bundled in assets/models/ or downloaded (optional).
 *
 * @property modelDownloadManager used to fetch models from remote if not bundled.
 * @property sessionManager ONNX runtime session manager.
 * @property context Android context for loading bundled assets.
 */
class Mc2Colorizer(
    private val modelDownloadManager: ModelDownloadManager,
    private val sessionManager: OnnxSessionManager,
    private val context: Context,
) : Colorizer {

    override val name: String = "Mc2Colorizer"

    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    private var generatorSession: OrtSession? = null
    private var denoiserSession: OrtSession? = null

    companion object {
        private const val TAG = "Mc2Colorizer"

        private const val GENERATOR_ASSET = "models/mc2_generator.onnx"
        private const val DENOISER_ASSET = "models/mc2_denoiser.onnx"

        val GENERATOR_MODEL = ModelInfo(
            name = "mc2_generator",
            url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/mc2_generator.onnx",
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            sizeBytes = 85_000_000L,
        )

        val DENOISER_MODEL = ModelInfo(
            name = "mc2_denoiser",
            url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/mc2_denoiser.onnx",
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            sizeBytes = 5_000_000L,
        )
    }

    override suspend fun prepare() {
        Log.d(TAG, "Preparing Mc2Colorizer...")

        // Load generator model
        val generatorBytes = try {
            context.assets.open(GENERATOR_ASSET).use { it.readBytes() }.also {
                Log.d(TAG, "Generator loaded from assets (${it.size} bytes)")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Generator asset not found, trying download: ${e.message}")
            modelDownloadManager.ensureModel(GENERATOR_MODEL).readBytes().also {
                Log.d(TAG, "Generator downloaded (${it.size} bytes)")
            }
        }
        Log.d(TAG, "Creating generator ONNX session...")
        generatorSession = sessionManager.createSession(generatorBytes)

        // Load denoiser model (optional — may not exist)
        val denoiserBytes = try {
            context.assets.open(DENOISER_ASSET).use { it.readBytes() }.also {
                Log.d(TAG, "Denoiser loaded from assets (${it.size} bytes)")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Denoiser asset not found, trying download: ${e.message}")
            try {
                modelDownloadManager.ensureModel(DENOISER_MODEL).readBytes().also {
                    Log.d(TAG, "Denoiser downloaded (${it.size} bytes)")
                }
            } catch (e2: Exception) {
                Log.w(TAG, "Denoiser not available, skipping: ${e2.message}")
                null
            }
        }

        if (denoiserBytes != null) {
            denoiserSession = sessionManager.createSession(denoiserBytes)
            Log.d(TAG, "Denoiser ONNX session created")
        }

        _isReady = true
        Log.d(TAG, "Mc2Colorizer ready (denoiser: ${denoiserSession != null})")
    }

    override suspend fun release() {
        Log.d(TAG, "Releasing Mc2Colorizer...")
        generatorSession?.let { sessionManager.closeSession(it) }
        generatorSession = null
        denoiserSession?.let { sessionManager.closeSession(it) }
        denoiserSession = null
        _isReady = false
        Log.d(TAG, "Mc2Colorizer released")
    }

    override suspend fun colorize(
        bitmap: Bitmap,
        config: ColorizerConfig,
    ): Bitmap = withContext(Dispatchers.Default) {
        val genSess = generatorSession
            ?: error("Mc2Colorizer not prepared. Call prepare() first.")

        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW <= 0 || srcH <= 0) return@withContext bitmap

        // ── 1. Scale input to processing size ──
        val colorizeSize = config.colorizationSize.coerceAtLeast(64)
        val scale = if (maxOf(srcW, srcH) > colorizeSize) {
            colorizeSize.toFloat() / maxOf(srcW, srcH)
        } else {
            1f
        }
        val procW = (srcW * scale).toInt().coerceAtLeast(1)
        val procH = (srcH * scale).toInt().coerceAtLeast(1)

        Log.d(TAG, "colorize(${srcW}x${srcH}) → scale=$scale → (${procW}x${procH})")

        val workingBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, procW, procH, true)
        } else {
            bitmap
        }
        val toRecycle = mutableListOf<Bitmap>()

        try {
            // ── 2. Convert to grayscale NCHW tensor [1, 1, H, W] in [0, 1] ──
            val area = procW * procH
            val pixels = IntArray(area)
            workingBitmap.getPixels(pixels, 0, procW, 0, 0, procW, procH)

            val grayArray = FloatArray(area)
            for (i in 0 until area) {
                val px = pixels[i]
                // Standard luminance weights
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                grayArray[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
            }

            val inputShape = longArrayOf(1L, 1L, procH.toLong(), procW.toLong())
            val inputTensor = OnnxTensor.createTensor(
                sessionManager.environment,
                FloatBuffer.wrap(grayArray),
                inputShape,
            )

            try {
                // ── 3. Generator inference ──
                val inputName = genSess.inputNames.iterator().next()
                val genResults = genSess.run(mapOf(inputName to inputTensor))

                try {
                    val genOutput = genResults.get(0) as OnnxTensor
                    val genBuf = genOutput.floatBuffer
                    val genShape = genOutput.info.shape
                    val genH = genShape[2].toInt()
                    val genW = genShape[3].toInt()
                    val genArea = genW * genH

                    // ── 4. Optional denoising step ──
                    val denoiseSigma = config.denoiseSigma.coerceAtLeast(0)
                    val finalBuf: FloatBuffer
                    val finalW: Int
                    val finalH: Int

                    if (denoiseSigma > 0 && denoiserSession != null) {
                        // Pass generator output through denoiser
                        val denoiseInput = OnnxTensor.createTensor(
                            sessionManager.environment,
                            genBuf.duplicate(),
                            longArrayOf(1L, 3L, genH.toLong(), genW.toLong()),
                        )
                        try {
                            val denInputName = denoiserSession!!.inputNames.iterator().next()
                            val denResults = denoiserSession!!.run(mapOf(denInputName to denoiseInput))
                            try {
                                val denOutput = denResults.get(0) as OnnxTensor
                                finalBuf = denOutput.floatBuffer
                                val denShape = denOutput.info.shape
                                finalH = denShape[2].toInt()
                                finalW = denShape[3].toInt()
                            } finally {
                                denResults.close()
                            }
                        } finally {
                            denoiseInput.close()
                        }
                    } else {
                        finalBuf = genBuf
                        finalH = genH
                        finalW = genW
                    }

                    val finalArea = finalW * finalH

                    // ── 5. Postprocess: clamp [0, 1] → [0, 255] → Bitmap ──
                    val outPixels = IntArray(finalArea)
                    for (i in 0 until finalArea) {
                        val r = (finalBuf.get(i).coerceIn(0f, 1f) * 255f)
                            .toInt().coerceIn(0, 255)
                        val g = (finalBuf.get(1 * finalArea + i).coerceIn(0f, 1f) * 255f)
                            .toInt().coerceIn(0, 255)
                        val b = (finalBuf.get(2 * finalArea + i).coerceIn(0f, 1f) * 255f)
                            .toInt().coerceIn(0, 255)
                        outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }

                    val outBitmap = Bitmap.createBitmap(
                        finalW, finalH, Bitmap.Config.ARGB_8888,
                    )
                    outBitmap.setPixels(outPixels, 0, finalW, 0, 0, finalW, finalH)

                    // ── 6. Resize output back to original dimensions ──
                    if (finalW != srcW || finalH != srcH) {
                        val result = Bitmap.createScaledBitmap(
                            outBitmap, srcW, srcH, true,
                        )
                        toRecycle.add(outBitmap)
                        result
                    } else {
                        outBitmap
                    }
                } finally {
                    genResults.close()
                }
            } finally {
                inputTensor.close()
            }
        } finally {
            if (scale < 1f && workingBitmap !== bitmap) {
                workingBitmap.recycle()
            }
            toRecycle.forEach { it.recycle() }
        }
    }
}
