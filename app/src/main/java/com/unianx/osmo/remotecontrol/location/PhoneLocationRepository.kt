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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class PhoneLocationRepository(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun locationUpdates(intervalMillis: Long = 100L): Flow<GpsSample> = callbackFlow {
        val locationManager = context.getSystemService(LocationManager::class.java)
            ?: run {
                close(IllegalStateException("LocationManager unavailable"))
                return@callbackFlow
            }

        val providers = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> listOf(LocationManager.GPS_PROVIDER)
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> listOf(LocationManager.NETWORK_PROVIDER)
            else -> emptyList()
        }

        if (providers.isEmpty()) {
            close(IllegalStateException("No location providers enabled"))
            return@callbackFlow
        }

        var previousSample: GpsSample? = null
        fun sendLocation(location: Location) {
            val sample = location.toGpsSample(previousSample)
            previousSample = sample
            trySend(sample)
        }

        val listener = LocationListener { location ->
            sendLocation(location)
        }

        providers.forEach { provider ->
            locationManager.getLastKnownLocation(provider)?.let(::sendLocation)
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

    private fun Location.toGpsSample(previousSample: GpsSample?): GpsSample {
        val satellites = extras?.getInt("satellites", 0) ?: 0
        val verticalAccuracy = if (hasVerticalAccuracy()) verticalAccuracyMeters else accuracy
        val speedAccuracy = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else 0f
        val derivedMotion = previousSample?.let { deriveMotionFrom(it) }

        return GpsSample(
            timestampMs = time,
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = if (hasAltitude()) altitude else 0.0,
            speedMetersPerSecond = if (hasSpeed()) speed else derivedMotion?.speedMetersPerSecond ?: 0f,
            accuracyMeters = accuracy,
            verticalAccuracyMeters = verticalAccuracy,
            speedAccuracyMetersPerSecond = speedAccuracy,
            bearingDegrees = if (hasBearing()) bearing else derivedMotion?.bearingDegrees ?: 0f,
            provider = provider.orEmpty(),
            satelliteCount = satellites,
        )
    }

    private fun Location.deriveMotionFrom(previousSample: GpsSample): DerivedMotion? {
        val deltaSeconds = (time - previousSample.timestampMs) / 1000.0
        if (deltaSeconds <= 0.0 || deltaSeconds > 10.0) return null

        val distanceMeters = haversineMeters(
            previousSample.latitude,
            previousSample.longitude,
            latitude,
            longitude,
        )
        if (distanceMeters < 0.5) {
            return DerivedMotion(speedMetersPerSecond = 0f, bearingDegrees = previousSample.bearingDegrees)
        }

        val speedMetersPerSecond = distanceMeters / deltaSeconds
        if (speedMetersPerSecond > 100.0) return null

        return DerivedMotion(
            speedMetersPerSecond = speedMetersPerSecond.toFloat(),
            bearingDegrees = initialBearingDegrees(
                previousSample.latitude,
                previousSample.longitude,
                latitude,
                longitude,
            ).toFloat(),
        )
    }

    private data class DerivedMotion(
        val speedMetersPerSecond: Float,
        val bearingDegrees: Float,
    )

    private fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val earthRadius = 6_371_000.0
        val latDelta = Math.toRadians(lat2 - lat1)
        val lonDelta = Math.toRadians(lon2 - lon1)
        val a = sin(latDelta / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(lonDelta / 2).pow(2.0)
        return 2 * earthRadius * kotlin.math.asin(sqrt(a))
    }

    private fun initialBearingDegrees(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val lonDeltaRad = Math.toRadians(lon2 - lon1)
        val y = sin(lonDeltaRad) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) -
            sin(lat1Rad) * cos(lat2Rad) * cos(lonDeltaRad)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
