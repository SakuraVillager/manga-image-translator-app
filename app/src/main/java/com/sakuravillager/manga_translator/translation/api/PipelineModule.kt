package com.sakuravillager.manga_translator.translation.api

interface PipelineModule {
    val name: String
    val isReady: Boolean
    suspend fun prepare()
    suspend fun release()
}
