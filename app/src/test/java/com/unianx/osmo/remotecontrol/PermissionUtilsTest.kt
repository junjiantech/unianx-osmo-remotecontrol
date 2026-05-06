package com.unianx.osmo.remotecontrol

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionUtilsTest {
    @Test
    fun `gps permissions always request fine and coarse location`() {
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            gpsPermissions().toList(),
        )
    }

    @Test
    fun `notification permissions are empty before Android 13 or notification only on Android 13 plus`() {
        val permissions = notificationPermissions().toList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), permissions)
        } else {
            assertTrue(permissions.isEmpty())
        }
    }

    @Test
    fun `background location permission is only requested on Android 10 plus`() {
        val permission = backgroundLocationPermission()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals(Manifest.permission.ACCESS_BACKGROUND_LOCATION, permission)
        } else {
            assertEquals(null, permission)
        }
    }
}
