package com.unianx.osmo.remotecontrol.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsStoreTest {
    @Test
    fun `theme mode falls back to system for unknown storage value`() {
        assertEquals(ThemeMode.System, ThemeMode.fromStorageValue(null))
        assertEquals(ThemeMode.System, ThemeMode.fromStorageValue(999))
    }

    @Test
    fun `mergeConnectionHistory moves latest entry to front and de-duplicates by address`() {
        val existing = listOf(
            ConnectionHistoryEntry(name = "旧设备", address = "AA:AA:AA:AA:AA:AA", connectedAtMs = 100L),
            ConnectionHistoryEntry(name = "其他设备", address = "BB:BB:BB:BB:BB:BB", connectedAtMs = 200L),
            ConnectionHistoryEntry(name = "重复设备", address = "AA:AA:AA:AA:AA:AA", connectedAtMs = 50L),
        )

        val merged = mergeConnectionHistory(
            existing = existing,
            latest = ConnectionHistoryEntry(
                name = "新设备名",
                address = "AA:AA:AA:AA:AA:AA",
                connectedAtMs = 300L,
            ),
            limit = 5,
        )

        assertEquals(listOf("AA:AA:AA:AA:AA:AA", "BB:BB:BB:BB:BB:BB"), merged.map { it.address })
        assertEquals("新设备名", merged.first().name)
        assertEquals(300L, merged.first().connectedAtMs)
    }

    @Test
    fun `mergeConnectionHistory enforces maximum history size`() {
        val existing = listOf(
            ConnectionHistoryEntry(name = "设备1", address = "01", connectedAtMs = 1L),
            ConnectionHistoryEntry(name = "设备2", address = "02", connectedAtMs = 2L),
            ConnectionHistoryEntry(name = "设备3", address = "03", connectedAtMs = 3L),
        )

        val merged = mergeConnectionHistory(
            existing = existing,
            latest = ConnectionHistoryEntry(name = "设备4", address = "04", connectedAtMs = 4L),
            limit = 3,
        )

        assertEquals(listOf("04", "01", "02"), merged.map { it.address })
    }

    @Test
    fun `mergeConnectionHistory preserves previous name when latest name is blank`() {
        val existing = listOf(
            ConnectionHistoryEntry(name = "Osmo 360", address = "AA", connectedAtMs = 100L),
        )

        val merged = mergeConnectionHistory(
            existing = existing,
            latest = ConnectionHistoryEntry(name = "", address = "AA", connectedAtMs = 200L),
            limit = 5,
        )

        assertEquals("Osmo 360", merged.first().name)
        assertEquals(200L, merged.first().connectedAtMs)
    }
}
