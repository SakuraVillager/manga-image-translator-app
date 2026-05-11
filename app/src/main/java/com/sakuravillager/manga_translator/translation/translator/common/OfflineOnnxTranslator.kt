package com.sakuravillager.manga_translator.translation.translator.common

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.util.Log
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.model.ModelInfo
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager
import com.sakuravillager.manga_translator.translation.onnx.createDefaultSessionOptions

/**
 * Abstract base class for all ONNX-based offline translators.
 *
 * Extends [OfflineTranslator] with ONNX-specific model lifecycle management:
 * - Model download via [ModelDownloadManager]
 * - ONNX session creation via [OnnxSessionManager]
 * - Tokenizer lifecycle (SentencePiece / HuggingFace)
 * - fp16/int8 quantization flag
 * - Graceful fallback on failure
 *
 * ### Subclass contract
 *
 * Subclasses **must** implement:
 * - [modelInfo] – the [ModelInfo] describing the model to download and load
 * - [preprocess] – convert input text to ONNX input tensors
 * - [postprocess] – convert ONNX output to translated text
 *
 * Subclasses **may** override:
 * - [loadTokenizer] / [unloadTokenizer] – tokenizer lifecycle
 * - [getLanguageCodeMap] – language code mappings (also works via [_LANGUAGE_CODE_MAP])
 * - [createSessionOptions] – custom [OrtSession.SessionOptions]
 * - [downloadModel] / [loadModel] / [unloadModel] – lifecycle steps
 *
 * ### Lifecycle
 *
 * ```
 * prepare()  → downloadModel() → loadModel() → loadTokenizer()
 * load()     → _load()         → loadModel() (idempotent guard)
 * translate  → _translate()    → _infer()    → preprocess → run → postprocess
 * unload()   → _unload()       → unloadModel()
 * release()  → unloadModel()   → (cleanup)
 * ```
 *
 * ### Error handling
 *
 * - Model download failure: `isReady = false`, `translate()` returns original text
 * - Model load failure: `isReady = false`, `translate()` returns original text
 * - Inference failure: falls back to original text for affected segments
 *
 * Ported from Python's `ModelWrapper` inference lifecycle in `utils/inference.py`.
 *
 * @param modelDownloadManager Used to download and verify model files.
 * @param onnxSessionManager  Singleton ONNX session factory.
 */
