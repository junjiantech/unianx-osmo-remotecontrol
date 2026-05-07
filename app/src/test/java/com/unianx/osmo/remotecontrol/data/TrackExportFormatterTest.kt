package com.unianx.osmo.remotecontrol.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackExportFormatterTest {
    @Test
    fun `buildTrackExportFileName uses expected extension and prefix`() {
        val session = sampleSession()

        assertTrue(buildTrackExportFileName(session, TrackExportFormat.GPX).startsWith("osmo-track-"))
        assertTrue(buildTrackExportFileName(session, TrackExportFormat.GPX).endsWith(".gpx"))
        assertTrue(buildTrackExportFileName(session, TrackExportFormat.TCX).endsWith(".tcx"))
    }

    @Test
    fun `gpx export contains all track points and utc timestamps`() {
        val gpx = formatTrackExport(sampleSession(), TrackExportFormat.GPX)

        assertTrue(gpx.contains("<gpx version=\"1.1\""))
        assertEquals(3, "<trkpt ".toRegex().findAll(gpx).count())
        assertTrue(gpx.contains("<time>2025-05-07T00:00:00Z</time>"))
        assertTrue(gpx.contains("<ele>12.50</ele>"))
    }

    @Test
    fun `tcx export contains monotonic cumulative distance`() {
        val tcx = formatTrackExport(sampleSession(), TrackExportFormat.TCX)
        val distances = "<Trackpoint>[\\s\\S]*?<DistanceMeters>(.*?)</DistanceMeters>".toRegex()
            .findAll(tcx)
            .map { it.groupValues[1].toDouble() }
            .toList()

        assertTrue(tcx.contains("<Activity Sport=\"Other\">"))
        assertEquals(3, distances.size)
        assertTrue(distances.zipWithNext().all { (left, right) -> right >= left })
    }

    private fun sampleSession(): GpsSession {
        return GpsSession(
            id = "abcdef1234567890",
            startedAtMs = 1_746_576_000_000L,
            endedAtMs = 1_746_576_120_000L,
            cameraName = "Osmo Action",
            cameraAddress = "AA:BB",
            recordModeLabel = "录像",
            samples = listOf(
                GpsSample(
                    timestampMs = 1_746_576_000_000L,
                    latitude = 31.2304,
                    longitude = 121.4737,
                    altitudeMeters = 12.5,
                    speedMetersPerSecond = 1.0f,
                    accuracyMeters = 3.0f,
                    verticalAccuracyMeters = 4.0f,
                    speedAccuracyMetersPerSecond = 0.5f,
                    bearingDegrees = 90f,
                    provider = "gps",
                    satelliteCount = 12,
                ),
                GpsSample(
                    timestampMs = 1_746_576_060_000L,
                    latitude = 31.2309,
                    longitude = 121.4742,
                    altitudeMeters = 13.0,
                    speedMetersPerSecond = 2.0f,
                    accuracyMeters = 3.0f,
                    verticalAccuracyMeters = 4.0f,
                    speedAccuracyMetersPerSecond = 0.5f,
                    bearingDegrees = 90f,
                    provider = "gps",
                    satelliteCount = 12,
                ),
                GpsSample(
                    timestampMs = 1_746_576_120_000L,
                    latitude = 31.2314,
                    longitude = 121.4747,
                    altitudeMeters = 13.4,
                    speedMetersPerSecond = 2.2f,
                    accuracyMeters = 3.0f,
                    verticalAccuracyMeters = 4.0f,
                    speedAccuracyMetersPerSecond = 0.5f,
                    bearingDegrees = 90f,
                    provider = "gps",
                    satelliteCount = 12,
                ),
            ),
        )
    }
}
