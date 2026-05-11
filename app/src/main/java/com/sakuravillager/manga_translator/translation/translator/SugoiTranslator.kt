package com.sakuravillager.manga_translator.translation.translator

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.util.Log
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.model.ModelInfo
import com.sakuravillager.manga_translator.translation.model.ModelRegistry
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager
import com.sakuravillager.manga_translator.translation.translator.common.OfflineOnnxTranslator
import java.nio.LongBuffer

/**
 * Sugoi V4.0 ONNX translator — JPN→ENG manga-specialized neural machine translation.
 *
 * Sugoi V4.0 is a T5-based encoder-decoder model fine-tuned on manga text,
 * exported as a single ONNX model for simplified inference.
 *
 * ### Architecture
 * ```
 * _infer → store language codes
 *        → preprocess (SentencePiece tokenize)
 *        → ONNX model single forward pass
 *        → postprocess (argmax → SentencePiece detokenize)
 * ```
 *
 * Unlike [MBart50Translator] and [M2M100Translator] which use separate
 * encoder/decoder ONNX models with autoregressive decoding, Sugoi V4 uses
 * a **single ONNX model** that internally handles both encoding and decoding
 * (using ONNX Loop or static unrolling), making the pipeline simpler.
 *
 * ### ONNX Model Files
 * - `sugoi_v4.onnx` — combined encoder-decoder ONNX model
 * - `sugoi_v4_tokenizer.spm` — SentencePiece tokenizer model
 *
 * ### Supported Language Pairs
 * - **JPN → ENG** (primary, manga-optimized)
 * - **ENG → JPN**
 * - **CHS → ENG**
 *
 * Language codes are mapped via [encodeWithLangToken] / [decodeWithLangToken]
 * to SentencePiece language prefix tokens (`__ja__`, `__en__`, `__zh__`).
 *
 * @param modelDownloadManager Used to download and verify model files.
 * @param onnxSessionManager  Singleton ONNX session factory.
 */
