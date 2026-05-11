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
 * Qwen2 ONNX-based LLM translator.
 *
 * Translates manga text using a Qwen2 Instruct model exported to ONNX format.
 * Because Qwen2 is an autoregressive decoder-only LLM, this implementation
 * overrides [_translate] directly instead of using the standard
 * [OfflineOnnxTranslator._infer] preprocess/run/postprocess pipeline.
 *
 * ### Architecture
 * ```
 * _translate → translateSegment for each text:
 *   1. formatChatTemplate  — Qwen2 Instruct chat template
 *   2. tokenize            — text → token IDs (placeholder)
 *   3. generate            — autoregressive ONNX inference loop
 *   4. detokenize          — token IDs → text (placeholder)
 *   5. cleanOutput         — strip special tokens
 * ```
 *
 * ### OOM Protection
 * - [useInt8] enabled by default for quantised inference
 * - [OutOfMemoryError] caught per segment → returns original text + Log.w
 * - [MAX_NEW_TOKENS] capped at 128 to limit memory growth
 *
 * ### Model Info
 * - Points to [ModelRegistry.QWEN2_MODEL] (placeholder — actual model TBD)
 * - Tokenizer file expected at `qwen2_1.5b_tokenizer` alongside the model
 *
 * @param modelDownloadManager Used to download and verify the ONNX model.
 * @param onnxSessionManager   Singleton ONNX session factory.
 */
