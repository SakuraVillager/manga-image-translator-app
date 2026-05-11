package com.sakuravillager.manga_translator.translation.data.config

data class ColorizerConfig(
    val colorizer: ColorizerType = ColorizerType.NONE,
    val colorizationSize: Int = 576,
    val denoiseSigma: Int = 30,
)