package com.unianx.osmo.remotecontrol.data

enum class TrackExportFormat(
    val extension: String,
    val mimeType: String,
) {
    GPX(
        extension = "gpx",
        mimeType = "application/gpx+xml",
    ),
    TCX(
        extension = "tcx",
        mimeType = "application/vnd.garmin.tcx+xml",
    ),
}
