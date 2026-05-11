package com.sakuravillager.manga_translator.translation.translator

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtSession
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.model.ModelInfo
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager
import com.sakuravillager.manga_translator.translation.translator.common.OfflineOnnxTranslator

/**
 * M2M100 multilingual neural machine translation model (418M parameters).
 *
 * Ported from the Python [M2M100Translator](https://github.com/zyddnys/manga-image-translator/blob/main/manga_translator/translators/m2m100.py)
 * which used CTranslate2.  This Kotlin version uses ONNX Runtime instead.
 *
 * ### Model architecture
 *
 * M2M100 is an encoder-decoder transformer supporting **direct translation
 * between 100 languages** without pivoting through English.  It uses a
 * SentencePiece tokenizer with a shared vocabulary for all languages.
 *
 * ### Translation pipeline
 *
 * ```
 * encode(srcText, srcLang)   → SentencePiece token IDs with source language token
 * encoder.forward(input_ids) → encoder_hidden_states
 * decoder loop:
 *   decoder.forward(token_ids, encoder_hidden_states) → logits
 *   argmax / sampling       → next token
 * detokenize(token_ids)     → translated text with language prefix stripped
 * ```
 *
 * ### Language codes
 *
 * Internal language codes (e.g. `"ENG"`, `"JPN"`) are mapped to M2M100
 * two-letter codes (`"en"`, `"ja"`) and wrapped as `"__en__"`, `"__ja__"`
 * tokens for the SentencePiece tokenizer.
 *
 * ### ONNX model files
 *
 * The M2M100 model is split into two ONNX sub-models for efficient inference:
 * - `m2m100_encoder.onnx`  — transformer encoder
 * - `m2m100_decoder.onnx`  — transformer decoder with past-key-value caching
 *
 * @param modelDownloadManager Used to download and verify model files.
 * @param onnxSessionManager  Singleton ONNX session factory.
 */
