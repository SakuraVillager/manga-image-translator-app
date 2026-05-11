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
 * MBart50 ONNX translator — facebook/mbart-large-50-many-to-many-mmt.
 *
 * MBart50 is a multilingual encoder-decoder transformer supporting 50 languages.
 * This implementation uses ONNX Runtime with separate encoder and decoder
 * models exported via HuggingFace Optimum.
 *
 * ### Architecture
 * ```
 * _translate → translateSegment for each text:
 *   1. tokenizeWithLang  — add source language prefix token + SentencePiece
 *   2. runEncoder        — ONNX encoder forward pass → encoder_hidden_states
 *   3. generate          — autoregressive decoder loop with target BOS token
 *   4. detokenize        — token IDs → text
 * ```
 *
 * ### ONNX Model Files
 * - `mbart50_encoder.onnx` — input_ids + attention_mask → last_hidden_state
 * - `mbart50_decoder.onnx` — input_ids + encoder_hidden_states → logits
 * - `mbart50_tokenizer.spm` — SentencePiece model for tokenization
 *
 * ### Tokenizer Note
 * Tokenization currently uses a character-level placeholder (identical to
 * [Qwen2Translator]). Replace [MBart50Tokenizer] with a proper SentencePiece
 * integration when the native library becomes available.
 *
 * @param modelDownloadManager Used to download and verify model files.
 * @param onnxSessionManager  Singleton ONNX session factory.
 */
