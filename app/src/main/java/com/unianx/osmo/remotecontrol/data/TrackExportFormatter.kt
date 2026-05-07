package com.unianx.osmo.remotecontrol.data

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private val utcFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
private val fileNameFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.US).withZone(ZoneOffset.UTC)

fun buildTrackExportFileName(
    session: GpsSession,
    format: TrackExportFormat,
): String {
    val startedAt = Instant.ofEpochMilli(session.startedAtMs)
    return "osmo-track-${fileNameFormatter.format(startedAt)}-${session.id.take(8)}.${format.extension}"
}

fun formatTrackExport(
    session: GpsSession,
    format: TrackExportFormat,
): String {
    return when (format) {
        TrackExportFormat.GPX -> buildGpx(session)
        TrackExportFormat.TCX -> buildTcx(session)
    }
}

fun writeTrackExport(
    exportRoot: File,
    session: GpsSession,
    format: TrackExportFormat,
): File {
    exportRoot.mkdirs()
    val target = File(exportRoot, buildTrackExportFileName(session, format))
    target.writeText(formatTrackExport(session, format))
    return target
}

private fun buildGpx(session: GpsSession): String {
    val builder = StringBuilder()
    builder.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    builder.append(
        """<gpx version="1.1" creator="Osmo Remote Console" xmlns="http://www.topografix.com/GPX/1/1">""",
    ).append('\n')
    builder.append("  <metadata>").append('\n')
    builder.append("    <name>").append(xmlEscape(session.cameraName)).append("</name>").append('\n')
    builder.append("    <time>").append(formatUtc(session.startedAtMs)).append("</time>").append('\n')
    builder.append("  </metadata>").append('\n')
    builder.append("  <trk>").append('\n')
    builder.append("    <name>").append(xmlEscape(session.cameraName)).append("</name>").append('\n')
    builder.append("    <type>").append(xmlEscape(session.recordModeLabel)).append("</type>").append('\n')
    builder.append("    <trkseg>").append('\n')
    session.samples.forEach { sample ->
        builder.append(
            "      <trkpt lat=\"${formatCoordinate(sample.latitude)}\" lon=\"${formatCoordinate(sample.longitude)}\">",
        ).append('\n')
        builder.append("        <ele>").append(formatDecimal(sample.altitudeMeters)).append("</ele>").append('\n')
        builder.append("        <time>").append(formatUtc(sample.timestampMs)).append("</time>").append('\n')
        builder.append("      </trkpt>").append('\n')
    }
    builder.append("    </trkseg>").append('\n')
    builder.append("  </trk>").append('\n')
    builder.append("</gpx>").append('\n')
    return builder.toString()
}

private fun buildTcx(session: GpsSession): String {
    val builder = StringBuilder()
    builder.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    builder.append(
        """<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""",
    ).append('\n')
    builder.append("  <Activities>").append('\n')
    builder.append("    <Activity Sport=\"Other\">").append('\n')
    builder.append("      <Id>").append(formatUtc(session.startedAtMs)).append("</Id>").append('\n')
    builder.append("      <Lap StartTime=\"").append(formatUtc(session.startedAtMs)).append("\">").append('\n')
    builder.append("        <TotalTimeSeconds>")
        .append(((session.endedAtMs - session.startedAtMs).coerceAtLeast(0L)) / 1000L)
        .append("</TotalTimeSeconds>")
        .append('\n')
    builder.append("        <DistanceMeters>").append(formatDecimal(totalDistanceMeters(session))).append("</DistanceMeters>").append('\n')
    builder.append("        <Track>").append('\n')

    var distanceMeters = 0.0
    session.samples.forEachIndexed { index, sample ->
        if (index > 0) {
            val previous = session.samples[index - 1]
            distanceMeters += haversineMeters(
                previous.latitude,
                previous.longitude,
                sample.latitude,
                sample.longitude,
            )
        }
        builder.append("          <Trackpoint>").append('\n')
        builder.append("            <Time>").append(formatUtc(sample.timestampMs)).append("</Time>").append('\n')
        builder.append("            <Position>").append('\n')
        builder.append("              <LatitudeDegrees>").append(formatCoordinate(sample.latitude)).append("</LatitudeDegrees>").append('\n')
        builder.append("              <LongitudeDegrees>").append(formatCoordinate(sample.longitude)).append("</LongitudeDegrees>").append('\n')
        builder.append("            </Position>").append('\n')
        builder.append("            <AltitudeMeters>").append(formatDecimal(sample.altitudeMeters)).append("</AltitudeMeters>").append('\n')
        builder.append("            <DistanceMeters>").append(formatDecimal(distanceMeters)).append("</DistanceMeters>").append('\n')
        builder.append("          </Trackpoint>").append('\n')
    }

    builder.append("        </Track>").append('\n')
    builder.append("      </Lap>").append('\n')
    builder.append("      <Notes>").append(xmlEscape(session.cameraName)).append("</Notes>").append('\n')
    builder.append("    </Activity>").append('\n')
    builder.append("  </Activities>").append('\n')
    builder.append("</TrainingCenterDatabase>").append('\n')
    return builder.toString()
}

private fun totalDistanceMeters(session: GpsSession): Double {
    return session.samples.zipWithNext { previous, next ->
        haversineMeters(previous.latitude, previous.longitude, next.latitude, next.longitude)
    }.sum()
}

private fun formatUtc(timestampMs: Long): String = utcFormatter.format(Instant.ofEpochMilli(timestampMs))

private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.8f", value)

private fun formatDecimal(value: Double): String = String.format(Locale.US, "%.2f", value)

private fun xmlEscape(value: String): String {
    return buildString(value.length) {
        value.forEach { ch ->
            append(
                when (ch) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> ch
                },
            )
        }
    }
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
