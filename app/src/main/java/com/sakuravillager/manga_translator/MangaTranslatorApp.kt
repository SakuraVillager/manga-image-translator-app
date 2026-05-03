package com.sakuravillager.manga_translator

import android.app.Application
import com.sakuravillager.manga_translator.data.logging.AppLogger
import com.sakuravillager.manga_translator.translation.di.KoinInitializer

class MangaTranslatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        KoinInitializer.start(this)
}

}
