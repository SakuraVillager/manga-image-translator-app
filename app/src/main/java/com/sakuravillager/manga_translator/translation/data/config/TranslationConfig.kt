package com.sakuravillager.manga_translator.translation.data.config

data class TranslationConfig(
    val detector: DetectorConfig = DetectorConfig(),
    val ocr: OcrConfig = OcrConfig(),
    val translator: TranslatorConfig = TranslatorConfig(),
    val colorizer: ColorizerConfig = ColorizerConfig(),
    val upscale: UpscaleConfig = UpscaleConfig(),
    val inpainter: InpainterConfig = InpainterConfig(),
    val renderer: RendererConfig = RendererConfig(),
    val kernelSize: Int = 3,
    val maskDilationOffset: Int = 20,
    val filterText: String? = null,
    val preDictPath: String? = null,
    val postDictPath: String? = null,
    val enablePostTranslationCheck: Boolean = true,
    val postCheckMaxRetryAttempts: Int = 3,
    val postCheckRepetitionThreshold: Int = 20,
    val postCheckTargetLangThreshold: Float = 0.5f,
    val ignoreErrors: Boolean = false,
)
