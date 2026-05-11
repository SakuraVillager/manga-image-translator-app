package com.sakuravillager.manga_translator.translation.translator

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.util.Log
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.model.ModelInfo
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager
import com.sakuravillager.manga_translator.translation.translator.common.OfflineOnnxTranslator
import java.nio.LongBuffer

/**
 * NLLB-200-distilled-600M ONNX translator — Meta's No Language Left Behind.
 *
 * NLLB is a massively multilingual encoder-decoder transformer supporting 200
 * languages (FLORES-200). This implementation uses ONNX Runtime with separate
 * encoder and decoder models exported via HuggingFace Optimum.
 *
 * ### Architecture
 * ```
 * _translate → translateSegment for each text:
 *   1. tokenizeWithLang  — prepend source lang token + SentencePiece
 *   2. runEncoder         — ONNX encoder forward pass → encoder_hidden_states
 *   3. generate           — autoregressive decoder loop with target BOS
 *   4. detokenize         — token IDs → text
 * ```
 *
 * ### ONNX Model Files
 * - `nllb_600m_encoder.onnx` — input_ids + attention_mask → last_hidden_state
 * - `nllb_600m_decoder.onnx` — input_ids + encoder_hidden_states → logits
 * - `nllb_600m_tokenizer.spm` — SentencePiece model for tokenization
 *
 * ### Language Codes
 * NLLB uses FLORES-200 codes in `{lang}_{script}` format (e.g. `eng_Latn`,
 * `zho_Hans`, `jpn_Jpan`). See [FLORES_200_LANGUAGES] for the full list.
 *
 * @param modelDownloadManager Used to download and verify model files.
 * @param onnxSessionManager  Singleton ONNX session factory.
 */
