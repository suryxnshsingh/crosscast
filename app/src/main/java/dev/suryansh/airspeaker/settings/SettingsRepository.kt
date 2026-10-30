package dev.suryansh.airspeaker.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.suryansh.airspeaker.core.DeviceIds
import dev.suryansh.airspeaker.core.ReceiverIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val store: DataStore<Preferences>) {

    /** The receiver identity. The device ID is generated and saved on first use. */
    val identity: Flow<ReceiverIdentity> = store.data
        .onStart { ensureDeviceId() }
        .mapNotNull { prefs ->
            prefs[DEVICE_ID]?.let { ReceiverIdentity(it, prefs[FRIENDLY_NAME] ?: DEFAULT_FRIENDLY_NAME) }
        }
        .distinctUntilChanged()

    suspend fun setFriendlyName(name: String) {
        store.edit { it[FRIENDLY_NAME] = name.trim().ifEmpty { DEFAULT_FRIENDLY_NAME } }
    }

    private suspend fun ensureDeviceId() {
        store.edit { prefs ->
            if (prefs[DEVICE_ID]?.let(DeviceIds::isValid) != true) prefs[DEVICE_ID] = DeviceIds.generate()
        }
    }

    companion object {
        const val DEFAULT_FRIENDLY_NAME = "Room Speaker"
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val FRIENDLY_NAME = stringPreferencesKey("friendly_name")

        fun from(context: Context) = SettingsRepository(context.applicationContext.settingsDataStore)
    }
}
