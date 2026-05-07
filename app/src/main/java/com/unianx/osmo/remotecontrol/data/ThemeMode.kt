package com.unianx.osmo.remotecontrol.data

import androidx.appcompat.app.AppCompatDelegate

enum class ThemeMode(val storageValue: Int) {
    System(0),
    Light(1),
    Dark(2),
    ;

    fun resolveDark(systemDark: Boolean): Boolean {
        return when (this) {
            System -> systemDark
            Light -> false
            Dark -> true
        }
    }

    fun toNightMode(): Int {
        return when (this) {
            System -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            Light -> AppCompatDelegate.MODE_NIGHT_NO
            Dark -> AppCompatDelegate.MODE_NIGHT_YES
        }
    }

    companion object {
        fun fromStorageValue(value: Int?): ThemeMode {
            return entries.firstOrNull { it.storageValue == value } ?: System
        }
    }
}
