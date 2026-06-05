package com.sakuravillager.manga_translator.translation.model

/**
 * Registry of downloadable model files.
 *
 * Default URLs point to the upstream Python project's GitHub release.
 * URLs can be overridden via AppPreferences (advanced settings) for power users.
 *
 * Models marked "ASSETS" are bundled in app/src/main/assets/models/
 * and loaded directly — no download needed.
 */
object ModelRegistry {
    val CTD_MODEL = ModelInfo(
        name = "comictextdetector",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/comictextdetector.pt.onnx",
        sha256 = "1a86ace74961413cbd650002e7bb4dcec4980ffa21b2f19b86933372071d718f",
        sizeBytes = 25_000_000L,
    )

    /** CTC OCR model — bundled in assets/models/ocr_ctc_48px.onnx (0.8 MB). */
    val OCR_48PX_MODEL = ModelInfo(
        name = "ocr_ctc_48px",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/ocr-ctc.zip",
        sha256 = "a6e6f5b3b12e41e0fa39f2c17a8284101dced6c566ac7cfec90ce98b6da5a67f",
        sizeBytes = 812_837L,
    )

    /**
     * AR 48px encoder ONNX model — ConvNeXt backbone + 4 XPOS TransformerEncoder layers.
     *
     * Input:  img [N, 3, 48, W] float32, img_widths [N] int64
     * Output: memory [N, W', 320] float32, input_mask [N, W'] bool
     *
     * Exported from the PyTorch checkpoint by scripts/export_ocr_ar_48px_onnx.py.
     */
    val OCR_AR_48PX_ENCODER = ModelInfo(
        name = "ocr_ar_48px_encoder",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/ocr_ar_48px_encoder.onnx",
        sha256 = "e397b5f3ac53ff5971b7b4e549eb1286342beb420b7f99d6597cdc0e49726c72",
        sizeBytes = 1_303_068L,
    )

    /**
     * AR 48px decoder step ONNX model — 5 XPOS TransformerDecoder layers + prediction heads.
     *
     * Single-step autoregressive decoder with PrecomputedXPOS lookup tables.
     *
     * Inputs:
     *   token_ids   [N]        int64  — current token (START=1 initially)
     *   step        []         int64  — scalar step index (0-based)
     *   memory      [N,W',320] float  — encoder output
     *   memory_mask [N,W']     bool   — encoder padding mask
     *   cache_flat  [N*6,255,320] float — KV cache (zeros initially)
     *
     * Outputs:
     *   logits        [N, dictSize] — character probability logits
     *   fg_colors     [N, 3]        — foreground RGB (0-1 range)
     *   bg_colors     [N, 3]        — background RGB (0-1 range)
     *   fg_indicators [N, 2]        — foreground presence indicator
     *   bg_indicators [N, 2]        — background presence indicator
     *   cache_flat_out [N*6,255,320] — updated KV cache
     *
     * Exported from the PyTorch checkpoint by scripts/export_ocr_ar_48px_onnx.py.
     */
    val OCR_AR_48PX_DECODER = ModelInfo(
        name = "ocr_ar_48px_decoder",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/ocr_ar_48px_decoder.onnx",
        sha256 = "d8953e1ed75e7ab68ff9a1c62882bb97b2d9d0cb123408b3c01b3a6b538e1787",
        sizeBytes = 103_498_951L,
    )

    /** CTC alphabet (v5) — bundled in assets/models/alphabet-all-v5.txt. */
    val ALPHABET_FILE = ModelInfo(
        name = "alphabet_v5",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/ocr-ctc.zip",
        sha256 = "c1295ae1962e69e35b5b225a0405d1f3432e368c9941d23bfd3acda12654da33",
        sizeBytes = 95_997L,
    )

    /**
     * CJK font (Noto Sans CJK JP Regular) — downloaded at runtime if not bundled.
     *
     * Source: googlefonts/noto-cjk Sans2.004 release.
     * Mirrored on jsDelivr CDN for fast, cacheable downloads.
     * Japanese variant ensures correct kanji glyphs for manga text.
     */
    val CJK_FONT = ModelInfo(
        name = "noto_sans_cjk_jp_regular",
        url = "https://cdn.jsdelivr.net/gh/googlefonts/noto-cjk@Sans2.004/Sans/OTF/Japanese/NotoSansCJKjp-Regular.otf",
        sha256 = "68a3fc98800b2a27b371f2fb79991daf3633bd89309d4ffaa6946fd587f375b5",
        sizeBytes = 16_467_736L,
    )

