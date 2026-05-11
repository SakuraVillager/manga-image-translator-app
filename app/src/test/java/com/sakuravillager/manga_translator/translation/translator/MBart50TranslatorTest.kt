package com.sakuravillager.manga_translator.translation.translator

import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager
import org.junit.Test
import org.junit.Assert.*
import android.app.Application

/**
 * JVM unit tests for [MBart50Translator].
 *
 * MBart50Translator is a final class (not `open`), so we test via public
 * methods and properties only.
 */
class MBart50TranslatorTest {

    @Test
    fun translatorCanBeConstructed() {
        val ctx = Application()
        val translator = MBart50Translator(ModelDownloadManager(ctx), OnnxSessionManager)
        assertNotNull("MBart50Translator should construct successfully", translator)
    }

    @Test
    fun translatorNameIsCorrect() {
        val ctx = Application()
        val translator = MBart50Translator(ModelDownloadManager(ctx), OnnxSessionManager)
        assertEquals("MBart50", translator.name)
    }

    @Test
    fun translatorNotReadyInitially() {
        val ctx = Application()
        val translator = MBart50Translator(ModelDownloadManager(ctx), OnnxSessionManager)
        assertFalse(translator.isReady)
    }

    @Test
    fun translatorSupportedSourceLanguagesIsNotEmpty() {
        val ctx = Application()
        val translator = MBart50Translator(ModelDownloadManager(ctx), OnnxSessionManager)
        assertNotNull("supportedSourceLanguages should not be null", translator.supportedSourceLanguages)
    }

    @Test
    fun translatorSupportedTargetLanguagesIsNotEmpty() {
        val ctx = Application()
        val translator = MBart50Translator(ModelDownloadManager(ctx), OnnxSessionManager)
        assertNotNull("supportedTargetLanguages should not be null", translator.supportedTargetLanguages)
    }
}