open class SugoiTranslator(
    modelDownloadManager: ModelDownloadManager,
    onnxSessionManager: OnnxSessionManager,
) : OfflineOnnxTranslator(modelDownloadManager, onnxSessionManager) {

    // ─── Constants ────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "SugoiTranslator"

        /**
         * Maximum source sequence length (input tokens).
         */
        private const val MAX_SOURCE_LENGTH = 256

        /**
         * Default model name — overridden in subclasses.
         */
        private const val DEFAULT_MODEL_NAME = "sugoi_v4"
    }

    /**
     * Model name used for ONNX model and tokenizer file lookups.
     *
     * This is an **open property** (not a companion const) so that subclasses
     * like [JparacrawlTranslator] can override it.  Defaults to `"sugoi_v4"`.
     */
    protected open val modelName: String = DEFAULT_MODEL_NAME

    /**
     * Vocabulary size for the model (T5-based, ~32k tokens for Sugoi V4).
     * Override in subclasses with different vocabularies.
     */
    protected open val vocabularySize: Int = 32_000

    // ─── Language Code Map ────────────────────────────────────────────────

    /**
     * Maps internal language codes (ISO 639-1 three-letter) to two-letter
     * codes used by SentencePiece language prefix tokens (`__ja__`, `__en__`,
     * `__zh__`).
     *
     * Sugoi V4 supports:
     * - JPN ↔ ENG (bidirectional)
     * - CHS → ENG
     */
    override val _LANGUAGE_CODE_MAP: Map<String, String> = mapOf(
        "JPN" to "ja",
        "ENG" to "en",
        "CHS" to "zh",
    )

    override fun getLanguageCodeMap(): Map<String, String> = _LANGUAGE_CODE_MAP

    // ─── Model Info ───────────────────────────────────────────────────────

    /**
     * ONNX model metadata for Sugoi V4.0.
     *
     * The model is a T5-based encoder-decoder exported as a single ONNX file.
     */
    override val modelInfo: ModelInfo
        get() = ModelRegistry.SUGOI_MODEL

    // ─── State ────────────────────────────────────────────────────────────

    /** SentencePiece tokenizer instance. */
    protected var spm: SentencePieceTokenizer? = null

    /**
     * Source language code for the current translation request.
     * Set in [_infer] before calling [preprocess].
     */
    private var currentFromLang: String? = null

    /**
     * Target language code for the current translation request.
     * Set in [_infer] before calling [postprocess].
     */
    private var currentToLang: String? = null

    // ─── Inference ────────────────────────────────────────────────────────

    /**
     * Overrides [OfflineOnnxTranslator._infer] to capture language codes
     * before delegating to the standard preprocess → run → postprocess pipeline.
     *
     * The language codes are stored in [currentFromLang] / [currentToLang]
     * and used by [preprocess] and [postprocess] for language-prefixed
     * tokenization and detokenization.
     */
    override suspend fun _infer(
        fromLang: String,
        toLang: String,
        queries: List<String>,
    ): List<String> {
        currentFromLang = fromLang
        currentToLang = toLang
        return super._infer(fromLang, toLang, queries)
    }

    // ─── Preprocessing: text → ONNX input tensors ────────────────────────

    /**
     * Tokenises source text using SentencePiece and creates encoder input tensors.
     *
     * For each query:
     * 1. Encodes with SentencePiece, including source language prefix token
     *    (e.g. `__ja__`) and special tokens (BOS/EOS)
     * 2. Truncates to [MAX_SOURCE_LENGTH]
     * 3. Pads to uniform length within the batch
     *
     * @param texts Source text segments to translate.
     * @return Map of model input names to [OnnxTensor]:
     *   - `"input_ids"`       — shape `(batch, seq_len)`, dtype int64
     *   - `"attention_mask"`  — shape `(batch, seq_len)`, dtype int64
     */
    override suspend fun preprocess(texts: List<String>): Map<String, OnnxTensor> {
        val sp = spm ?: throw IllegalStateException("SentencePiece tokenizer not loaded")
        val srcLang = currentFromLang
        val batchSize = texts.size

        // Tokenize each query — use language prefix if available, else plain encode
        val allInputIds = texts.map { text ->
            val tokens = if (srcLang != null) {
                sp.encodeWithLangToken(text, srcLang)
            } else {
                sp.encodeWithSpecialTokens(text)
            }
            tokens.take(MAX_SOURCE_LENGTH)
        }

        val maxLen = allInputIds.maxOfOrNull { it.size } ?: 1
        val paddedLen = maxOf(maxLen, 1)

        // Build padded input_ids and attention_mask
        val inputIds = LongArray(batchSize * paddedLen)
        val attentionMask = LongArray(batchSize * paddedLen)

        for (i in allInputIds.indices) {
            val row = allInputIds[i]
            val offset = i * paddedLen
            for (j in row.indices) {
                inputIds[offset + j] = row[j].toLong()
                attentionMask[offset + j] = 1L
            }
            // Remaining entries stay 0 (padded)
        }

        val env = onnxSessionManager.environment
        val shape = longArrayOf(batchSize.toLong(), paddedLen.toLong())

        return mapOf(
            "input_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape),
            "attention_mask" to OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape),
        )
    }

    // ─── Postprocessing: ONNX output → translated text ───────────────────

    /**
     * Converts ONNX model output logits to translated text.
     *
     * The model output is expected to contain logits of shape
     * `[batch, seq_len, vocab_size]`.  For each position in each batch item:
     * 1. Argmax over the vocabulary dimension to get the predicted token ID
     * 2. Strip special tokens (BOS, PAD)
     * 3. Stop at EOS token
     * 4. Decode via SentencePiece (with language prefix stripping)
     *
     * If the output tensor is named `"token_ids"` instead of `"logits"`,
     * the values are treated as direct token IDs (skip argmax).
     *
     * @param result Raw ONNX inference result.
     * @param texts  Original source text segments (for fallback).
     * @return Translated text segments, one per input query.
     */
    override suspend fun postprocess(
        result: OrtSession.Result,
        texts: List<String>,
    ): List<String> {
        val sp = spm ?: throw IllegalStateException("SentencePiece tokenizer not loaded")
        val tgtLang = currentToLang

        // Determine output type: logits or direct token IDs
        val logitsTensor = try {
            result.get("logits").orElse(null) as? OnnxTensor
        } catch (_: Exception) {
            null
        }
        val tokenIdsTensor = if (logitsTensor == null) {
            try {
                result.get("token_ids").orElse(null) as? OnnxTensor
            } catch (_: Exception) {
                null
            }
        } else null

        if (logitsTensor == null && tokenIdsTensor == null) {
            Log.w(TAG, "Model output has no recognised tensor name (expected 'logits' or 'token_ids')")
            return texts
        }

        val batchSize = texts.size
        val results = mutableListOf<String>()

        if (tokenIdsTensor != null) {
            // ── Direct token IDs output ──────────────────────────────
            val buf = tokenIdsTensor.longBuffer ?: return texts
            val shape = tokenIdsTensor.info.shape
            val seqLen = if (shape.size >= 2) shape[1].toInt() else buf.capacity() / batchSize

            for (b in 0 until batchSize) {
                val tokenIds = mutableListOf<Int>()
                val offset = b * seqLen
                for (s in 0 until seqLen) {
                    val id = buf.get(offset + s).toInt()
                    if (id == sp.eosId) break
                    if (id != sp.bosId && id != sp.padId) {
                        tokenIds.add(id)
                    }
                }
                val decoded = if (tgtLang != null) {
                    sp.decodeWithLangToken(tokenIds, tgtLang)
                } else {
                    sp.decode(tokenIds)
                }
                results.add(if (decoded.isBlank()) texts[b] else decoded)
            }
        } else {
            // ── Logits output: argmax over vocabulary ────────────────
            val logitsData = logitsTensor!!.floatBuffer ?: return texts
            val shape = logitsTensor.info.shape
            val vocabSize = if (shape.size >= 3) shape[2].toInt() else vocabularySize
            val seqLen = if (shape.size >= 2) shape[1].toInt() else 1

            for (b in 0 until batchSize) {
                val tokenIds = mutableListOf<Int>()
                for (s in 0 until seqLen.coerceAtMost(MAX_SOURCE_LENGTH)) {
                    val offset = (b * seqLen + s) * vocabSize
                    var bestId = 0
                    var bestScore = Float.NEGATIVE_INFINITY
                    for (v in 0 until vocabSize) {
                        val score = logitsData.get(offset + v)
                        if (score > bestScore) {
                            bestScore = score
                            bestId = v
                        }
                    }
                    if (bestId == sp.eosId) break
                    if (bestId != sp.bosId && bestId != sp.padId) {
                        tokenIds.add(bestId)
                    }
                }
                val decoded = if (tgtLang != null) {
                    sp.decodeWithLangToken(tokenIds, tgtLang)
                } else {
                    sp.decode(tokenIds)
                }
                results.add(if (decoded.isBlank()) texts[b] else decoded)
            }
        }

        return results
    }

    // ─── Tokenizer Lifecycle ──────────────────────────────────────────────

    /**
     * Loads the SentencePiece tokenizer from the model directory.
     *
     * The SentencePiece .model file is expected alongside the ONNX model,
     * named `{modelName}_tokenizer.spm`.  For Sugoi V4 this is
     * `sugoi_v4_tokenizer.spm`.
     */
    override suspend fun loadTokenizer() {
        try {
            val modelFile = modelDownloadManager.getModelFile("${modelName}_tokenizer.spm")
            if (!modelFile.exists()) {
                Log.w(TAG, "Tokenizer file not found at ${modelFile.absolutePath}" +
                    " — falling back to character-level placeholder")
                return
            }
            val bytes = modelFile.readBytes()
            spm = SentencePieceTokenizer(bytes)
            Log.d(TAG, "SentencePiece tokenizer loaded" +
                " (vocabulary: ${spm?.vocabulary?.size ?: 0} pieces)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SentencePiece tokenizer", e)
            spm = null
        }
    }

    /**
     * Releases the SentencePiece tokenizer resources.
     */
    override suspend fun unloadTokenizer() {
        spm = null
    }
}