abstract class OfflineOnnxTranslator(
    protected val modelDownloadManager: ModelDownloadManager,
    protected val onnxSessionManager: OnnxSessionManager,
) : OfflineTranslator() {

    companion object {
        private const val TAG = "OfflineOnnxTranslator"
    }

    // ─── Abstract ───────────────────────────────────────────────────

    /**
     * Model metadata for the ONNX model file.
     *
     * Used by [downloadModel] to locate and download the model, and by
     * [loadModel] to read the file from disk.
     *
     * Typically sourced from [com.sakuravillager.manga_translator.translation.model.ModelRegistry].
     */
    protected abstract val modelInfo: ModelInfo

    /**
     * Converts input text segments to ONNX input tensors.
     *
     * The returned map should contain one entry per model input, keyed by
     * the input name expected by the ONNX model. Callers are responsible
     * for closing the returned tensors (the base class handles this in
     * [_infer]).
     *
     * @param texts Source text segments to translate.
     * @return A map of input names to [OnnxTensor] for the ONNX model.
     */
    protected abstract suspend fun preprocess(texts: List<String>): Map<String, OnnxTensor>

    /**
     * Converts ONNX model output to translated text segments.
     *
     * @param result The raw ONNX inference result.  **Not** closed by this
     *               method — the caller ([_infer]) handles cleanup.
     * @param texts  The original source text segments (for alignment).
     * @return Translated text segments, one per input query.
     */
    protected abstract suspend fun postprocess(result: OrtSession.Result, texts: List<String>): List<String>

    /**
     * Returns the language code mapping (ISO 639-1 to internal codes).
     *
     * Override this instead of [_LANGUAGE_CODE_MAP] when using the
     * [OfflineOnnxTranslator] base class, or simply override
     * [_LANGUAGE_CODE_MAP] directly (it is used by [CommonTranslator]).
     *
     * Default: empty map (languages passed through as-is).
     */
    protected open fun getLanguageCodeMap(): Map<String, String> = emptyMap()

    // ─── State ──────────────────────────────────────────────────────

    /**
     * The loaded ONNX session, or `null` if the model is not loaded.
     */
    protected var session: OrtSession? = null

    /**
     * Optional tokenizer instance (SentencePiece or HuggingFace).
     *
     * Managed by [loadTokenizer] / [unloadTokenizer].  Subclasses should
     * cast this to the concrete tokenizer type as needed.
     */
    protected var tokenizer: Any? = null

    /**
     * Enable fp16 (half-precision) inference.
     *
     * When `true`, [createSessionOptions] adds a config entry requesting
     * fp16 execution.  This is only effective when the ONNX model and
     * the runtime device support fp16.
     */
    protected var useFp16: Boolean = false

    /**
     * Enable int8 quantization.
     *
     * When `true`, [createSessionOptions] adds a config entry requesting
     * int8 quantised execution.  This is only effective when the ONNX
     * model and the runtime device support int8.
     */
    protected var useInt8: Boolean = false

    /**
     * Internal ready flag.  Set to `true` only after [loadModel] succeeds.
     */
    private var _modelReady: Boolean = false

    // ─── PipelineModule — isReady / prepare / release ────────────────

    /**
     * Returns `true` when the model is loaded and ready for inference.
     *
     * Overrides [CommonTranslator.isReady] which always returns `true`.
     */
    override val isReady: Boolean
        get() = _modelReady

    /**
     * Prepares the translator: downloads the model if needed, then loads it.
     *
     * If either step fails, [isReady] remains `false` and subsequent calls
     * to [translate] return the original text unchanged.
     *
     * Matches Python `ModelWrapper.download()` + `ModelWrapper.load()`.
     */
    override suspend fun prepare() {
        Log.d(TAG, "Preparing ${this::class.simpleName}…")
        try {
            downloadModel()
            loadModel()
            if (_modelReady) {
                Log.d(TAG, "${this::class.simpleName} ready")
            } else {
                Log.w(TAG, "${this::class.simpleName} prepare completed but model is not ready")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare ${this::class.simpleName}", e)
            _modelReady = false
        }
    }

    /**
     * Releases all resources: closes the ONNX session and unloads the
     * tokenizer.  After this call [isReady] returns `false`.
     */
    override suspend fun release() {
        Log.d(TAG, "Releasing ${this::class.simpleName}…")
        unloadModel()
        Log.d(TAG, "${this::class.simpleName} released")
    }

    // ─── OfflineTranslator lifecycle ─────────────────────────────────

    /**
     * Loads the model for the given language pair on the specified device.
     *
     * Delegates to [loadModel].  If the model is already loaded, this
     * call is idempotent (no re-initialisation).
     */
    override suspend fun _load(fromLang: String, toLang: String, device: String) {
        if (session == null) {
            loadModel()
        }
    }

    /**
     * Unloads the model and releases resources.
     *
     * Delegates to [unloadModel].
     */
    override suspend fun _unload() {
        unloadModel()
    }

    /**
     * Runs ONNX model inference on the given [queries].
     *
     * Steps:
     * 1. If the model is not loaded, return [queries] unchanged (graceful fallback)
     * 2. [preprocess] — convert text to ONNX tensors
     * 3. `sess.run(inputs)` — execute the ONNX model
     * 4. [postprocess] — convert output tensors to translated text
     * 5. Cleanup: close output result and input tensors
     *
     * Any exception during inference is caught and logged; the original
     * text is returned as a fallback.
     *
     * Matches Python `ModelWrapper.infer()` → `_infer()`.
     */
    override suspend fun _infer(
        fromLang: String,
        toLang: String,
        queries: List<String>,
    ): List<String> {
        val sess = session
        if (sess == null) {
            Log.w(TAG, "Model not loaded — returning original text (${queries.size} segments)")
            return queries
        }

        return try {
            val inputs = preprocess(queries)

            try {
                val result = sess.run(inputs)

                try {
                    postprocess(result, queries)
                } finally {
                    // Free ONNX Runtime output resources
                    result.close()
                }
            } finally {
                // Free input tensors created by preprocess
                for (tensor in inputs.values) {
                    try {
                        if (!tensor.isClosed) tensor.close()
                    } catch (_: Exception) {
                        // Best-effort cleanup
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ONNX inference failed — falling back to original text", e)
            queries
        }
    }

    // ─── Model lifecycle ─────────────────────────────────────────────

    /**
     * Downloads the model file if not already present.
     *
     * Uses [modelDownloadManager.ensureModel] which verifies the SHA-256
     * hash of any existing file and re-downloads if the hash does not match.
     *
     * Override to customise download behaviour (e.g. alternate URL, asset
     * fallback as done in [com.sakuravillager.manga_translator.translation.inpaint.AotInpainter]).
     *
     * @param force If `true`, re-download even if the file exists and is valid.
     */
    protected open suspend fun downloadModel(force: Boolean = false) {
        modelDownloadManager.ensureModel(modelInfo)
    }

    /**
     * Loads the ONNX model into memory and creates an [OrtSession].
     *
     * 1. Reads the model file from disk via [ModelDownloadManager.getModelFile]
     * 2. Creates [OrtSession.SessionOptions] via [createSessionOptions]
     * 3. Creates the ONNX session via [OnnxSessionManager.createSession]
     * 4. Loads the tokenizer via [loadTokenizer]
     * 5. Sets [isReady] to `true` on success
     *
     * On failure, [isReady] remains `false` and [session] is `null`.
     */
    protected open suspend fun loadModel() {
        try {
            val modelFile = modelDownloadManager.getModelFile(modelInfo.name)
            if (!modelFile.exists()) {
                Log.w(TAG, "Model file not found at ${modelFile.absolutePath}")
                _modelReady = false
                return
            }

            val modelBytes = modelFile.readBytes()
            Log.d(TAG, "Model file read (${modelBytes.size} bytes)")

            val options = createSessionOptions()
            session = onnxSessionManager.createSession(modelBytes, options)

            Log.d(TAG, "ONNX session created")

            loadTokenizer()
            _modelReady = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            session = null
            _modelReady = false
        }
    }

    /**
     * Unloads the model: closes the ONNX session and releases tokenizer
     * resources.
     *
     * Safe to call multiple times — subsequent calls are no-ops once
     * [session] is `null`.
     */
    protected open suspend fun unloadModel() {
        // Unload tokenizer first (may reference the ONNX session)
        try {
            unloadTokenizer()
        } catch (e: Exception) {
            Log.w(TAG, "Error unloading tokenizer", e)
        }

        // Close and release the ONNX session
        val currentSession = session
        if (currentSession != null) {
            try {
                onnxSessionManager.closeSession(currentSession)
                Log.d(TAG, "ONNX session closed")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing ONNX session", e)
            }
        }

        session = null
        tokenizer = null
        _modelReady = false
    }

    // ─── Session options ─────────────────────────────────────────────

    /**
     * Creates [OrtSession.SessionOptions] for model loading.
     *
     * Base implementation applies:
     * - Default optimisation (all levels) and 4 intra-op threads (from
     *   [createDefaultSessionOptions])
     * - fp16 config entry when [useFp16] is `true`
     * - int8 config entry when [useInt8] is `true`
     *
     * Override to provide custom options (e.g. custom thread count,
     * execution providers).
     *
     * Return `null` to let [OnnxSessionManager.createSession] use its own
     * defaults.
     */
    protected open fun createSessionOptions(): OrtSession.SessionOptions? {
        if (!useFp16 && !useInt8) return null

        return OrtSession.SessionOptions().apply {
            // Start from the project defaults
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(4)

            if (useFp16) {
                addConfigEntry("session.enable_fp16", "1")
                Log.d(TAG, "fp16 enabled in session options")
            }
            if (useInt8) {
                addConfigEntry("session.enable_quantization", "1")
                Log.d(TAG, "int8 enabled in session options")
            }
        }
    }

    // ─── Tokenizer lifecycle ─────────────────────────────────────────

    /**
     * Loads the tokenizer (SentencePiece or HuggingFace).
     *
     * Override in subclasses that require tokenization.  The loaded
     * tokenizer should be assigned to [tokenizer] so it can be released
     * by [unloadTokenizer].
     *
     * Called automatically from [loadModel] after the ONNX session is
     * created.
     */
    protected open suspend fun loadTokenizer() {
        // No-op by default
    }

    /**
     * Unloads / releases the tokenizer resources.
     *
     * Override in subclasses that require tokenization.  Called
     * automatically from [unloadModel] before the ONNX session is closed.
     */
    protected open suspend fun unloadTokenizer() {
        // No-op by default
    }
}
