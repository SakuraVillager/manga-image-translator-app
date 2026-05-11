package com.sakuravillager.manga_translator.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object HistoryTextRegionDecoder {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decode(payload: String?): List<TextRegionSnapshot> {
        if (payload.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<TextRegionSnapshot>>(payload)
        }.getOrElse { emptyList() }
    }
}