open class M2M100Translator(
    modelDownloadManager: ModelDownloadManager,
    onnxSessionManager: OnnxSessionManager,
) : OfflineOnnxTranslator(modelDownloadManager, onnxSessionManager) {

    // ─── Language Code Map ────────────────────────────────────────────────

    /**
     * Maps internal language codes (from [VALID_LANGUAGES]) to M2M100
     * two-letter codes.  Both Simplified and Traditional Chinese map to `"zh"`
     * since M2M100 uses a single Chinese model.
     *
     * M2M100 supports 100 languages natively.  The codes below cover all
     * languages exposed by the app; additional codes can be added by
     * extending this map.
     *
     * Refer to the [M2M100 language list](https://github.com/ymoslem/DesktopTranslator/blob/main/utils/m2m_languages.json)
     * for the full set of 100 supported language codes.
     */
    override val _LANGUAGE_CODE_MAP: Map<String, String> = mapOf(
        "CHS" to "zh",
        "CHT" to "zh",
        "ENG" to "en",
        "JPN" to "ja",
        "KOR" to "ko",
        "FRA" to "fr",
        "DEU" to "de",
        "ESP" to "es",
        "ITA" to "it",
        "NLD" to "nl",
        "PLK" to "pl",
        "PTB" to "pt",
        "RUS" to "ru",
        "CSY" to "cs",
        "HUN" to "hu",
        "ROM" to "ro",
        "TRK" to "tr",
        "UKR" to "uk",
        "VIN" to "vi",
        "ARA" to "ar",
        "CNR" to "cnr", // Montenegrin — maps to Serbian in M2M100
        "SRP" to "sr",
        "HRV" to "hr",
        "THA" to "th",
        "IND" to "id",
        "FIL" to "tl",
    )

    // ─── Model Info ───────────────────────────────────────────────────────

    /**
     * ONNX model metadata for M2M100-418M.
     *
     * The upstream Python release uses a CTranslate2 model (`.ct2` folder).
     * The ONNX version is converted separately and hosted at a different URL.
     *
     * **TODO**: Replace with the actual ONNX model URL after conversion.
     */
    override val modelInfo: ModelInfo = ModelInfo(
        name = MODEL_NAME,
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/m2m100_418m_ct2.zip",
        sha256 = "8a9cd0e00505a7879f26e5a1b396b447bc29967783a1e17e8df5eecb0c13d1c3",
        sizeBytes = 818_000_000L, // ~818 MB for the CT2 model; ONNX expected similar
    )

    // ─── SentencePiece model file name ────────────────────────────────────

    protected open val sentencePieceModelName: String = "sentencepiece.model"

    // ─── ONNX sub-model file names ────────────────────────────────────────

    protected open val encoderModelName: String = "$MODEL_NAME.encoder.onnx"
    protected open val decoderModelName: String = "$MODEL_NAME.decoder.onnx"

    // ─── State ────────────────────────────────────────────────────────────

    /** SentencePiece tokenizer instance. */
    private var spm: SentencePieceTokenizer? = null

    /** Decoder ONNX session (separate from the encoder `session` in the base class). */
    private var decoderSession: OrtSession? = null

    /**
     * Maximum sequence length for encoder input.
     * M2M100 default: 128 tokens.
     */
    protected open val maxSourceLength: Int = 128

    /**
     * Maximum number of tokens to generate during decoding.
     */
    protected open val maxTargetLength: Int = 256

    /**
     * Beam size for decoding.
     */
    protected open val beamSize: Int = 5

    /**
     * Repetition penalty for decoding (1.0 = no penalty).
     */
    protected open val repetitionPenalty: Float = 1.2f

    // ─── OfflineOnnxTranslator overrides ─────────────────────────────────

    override fun getLanguageCodeMap(): Map<String, String> = _LANGUAGE_CODE_MAP

    // ─── Preprocessing: text → encoder input tensors ─────────────────────

    /**
     * Tokenises source text using SentencePiece and creates encoder input tensors.
     *
     * For each query:
     * 1. Prepends the source language token (e.g. `__en__`)
     * 2. Encodes with SentencePiece (including BOS/EOS special tokens)
     * 3. Pads / truncates to [maxSourceLength]
     *
     * @param texts Source text segments to translate.
     * @return Map of encoder input names to [OnnxTensor]:
     *   - `"input_ids"`       — shape `(batch, seq_len)`, dtype int64
     *   - `"attention_mask"`  — shape `(batch, seq_len)`, dtype int64
     *   - `"lang_code"`       — source language token IDs, shape `(batch,)`, dtype int64
     */
    override suspend fun preprocess(texts: List<String>): Map<String, OnnxTensor> {
        val sp = spm ?: throw IllegalStateException("SentencePiece tokenizer not loaded")
        // fromLang and toLang are captured from _infer context.
        // They are stored by _infer before calling preprocess.
        val srcLang = currentFromLang ?: "en"
        val batchSize = texts.size.toLong()

        // Tokenize each query with source-language prefix
        val allInputIds = texts.map { text ->
            val tokens = sp.encodeWithLangToken(text, srcLang)
            tokens.take(maxSourceLength)
        }

        val maxLen = allInputIds.maxOfOrNull { it.size } ?: 1
        val paddedLen = maxOf(maxLen, 1)

        // Build padded input_ids and attention_mask
        val inputIds = LongArray(batchSize.toInt() * paddedLen)
        val attentionMask = LongArray(batchSize.toInt() * paddedLen)

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
        val shape = longArrayOf(batchSize, paddedLen.toLong())

        return mapOf(
            "input_ids" to OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(inputIds), shape),
            "attention_mask" to OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(attentionMask), shape),
        )
    }

    // ─── Postprocessing: decoder output → translated text ────────────────

    /**
     * Converts decoder output logits to translated text.
     *
     * For each query:
     * 1. Extracts the generated token IDs from the decoder loop
     * 2. Decodes via SentencePiece
     * 3. Strips the target language prefix token
     *
     * **Note:** Full autoregressive decoding with beam search is handled
     * in [_infer].  This [postprocess] method is called by the base class
     * for each decoder step's output.
     *
     * @param result Raw ONNX inference result from one decoder step.
     * @param texts  Original source text segments (for alignment).
     * @return Translated text segments, one per input query.
     */
    override suspend fun postprocess(
        result: OrtSession.Result,
        texts: List<String>,
    ): List<String> {
        val sp = spm ?: throw IllegalStateException("SentencePiece tokenizer not loaded")
        val tgtLang = currentToLang ?: "en"

        // Extract logits: shape (batch, 1, vocab_size)
        val logitsTensor = (result.get("logits").orElse(null) as? OnnxTensor) ?: return texts
        val logitsData = logitsTensor.floatBuffer ?: return texts

        val vocabSize = vocabularySize
        val batchSize = texts.size

        // Greedy decoding: argmax over vocab dimension for each batch item
        val generatedIds = mutableListOf<List<Int>>()
        for (b in 0 until batchSize) {
            val offset = b * vocabSize
            var bestId = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (v in 0 until vocabSize) {
                val score = logitsData.get(offset + v)
                if (score > bestScore) {
                    bestScore = score
                    bestId = v
                }
            }
            generatedIds.add(listOf(bestId))
        }

        // Decode each batch item
        return generatedIds.map { ids ->
            val text = sp.decodeWithLangToken(ids, tgtLang)
            if (text.isBlank() || text.isEmpty()) texts[generatedIds.indexOf(ids)]
            else text
        }
    }

    // ─── Inference override (full encoder-decoder loop) ──────────────────

    /**
     * Stores the current source/target language codes for use by [preprocess].
     */
    private var currentFromLang: String? = null
    private var currentToLang: String? = null

    /**
     * The vocabulary size of the M2M100 model (typically 128112 for M2M100-418M).
     */
    protected open val vocabularySize: Int = 128_112

    /**
     * Runs the full M2M100 encoder-decoder inference pipeline.
     *
     * Steps:
     * 1. Store language codes for [preprocess]/[postprocess]
     * 2. Run encoder forward pass to get `encoder_hidden_states`
     * 3. Initialize decoder with BOS token for each item
     * 4. Loop: run decoder step → sample next token → append to generated
     * 5. If all items hit EOS or max length, stop
     * 6. Decode generated token IDs to text
     *
     * @param fromLang Source language code (M2M100 two-letter, e.g. `"en"`).
     * @param toLang   Target language code (M2M100 two-letter, e.g. `"fr"`).
     * @param queries  Source text segments to translate.
     * @return Translated text segments.
     */
    override suspend fun _infer(
        fromLang: String,
        toLang: String,
        queries: List<String>,
    ): List<String> {
        currentFromLang = fromLang
        currentToLang = toLang

        val sess = session
        val decSess = decoderSession
        val sp = spm

        if (sess == null || decSess == null || sp == null) {
            Log.w(TAG, "Encoder or decoder model not loaded — returning original text")
            return queries
        }

        val env = onnxSessionManager.environment

        return try {
            // ── Step 1: Encoder forward pass ──────────────────────────────
            val inputs = preprocess(queries)
            val encoderResult = try {
                sess.run(inputs)
            } finally {
                // Close input tensors after encoder run
                for (tensor in inputs.values) {
                    try { if (!tensor.isClosed) tensor.close() } catch (_: Exception) { }
                }
            }

            val encoderHiddenState: OnnxTensor
            try {
                val optLastState = encoderResult.get("last_hidden_state")
                val optEncState = encoderResult.get("encoder_hidden_states")
                val rawValue = if (optLastState.isPresent) optLastState.get()
                    else if (optEncState.isPresent) optEncState.get()
                    else encoderResult.get(0)
                encoderHiddenState = (rawValue as? OnnxTensor)
                    ?: throw IllegalStateException("Encoder output not found")
            } catch (e: Exception) {
                encoderResult.close()
                throw e
            }

            // ── Step 2: Autoregressive decoder loop ───────────────────────
            val batchSize = queries.size
            val eosId = sp.eosId

            // Initialize each item with target-language prefix + BOS
            val targetPrefix = "__${toLang}__"
            val targetPrefixIds = sp.encode(targetPrefix)

            // Generated token sequences for each batch item
            val generatedSequences = Array(batchSize) { mutableListOf<Int>() }

            // Initialize all sequences with the target prefix tokens
            for (b in 0 until batchSize) {
                generatedSequences[b].addAll(targetPrefixIds)
                generatedSequences[b].add(sp.bosId)
            }

            val finished = BooleanArray(batchSize) { false }
            var allFinished = false
            var step = 0

            while (!allFinished && step < maxTargetLength) {
                allFinished = true

                for (b in 0 until batchSize) {
                    if (finished[b]) continue
                    allFinished = false

                    val currentSeq = generatedSequences[b]
                    val lastTokenId = currentSeq.last().toLong()

                    // Build decoder input: single token
                    val decInputIds = longArrayOf(lastTokenId)
                    val decShape = longArrayOf(1L, 1L)

                    val decInputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(decInputIds), decShape)
                    val encTensor = encoderHiddenState // shared across batch items

                    val decoderInputs = mutableMapOf<String, OnnxTensor>(
                        "input_ids" to decInputTensor,
                        "encoder_hidden_states" to encTensor,
                    )

                    try {
                        val decoderResult = decSess.run(decoderInputs)
                        try {
                            val logitsRaw = decoderResult.get("logits").orElse(null)
                                ?: decoderResult.get(0)
                            val logitsTensor = (logitsRaw as? OnnxTensor)
                                ?: throw IllegalStateException("Decoder logits not found")
                            val logitsBuf = logitsTensor.floatBuffer
                            val vocabSize = logitsBuf.capacity()

                            // Greedy: pick the highest-scoring token
                            var bestId = 0
                            var bestScore = Float.NEGATIVE_INFINITY
                            for (v in 0 until vocabSize) {
                                val score = logitsBuf.get(v)
                                if (score > bestScore) {
                                    bestScore = score
                                    bestId = v
                                }
                            }

                            currentSeq.add(bestId)

                            // Finish this item if we hit EOS
                            if (bestId == eosId) {
                                finished[b] = true
                            }
                        } finally {
                            decoderResult.close()
                        }
                    } finally {
                        decInputTensor.close()
                    }
                }

                step++
            }

            // ── Step 3: Postprocess — decode token IDs to text ────────────
            val results = generatedSequences.map { seq ->
                sp.decodeWithLangToken(seq, toLang)
            }

            // Clean up encoder resources
            try { encoderResult.close() } catch (_: Exception) { }

            results
        } catch (e: Exception) {
            Log.e(TAG, "M2M100 inference failed — returning original text", e)
            queries
        }
    }

    // ─── Tokenizer Lifecycle ──────────────────────────────────────────────

    /**
     * Loads the SentencePiece tokenizer from the model directory.
     *
     * The SentencePiece .model file is expected alongside the ONNX models
     * in the model download directory.
     */
    override suspend fun loadTokenizer() {
        try {
            val modelFile = modelDownloadManager.getModelFile(sentencePieceModelName)
            if (!modelFile.exists()) {
                Log.w(TAG, "SentencePiece model not found at ${modelFile.absolutePath}")
                return
            }
            val modelBytes = modelFile.readBytes()
            spm = SentencePieceTokenizer(modelBytes)
            Log.d(TAG, "SentencePiece tokenizer loaded (vocabulary: ${spm?.vocabulary?.size ?: 0} pieces)")
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

    // ─── Model Lifecycle ─────────────────────────────────────────────────

    /**
     * Overridden to also load the decoder ONNX model.
     *
     * In addition to the base [OfflineOnnxTranslator.loadModel] which loads
     * the encoder session, this method loads the decoder session from a
     * second ONNX file.
     */
    override suspend fun loadModel() {
        try {
            // Load encoder (base class handles this)
            super.loadModel()

            // Load decoder
            val modelFile = modelDownloadManager.getModelFile(decoderModelName)
            if (!modelFile.exists()) {
                Log.w(TAG, "Decoder model not found at ${modelFile.absolutePath}")
                return
            }

            val modelBytes = modelFile.readBytes()
            Log.d(TAG, "Decoder model file read (${modelBytes.size} bytes)")

            val options = createSessionOptions()
            decoderSession = onnxSessionManager.createSession(modelBytes, options)
            Log.d(TAG, "Decoder ONNX session created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load M2M100 model", e)
            session = null
            decoderSession = null
            tokenizer = null
            // isReady remains false
        }
    }

    /**
     * Overridden to also close the decoder session.
     */
    override suspend fun unloadModel() {
        // Unload tokenizer first
        try { unloadTokenizer() } catch (e: Exception) { Log.w(TAG, "Error unloading tokenizer", e) }

        // Close decoder session
        val currentDecSession = decoderSession
        if (currentDecSession != null) {
            try {
                onnxSessionManager.closeSession(currentDecSession)
                Log.d(TAG, "Decoder ONNX session closed")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing decoder session", e)
            }
        }
        decoderSession = null

        // Close encoder session (handled by base class)
        super.unloadModel()
    }

    // ─── Utils ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "M2M100Translator"

        /** Model name (used for file names and registry lookups). */
        const val MODEL_NAME = "m2m100_418m"
    }
}
