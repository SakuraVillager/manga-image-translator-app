package com.sakuravillager.manga_translator

import android.app.Application
import com.sakuravillager.manga_translator.data.logging.AppLogger
import com.sakuravillager.manga_translator.translation.di.KoinInitializer

class MangaTranslatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        AppLogger.init(this)
        KoinInitializer.start(this)
    }

    companion object {
        lateinit var appContext: android.content.Context
            private set
    }
}
