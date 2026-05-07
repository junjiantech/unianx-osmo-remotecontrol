package com.unianx.osmo.remotecontrol.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TrackBrowserModelsTest {
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: Instant = Instant.parse("2026-05-07T12:00:00Z")

    @Test
    fun `all filter keeps reverse chronological order`() {
        val filtered = filterTrackSummaries(sampleSummaries(), TrackTimeFilter.All, zoneId, now)

        assertEquals(listOf("today", "week", "month", "older"), filtered.map { it.id })
    }

    @Test
    fun `last 7 days filter is inclusive by local date`() {
        val filtered = filterTrackSummaries(sampleSummaries(), TrackTimeFilter.Last7Days, zoneId, now)

        assertEquals(listOf("today", "week"), filtered.map { it.id })
    }

    @Test
    fun `last 30 days filter is inclusive by local date`() {
        val filtered = filterTrackSummaries(sampleSummaries(), TrackTimeFilter.Last30Days, zoneId, now)

        assertEquals(listOf("today", "week", "month"), filtered.map { it.id })
    }

    @Test
    fun `custom filter normalizes reversed range`() {
        val normalized = normalizeTrackCustomFilter(
            startDate = LocalDate.of(2026, 5, 7),
            endDate = LocalDate.of(2026, 5, 1),
        )

        assertEquals(LocalDate.of(2026, 5, 7), normalized.startDate)
        assertEquals(LocalDate.of(2026, 5, 7), normalized.endDate)
    }

    @Test
    fun `custom filter uses local date inclusion`() {
        val filtered = filterTrackSummaries(
            sampleSummaries(),
            TrackTimeFilter.Custom(
                startDate = LocalDate.of(2026, 5, 1),
                endDate = LocalDate.of(2026, 5, 6),
            ),
            zoneId,
            now,
        )

        assertEquals(listOf("week"), filtered.map { it.id })
    }

    @Test
    fun `default custom filter spans last 7 calendar days`() {
        val filter = defaultTrackCustomFilter(zoneId)

        assertTrue(!filter.endDate.isBefore(filter.startDate))
        assertEquals(6, filter.endDate.toEpochDay() - filter.startDate.toEpochDay())
    }

    private fun sampleSummaries(): List<GpsSessionSummary> {
        return listOf(
            summary(id = "older", startedAtMs = timestamp("2026-03-30T10:00:00Z")),
            summary(id = "month", startedAtMs = timestamp("2026-04-20T10:00:00Z")),
            summary(id = "week", startedAtMs = timestamp("2026-05-02T02:00:00Z")),
            summary(id = "today", startedAtMs = timestamp("2026-05-07T01:00:00Z")),
        )
    }

    private fun summary(id: String, startedAtMs: Long): GpsSessionSummary {
        return GpsSessionSummary(
            id = id,
            startedAtMs = startedAtMs,
            endedAtMs = startedAtMs + 60_000L,
            durationSeconds = 60L,
            distanceMeters = 100.0,
            averageSpeedKmh = 6.0,
            maxSpeedKmh = 8.0,
            sampleCount = 2,
            cameraName = "Osmo",
            recordModeLabel = "录像",
        )
    }

    private fun timestamp(iso: String): Long = Instant.parse(iso).toEpochMilli()
}