open class NllbTranslator(
    modelDownloadManager: ModelDownloadManager,
    onnxSessionManager: OnnxSessionManager,
) : OfflineOnnxTranslator(modelDownloadManager, onnxSessionManager) {

    companion object {
        private const val TAG = "NllbTranslator"

        /** Maximum new tokens to generate in the autoregressive decoder loop. */
        private const val MAX_NEW_TOKENS = 128

        /** Maximum encoder input length (source text tokens). */
        private const val MAX_ENCODER_LENGTH = 256

        // ── NLLB special token IDs (SentencePiece) ─────────────────────────
        private const val PAD_TOKEN_ID = 1L
        private const val EOS_TOKEN_ID = 2L

        /** Model name prefix for file resolution. */
        protected const val MODEL_NAME = "nllb_600m"

        // ─── FLORES-200 language codes (NLLB-200 full reference) ───────────
        //
        // Complete list of all 200+ languages supported by NLLB-200.
        // Format: FLORES-200 code → English language name.
        // Source: https://github.com/facebookresearch/flores/blob/main/flores200
        //
        // Codes use `{iso639-3}_{script}` naming (e.g. eng_Latn, zho_Hans).
        val FLORES_200_LANGUAGES: Map<String, String> = mapOf(
            "ace_Arab" to "Acehnese (Arabic script)",
            "ace_Latn" to "Acehnese (Latin script)",
            "acm_Arab" to "Mesopotamian Arabic",
            "acq_Arab" to "Ta'izzi-Adeni Arabic",
            "aeb_Arab" to "Tunisian Arabic",
            "afr_Latn" to "Afrikaans",
            "ajp_Arab" to "South Levantine Arabic",
            "aka_Latn" to "Akan",
            "amh_Ethi" to "Amharic",
            "apc_Arab" to "North Levantine Arabic",
            "arb_Arab" to "Modern Standard Arabic",
            "arb_Latn" to "Modern Standard Arabic (Romanized)",
            "ars_Arab" to "Najdi Arabic",
            "ary_Arab" to "Moroccan Arabic",
            "arz_Arab" to "Egyptian Arabic",
            "asm_Beng" to "Assamese",
            "ast_Latn" to "Asturian",
            "awa_Deva" to "Awadhi",
            "ayr_Latn" to "Central Aymara",
            "azb_Arab" to "South Azerbaijani",
            "azj_Latn" to "North Azerbaijani",
            "bak_Cyrl" to "Bashkir",
            "bam_Latn" to "Bambara",
            "ban_Latn" to "Balinese",
            "bel_Cyrl" to "Belarusian",
            "bem_Latn" to "Bemba",
            "ben_Beng" to "Bengali",
            "bho_Deva" to "Bhojpuri",
            "bjn_Arab" to "Banjar (Arabic script)",
            "bjn_Latn" to "Banjar (Latin script)",
            "bod_Tibt" to "Standard Tibetan",
            "bos_Latn" to "Bosnian",
            "bug_Latn" to "Buginese",
            "bul_Cyrl" to "Bulgarian",
            "cat_Latn" to "Catalan",
            "ceb_Latn" to "Cebuano",
            "ces_Latn" to "Czech",
            "cjk_Latn" to "Chokwe",
            "ckb_Arab" to "Central Kurdish",
            "crh_Latn" to "Crimean Tatar",
            "cym_Latn" to "Welsh",
            "dan_Latn" to "Danish",
            "deu_Latn" to "German",
            "dik_Latn" to "Southwestern Dinka",
            "dyu_Latn" to "Dyula",
            "dzo_Tibt" to "Dzongkha",
            "ell_Grek" to "Greek",
            "eng_Latn" to "English",
            "epo_Latn" to "Esperanto",
            "est_Latn" to "Estonian",
            "eus_Latn" to "Basque",
            "ewe_Latn" to "Ewe",
            "fao_Latn" to "Faroese",
            "fij_Latn" to "Fijian",
            "fin_Latn" to "Finnish",
            "fon_Latn" to "Fon",
            "fra_Latn" to "French",
            "fur_Latn" to "Friulian",
            "fuv_Latn" to "Nigerian Fulfulde",
            "gla_Latn" to "Scottish Gaelic",
            "gle_Latn" to "Irish",
            "glg_Latn" to "Galician",
            "grn_Latn" to "Guarani",
            "guj_Gujr" to "Gujarati",
            "hat_Latn" to "Haitian Creole",
            "hau_Latn" to "Hausa",
            "heb_Hebr" to "Hebrew",
            "hin_Deva" to "Hindi",
            "hne_Deva" to "Chhattisgarhi",
            "hrv_Latn" to "Croatian",
            "hun_Latn" to "Hungarian",
            "hye_Armn" to "Armenian",
            "ibo_Latn" to "Igbo",
            "ilo_Latn" to "Ilocano",
            "ind_Latn" to "Indonesian",
            "isl_Latn" to "Icelandic",
            "ita_Latn" to "Italian",
            "jav_Latn" to "Javanese",
            "jpn_Jpan" to "Japanese",
            "kab_Latn" to "Kabyle",
            "kac_Latn" to "Jingpho",
            "kam_Latn" to "Kamba",
            "kan_Knda" to "Kannada",
            "kas_Arab" to "Kashmiri (Arabic script)",
            "kas_Deva" to "Kashmiri (Devanagari script)",
            "kat_Geor" to "Georgian",
            "knc_Arab" to "Central Kanuri (Arabic script)",
            "knc_Latn" to "Central Kanuri (Latin script)",
            "kaz_Cyrl" to "Kazakh",
            "kbp_Latn" to "Kabiyè",
            "kea_Latn" to "Kabuverdianu",
            "khm_Khmr" to "Khmer",
            "kik_Latn" to "Kikuyu",
            "kin_Latn" to "Kinyarwanda",
            "kir_Cyrl" to "Kyrgyz",
            "kmb_Latn" to "Kimbundu",
            "kmr_Latn" to "Northern Kurdish",
            "kon_Latn" to "Kikongo",
            "kor_Hang" to "Korean",
            "lao_Laoo" to "Lao",
            "lij_Latn" to "Ligurian",
            "lim_Latn" to "Limburgish",
            "lin_Latn" to "Lingala",
            "lit_Latn" to "Lithuanian",
            "lmo_Latn" to "Lombard",
            "ltg_Latn" to "Latgalian",
            "ltz_Latn" to "Luxembourgish",
            "lua_Latn" to "Luba-Kasai",
            "lug_Latn" to "Ganda",
            "luo_Latn" to "Luo",
            "lus_Latn" to "Mizo",
            "lvs_Latn" to "Standard Latvian",
            "mag_Deva" to "Magahi",
            "mai_Deva" to "Maithili",
            "mal_Mlym" to "Malayalam",
            "mar_Deva" to "Marathi",
            "min_Arab" to "Minangkabau (Arabic script)",
            "min_Latn" to "Minangkabau (Latin script)",
            "mkd_Cyrl" to "Macedonian",
            "plt_Latn" to "Plateau Malagasy",
            "mlt_Latn" to "Maltese",
            "mni_Beng" to "Meitei (Bengali script)",
            "khk_Cyrl" to "Halh Mongolian",
            "mos_Latn" to "Mossi",
            "mri_Latn" to "Maori",
            "mya_Mymr" to "Burmese",
            "nld_Latn" to "Dutch",
            "nno_Latn" to "Norwegian Nynorsk",
            "nob_Latn" to "Norwegian Bokmål",
            "npi_Deva" to "Nepali",
            "nso_Latn" to "Northern Sotho",
            "nus_Latn" to "Nuer",
            "nya_Latn" to "Nyanja",
            "oci_Latn" to "Occitan",
            "gaz_Latn" to "West Central Oromo",
            "ory_Orya" to "Odia",
            "pag_Latn" to "Pangasinan",
            "pan_Guru" to "Eastern Panjabi",
            "pap_Latn" to "Papiamento",
            "pes_Arab" to "Western Persian",
            "pol_Latn" to "Polish",
            "por_Latn" to "Portuguese",
            "prs_Arab" to "Dari",
            "pbt_Arab" to "Southern Pashto",
            "quy_Latn" to "Ayacucho Quechua",
            "ron_Latn" to "Romanian",
            "run_Latn" to "Rundi",
            "rus_Cyrl" to "Russian",
            "sag_Latn" to "Sango",
            "san_Deva" to "Sanskrit",
            "sat_Olck" to "Santali",
            "scn_Latn" to "Sicilian",
            "shn_Mymr" to "Shan",
            "sin_Sinh" to "Sinhala",
            "slk_Latn" to "Slovak",
            "slv_Latn" to "Slovenian",
            "smo_Latn" to "Samoan",
            "sna_Latn" to "Shona",
            "snd_Arab" to "Sindhi",
            "som_Latn" to "Somali",
            "sot_Latn" to "Southern Sotho",
            "spa_Latn" to "Spanish",
            "als_Latn" to "Tosk Albanian",
            "srd_Latn" to "Sardinian",
            "srp_Cyrl" to "Serbian",
            "ssw_Latn" to "Swati",
            "sun_Latn" to "Sundanese",
            "swe_Latn" to "Swedish",
            "swh_Latn" to "Swahili",
            "szl_Latn" to "Silesian",
            "tam_Taml" to "Tamil",
            "tat_Cyrl" to "Tatar",
            "tel_Telu" to "Telugu",
            "tgk_Cyrl" to "Tajik",
            "tgl_Latn" to "Tagalog",
            "tha_Thai" to "Thai",
            "tir_Ethi" to "Tigrinya",
            "taq_Latn" to "Tamasheq (Latin script)",
            "taq_Tfng" to "Tamasheq (Tifinagh script)",
            "tpi_Latn" to "Tok Pisin",
            "tsn_Latn" to "Tswana",
            "tso_Latn" to "Tsonga",
            "tuk_Latn" to "Turkmen",
            "tum_Latn" to "Tumbuka",
            "tur_Latn" to "Turkish",
            "twi_Latn" to "Twi",
            "tzm_Tfng" to "Central Atlas Tamazight",
            "uig_Arab" to "Uyghur",
            "ukr_Cyrl" to "Ukrainian",
            "umb_Latn" to "Umbundu",
            "urd_Arab" to "Urdu",
            "uzn_Latn" to "Northern Uzbek",
            "vec_Latn" to "Venetian",
            "vie_Latn" to "Vietnamese",
            "war_Latn" to "Waray",
            "wol_Latn" to "Wolof",
            "xho_Latn" to "Xhosa",
            "ydd_Hebr" to "Eastern Yiddish",
            "yor_Latn" to "Yoruba",
            "yue_Hant" to "Yue Chinese",
            "zho_Hans" to "Chinese (Simplified)",
            "zho_Hant" to "Chinese (Traditional)",
            "zsm_Latn" to "Standard Malay",
            "zul_Latn" to "Zulu",
        )

        // ─── Model definitions ─────────────────────────────────────────────

        /**
         * Encoder ONNX model metadata for NLLB-200-distilled-600M.
         *
         * ⚠️ PLACEHOLDER — SHA-256 hash and URL are not yet finalised.
         */
        val NLLB_ENCODER_MODEL = ModelInfo(
            name = "${MODEL_NAME}_encoder",
            url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/${MODEL_NAME}_encoder.onnx",
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            sizeBytes = 600_000_000L,
        )

        /**
         * Decoder ONNX model metadata for NLLB-200-distilled-600M.
         *
         * ⚠️ PLACEHOLDER — SHA-256 hash and URL are not yet finalised.
         */
        val NLLB_DECODER_MODEL = ModelInfo(
            name = "${MODEL_NAME}_decoder",
            url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/${MODEL_NAME}_decoder.onnx",
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            sizeBytes = 600_000_000L,
        )

        /**
         * SentencePiece tokenizer model metadata for NLLB-200-distilled-600M.
         *
         * ⚠️ PLACEHOLDER — SHA-256 hash and URL are not yet finalised.
         */
        val NLLB_TOKENIZER_MODEL = ModelInfo(
            name = "${MODEL_NAME}_tokenizer",
            url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/${MODEL_NAME}_tokenizer.spm",
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            sizeBytes = 1_000_000L,
        )
    }

    // ─── Language Code Map ───────────────────────────────────────────────

    /**
     * Maps internal language codes (from [VALID_LANGUAGES]) to NLLB FLORES-200
     * codes. Both Simplified and Traditional Chinese map to their respective
     * NLLB codes (`zho_Hans` / `zho_Hant`).
     *
     * NLLB supports 200+ languages. This map covers all languages exposed by
     * the app plus additional common NLLB languages for future expansion.
     * See [FLORES_200_LANGUAGES] for the complete reference.
     */
    override val _LANGUAGE_CODE_MAP: Map<String, String> = mapOf(
        // ── Primary app languages ──────────────────────────────────────
        "ARA" to "arb_Arab",
        "CHS" to "zho_Hans",
        "CHT" to "zho_Hant",
        "CSY" to "ces_Latn",
        "DEU" to "deu_Latn",
        "ENG" to "eng_Latn",
        "ESP" to "spa_Latn",
        "FRA" to "fra_Latn",
        "HRV" to "hrv_Latn",
        "HUN" to "hun_Latn",
        "IND" to "ind_Latn",
        "ITA" to "ita_Latn",
        "JPN" to "jpn_Jpan",
        "KOR" to "kor_Hang",
        "NLD" to "nld_Latn",
        "POL" to "pol_Latn",
        "PTB" to "por_Latn",
        "ROM" to "ron_Latn",
        "RUS" to "rus_Cyrl",
        "SRP" to "srp_Cyrl",
        "THA" to "tha_Thai",
        "TRK" to "tur_Latn",
        "UKR" to "ukr_Cyrl",
        "VIN" to "vie_Latn",
        // ── Additional NLLB languages (app internal codes) ─────────────
        "BEL" to "bel_Cyrl",      // Belarusian
        "BEN" to "ben_Beng",      // Bengali
        "BUL" to "bul_Cyrl",      // Bulgarian
        "CAT" to "cat_Latn",      // Catalan
        "CES" to "ces_Latn",      // Czech (same as CSY)
        "DAN" to "dan_Latn",      // Danish
        "EST" to "est_Latn",      // Estonian
        "FIL" to "tgl_Latn",      // Filipino (Tagalog)
        "FIN" to "fin_Latn",      // Finnish
        "GEO" to "kat_Geor",      // Georgian
        "GLG" to "glg_Latn",      // Galician
        "GUJ" to "guj_Gujr",      // Gujarati
        "HEB" to "heb_Hebr",      // Hebrew
        "HIN" to "hin_Deva",      // Hindi
        "KAZ" to "kaz_Cyrl",      // Kazakh
        "KHM" to "khm_Khmr",      // Khmer
        "LAO" to "lao_Laoo",      // Lao
        "LIT" to "lit_Latn",      // Lithuanian
        "LAV" to "lvs_Latn",      // Latvian
        "MKD" to "mkd_Cyrl",      // Macedonian
        "MLT" to "mlt_Latn",      // Maltese
        "MON" to "khk_Cyrl",      // Mongolian (Halh)
        "MAR" to "mar_Deva",      // Marathi
        "MYA" to "mya_Mymr",      // Burmese
        "NEP" to "npi_Deva",      // Nepali
        "PUS" to "pbt_Arab",      // Pashto
        "SIN" to "sin_Sinh",      // Sinhala
        "SLO" to "slk_Latn",      // Slovak
        "SLV" to "slv_Latn",      // Slovenian
        "SWA" to "swh_Latn",      // Swahili
        "SWE" to "swe_Latn",      // Swedish
        "TAM" to "tam_Taml",      // Tamil
        "TEL" to "tel_Telu",      // Telugu
        "TGL" to "tgl_Latn",      // Tagalog
        "URD" to "urd_Arab",      // Urdu
        "UZB" to "uzn_Latn",      // Uzbek
        "XHO" to "xho_Latn",      // Xhosa
        "ZHO" to "zho_Hans",      // Chinese (generic → Simplified)
    )

    override fun getLanguageCodeMap(): Map<String, String> = _LANGUAGE_CODE_MAP

    // ─── State ─────────────────────────────────────────────────────────

    /** Decoder ONNX session (loaded alongside the encoder session). */
    private var decoderSession: OrtSession? = null

    /** NLLB SentencePiece tokenizer wrapper. */
    private var nllbTokenizer: SentencePieceTokenizer? = null

    /**
     * Maximum source sequence length (in tokens).
     * Can be overridden for larger contexts.
     */
    protected open val maxSourceLength: Int = MAX_ENCODER_LENGTH

    /**
     * Maximum target sequence length (in tokens) for decoder generation.
     */
    protected open val maxTargetLength: Int = MAX_NEW_TOKENS

    // ─── Model metadata ───────────────────────────────────────────────

    override val name: String = "NLLB"

    override val modelInfo: ModelInfo
        get() = NLLB_ENCODER_MODEL

    /**
     * Decoder model info (loaded separately from the encoder).
     */
    protected open val decoderModelInfo: ModelInfo
        get() = NLLB_DECODER_MODEL

    /**
     * Tokenizer model info.
     */
    protected open val tokenizerModelInfo: ModelInfo
        get() = NLLB_TOKENIZER_MODEL

    // ─── Model Lifecycle ───────────────────────────────────────────────

    /**
     * Overrides [OfflineOnnxTranslator.loadModel] to load both the encoder
     * (via super) and the decoder ONNX model, plus the SentencePiece tokenizer.
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

            // Set ready flag
            val isReady = session != null && decoderSession != null && nllbTokenizer != null
            if (!isReady) {
                Log.w(TAG, "NLLB model partially loaded: encoder=${session != null}, decoder=${decoderSession != null}, tokenizer=${nllbTokenizer != null}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load NLLB model", e)
            session = null
            decoderSession = null
            nllbTokenizer = null
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
        nllbTokenizer = null
    }

    // ─── Tokenizer Lifecycle ───────────────────────────────────────────

    /**
     * Loads the SentencePiece tokenizer model file.
     *
     * The tokenizer file is expected to be named `nllb_600m_tokenizer.spm`
     * alongside the encoder and decoder ONNX models.
     */
    override suspend fun loadTokenizer() {
        try {
            val tokenizerFile = modelDownloadManager.getModelFile(tokenizerModelInfo.name)
            if (tokenizerFile.exists()) {
                val bytes = tokenizerFile.readBytes()
                nllbTokenizer = SentencePieceTokenizer(bytes)
                Log.d(TAG, "NLLB tokenizer loaded (${bytes.size} bytes)")
            } else {
                Log.w(TAG, "Tokenizer file not found at ${tokenizerFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load tokenizer", e)
        }
    }

    override suspend fun unloadTokenizer() {
        nllbTokenizer = null
    }

    // ─── Pipeline stubs ────────────────────────────────────────────────

    /**
     * NLLB uses a custom encoder-decoder pipeline in [_translate].
     * The standard [preprocess]/[postprocess] pipeline is not used.
     */
    override suspend fun preprocess(texts: List<String>): Map<String, OnnxTensor> =
        throw UnsupportedOperationException(
            "NLLB uses encoder-decoder autoregressive pipeline, not standard preprocess",
        )

    override suspend fun postprocess(result: OrtSession.Result, texts: List<String>): List<String> =
        throw UnsupportedOperationException(
            "NLLB uses encoder-decoder autoregressive pipeline, not standard postprocess",
        )

    // ─── Translation ───────────────────────────────────────────────────

    /**
     * Translates a batch of text segments using the NLLB encoder-decoder model.
     *
     * Each segment is processed individually (no batching across segments).
     *
     * @param fromLang Resolved source language code (FLORES-200 format, e.g. "eng_Latn").
     * @param toLang   Resolved target language code (FLORES-200 format, e.g. "zho_Hans").
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
     * 1. Tokenize input with source language prefix token + SentencePiece
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
     * - [maxTargetLength] new tokens have been generated
     *
     * @param decSess       The decoder ONNX session.
     * @param encoderHidden The encoder hidden states tensor (from [runEncoder]).
     * @param toLang        Target language code (e.g. "zho_Hans") for forced BOS.
     * @return List of generated token IDs (excluding the forced BOS token).
     */
    private suspend fun generate(
        decSess: OrtSession,
        encoderHidden: OnnxTensor,
        toLang: String,
    ): List<Long> {
        // Get the target language BOS token ID from the tokenizer vocabulary.
        // NLLB uses the language code itself as the decoder-start token
        // (e.g. "zho_Hans" is in the SentencePiece vocabulary).
        val targetBosId = getLangTokenId(toLang) ?: 0L

        // Start with the target language token
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

        for (step in 0 until maxTargetLength) {
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
     *
     * NLLB-200-distilled-600M has a vocabulary size of ~256,000.
     */
    private fun sampleNextToken(logitsTensor: OnnxTensor, seqLen: Int): Long {
        val buf = logitsTensor.floatBuffer

        // Read shape: [batch, seq_len, vocab_size]
        val shape = logitsTensor.info.shape
        val vocabSize = if (shape.size >= 3) shape[2].toInt() else 256_206 // NLLB-200 vocab size

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

    // ─── Tokenization ─────────────────────────────────────────────────

    /**
     * Tokenizes input text with the source language prefix token.
     *
     * For NLLB, the source language code (e.g. `eng_Latn`) is a token in the
     * SentencePiece vocabulary. The input to the encoder is:
     * ```
     * <s> {srcLang} {text} </s>
     * ```
     *
     * @param text    The input text to tokenize.
     * @param srcLang Source language code (FLORES-200 format, e.g. "eng_Latn").
     * @return Pair of (input_ids, attention_mask).
     */
    private fun tokenizeWithLang(text: String, srcLang: String): Pair<List<Long>, List<Long>> {
        val tokenizer = nllbTokenizer
        if (tokenizer != null) {
            // Look up the source language token ID in the SentencePiece vocabulary
            val srcLangTokenId = tokenizer.pieceToId[srcLang]?.toLong() ?: 0L

            // Encode the text using SentencePiece
            val textIds = tokenizer.encode(text)

            // Build input: <s> {srcLang} {text} </s>
            val bosId = tokenizer.bosId.toLong()
            val eosId = tokenizer.eosId.toLong()

            val ids = mutableListOf(bosId, srcLangTokenId)
            ids.addAll(textIds.map { it.toLong() })
            ids.add(eosId)

            // Apply source length cap
            val cappedIds = if (ids.size > maxSourceLength) {
                // Keep BOS + lang token, cap the rest, add EOS
                listOf(ids[0], ids[1]) +
                    ids.subList(2, ids.size - 1).take(maxSourceLength - 3).map { it } +
                    listOf(ids.last())
            } else ids

            return cappedIds to List(cappedIds.size) { 1L }
        }

        // Fallback: character-level placeholder tokenization
        val ids = listOf(0L) + text.map { it.code.toLong() } + listOf(EOS_TOKEN_ID)
        return ids to List(ids.size) { 1L }
    }

    /**
     * Detokenizes decoder output token IDs back to text.
     *
     * Uses SentencePiece decoder via [SentencePieceTokenizer.decode].
     *
     * @param tokens Token IDs to detokenize (excluding BOS).
     * @return Decoded text string.
     */
    private fun detokenize(tokens: List<Long>): String {
        val tokenizer = nllbTokenizer
        if (tokenizer != null) {
            val intIds = tokens.map { it.toInt() }
            return tokenizer.decode(intIds)
        }

        // Fallback: character-level detokenization
        return tokens.map { it.toInt().toChar() }.joinToString("")
    }

    /**
     * Returns the token ID for an NLLB FLORES-200 language code.
     *
     * In the NLLB SentencePiece vocabulary, each FLORES-200 language code
     * (e.g. `eng_Latn`, `zho_Hans`, `jpn_Jpan`) is a token included in the
     * vocabulary, as these are special "control tokens" or regular tokens
     * in the SentencePiece model.
     *
     * @param langCode FLORES-200 language code (e.g. "eng_Latn", "zho_Hans").
     * @return Token ID for the language code, or `null` if not found.
     */
    private fun getLangTokenId(langCode: String): Long? {
        val tokenizer = nllbTokenizer ?: return null
        return tokenizer.pieceToId[langCode]?.toLong()
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
}