    /**
     * AOT-GAN inpainting model (ONNX).
     *
     * Bundled in app/src/main/assets/models/aot_inpainting.onnx (~22.4 MB).
     * Falls back to GitHub Release download if the asset is missing.
     *
     * Exported from the PyTorch checkpoint by export_aot_inpainting_onnx.py:
     *   https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/inpainting.ckpt
     *
     * Architecture: AOTGenerator(4, 3) — 4 input channels (mask+R/G/B),
     * 3 output channels (R/G/B). Input normalized to [-1, 1].
     * Weight Standardization frozen before export (452 ONNX nodes).
     */
    val AOT_INPAINTING_MODEL = ModelInfo(
        name = "aot_inpainting",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/aot_inpainting.onnx",
        sha256 = "01f7a09a108e55c8acaab957a960e43608601c8b08c136ee6b82006e0aa6dcae",
        sizeBytes = 23_471_634L,
    )

    /**
     * Qwen2-1.5B Instruct ONNX model for LLM-based translation.
     *
     * ⚠️ PLACEHOLDER — SHA-256 hash and URL are not yet finalised.
     * The model export process is TBD; this entry will be updated once
     * the ONNX export pipeline is ready.
     *
     * Model source: https://huggingface.co/Qwen/Qwen2-1.5B-Instruct
     * Expected size (int8 quantised): ~1.5 GB
     */
    val QWEN2_MODEL = ModelInfo(
        name = "qwen2_1.5b",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/qwen2-1.5b-instruct.onnx",
        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        sizeBytes = 1_500_000_000L,
    )

    /**
     * Qwen2-7B Instruct ONNX model for higher-quality LLM translation.
     *
     * ⚠️ PLACEHOLDER — SHA-256 hash and URL are not yet finalised.
     *
     * Model source: https://huggingface.co/Qwen/Qwen2-7B-Instruct
     * Expected size (fp16): ~14 GB
     */
    val QWEN2_BIG_MODEL = ModelInfo(
        name = "qwen2_7b",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/qwen2-7b-instruct.onnx",
        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        sizeBytes = 14_000_000_000L,
    )

    /**
     * M2M100-418M multilingual translation model (ONNX encoder-decoder).
     *
     * Converted from the CTranslate2 model.  The model file is a zip archive
     * containing:
     * - `m2m100_418m.encoder.onnx`       — encoder sub-model
     * - `m2m100_418m.decoder.onnx`       — decoder sub-model with past KV cache
     * - `sentencepiece.model`             — SentencePiece tokenizer model
     *
     * Upstream: m2m100_418m_ct2.zip (CTranslate2 format)
     *   URL: https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/m2m100_418m_ct2.zip
     *   Hash: 8a9cd0e00505a7879f26e5a1b396b447bc29967783a1e17e8df5eecb0c13d1c3
     */
    val M2M100_418M_MODEL = ModelInfo(
        name = "m2m100_418m",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/m2m100_418m_ct2.zip",
        sha256 = "8a9cd0e00505a7879f26e5a1b396b447bc29967783a1e17e8df5eecb0c13d1c3",
        sizeBytes = 818_000_000L,
    )

    /**
     * M2M100-1.2B multilingual translation model (ONNX encoder-decoder).
     *
     * Larger variant of [M2M100_418M_MODEL] with 1.2B parameters.
     *
     * Upstream: m2m100_12b_ct2.zip (CTranslate2 format)
     *   URL: https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/m2m100_12b_ct2.zip
     *   Hash: 742d5380c2837affd3680339145d37fc78f537ad633958347b76e9be9c577662
     */
    val M2M100_12B_MODEL = ModelInfo(
        name = "m2m100_12b",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/m2m100_12b_ct2.zip",
        sha256 = "742d5380c2837affd3680339145d37fc78f537ad633958347b76e9be9c577662",
        sizeBytes = 2_400_000_000L,
    )

