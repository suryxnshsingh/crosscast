package dev.suryansh.crosscast.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.suryansh.crosscast.core.DeviceIds
import dev.suryansh.crosscast.core.ReceiverIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    /** Bring Now Playing to the front (and wake the TV) when a sender starts playing. */
    val autoShowUi: Flow<Boolean> = store.data.map { it[AUTO_SHOW_UI] ?: true }.distinctUntilChanged()

    /** How long the clock stays up after a session ends before returning to the previous screen. */
    val idleTimeoutMs: Flow<Long> = store.data.map { (it[IDLE_TIMEOUT_S] ?: DEFAULT_IDLE_TIMEOUT_S) * 1000L }.distinctUntilChanged()

    /** Added to the playback latency, for outputs whose own delay is off (e.g. a soundbar). */
    val latencyOffsetMs: Flow<Int> = store.data.map { it[LATENCY_OFFSET_MS] ?: 0 }.distinctUntilChanged()

    val startOnBoot: Flow<Boolean> = store.data.map { it[START_ON_BOOT] ?: true }.distinctUntilChanged()

    suspend fun setAutoShowUi(value: Boolean) = store.edit { it[AUTO_SHOW_UI] = value }

    suspend fun setIdleTimeoutSeconds(value: Int) = store.edit { it[IDLE_TIMEOUT_S] = value.coerceIn(10, 3600) }

    suspend fun setLatencyOffsetMs(value: Int) = store.edit { it[LATENCY_OFFSET_MS] = value.coerceIn(-500, 500) }

    suspend fun setStartOnBoot(value: Boolean) = store.edit { it[START_ON_BOOT] = value }

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
        const val DEFAULT_IDLE_TIMEOUT_S = 180
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val FRIENDLY_NAME = stringPreferencesKey("friendly_name")
        private val AUTO_SHOW_UI = booleanPreferencesKey("auto_show_ui")
        private val IDLE_TIMEOUT_S = intPreferencesKey("idle_timeout_s")
        private val LATENCY_OFFSET_MS = intPreferencesKey("latency_offset_ms")
        private val START_ON_BOOT = booleanPreferencesKey("start_on_boot")

        fun from(context: Context) = SettingsRepository(context.applicationContext.settingsDataStore)
    }
}
