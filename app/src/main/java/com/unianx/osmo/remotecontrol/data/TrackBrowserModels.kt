package com.unianx.osmo.remotecontrol.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface TrackTimeFilter {
    data object All : TrackTimeFilter

    data object Last7Days : TrackTimeFilter

    data object Last30Days : TrackTimeFilter

    data class Custom(
        val startDate: LocalDate,
        val endDate: LocalDate,
    ) : TrackTimeFilter
}

data class TrackShareRequest(
    val absolutePath: String,
    val format: TrackExportFormat,
)

data class TrackBrowserUiState(
    val allSummaries: List<GpsSessionSummary> = emptyList(),
    val filteredSummaries: List<GpsSessionSummary> = emptyList(),
    val currentFilter: TrackTimeFilter = TrackTimeFilter.All,
    val draftCustomFilter: TrackTimeFilter.Custom = defaultTrackCustomFilter(),
    val selectedSessionId: String? = null,
    val selectedSession: GpsSession? = null,
    val isLoadingList: Boolean = false,
    val isLoadingDetail: Boolean = false,
    val isExporting: Boolean = false,
    val pendingShareRequest: TrackShareRequest? = null,
    val message: String? = null,
)

fun defaultTrackCustomFilter(zoneId: ZoneId = ZoneId.systemDefault()): TrackTimeFilter.Custom {
    val today = LocalDate.now(zoneId)
    return TrackTimeFilter.Custom(
        startDate = today.minusDays(6),
        endDate = today,
    )
}

fun normalizeTrackCustomFilter(
    startDate: LocalDate,
    endDate: LocalDate,
): TrackTimeFilter.Custom {
    return if (endDate.isBefore(startDate)) {
        TrackTimeFilter.Custom(startDate = startDate, endDate = startDate)
    } else {
        TrackTimeFilter.Custom(startDate = startDate, endDate = endDate)
    }
}

fun filterTrackSummaries(
    summaries: List<GpsSessionSummary>,
    filter: TrackTimeFilter,
    zoneId: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
): List<GpsSessionSummary> {
    val sorted = summaries.sortedByDescending { it.startedAtMs }
    val today = now.atZone(zoneId).toLocalDate()

    return when (filter) {
        TrackTimeFilter.All -> sorted
        TrackTimeFilter.Last7Days -> filterTrackSummariesByDateRange(
            summaries = sorted,
            startDate = today.minusDays(6),
            endDate = today,
            zoneId = zoneId,
        )

        TrackTimeFilter.Last30Days -> filterTrackSummariesByDateRange(
            summaries = sorted,
            startDate = today.minusDays(29),
            endDate = today,
            zoneId = zoneId,
        )

        is TrackTimeFilter.Custom -> {
            val normalized = normalizeTrackCustomFilter(filter.startDate, filter.endDate)
            filterTrackSummariesByDateRange(
                summaries = sorted,
                startDate = normalized.startDate,
                endDate = normalized.endDate,
                zoneId = zoneId,
            )
        }
    }
}

private fun filterTrackSummariesByDateRange(
    summaries: List<GpsSessionSummary>,
    startDate: LocalDate,
    endDate: LocalDate,
    zoneId: ZoneId,
): List<GpsSessionSummary> {
    return summaries.filter { session ->
        val sessionDate = Instant.ofEpochMilli(session.startedAtMs).atZone(zoneId).toLocalDate()
        !sessionDate.isBefore(startDate) && !sessionDate.isAfter(endDate)
    }
}
