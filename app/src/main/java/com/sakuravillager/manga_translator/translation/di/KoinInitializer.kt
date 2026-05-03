package com.sakuravillager.manga_translator.translation.di

import org.koin.core.Koin
import org.koin.core.context.startKoin

object KoinInitializer {
    private var initialized = false

    fun start(): Koin {
        if (initialized) {
            return org.koin.core.context.GlobalContext.get()
        }
        val app = startKoin {
            modules(translationModule)
        }
        initialized = true
        return app
    }

    fun isInitialized(): Boolean = initialized
}
