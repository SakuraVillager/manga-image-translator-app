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

    /** CTC alphabet (v5) — bundled in assets/models/alphabet-all-v5.txt. */
    val ALPHABET_FILE = ModelInfo(
        name = "alphabet_v5",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/ocr-ctc.zip",
        sha256 = "c1295ae1962e69e35b5b225a0405d1f3432e368c9941d23bfd3acda12654da33",
        sizeBytes = 95_997L,
    )

    /**
     * CJK font (Noto Sans CJK KR Regular) — downloaded at runtime if not bundled.
     *
     * Source: googlefonts/noto-cjk Sans2.004 release.
     * Mirrored on jsDelivr CDN for fast, cacheable downloads.
     */
    val CJK_FONT = ModelInfo(
        name = "noto_sans_cjk_kr_regular",
        url = "https://cdn.jsdelivr.net/gh/googlefonts/noto-cjk@Sans2.004/Sans/OTF/Korean/NotoSansCJKkr-Regular.otf",
        sha256 = "6bcb2a0703aa137e874fc2dffa85f6c21ba9a67fa329e81b8c801663af7e992a",
        sizeBytes = 16_433_112L,
    )

    /**
     * AOT-GAN inpainting model (ONNX).
     *
     * Bundled in app/src/main/assets/models/aot_inpainting.onnx (~1.2 MB).
     * Falls back to GitHub Release download if the asset is missing.
     *
     * Exported from the PyTorch checkpoint by export_onnx.py:
     *   https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/inpainting.ckpt
     *
     * Architecture: AOTGenerator(4, 3) — 4 input channels (mask+R/G/B),
     * 3 output channels (R/G/B). Input normalized to [-1, 1].
     */
    val AOT_INPAINTING_MODEL = ModelInfo(
        name = "aot_inpainting",
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/aot_inpainting.onnx",
        sha256 = "a3fc6e855133cb65fd56eb5f500f2f5facafda90d85c652332361efee7b2382b",
        sizeBytes = 23_997_372L,
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

    val allModels: List<ModelInfo> = listOf(
        CTD_MODEL,
        OCR_48PX_MODEL,
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
    )

    fun getModel(name: String): ModelInfo? = allModels.find { it.name == name }
}
