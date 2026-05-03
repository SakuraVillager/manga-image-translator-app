package com.sakuravillager.manga_translator.translation.model

object ModelRegistry {
    val CTD_MODEL = ModelInfo(
        name = "ctd",
        url = "https://github.com/placeholder/ctd.onnx",  // PLACEHOLDER URL
        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",  // PLACEHOLDER
        sizeBytes = 50_000_000L,
    )
    val OCR_48PX_MODEL = ModelInfo(
        name = "ocr_48px",
        url = "https://github.com/placeholder/ocr_48px.onnx",  // PLACEHOLDER
        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        sizeBytes = 20_000_000L,
    )
    val ALPHABET_FILE = ModelInfo(
        name = "alphabet",
        url = "https://github.com/placeholder/alphabet.txt",  // PLACEHOLDER
        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        sizeBytes = 50_000L,
    )

    val allModels: List<ModelInfo> = listOf(CTD_MODEL, OCR_48PX_MODEL, ALPHABET_FILE)

    fun getModel(name: String): ModelInfo? = allModels.find { it.name == name }
}
