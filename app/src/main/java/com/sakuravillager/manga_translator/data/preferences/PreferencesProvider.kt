package com.sakuravillager.manga_translator.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.preferencesDataStore
import com.sakuravillager.manga_translator.data.logging.AppLogger

private val Context.dataStore by preferencesDataStore(name = "app_preferences")

object PreferencesProvider {
    
    private var _context: Context? = null
    
    val context: Context
        get() = _context
            ?: throw IllegalStateException("PreferencesProvider must be initialized before use")

    private var _dataStore: DataStore<androidx.datastore.preferences.core.Preferences>? = null

    val dataStore: DataStore<androidx.datastore.preferences.core.Preferences>
        get() = _dataStore 
            ?: throw IllegalStateException("PreferencesProvider must be initialized before use")

    // Singleton repository for global access
    private var _repository: PreferencesRepository? = null
    
    val repository: PreferencesRepository
        get() = _repository
            ?: throw IllegalStateException("PreferencesProvider must be initialized before use")

    fun initialize(context: Context) {
        _context = context.applicationContext
        _dataStore = context.dataStore
        _repository = PreferencesRepository(dataStore)
        AppLogger.i("Preferences", "Preferences initialized")
    }
}