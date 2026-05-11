package com.sakuravillager.manga_translator.translation.translator.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidLanguagesTest {

    @Test
    fun `VALID_LANGUAGES has exactly 26 entries`() {
        assertEquals(26, VALID_LANGUAGES.size)
    }

    @Test
    fun `VALID_LANGUAGES contains all expected keys`() {
        val expectedKeys = setOf(
            "CHS", "CHT", "CSY", "NLD", "ENG", "FRA", "DEU", "HUN",
            "ITA", "JPN", "KOR", "POL", "PTB", "ROM", "RUS", "ESP",
            "TRK", "UKR", "VIN", "ARA", "CNR", "SRP", "HRV", "THA",
            "IND", "FIL",
        )
        assertEquals(expectedKeys, VALID_LANGUAGES.keys)
    }

    @Test
    fun `VALID_LANGUAGES display names match expected values`() {
        assertEquals("Chinese (Simplified)", VALID_LANGUAGES["CHS"])
        assertEquals("Chinese (Traditional)", VALID_LANGUAGES["CHT"])
        assertEquals("Czech", VALID_LANGUAGES["CSY"])
        assertEquals("Dutch", VALID_LANGUAGES["NLD"])
        assertEquals("English", VALID_LANGUAGES["ENG"])
        assertEquals("French", VALID_LANGUAGES["FRA"])
        assertEquals("German", VALID_LANGUAGES["DEU"])
        assertEquals("Hungarian", VALID_LANGUAGES["HUN"])
        assertEquals("Italian", VALID_LANGUAGES["ITA"])
        assertEquals("Japanese", VALID_LANGUAGES["JPN"])
        assertEquals("Korean", VALID_LANGUAGES["KOR"])
        assertEquals("Polish", VALID_LANGUAGES["POL"])
        assertEquals("Portuguese (Brazil)", VALID_LANGUAGES["PTB"])
        assertEquals("Romanian", VALID_LANGUAGES["ROM"])
        assertEquals("Russian", VALID_LANGUAGES["RUS"])
        assertEquals("Spanish", VALID_LANGUAGES["ESP"])
        assertEquals("Turkish", VALID_LANGUAGES["TRK"])
        assertEquals("Ukrainian", VALID_LANGUAGES["UKR"])
        assertEquals("Vietnamese", VALID_LANGUAGES["VIN"])
        assertEquals("Arabic", VALID_LANGUAGES["ARA"])
        assertEquals("Montenegrin", VALID_LANGUAGES["CNR"])
        assertEquals("Serbian", VALID_LANGUAGES["SRP"])
        assertEquals("Croatian", VALID_LANGUAGES["HRV"])
        assertEquals("Thai", VALID_LANGUAGES["THA"])
        assertEquals("Indonesian", VALID_LANGUAGES["IND"])
        assertEquals("Filipino (Tagalog)", VALID_LANGUAGES["FIL"])
    }

    @Test
    fun `ISO_639_1_TO_VALID_LANGUAGES has exactly 25 entries`() {
        assertEquals(25, ISO_639_1_TO_VALID_LANGUAGES.size)
    }

    @Test
    fun `ISO_639_1_TO_VALID_LANGUAGES contains all expected keys`() {
        val expectedKeys = setOf(
            "zh", "ja", "en", "ko", "vi",
            "cs", "nl", "fr", "de", "hu",
            "it", "pl", "pt", "ro", "ru",
            "es", "tr", "uk", "ar",
            "cnr", "sr", "hr",
            "th", "id", "tl",
        )
        assertEquals(expectedKeys, ISO_639_1_TO_VALID_LANGUAGES.keys)
    }

    @Test
    fun `ISO_639_1_TO_VALID_LANGUAGES mappings match expected values`() {
        assertEquals("CHS", ISO_639_1_TO_VALID_LANGUAGES["zh"])
        assertEquals("JPN", ISO_639_1_TO_VALID_LANGUAGES["ja"])
        assertEquals("ENG", ISO_639_1_TO_VALID_LANGUAGES["en"])
        assertEquals("KOR", ISO_639_1_TO_VALID_LANGUAGES["ko"])
        assertEquals("VIN", ISO_639_1_TO_VALID_LANGUAGES["vi"])
        assertEquals("CSY", ISO_639_1_TO_VALID_LANGUAGES["cs"])
        assertEquals("NLD", ISO_639_1_TO_VALID_LANGUAGES["nl"])
        assertEquals("FRA", ISO_639_1_TO_VALID_LANGUAGES["fr"])
        assertEquals("DEU", ISO_639_1_TO_VALID_LANGUAGES["de"])
        assertEquals("HUN", ISO_639_1_TO_VALID_LANGUAGES["hu"])
        assertEquals("ITA", ISO_639_1_TO_VALID_LANGUAGES["it"])
        assertEquals("POL", ISO_639_1_TO_VALID_LANGUAGES["pl"])
        assertEquals("PTB", ISO_639_1_TO_VALID_LANGUAGES["pt"])
        assertEquals("ROM", ISO_639_1_TO_VALID_LANGUAGES["ro"])
        assertEquals("RUS", ISO_639_1_TO_VALID_LANGUAGES["ru"])
        assertEquals("ESP", ISO_639_1_TO_VALID_LANGUAGES["es"])
        assertEquals("TRK", ISO_639_1_TO_VALID_LANGUAGES["tr"])
        assertEquals("UKR", ISO_639_1_TO_VALID_LANGUAGES["uk"])
        assertEquals("ARA", ISO_639_1_TO_VALID_LANGUAGES["ar"])
        assertEquals("CNR", ISO_639_1_TO_VALID_LANGUAGES["cnr"])
        assertEquals("SRP", ISO_639_1_TO_VALID_LANGUAGES["sr"])
        assertEquals("HRV", ISO_639_1_TO_VALID_LANGUAGES["hr"])
        assertEquals("THA", ISO_639_1_TO_VALID_LANGUAGES["th"])
        assertEquals("IND", ISO_639_1_TO_VALID_LANGUAGES["id"])
        assertEquals("FIL", ISO_639_1_TO_VALID_LANGUAGES["tl"])
    }

    @Test
    fun `every VALID_LANGUAGES key has a reverse mapping in ISO_639_1_TO_VALID_LANGUAGES`() {
        // All values in ISO_639_1_TO_VALID_LANGUAGES should be keys in VALID_LANGUAGES
        val reverseValues = ISO_639_1_TO_VALID_LANGUAGES.values.toSet()
        for (code in reverseValues) {
            assertTrue("$code is in ISO_639_1_TO_VALID_LANGUAGES but not in VALID_LANGUAGES", VALID_LANGUAGES.containsKey(code))
        }
    }
}
