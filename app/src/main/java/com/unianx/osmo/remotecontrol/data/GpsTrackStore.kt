package com.unianx.osmo.remotecontrol.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class GpsTrackStore(context: Context) {
    private val root = File(context.filesDir, "gps-sessions").apply { mkdirs() }
    private val exportRoot = File(context.cacheDir, "track-exports").apply { mkdirs() }
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    suspend fun saveSession(session: GpsSession) = withContext(Dispatchers.IO) {
        val target = File(root, "${session.id}.json")
        target.writeText(json.encodeToString(GpsSession.serializer(), session))
    }

    suspend fun loadRecentSummaries(limit: Int = 6): List<GpsSessionSummary> = withContext(Dispatchers.IO) {
        loadAllSummaries().take(limit)
    }

    suspend fun loadAllSummaries(): List<GpsSessionSummary> = withContext(Dispatchers.IO) {
        loadAllSessions().map { it.toSummary() }
    }

    suspend fun loadSession(sessionId: String): GpsSession? = withContext(Dispatchers.IO) {
        val target = File(root, "$sessionId.json")
        if (!target.exists()) {
            null
        } else {
            runCatching {
                json.decodeFromString(GpsSession.serializer(), target.readText())
            }.getOrNull()
        }
    }

    suspend fun exportSession(
        sessionId: String,
        format: TrackExportFormat,
    ): File? = withContext(Dispatchers.IO) {
        val session = loadSession(sessionId) ?: return@withContext null
        runCatching {
            writeTrackExport(exportRoot = exportRoot, session = session, format = format)
        }.getOrNull()
    }

    private fun loadAllSessions(): List<GpsSession> {
        return root.listFiles { file -> file.extension == "json" }
            ?.sortedByDescending(File::lastModified)
            ?.mapNotNull { file ->
                runCatching {
                    json.decodeFromString(GpsSession.serializer(), file.readText())
                }.getOrNull()
            }
            ?.sortedByDescending(GpsSession::startedAtMs)
            .orEmpty()
    }
}