open class Qwen2Translator(
    modelDownloadManager: ModelDownloadManager,
    onnxSessionManager: OnnxSessionManager,
) : OfflineOnnxTranslator(modelDownloadManager, onnxSessionManager) {

    companion object {
        private const val TAG = "Qwen2Translator"

        // ── Qwen2 chat template tokens ───────────────────────────────
        private const val IM_START = "<|im_start|>"
        private const val IM_END = "<|im_end|>"
        private const val EOS_TOKEN = "<|im_end|>"

        // ── Generation parameters ────────────────────────────────────
        private const val MAX_NEW_TOKENS = 128
        private const val MAX_CONTEXT_LENGTH = 512
        private const val TOP_K = 40
        private const val TEMPERATURE = 0.7f

        // Qwen2-1.5B default EOS token ID (<|im_end|>)
        private const val DEFAULT_EOS_TOKEN_ID = 151645L
    }

    // ─── Initialisation ──────────────────────────────────────────────

    init {
        // Enable int8 quantisation by default for resource-constrained mobile devices.
        // The base class [createSessionOptions] applies the config entry when this is set.
        useInt8 = true
    }

    override val modelInfo: ModelInfo
        get() = ModelRegistry.QWEN2_MODEL

    // ─── Language code mapping ───────────────────────────────────────

    override val _LANGUAGE_CODE_MAP: Map<String, String> = mapOf(
        "CHS" to "Simplified Chinese",
        "CHT" to "Traditional Chinese",
        "CSY" to "Czech",
        "NLD" to "Dutch",
        "ENG" to "English",
        "FRA" to "French",
        "DEU" to "German",
        "HUN" to "Hungarian",
        "ITA" to "Italian",
        "JPN" to "Japanese",
        "KOR" to "Korean",
        "PLK" to "Polish",
        "PTB" to "Portuguese (Brazil)",
        "ROM" to "Romanian",
        "RUS" to "Russian",
        "ESP" to "Spanish",
        "TRK" to "Turkish",
        "UKR" to "Ukrainian",
        "VIN" to "Vietnamese",
        "ARA" to "Arabic",
        "IND" to "Indonesian",
    )

    // ─── State ───────────────────────────────────────────────────────

    /** EOS token ID, read from model metadata or default. */
    private var eosTokenId: Long = DEFAULT_EOS_TOKEN_ID

    /**
     * Raw tokenizer data loaded from the model directory.
     * Expected to be a HuggingFace `tokenizer.json` file.
     * TODO: Replace with proper HuggingFace tokenizer integration.
     */
    private var tokenizerBytes: ByteArray? = null

    // ─── Chat Template ───────────────────────────────────────────────

    /**
     * Formats a prompt using the Qwen2 Instruct chat template.
     *
     * Template:
     * ```
     * <|im_start|>system
     * {system_prompt}<|im_end|>
     * <|im_start|>user
     * {user_message}<|im_end|>
     * <|im_start|>assistant
     * {assistant_prefix}
     * ```
     */
    protected fun formatChatTemplate(
        systemPrompt: String,
        userMessages: List<String>,
        assistantPrefix: String = "",
    ): String = buildString {
        append("${IM_START}system\n$systemPrompt$IM_END\n")
        for (msg in userMessages) {
            append("${IM_START}user\n$msg$IM_END\n")
        }
        append("${IM_START}assistant\n$assistantPrefix")
    }

    // ─── Override _translate (bypass standard pipeline) ──────────────

    /**
     * Qwen2 uses autoregressive generation and does not fit the standard
     * [preprocess]/[postprocess] pipeline.  These stubs must be provided
     * to satisfy the abstract contract but are never called.
     */
    override suspend fun preprocess(texts: List<String>): Map<String, OnnxTensor> =
        throw UnsupportedOperationException(
            "Qwen2 uses autoregressive generation, not standard preprocess",
        )

    override suspend fun postprocess(result: OrtSession.Result, texts: List<String>): List<String> =
        throw UnsupportedOperationException(
            "Qwen2 uses autoregressive generation, not standard postprocess",
        )

    /**
     * Translates a batch of text segments using the Qwen2 model.
     *
     * Each segment is processed individually (batching autoregressive
     * generation across segments is non-trivial and left as future work).
     *
     * @param fromLang Source language code (resolved by [parseLanguageCodes]).
     * @param toLang   Target language code (resolved by [parseLanguageCodes]).
     * @param queries  Source text segments to translate.
     * @return Translated text segments, one per input query.
     */
    override suspend fun _translate(
        fromLang: String,
        toLang: String,
        queries: List<String>,
    ): List<String> {
        val sess = session
        if (sess == null) {
            Log.w(TAG, "Model not loaded — returning original text")
            return queries
        }

        val results = MutableList(queries.size) { "" }
        for ((i, query) in queries.withIndex()) {
            results[i] = translateSegment(sess, query, fromLang, toLang)
        }
        return results
    }

    /**
     * Translates a single text segment.
     *
     * Steps:
     * 1. Build the Qwen2 Instruct prompt with system message
     * 2. Tokenise the prompt
     * 3. Run autoregressive generation
     * 4. Detokenise the output
     * 5. Clean special tokens from the response
     *
     * On failure (OOM or model error), the original [text] is returned as
     * a graceful fallback.
     */
    private suspend fun translateSegment(
        sess: OrtSession,
        text: String,
        fromLang: String,
        toLang: String,
    ): String = try {
        val prompt = formatChatTemplate(
            systemPrompt = "You are a professional manga translator. Translate the following text from $fromLang to $toLang. Output only the translation.",
            userMessages = listOf(text),
            assistantPrefix = "",
        )
        val inputIds = tokenize(prompt)
        val outputIds = generate(sess, inputIds)
        val rawOutput = detokenize(outputIds)
        cleanOutput(rawOutput)
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "OOM for segment: ${text.take(40)}…, returning original", e)
        text
    } catch (e: Exception) {
        Log.e(TAG, "Inference failed for segment: ${text.take(40)}…", e)
        text
    }

    // ─── Tokenizer (placeholder) ─────────────────────────────────────

    /**
     * Loads the HuggingFace tokenizer file from the model directory.
     *
     * The tokenizer is expected to be a HuggingFace `tokenizer.json` file
     * stored alongside the ONNX model, named `qwen2_1.5b_tokenizer`.
     *
     * TODO: Implement actual BPE / tiktoken tokenization.  The current
     *       [tokenize] method is a placeholder that maps characters to
     *       their Unicode code points, which will not produce meaningful
     *       translations.
     */
    override suspend fun loadTokenizer() {
        try {
            val tokenizerFile = modelDownloadManager.getModelFile("${modelInfo.name}_tokenizer")
            if (tokenizerFile.exists()) {
                tokenizerBytes = tokenizerFile.readBytes()
                Log.d(TAG, "Tokenizer loaded (${tokenizerBytes?.size ?: 0} bytes)")
            } else {
                Log.w(TAG, "Tokenizer file not found at ${tokenizerFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load tokenizer", e)
        }
    }

    override suspend fun unloadTokenizer() {
        tokenizerBytes = null
    }

    /**
     * Placeholder tokenization — maps each character to its Unicode code point.
     *
     * TODO: Replace with proper HuggingFace tokenizer (tokenizer.json)
     * using either ONNX Runtime's built-in tokenizer or a Kotlin port of
     * HuggingFace tokenizers (e.g. via the `tokenizers` JNI library).
     */
    private fun tokenize(text: String): List<Long> {
        return text.map { it.code.toLong() }
    }

    /**
     * Placeholder detokenization — maps each token ID back to a character.
     *
     * TODO: Replace with proper detokenizer matching [tokenize].
     */
    private fun detokenize(tokens: List<Long>): String {
        return tokens.map { it.toInt().toChar() }.joinToString("")
    }

    // ─── Autoregressive Generation ───────────────────────────────────

    /**
     * Runs the autoregressive generation loop.
     *
     * At each step, the full token sequence (prompt + generated so far) is
     * fed through the ONNX model.  The logits for the last position are
     * extracted, and the highest-probability token is selected (greedy).
     *
     * Generation stops when:
     * - The EOS token is produced
     * - [MAX_NEW_TOKENS] new tokens have been generated
     * - The total sequence length reaches [MAX_CONTEXT_LENGTH]
     *
     * @param sess     The loaded ONNX session.
     * @param inputIds The tokenised prompt (input to the model).
     * @return Only the **newly generated** tokens (prompt tokens stripped).
     */
    private suspend fun generate(
        sess: OrtSession,
        inputIds: List<Long>,
    ): List<Long> {
        val generated = inputIds.toMutableList()
        val maxLen = minOf(inputIds.size + MAX_NEW_TOKENS, MAX_CONTEXT_LENGTH)

        // Determine ONNX input/output names from the session metadata
        val inputNames = sess.inputNames
        val inputIdName = if (inputNames.contains("input_ids")) "input_ids"
            else inputNames.iterator().next()
        val maskName = if (inputNames.contains("attention_mask")) "attention_mask"
            else if (inputNames.size > 1) inputNames.drop(1).iterator().next()
            else "attention_mask"

        val outputNames = sess.outputNames
        val outputName = if (outputNames.contains("logits")) "logits"
            else outputNames.iterator().next()

        for (step in 0 until MAX_NEW_TOKENS) {
            if (generated.size >= maxLen) break

            // Create input tensors with current sequence
            val inputTensor = createLongTensor(generated)
            val maskTensor = createLongTensor(List(generated.size) { 1L })

            try {
                val outputs = sess.run(
                    mapOf(
                        inputIdName to (inputTensor as OnnxTensor),
                        maskName to (maskTensor as OnnxTensor),
                    ),
                )

                try {
                    val nextToken = sampleNextToken(outputs, outputName, generated.size)
                    if (nextToken == eosTokenId) break
                    generated.add(nextToken)
                } finally {
                    outputs.close()
                }
            } finally {
                if (!inputTensor.isClosed) inputTensor.close()
                if (!maskTensor.isClosed) maskTensor.close()
            }
        }

        // Return only the newly generated tokens (strip the prompt)
        return generated.drop(inputIds.size)
    }

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

    /**
     * Extracts the next-token logits from the ONNX model output and
     * returns the token ID with the highest probability (greedy decoding).
     *
     * The model output is expected to have shape `[1, seq_len, vocab_size]`.
     * Logits at position `seq_len - 1` (the last token in the sequence)
     * are used to predict the next token.
     */
    private fun sampleNextToken(
        outputs: OrtSession.Result,
        outputName: String,
        seqLen: Int,
    ): Long {
        val logitsTensor = outputs.get(outputName) as OnnxTensor
        val buf = logitsTensor.floatBuffer

        // Read shape: [batch, seq_len, vocab_size]
        val shape = logitsTensor.info.shape
        val vocabSize = if (shape.size >= 3) shape[2].toInt() else 152064

        // Position the buffer at the last token's logits
        val offset = ((seqLen - 1).coerceAtLeast(0)) * vocabSize.toLong()
        val lastPosLogits = FloatArray(vocabSize)
        buf.position(offset.toInt())
        buf.get(lastPosLogits, 0, vocabSize)

        return greedySample(lastPosLogits)
    }

    /**
     * Greedy (argmax) sampling — returns the index of the highest logit.
     *
     * For production use, consider [topKSample] or temperature scaling
     * for more diverse outputs.
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

    /**
     * Top-K filtered sampling (reserved for future use).
     *
     * Filters to the top-K logits, applies softmax, and samples from the
     * resulting distribution.  Currently defaults to greedy (argmax) for
     * deterministic output.
     */
    @Suppress("unused")
    private fun topKSample(logits: FloatArray, k: Int): Long {
        val indexed = logits.mapIndexed { i, v -> i to v }
            .sortedByDescending { it.second }
            .take(k)

        // Softmax over top-K
        val maxLogit = indexed.maxOf { it.second }
        val expVals = indexed.map { kotlin.math.exp((it.second - maxLogit).toDouble()) }
        val sumExp = expVals.sum()
        @Suppress("UNUSED_VARIABLE")
        val probs = expVals.map { it / sumExp }

        // Greedy (argmax) for simplicity; replace with multinomial sampling
        // when probabilistic behaviour is desired.
        return indexed[0].first.toLong()
    }

    // ─── Output Cleaning ─────────────────────────────────────────────

    /**
     * Strips Qwen2 special tokens from the model output.
     *
     * Removes:
     * - `<|im_start|>` tokens
     * - `<|im_end|>` tokens (including EOS)
     * - Leading/trailing whitespace
     */
    private fun cleanOutput(output: String): String {
        return output
            .replace(IM_START, "")
            .replace(IM_END, "")
            .replace(EOS_TOKEN, "")
            .trim()
    }

    override fun getLanguageCodeMap(): Map<String, String> = _LANGUAGE_CODE_MAP
}
