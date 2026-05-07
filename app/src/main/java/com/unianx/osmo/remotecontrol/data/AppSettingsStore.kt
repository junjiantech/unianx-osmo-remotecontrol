package com.unianx.osmo.remotecontrol.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.unianx.osmo.remotecontrol.logging.AppLogger
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")
private const val CONNECTION_HISTORY_LIMIT = 6

class AppSettingsStore(private val context: Context) {
    private val dataStore = context.appSettingsDataStore

    suspend fun loadControllerIdentity(): ControllerIdentity? {
        val preferences = readPreferences()
        val deviceId = preferences[KEY_CONTROLLER_DEVICE_ID] ?: return null
        val pseudoMacHex = preferences[KEY_CONTROLLER_PSEUDO_MAC] ?: return null
        val pseudoMac = runCatching {
            pseudoMacHex.split(':')
                .map { it.toInt(16).toByte() }
                .toByteArray()
        }.getOrNull() ?: return null
        if (pseudoMac.size != 6) return null

        return ControllerIdentity(
            deviceId = deviceId,
            pseudoMac = pseudoMac,
        )
    }

    suspend fun saveControllerIdentity(identity: ControllerIdentity) {
        dataStore.edit { preferences ->
            preferences[KEY_CONTROLLER_DEVICE_ID] = identity.deviceId
            preferences[KEY_CONTROLLER_PSEUDO_MAC] = identity.pseudoMacHex
        }
        AppLogger.i("AppSettingsStore", "controller identity saved")
    }

    suspend fun loadLastConnectedCameraAddress(): String? {
        return readPreferences()[KEY_LAST_CONNECTED_CAMERA_ADDRESS]
    }

    suspend fun loadThemeMode(): ThemeMode {
        return ThemeMode.fromStorageValue(readPreferences()[KEY_THEME_MODE])
    }

    suspend fun saveThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[KEY_THEME_MODE] = mode.storageValue
        }
        AppLogger.i("AppSettingsStore", "theme mode saved mode=${mode.name}")
    }

    suspend fun saveLastConnectedCameraAddress(address: String) {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_CONNECTED_CAMERA_ADDRESS] = address
        }
        AppLogger.i("AppSettingsStore", "last connected camera saved address=$address")
    }

    suspend fun loadRecentConnections(): List<ConnectionHistoryEntry> {
        val raw = readPreferences()[KEY_CONNECTION_HISTORY].orEmpty()
        return decodeConnectionHistory(raw)
    }

    suspend fun recordConnectedCamera(
        name: String,
        address: String,
        connectedAtMs: Long = System.currentTimeMillis(),
    ) {
        val latest = ConnectionHistoryEntry(
            name = name,
            address = address,
            connectedAtMs = connectedAtMs,
        )
        dataStore.edit { preferences ->
            val current = decodeConnectionHistory(preferences[KEY_CONNECTION_HISTORY].orEmpty())
            val merged = mergeConnectionHistory(existing = current, latest = latest)
            preferences[KEY_CONNECTION_HISTORY] = json.encodeToString(merged)
            preferences[KEY_LAST_CONNECTED_CAMERA_ADDRESS] = address
        }
        AppLogger.i("AppSettingsStore", "connection history recorded address=$address")
    }

    private suspend fun readPreferences(): Preferences {
        return dataStore.data
            .catch { throwable ->
                if (throwable is IOException) {
                    AppLogger.w("AppSettingsStore", "preferences read failed, fallback to empty", throwable)
                    emit(emptyPreferences())
                } else {
                    throw throwable
                }
            }
            .first()
    }

    private fun decodeConnectionHistory(raw: String): List<ConnectionHistoryEntry> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ConnectionHistoryEntry>>(raw)
        }.onFailure { throwable ->
            AppLogger.w("AppSettingsStore", "connection history decode failed", throwable)
        }.getOrDefault(emptyList())
            .filter { it.address.isNotBlank() }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
        }
        val KEY_CONTROLLER_DEVICE_ID = intPreferencesKey("controller_device_id")
        val KEY_CONTROLLER_PSEUDO_MAC = stringPreferencesKey("controller_pseudo_mac")
        val KEY_THEME_MODE = intPreferencesKey("theme_mode")
        val KEY_LAST_CONNECTED_CAMERA_ADDRESS = stringPreferencesKey("last_connected_camera_address")
        val KEY_CONNECTION_HISTORY = stringPreferencesKey("connection_history")
    }
}

internal fun mergeConnectionHistory(
    existing: List<ConnectionHistoryEntry>,
    latest: ConnectionHistoryEntry,
    limit: Int = CONNECTION_HISTORY_LIMIT,
): List<ConnectionHistoryEntry> {
    if (latest.address.isBlank()) return existing.take(limit)

    val preservedName = existing.firstOrNull { it.address.equals(latest.address, ignoreCase = true) }
        ?.name
        .orEmpty()
    val normalizedLatest = latest.copy(
        name = latest.name.ifBlank { preservedName },
    )

    return buildList {
        add(normalizedLatest)
        existing.forEach { entry ->
            if (!entry.address.equals(normalizedLatest.address, ignoreCase = true) && entry.address.isNotBlank()) {
                add(entry)
            }
        }
    }.take(limit)
}
