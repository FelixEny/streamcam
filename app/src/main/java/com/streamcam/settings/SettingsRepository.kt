package com.streamcam.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streamcam.camera.CameraSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Persists [CameraSettings] and [StreamSettings] via Jetpack DataStore. Encoding/clamping lives
 * in [SettingsCodec] (JVM-testable); this class is only the Android glue.
 */
class SettingsRepository(private val context: Context) {

    val cameraSettings: Flow<CameraSettings> =
        context.settingsDataStore.data.map { prefs -> SettingsCodec.decodeCamera(prefs.asFlatMap()) }

    val streamSettings: Flow<StreamSettings> =
        context.settingsDataStore.data.map { prefs -> SettingsCodec.decodeStream(prefs.asFlatMap()) }

    suspend fun saveCamera(settings: CameraSettings) = write(SettingsCodec.encodeCamera(settings))

    suspend fun saveStream(settings: StreamSettings) = write(SettingsCodec.encodeStream(settings))

    private suspend fun write(values: Map<String, Any>) {
        context.settingsDataStore.edit { prefs ->
            for ((key, value) in values) {
                when (value) {
                    is Boolean -> prefs[booleanPreferencesKey(key)] = value
                    is Int -> prefs[intPreferencesKey(key)] = value
                    is Long -> prefs[longPreferencesKey(key)] = value
                    is Float -> prefs[floatPreferencesKey(key)] = value
                    is String -> prefs[stringPreferencesKey(key)] = value
                }
            }
        }
    }

    /** Flattens preference keys to plain string-keyed values for [SettingsCodec]. */
    private fun Preferences.asFlatMap(): Map<String, Any?> =
        asMap().entries.associate { (key, value) -> key.name to value }
}
