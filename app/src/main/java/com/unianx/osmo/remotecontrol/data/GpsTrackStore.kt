package com.unianx.osmo.remotecontrol.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class GpsTrackStore(context: Context) {
    private val root = File(context.filesDir, "gps-sessions").apply { mkdirs() }
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    suspend fun saveSession(session: GpsSession) = withContext(Dispatchers.IO) {
        val target = File(root, "${session.id}.json")
        target.writeText(json.encodeToString(GpsSession.serializer(), session))
    }

    suspend fun loadRecentSummaries(limit: Int = 6): List<GpsSessionSummary> = withContext(Dispatchers.IO) {
        root.listFiles { file -> file.extension == "json" }
            ?.sortedByDescending(File::lastModified)
            ?.take(limit)
            ?.mapNotNull { file ->
                runCatching {
                    val session = json.decodeFromString(GpsSession.serializer(), file.readText())
                    session.toSummary()
                }.getOrNull()
            }
            .orEmpty()
    }
}
