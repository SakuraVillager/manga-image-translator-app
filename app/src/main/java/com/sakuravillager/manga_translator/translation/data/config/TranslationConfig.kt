package com.sakuravillager.manga_translator.translation.data.config

data class TranslationConfig(
    val detector: DetectorConfig = DetectorConfig(),
    val ocr: OcrConfig = OcrConfig(),
    val translator: TranslatorConfig = TranslatorConfig(),
    val inpainter: InpainterConfig = InpainterConfig(),
    val renderer: RendererConfig = RendererConfig(),
    val kernelSize: Int = 3,
    val maskDilationOffset: Int = 20,
    val filterText: String? = null,
    val preDictPath: String? = null,
    val postDictPath: String? = null,
)
