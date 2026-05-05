package com.sakuravillager.manga_translator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.sakuravillager.manga_translator.data.local.DatabaseProvider
import com.sakuravillager.manga_translator.data.logging.AppLogger
import com.sakuravillager.manga_translator.data.preferences.PreferencesProvider
import com.sakuravillager.manga_translator.data.preferences.PreferencesRepository
import com.sakuravillager.manga_translator.translation.di.KoinInitializer
import com.sakuravillager.manga_translator.ui.theme.MangaTranslatorTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var preferencesRepository: PreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize PreferencesProvider
        PreferencesProvider.initialize(this)
        preferencesRepository = PreferencesRepository(PreferencesProvider.dataStore)

        // Initialize DatabaseProvider (required before any ViewModel accesses DAO)
        DatabaseProvider.getDatabase(this)

        // Remove old mock data from the previous demo version
        lifecycleScope.launch {
            DatabaseProvider.dao.deleteAllMockData()
        }

        // Initialize Koin DI
        KoinInitializer.start(this)
        AppLogger.i("App", "Koin DI initialized")

        AppLogger.i("App", "Application started")

        enableEdgeToEdge()
        setContent {
            val preferences by preferencesRepository.getPreferences().collectAsState(
                initial = null
            )

            // Determine dark theme based on preference or system default
            val isDarkTheme = when (preferences?.themeMode) {
                "light" -> false
                "dark" -> true
                else -> null // "system" - will resolve below
            }

            val useDarkTheme = isDarkTheme ?: androidx.compose.foundation.isSystemInDarkTheme()

            MangaTranslatorTheme(
                darkTheme = useDarkTheme,
                colorSchemeName = preferences?.colorScheme ?: "default",
                pureBlackDarkMode = preferences?.pureBlackDarkMode ?: false
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ComicTransApp()
                }
            }
        }
    }
}