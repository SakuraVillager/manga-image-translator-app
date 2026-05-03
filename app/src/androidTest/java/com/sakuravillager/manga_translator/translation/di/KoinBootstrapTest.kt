package com.sakuravillager.manga_translator.translation.di

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sakuravillager.manga_translator.translation.pipeline.TranslationPipeline
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import org.koin.test.get
import kotlin.test.assertNotNull

@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class KoinBootstrapTest : KoinTest {

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun verify_Koin_boots_and_resolves_TranslationPipeline() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        KoinInitializer.start(context)
        val pipeline = get<TranslationPipeline>()
        assertNotNull(pipeline)
    }
}
