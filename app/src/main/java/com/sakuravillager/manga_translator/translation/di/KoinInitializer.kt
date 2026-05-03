package com.sakuravillager.manga_translator.translation.di

import android.content.Context
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.context.startKoin

object KoinInitializer {
    private var initialized = false

    fun start(context: Context): Koin {
        if (initialized) {
            return org.koin.core.context.GlobalContext.get()
        }
        val app = startKoin {
            androidContext(context)
            modules(translationModule)
        }.koin
        initialized = true
        return app
    }

    fun isInitialized(): Boolean = initialized
}
