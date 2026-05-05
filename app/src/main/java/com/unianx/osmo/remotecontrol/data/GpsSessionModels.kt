package com.unianx.osmo.remotecontrol.data

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class GpsSample(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val speedMetersPerSecond: Float,
    val accuracyMeters: Float,
    val verticalAccuracyMeters: Float,
    val speedAccuracyMetersPerSecond: Float,
    val bearingDegrees: Float,
    val provider: String,
    val satelliteCount: Int,
) {
    val speedKmh: Double
        get() = speedMetersPerSecond * 3.6

    val horizontalAccuracyMeters: Float
        get() = accuracyMeters
}

@Serializable
data class GpsSession(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val cameraName: String,
    val cameraAddress: String?,
    val recordModeLabel: String,
    val samples: List<GpsSample>,
)

data class GpsSessionSummary(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationSeconds: Long,
    val distanceMeters: Double,
    val averageSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val sampleCount: Int,
    val cameraName: String,
    val recordModeLabel: String,
) {
    val distanceLabel: String
        get() = if (distanceMeters >= 1000) {
            String.format(Locale.US, "%.2f 公里", distanceMeters / 1000.0)
        } else {
            String.format(Locale.US, "%.0f 米", distanceMeters)
        }
}

fun GpsSession.toSummary(): GpsSessionSummary {
    val distance = samples.zipWithNext { previous, next ->
        haversineMeters(previous.latitude, previous.longitude, next.latitude, next.longitude)
    }.sum()
    val durationSeconds = ((endedAtMs - startedAtMs) / 1000L).coerceAtLeast(0L)
    val averageSpeed = if (durationSeconds == 0L) 0.0 else distance / durationSeconds * 3.6
    val maxSpeed = samples.maxOfOrNull { it.speedKmh } ?: 0.0

    return GpsSessionSummary(
        id = id,
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        durationSeconds = durationSeconds,
        distanceMeters = distance,
        averageSpeedKmh = averageSpeed,
        maxSpeedKmh = maxSpeed,
        sampleCount = samples.size,
        cameraName = cameraName,
        recordModeLabel = recordModeLabel,
    )
}

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
    val c = 2 * asin(sqrt(a))
    return earthRadius * c
}
