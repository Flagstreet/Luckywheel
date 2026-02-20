package se.rfab.luckywheel

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Singleton DataStore tied to the application context. */
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

object AppSettings {

    private val THEME_ID      = stringPreferencesKey("theme_id")
    private val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")

    fun themeId(context: Context): Flow<String> =
        context.dataStore.data.map { prefs -> prefs[THEME_ID] ?: "standard" }

    fun soundEnabled(context: Context): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[SOUND_ENABLED] ?: true }

    suspend fun setThemeId(context: Context, id: String) {
        context.dataStore.edit { it[THEME_ID] = id }
    }

    suspend fun setSoundEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[SOUND_ENABLED] = enabled }
    }
}
