package com.unianx.osmo.remotecontrol.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.unianx.osmo.remotecontrol.data.GpsSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class PhoneLocationRepository(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun locationUpdates(intervalMillis: Long = 1000L): Flow<GpsSample> = callbackFlow {
        val locationManager = context.getSystemService(LocationManager::class.java)
            ?: run {
                close(IllegalStateException("LocationManager unavailable"))
                return@callbackFlow
            }

        val providers = buildList {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }

        if (providers.isEmpty()) {
            close(IllegalStateException("No location providers enabled"))
            return@callbackFlow
        }

        val listener = LocationListener { location ->
            trySend(location.toGpsSample())
        }

        providers.forEach { provider ->
            locationManager.getLastKnownLocation(provider)?.let { trySend(it.toGpsSample()) }
            locationManager.requestLocationUpdates(
                provider,
                intervalMillis,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        }

        awaitClose {
            locationManager.removeUpdates(listener)
        }
    }

    private fun Location.toGpsSample(): GpsSample {
        val satellites = extras?.getInt("satellites", 0) ?: 0
        val verticalAccuracy = if (hasVerticalAccuracy()) verticalAccuracyMeters else accuracy
        val speedAccuracy = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else 0f

        return GpsSample(
            timestampMs = time,
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = if (hasAltitude()) altitude else 0.0,
            speedMetersPerSecond = if (hasSpeed()) speed else 0f,
            accuracyMeters = accuracy,
            verticalAccuracyMeters = verticalAccuracy,
            speedAccuracyMetersPerSecond = speedAccuracy,
            bearingDegrees = if (hasBearing()) bearing else 0f,
            provider = provider.orEmpty(),
            satelliteCount = satellites,
        )
    }
}
