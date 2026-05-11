package com.sakuravillager.manga_translator.translation.translator.common

/**
 * Matches Python `manga_translator/translators/common.py` VALID_LANGUAGES.
 *
 * Maps internal language codes to human-readable display names.
 */
val VALID_LANGUAGES: Map<String, String> = mapOf(
    "CHS" to "Chinese (Simplified)",
    "CHT" to "Chinese (Traditional)",
    "CSY" to "Czech",
    "NLD" to "Dutch",
    "ENG" to "English",
    "FRA" to "French",
    "DEU" to "German",
    "HUN" to "Hungarian",
    "ITA" to "Italian",
    "JPN" to "Japanese",
    "KOR" to "Korean",
    "POL" to "Polish",
    "PTB" to "Portuguese (Brazil)",
    "ROM" to "Romanian",
    "RUS" to "Russian",
    "ESP" to "Spanish",
    "TRK" to "Turkish",
    "UKR" to "Ukrainian",
    "VIN" to "Vietnamese",
    "ARA" to "Arabic",
    "CNR" to "Montenegrin",
    "SRP" to "Serbian",
    "HRV" to "Croatian",
    "THA" to "Thai",
    "IND" to "Indonesian",
    "FIL" to "Filipino (Tagalog)",
)

/**
 * Matches Python `manga_translator/translators/common.py` ISO_639_1_TO_VALID_LANGUAGES.
 *
 * Maps ISO 639-1 language codes to internal language codes.
 */
val ISO_639_1_TO_VALID_LANGUAGES: Map<String, String> = mapOf(
    "zh" to "CHS",
    "ja" to "JPN",
    "en" to "ENG",
    "ko" to "KOR",
    "vi" to "VIN",
    "cs" to "CSY",
    "nl" to "NLD",
    "fr" to "FRA",
    "de" to "DEU",
    "hu" to "HUN",
    "it" to "ITA",
    "pl" to "POL",
    "pt" to "PTB",
    "ro" to "ROM",
    "ru" to "RUS",
    "es" to "ESP",
    "tr" to "TRK",
    "uk" to "UKR",
    "ar" to "ARA",
    "cnr" to "CNR",
    "sr" to "SRP",
    "hr" to "HRV",
    "th" to "THA",
    "id" to "IND",
    "tl" to "FIL",
)