    // ─── MBart50 ─────────────────────────────────────────────────────
    //
    // facebook/mbart-large-50-many-to-many-mmt exported to ONNX.
    // Encoder-decoder model with SentencePiece tokenizer.
    //
    // ⚠️ PLACEHOLDER — SHA-256 hashes and URLs are not yet finalised.
    //     Will be updated when ONNX model export pipeline is complete.

    val MBART50_MODEL = ModelInfo(
        name = "mbart50_encoder",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/mbart50_encoder.onnx",
        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        sizeBytes = 600_000_000L,
    )

    val MBART50_DECODER_MODEL = ModelInfo(
        name = "mbart50_decoder",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/mbart50_decoder.onnx",
        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        sizeBytes = 600_000_000L,
    )

    val MBART50_TOKENIZER_MODEL = ModelInfo(
        name = "mbart50_tokenizer",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/mbart50_tokenizer.spm",
        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        sizeBytes = 1_000_000L,
    )

    // ─── Sugoi V4.0 ───────────────────────────────────────────────────
    //
    // T5-based encoder-decoder model fine-tuned on manga text for
    // JPN→ENG translation.  Exported as a single ONNX model with a
    // SentencePiece tokenizer.
    //
    // ⚠️ PLACEHOLDER — SHA-256 hashes and URLs are not yet finalised.
    //     Will be updated when ONNX model export pipeline is complete.

    val SUGOI_MODEL = ModelInfo(
        name = "sugoi_v4",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/sugoi_v4.onnx",
        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        sizeBytes = 500_000_000L,
    )

    val SUGOI_TOKENIZER_MODEL = ModelInfo(
        name = "sugoi_v4_tokenizer",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/sugoi_v4_tokenizer.spm",
        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        sizeBytes = 1_000_000L,
    )

    // ─── JParaCrawl ───────────────────────────────────────────────────
    //
    // Transformer-based model trained on the JParaCrawl corpus for
    // Japanese↔English translation.  Exported as a single ONNX model
    // with a SentencePiece tokenizer.
    //
    // ⚠️ PLACEHOLDER — SHA-256 hashes and URLs are not yet finalised.
    //     Will be updated when ONNX model export pipeline is complete.

    val JPARACRAWL_MODEL = ModelInfo(
        name = "jparacrawl",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/jparacrawl.onnx",
        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        sizeBytes = 300_000_000L,
    )

    val JPARACRAWL_TOKENIZER_MODEL = ModelInfo(
        name = "jparacrawl_tokenizer",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/jparacrawl_tokenizer.spm",
        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        sizeBytes = 1_000_000L,
    )

    // ─── JParaCrawl Big ───────────────────────────────────────────────
    //
    // Larger variant of JParaCrawl with more parameters for higher-quality
    // Japanese↔English translation.
    //
    // ⚠️ PLACEHOLDER — SHA-256 hashes and URLs are not yet finalised.
    //     Will be updated when ONNX model export pipeline is complete.

    val JPARACRAWL_BIG_MODEL = ModelInfo(
        name = "jparacrawl_big",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/jparacrawl_big.onnx",
        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        sizeBytes = 800_000_000L,
    )

    val JPARACRAWL_BIG_TOKENIZER_MODEL = ModelInfo(
        name = "jparacrawl_big_tokenizer",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/jparacrawl_big_tokenizer.spm",
        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        sizeBytes = 1_000_000L,
    )

    val allModels: List<ModelInfo> = listOf(
        CTD_MODEL,
        OCR_48PX_MODEL,
        OCR_AR_48PX_ENCODER,
        ALPHABET_FILE,
        CJK_FONT,
        AOT_INPAINTING_MODEL,
        QWEN2_MODEL,
        QWEN2_BIG_MODEL,
        M2M100_418M_MODEL,
        M2M100_12B_MODEL,
        MBART50_MODEL,
        MBART50_DECODER_MODEL,
        MBART50_TOKENIZER_MODEL,
        SUGOI_MODEL,
        SUGOI_TOKENIZER_MODEL,
        JPARACRAWL_MODEL,
        JPARACRAWL_TOKENIZER_MODEL,
        JPARACRAWL_BIG_MODEL,
        JPARACRAWL_BIG_TOKENIZER_MODEL,
    )

    fun getModel(name: String): ModelInfo? = allModels.find { it.name == name }
}
