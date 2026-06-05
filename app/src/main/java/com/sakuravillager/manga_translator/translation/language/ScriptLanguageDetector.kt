package com.sakuravillager.manga_translator.translation.language

object ScriptLanguageDetector : LanguageDetector {
    override fun detect(text: String): LanguageDetection {
        if (text.length < 4) return LanguageDetection("UNKNOWN", 0f)

        var cjkScore = 0f
        var hiraganaScore = 0f
        var katakanaScore = 0f
        var koreanScore = 0f
        var arabicScore = 0f
        var thaiScore = 0f
        var cyrillicScore = 0f
        var latinScore = 0f

        for (ch in text) {
            when {
                ch in '\u3040'..'\u309f' -> hiraganaScore += 2.0f
                ch in '\u30a0'..'\u30ff' -> katakanaScore += 1.5f
                ch in '\uac00'..'\ud7af' || ch in '\u1100'..'\u11ff' -> koreanScore += 1.5f
                ch in '\u0600'..'\u06ff' || ch in '\u0750'..'\u077f' || ch in '\u08a0'..'\u08ff' -> arabicScore += 1.5f
                ch in '\u0e00'..'\u0e7f' -> thaiScore += 1.5f
                ch in '\u0400'..'\u04ff' -> cyrillicScore += 1.5f
                ch.isLetter() && ch.code < 128 -> latinScore += 0.5f
                ch in '\u3000'..'\u303f' || ch in '\u4e00'..'\u9fff' ||
                    ch in '\u3400'..'\u4dbf' || ch in '\uf900'..'\ufaff' -> cjkScore += 1.0f
            }
        }

        val scores = mapOf(
            "JPN" to hiraganaScore * 2 + katakanaScore * 1.5f,
            "KOR" to koreanScore * 1.5f,
            "ARA" to arabicScore * 1.5f,
            "CHS" to cjkScore,
            "ENG" to latinScore * 0.5f,
            "THA" to thaiScore * 1.5f,
            "RUS" to cyrillicScore * 1.5f,
        )
        val maxEntry = scores.maxByOrNull { it.value } ?: return LanguageDetection("UNKNOWN", 0f)
        val totalScore = scores.values.sum()
        val confidence = if (totalScore > 0f) maxEntry.value / totalScore else 0f

        val language = when {
            thaiScore > 0 -> "THA"
            cyrillicScore > 0 -> "RUS"
            hiraganaScore > 0 || (katakanaScore > 0 && hiraganaScore + katakanaScore >= cjkScore) -> "JPN"
            koreanScore > 0 -> "KOR"
            arabicScore > 0 -> "ARA"
            cjkScore > 0 -> "CHS"
            latinScore > 0 -> "ENG"
            else -> "UNKNOWN"
        }

        return LanguageDetection(language, confidence)
    }
}

