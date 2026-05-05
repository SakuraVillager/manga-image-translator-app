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

    val allModels: List<ModelInfo> = listOf(CTD_MODEL, OCR_48PX_MODEL, ALPHABET_FILE, CJK_FONT, AOT_INPAINTING_MODEL)

    fun getModel(name: String): ModelInfo? = allModels.find { it.name == name }
}