class MBart50Translator(
    modelDownloadManager: ModelDownloadManager,
    onnxSessionManager: OnnxSessionManager,
) : OfflineOnnxTranslator(modelDownloadManager, onnxSessionManager) {

    companion object {
        private const val TAG = "MBart50Translator"

        /**
         * Maximum new tokens to generate in the autoregressive decoder loop.
         */
        private const val MAX_NEW_TOKENS = 128

        /**
         * Maximum encoder input length (source text tokens).
         */
        private const val MAX_ENCODER_LENGTH = 256

        // ── MBart50 special token IDs (SentencePiece) ────────────────
        private const val PAD_TOKEN_ID = 1L
        private const val EOS_TOKEN_ID = 2L
    }

    // ─── Model metadata ───────────────────────────────────────────────

    override val name: String = "MBart50"

    override val modelInfo: ModelInfo
        get() = ModelRegistry.MBART50_MODEL

    /**
     * Decoder model info (loaded separately from the encoder).
     */
    private val decoderModelInfo: ModelInfo
        get() = ModelRegistry.MBART50_DECODER_MODEL

    // ─── Language code mapping ─────────────────────────────────────────

    /**
     * Maps internal language codes to MBart50 language codes.
     *
     * MBart50 uses `xx_XX` format (e.g., `en_XX`, `zh_CN`, `ja_XX`).
     * See https://huggingface.co/facebook/mbart-large-50 for the full list.
     *
     * Covers all 22 languages from [VALID_LANGUAGES] that MBart50 directly
     * supports, plus additional MBart50-only languages for future use.
     */
    override val _LANGUAGE_CODE_MAP: Map<String, String> = mapOf(
        // ── Full MBart50 50-language support ───────────────────────────
        // ISO 639-1 → MBart50 code (xx_XX format)
        "ARA" to "ar_AR",
        "CHS" to "zh_CN",
        "CHT" to "zh_CN",      // zh_TW not available in MBart50; fallback to zh_CN
        "CSY" to "cs_CZ",
        "DEU" to "de_DE",
        "ENG" to "en_XX",
        "ESP" to "es_XX",
        "FRA" to "fr_XX",
        "HRV" to "hr_HR",
        "IND" to "id_ID",
        "ITA" to "it_IT",
        "JPN" to "ja_XX",
        "KOR" to "ko_KR",
        "NLD" to "nl_XX",
        "POL" to "pl_PL",
        "PTB" to "pt_XX",
        "ROM" to "ro_RO",
        "RUS" to "ru_RU",
        "SWA" to "sw_KE",
        "THA" to "th_TH",
        "TRK" to "tr_TR",
        "UKR" to "uk_UA",
        "URD" to "ur_PK",
        "VIN" to "vi_VN",
        // Additional MBart50 languages (for future expansion)
        "AZE" to "az_AZ",
        "BEL" to "bn_IN",       // Bengali
        "EST" to "et_EE",
        "FIN" to "fi_FI",
        "GEO" to "ka_GE",
        "GLS" to "gl_ES",
        "Guj" to "gu_IN",
        "HEB" to "he_IL",
        "HIN" to "hi_IN",
        "KAZ" to "kk_KZ",
        "KHM" to "km_KH",
        "LIT" to "lt_LT",
        "LAV" to "lv_LV",
        "MKD" to "mk_MK",
        "MLT" to "ml_IN",
        "MON" to "mn_MN",
        "MAR" to "mr_IN",
        "MYA" to "my_MM",
        "NEP" to "ne_NP",
        "PUS" to "ps_AF",
        "SIN" to "si_LK",
        "SLO" to "sl_SI",
        "SWE" to "sv_SE",
        "TAM" to "ta_IN",
        "TEL" to "te_IN",
        "TGL" to "tl_XX",       // Tagalog
        "UZB" to "uz_UZ",
        "XHO" to "xh_ZA",
        "ZHO" to "zh_CN",
    )

    override fun getLanguageCodeMap(): Map<String, String> = _LANGUAGE_CODE_MAP

    // ─── State ─────────────────────────────────────────────────────────

    /** Decoder ONNX session (loaded alongside the encoder session). */
    private var decoderSession: OrtSession? = null

    /** MBart50 SentencePiece tokenizer wrapper. */
    private var mbartTokenizer: MBart50Tokenizer? = null

    // ─── Model Lifecycle ───────────────────────────────────────────────

    /**
     * Overrides [OfflineOnnxTranslator.loadModel] to load both the encoder
     * (via super) and the decoder ONNX model.
     */
    override suspend fun loadModel() {
        try {
            super.loadModel() // Loads encoder → this.session

            // Load decoder model separately
            val decoderFile = modelDownloadManager.getModelFile(decoderModelInfo.name)
            if (!decoderFile.exists()) {
                Log.w(TAG, "Decoder model file not found at ${decoderFile.absolutePath}")
                decoderSession = null
                return
            }

            val decoderBytes = decoderFile.readBytes()
            Log.d(TAG, "Decoder model file read (${decoderBytes.size} bytes)")

            val options = createSessionOptions()
            decoderSession = onnxSessionManager.createSession(decoderBytes, options)
            Log.d(TAG, "Decoder ONNX session created")

            loadTokenizer()

            // Set ready flag (super.loadModel does this but we override to
            // also check decoder)
            val isReady = session != null && decoderSession != null && mbartTokenizer != null
            if (!isReady) {
                Log.w(TAG, "MBart50 model partially loaded: encoder=${session != null}, decoder=${decoderSession != null}, tokenizer=${mbartTokenizer != null}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load MBart50 model", e)
            session = null
            decoderSession = null
            mbartTokenizer = null
        }
    }

    /**
     * Overrides [OfflineOnnxTranslator.unloadModel] to release decoder session
     * and tokenizer resources.
     */
    override suspend fun unloadModel() {
        super.unloadModel() // Closes encoder session + tokenizer

        try {
            decoderSession?.let { onnxSessionManager.closeSession(it) }
            Log.d(TAG, "Decoder ONNX session closed")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing decoder session", e)
        }
        decoderSession = null
        mbartTokenizer = null
    }

    // ─── Tokenizer Lifecycle ───────────────────────────────────────────

    /**
     * Loads the SentencePiece tokenizer model file.
     *
     * The tokenizer file is expected to be named `mbart50_tokenizer.spm`
     * alongside the encoder and decoder ONNX models.
     *
     * TODO: Replace [MBart50Tokenizer] with proper SentencePiece JNI binding.
     */
    override suspend fun loadTokenizer() {
        try {
            val tokenizerFile = modelDownloadManager.getModelFile(
                ModelRegistry.MBART50_TOKENIZER_MODEL.name,
            )
            if (tokenizerFile.exists()) {
                val bytes = tokenizerFile.readBytes()
                mbartTokenizer = MBart50Tokenizer(bytes)
                Log.d(TAG, "MBart50 tokenizer loaded (${bytes.size} bytes)")
            } else {
                Log.w(TAG, "Tokenizer file not found at ${tokenizerFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load tokenizer", e)
        }
    }

    override suspend fun unloadTokenizer() {
        mbartTokenizer = null
    }

    // ─── Pipeline stubs ────────────────────────────────────────────────

    /**
     * MBart50 uses a custom encoder-decoder pipeline in [_translate].
     * The standard [preprocess]/[postprocess] pipeline is not used.
     */
    override suspend fun preprocess(texts: List<String>): Map<String, OnnxTensor> =
        throw UnsupportedOperationException(
            "MBart50 uses encoder-decoder autoregressive pipeline, not standard preprocess",
        )

    override suspend fun postprocess(result: OrtSession.Result, texts: List<String>): List<String> =
        throw UnsupportedOperationException(
            "MBart50 uses encoder-decoder autoregressive pipeline, not standard postprocess",
        )

    // ─── Translation ───────────────────────────────────────────────────

    /**
     * Translates a batch of text segments using the MBart50 encoder-decoder model.
     *
     * Each segment is processed individually (no batching across segments).
     *
     * @param fromLang Resolved source language code (MBart50 format, e.g. "en_XX").
     * @param toLang   Resolved target language code (MBart50 format, e.g. "zh_CN").
     * @param queries  Source text segments to translate.
     * @return Translated text segments, one per input query.
     */
    override suspend fun _translate(
        fromLang: String,
        toLang: String,
        queries: List<String>,
    ): List<String> {
        val encSess = session
        val decSess = decoderSession
        if (encSess == null || decSess == null) {
            Log.w(TAG, "Model not fully loaded — returning original text")
            return queries
        }

        val results = MutableList(queries.size) { "" }
        for ((i, query) in queries.withIndex()) {
            try {
                results[i] = translateSegment(encSess, decSess, query, fromLang, toLang)
            } catch (e: Exception) {
                Log.e(TAG, "Translation failed for segment: ${query.take(40)}…", e)
                results[i] = query // Graceful fallback
            }
        }
        return results
    }

    /**
     * Translates a single text segment through the encoder-decoder pipeline.
     *
     * Steps:
     * 1. Tokenize input with source language prefix token
     * 2. Run encoder forward pass → encoder_hidden_states
     * 3. Autoregressive decoder loop with forced target BOS token
     * 4. Detokenize output tokens → translated text
     */
    private suspend fun translateSegment(
        encSess: OrtSession,
        decSess: OrtSession,
        text: String,
        fromLang: String,
        toLang: String,
    ): String {
        // ── 1. Tokenize ──────────────────────────────────────────────
        val (inputIds, attentionMask) = tokenizeWithLang(text, fromLang)

        // ── 2. Run encoder ───────────────────────────────────────────
        val inputTensor = createLongTensor(inputIds)
        val maskTensor = createLongTensor(attentionMask)

        return try {
            val encoderResult = encSess.run(
                mapOf(
                    "input_ids" to (inputTensor as OnnxTensor),
                    "attention_mask" to (maskTensor as OnnxTensor),
                ),
            )

            try {
                val encoderHiddenStates = extractEncoderHidden(encoderResult)

                // ── 3. Autoregressive decoder loop ───────────────────
                val outputIds = generate(decSess, encoderHiddenStates, toLang)

                // ── 4. Detokenize ────────────────────────────────────
                detokenize(outputIds)
            } finally {
                encoderResult.close()
            }
        } finally {
            // Close input tensors
            if (!inputTensor.isClosed) inputTensor.close()
            if (!maskTensor.isClosed) maskTensor.close()
        }
    }

    // ─── Encoder ───────────────────────────────────────────────────────

    /**
     * Extracts the encoder hidden states from the encoder ONNX output.
     *
     * The encoder output is expected to have an output named
     * `"last_hidden_state"` (standard HuggingFace Optimum convention).
     *
     * The encoder [session] reference is used here to enumerate output names.
     *
     * @return The [OnnxTensor] containing encoder_hidden_states.
     *         **Not closed** here — caller is responsible for cleanup.
     */
    private fun extractEncoderHidden(result: OrtSession.Result): OnnxTensor {
        val encSess = session ?: error("Encoder session is null")
        val outputNames = encSess.outputNames
        val encoderOutputName = when {
            outputNames.contains("last_hidden_state") -> "last_hidden_state"
            outputNames.contains("encoder_output") -> "encoder_output"
            outputNames.contains("hidden_states") -> "hidden_states"
            else -> outputNames.iterator().next()
        }
        @Suppress("UNCHECKED_CAST")
        return result.get(encoderOutputName).get() as OnnxTensor
    }

    // ─── Autoregressive Decoder ───────────────────────────────────────

    /**
     * Runs the autoregressive decoder loop.
     *
     * Starting from the target language BOS token, iteratively feeds the
     * decoder with the accumulated token sequence and samples the next token.
     *
     * Stops when:
     * - EOS token ([EOS_TOKEN_ID]) is generated
     * - [MAX_NEW_TOKENS] new tokens have been generated
     *
     * @param decSess       The decoder ONNX session.
     * @param encoderHidden The encoder hidden states tensor (from [runEncoder]).
     * @param toLang        Target language code (e.g. "zh_CN") for forced BOS.
     * @return List of generated token IDs (excluding the forced BOS token).
     */
    private suspend fun generate(
        decSess: OrtSession,
        encoderHidden: OnnxTensor,
        toLang: String,
    ): List<Long> {
        // Get the target language BOS token ID from the tokenizer
        val targetBosId = mbartTokenizer?.getLangCodeId(toLang) ?: 0L

        // Start with the target language BOS token
        val generated = mutableListOf(targetBosId)

        // Determine ONNX input/output names from decoder session metadata
        val decInputNames = decSess.inputNames
        val decInputIdName = when {
            decInputNames.contains("input_ids") -> "input_ids"
            else -> decInputNames.iterator().next()
        }
        val decEncName = when {
            decInputNames.contains("encoder_hidden_states") -> "encoder_hidden_states"
            else -> decInputNames.drop(1).iterator().next()
        }

        val decOutputNames = decSess.outputNames
        val decOutputName = when {
            decOutputNames.contains("logits") -> "logits"
            else -> decOutputNames.iterator().next()
        }

        for (step in 0 until MAX_NEW_TOKENS) {
            // Feed the full sequence so far to the decoder
            val decInputTensor = createLongTensor(generated)

            try {
                val decoderResult = decSess.run(
                    mapOf(
                        decInputIdName to (decInputTensor as OnnxTensor),
                        decEncName to encoderHidden,
                    ),
                )

                try {
                    // Extract logits and sample next token
                    @Suppress("UNCHECKED_CAST")
                    val logitsTensor = decoderResult.get(decOutputName).get() as OnnxTensor
                    val nextToken = sampleNextToken(logitsTensor, generated.size)

                    if (nextToken == EOS_TOKEN_ID) break
                    generated.add(nextToken)
                } finally {
                    decoderResult.close()
                }
            } finally {
                if (!decInputTensor.isClosed) decInputTensor.close()
            }
        }

        // Strip the forced BOS token from the output
        return generated.drop(1)
    }

    // ─── Sampling ──────────────────────────────────────────────────────

    /**
     * Samples the next token ID from the decoder output logits.
     *
     * The decoder output is expected to have shape `[1, dec_seq_len, vocab_size]`.
     * Logits at the last position (the most recently generated token) are used
     * for greedy (argmax) sampling.
     */
    private fun sampleNextToken(logitsTensor: OnnxTensor, seqLen: Int): Long {
        val buf = logitsTensor.floatBuffer

        // Read shape: [batch, seq_len, vocab_size]
        val shape = logitsTensor.info.shape
        val vocabSize = if (shape.size >= 3) shape[2].toInt() else 250054 // MBart50 vocab size

        // Position the buffer at the last token's logits
        val offset = ((seqLen - 1).coerceAtLeast(0)) * vocabSize.toLong()
        val lastPosLogits = FloatArray(vocabSize)
        buf.position(offset.toInt())
        buf.get(lastPosLogits, 0, vocabSize)

        return greedySample(lastPosLogits)
    }

    /**
     * Greedy (argmax) sampling — returns the index of the highest logit.
     */
    private fun greedySample(logits: FloatArray): Long {
        var bestIdx = 0
        var bestVal = Float.NEGATIVE_INFINITY
        for (i in logits.indices) {
            if (logits[i] > bestVal) {
                bestVal = logits[i]
                bestIdx = i
            }
        }
        return bestIdx.toLong()
    }

    // ─── Tokenizer (placeholder) ───────────────────────────────────────

    /**
     * Placeholder tokenization — maps each character to its Unicode code point,
     * prepended by a source language marker token.
     *
     * TODO: Replace with proper SentencePiece tokenization using the loaded
     * [MBart50Tokenizer] when the native library is available.
     */
    private fun tokenizeWithLang(text: String, fromLang: String): Pair<List<Long>, List<Long>> {
        val tokenizer = mbartTokenizer
        if (tokenizer != null) {
            return tokenizer.encode(text, fromLang)
        }

        // Fallback: character-level placeholder tokenization
        // Prepend source language marker (0) and append EOS
        val ids = listOf(0L) + text.map { it.code.toLong() } + listOf(EOS_TOKEN_ID)
        return ids to List(ids.size) { 1L }
    }

    /**
     * Placeholder detokenization — maps each token ID back to a character.
     *
     * TODO: Replace with proper SentencePiece detokenization.
     */
    private fun detokenize(tokens: List<Long>): String {
        val tokenizer = mbartTokenizer
        if (tokenizer != null) {
            return tokenizer.decode(tokens)
        }

        // Fallback: character-level detokenization
        return tokens.map { it.toInt().toChar() }.joinToString("")
    }

    // ─── Tensor Helpers ────────────────────────────────────────────────

    /**
     * Creates an int64 ONNX tensor from a list of token IDs.
     *
     * Shape: `[1, values.size]`
     */
    private fun createLongTensor(values: List<Long>): OnnxTensor {
        val shape = longArrayOf(1, values.size.toLong())
        val buffer = LongBuffer.allocate(values.size)
        values.forEach { buffer.put(it) }
        buffer.flip()
        return OnnxTensor.createTensor(onnxSessionManager.environment, buffer, shape)
    }

    // ─── Inner: MBart50 Tokenizer Wrapper ─────────────────────────────

    /**
     * Wraps the MBart50 SentencePiece tokenizer model.
     *
     * The tokenizer file is a SentencePiece `.spm` model loaded as raw bytes.
     * In production, this class should delegate to the SentencePiece native
     * library (libsentencepiece.so) via JNI for efficient tokenization.
     *
     * For now, the [encode] and [decode] methods fall back to character-level
     * placeholders, matching the behaviour of [Qwen2Translator].
     *
     * @param modelBytes Raw bytes of the SentencePiece .spm model file.
     */
    private class MBart50Tokenizer(
        @Suppress("unused") private val modelBytes: ByteArray,
    ) {
        /**
         * Language code to token ID mapping.
         *
         * In the MBart50 SentencePiece vocabulary, each language code
         * (e.g. "en_XX", "zh_CN") is a special token with a specific ID.
         * This map is populated from the model metadata.
         *
         * TODO: Parse from SentencePiece model file when native lib is available.
         */
        private val langCodeToId: Map<String, Long> = mapOf(
            "ar_AR" to 250001L, "cs_CZ" to 250002L,
            "de_DE" to 250003L, "en_XX" to 250004L,
            "es_XX" to 250005L, "et_EE" to 250006L,
            "fi_FI" to 250007L, "fr_XX" to 250008L,
            "gu_IN" to 250009L, "hi_IN" to 250010L,
            "it_IT" to 250011L, "ja_XX" to 250012L,
            "kk_KZ" to 250013L, "ko_KR" to 250014L,
            "lt_LT" to 250015L, "lv_LV" to 250016L,
            "my_MM" to 250017L, "ne_NP" to 250018L,
            "nl_XX" to 250019L, "ro_RO" to 250020L,
            "ru_RU" to 250021L, "si_LK" to 250022L,
            "tr_TR" to 250023L, "vi_VN" to 250024L,
            "zh_CN" to 250025L, "af_ZA" to 250026L,
            "az_AZ" to 250027L, "bn_IN" to 250028L,
            "fa_IR" to 250029L, "he_IL" to 250030L,
            "hr_HR" to 250031L, "id_ID" to 250032L,
            "ka_GE" to 250033L, "km_KH" to 250034L,
            "mk_MK" to 250035L, "ml_IN" to 250036L,
            "mn_MN" to 250037L, "mr_IN" to 250038L,
            "pl_PL" to 250039L, "ps_AF" to 250040L,
            "pt_XX" to 250041L, "sv_SE" to 250042L,
            "sw_KE" to 250043L, "ta_IN" to 250044L,
            "te_IN" to 250045L, "th_TH" to 250046L,
            "tl_XX" to 250047L, "uk_UA" to 250048L,
            "ur_PK" to 250049L, "xh_ZA" to 250050L,
            "gl_ES" to 250051L, "sl_SI" to 250052L,
        )

        /**
         * Encodes input text with a source language prefix token.
         *
         * The MBart50 tokenizer prepends the source language token (e.g. `en_XX`)
         * to the input and appends the EOS token (`</s>`).
         *
         * @param text   The input text to tokenize.
         * @param srcLang Source language code (MBart50 format, e.g. "en_XX").
         * @return Pair of (input_ids, attention_mask).
         */
        fun encode(text: String, srcLang: String): Pair<List<Long>, List<Long>> {
            // TODO: Replace with actual SentencePiece tokenization.
            //       The current implementation uses character-level fallback.
            val langId = langCodeToId[srcLang] ?: 0L
            val ids = mutableListOf(langId)
            ids.addAll(text.map { it.code.toLong() })
            ids.add(EOS_TOKEN_ID)
            return ids to List(ids.size) { 1L }
        }

        /**
         * Decodes a list of token IDs back to text.
         *
         * @param tokenIds Token IDs to decode.
         * @return Decoded text string.
         */
        fun decode(tokenIds: List<Long>): String {
            // TODO: Replace with actual SentencePiece detokenization.
            return tokenIds.map { it.toInt().toChar() }.joinToString("")
        }

        /**
         * Returns the token ID for a given MBart50 language code.
         *
         * @param langCode MBart50 language code (e.g. "en_XX", "zh_CN").
         * @return Token ID for the language code, or 0L if not found.
         */
        fun getLangCodeId(langCode: String): Long {
            return langCodeToId[langCode] ?: 0L
        }
    }
}
