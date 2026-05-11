package com.sakuravillager.manga_translator.data.model

import kotlinx.serialization.Serializable

@Serializable
data class TextRegionSnapshot(
    val text: String,
    val textRaw: String,
    val translation: String,
    val language: String?,
    val sourceLanguage: String?,
    val targetLanguage: String?,
    val fontSize: Float,
    val angle: Float,
    val fontFamily: String,
    val bold: Boolean,
    val underline: Boolean,
    val italic: Boolean,
    val fgColor: Int?,
    val bgColor: Int?,
    val opacity: Float,
    val lineSpacing: Float,
    val letterSpacing: Float,
    val shadowRadius: Float,
    val shadowStrength: Float,
    val shadowColor: Int?,
    val shadowOffsetX: Float,
    val shadowOffsetY: Float,
    val direction: String,
    val alignment: String,
    val probability: Float,
    val panelIndex: Int,
    val texts: List<String>,
    val lines: List<List<PointSnapshot>>,
)

@Serializable
data class PointSnapshot(
    val x: Float,
    val y: Float,
)