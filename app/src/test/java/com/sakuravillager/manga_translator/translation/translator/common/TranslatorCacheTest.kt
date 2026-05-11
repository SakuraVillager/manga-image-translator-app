package com.sakuravillager.manga_translator.translation.translator.common

import com.sakuravillager.manga_translator.translation.data.config.TranslatorType
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class TranslatorCacheTest {

    @After
    fun tearDown() {
        clearTranslatorCache()
    }

    @Test
    fun `getTranslator returns same instance for same key`() {
        val first = getTranslator(TranslatorType.NONE) { Any() }
        val second = getTranslator(TranslatorType.NONE) { Any() }
        assertSame(
            "Second call should return the cached instance",
            first,
            second,
        )
    }

    @Test
    fun `getTranslator returns different instances for different keys`() {
        val none = getTranslator(TranslatorType.NONE) { Any() }
        val original = getTranslator(TranslatorType.ORIGINAL) { Any() }
        assertNotSame(
            "Different keys should return different instances",
            none,
            original,
        )
    }

    @Test
    fun `factory is called only once per key`() {
        var callCount = 0
        val factory: () -> Any = {
            callCount++
            Any()
        }

        getTranslator(TranslatorType.GPT_COMPATIBLE, factory)
        getTranslator(TranslatorType.GPT_COMPATIBLE, factory)
        getTranslator(TranslatorType.GPT_COMPATIBLE, factory)

        assert(callCount == 1) { "Factory should be called exactly once, but was called $callCount times" }
    }

    @Test
    fun `clearTranslatorCache clears all cached instances`() {
        val first = getTranslator(TranslatorType.DEEPL) { Any() }
        clearTranslatorCache()
        val second = getTranslator(TranslatorType.DEEPL) { Any() }
        assertNotSame(
            "After clearing, a new instance should be created",
            first,
            second,
        )
    }

    @Test
    fun `getTranslator returns non-null instances`() {
        val instance = getTranslator(TranslatorType.BAIDU) { Any() }
        assertNotNull("Cached instance should not be null", instance)
    }
}
