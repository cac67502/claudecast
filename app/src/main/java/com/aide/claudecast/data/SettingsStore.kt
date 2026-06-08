package com.aide.claudecast.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aide.claudecast.ui.theme.ThemePref
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "claudecast_settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val SPEED = floatPreferencesKey("default_speed")
        val THEME = intPreferencesKey("theme_pref")
    }

    val defaultSpeed: Flow<Float> = context.dataStore.data.map { it[Keys.SPEED] ?: 1.0f }
    val themePref: Flow<ThemePref> = context.dataStore.data.map {
        ThemePref.entries.getOrElse(it[Keys.THEME] ?: ThemePref.Dark.ordinal) { ThemePref.Dark }
    }

    suspend fun setSpeed(speed: Float) {
        context.dataStore.edit { it[Keys.SPEED] = speed }
    }

    suspend fun setTheme(pref: ThemePref) {
        context.dataStore.edit { it[Keys.THEME] = pref.ordinal }
    }
}
