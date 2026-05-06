package com.unianx.osmo.remotecontrol.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object AppLogger {
    private const val logDirName = "logs"
    private const val activeLogName = "remotecontrol.log"
    private const val archivedLogName = "remotecontrol.log.1"
    private const val maxLogBytes = 512 * 1024L

    private val fileWriter = Executors.newSingleThreadExecutor()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        i("AppLogger", "logger initialized, path=${logFile()?.absolutePath.orEmpty()}")
    }

    fun d(tag: String, message: String) = write(Log.DEBUG, tag, message, null)

    fun i(tag: String, message: String) = write(Log.INFO, tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) = write(Log.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) = write(Log.ERROR, tag, message, throwable)

    fun logFilePath(): String? = logFile()?.absolutePath

    private fun write(priority: Int, tag: String, message: String, throwable: Throwable?) {
        val safeMessage = if (throwable == null) {
            message
        } else {
            "$message\n${Log.getStackTraceString(throwable)}"
        }

        Log.println(priority, tag, safeMessage)

        val context = appContext ?: return
        val line = buildString {
            append(timestampFormat.format(Date()))
            append(' ')
            append(priorityName(priority))
            append('/')
            append(tag)
            append(": ")
            append(safeMessage)
            append('\n')
        }

        fileWriter.execute {
            runCatching {
                val file = logFile(context)
                rotateIfNeeded(file)
                file.appendText(line)
            }.onFailure { fileError ->
                Log.e("AppLogger", "failed to persist log", fileError)
            }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (file.exists() && file.length() >= maxLogBytes) {
            val archived = File(file.parentFile, archivedLogName)
            if (archived.exists()) {
                archived.delete()
            }
            file.renameTo(archived)
            file.writeText("")
        }
    }

    private fun logFile(): File? {
        val context = appContext ?: return null
        return logFile(context)
    }

    private fun logFile(context: Context): File {
        val dir = File(context.filesDir, logDirName).apply { mkdirs() }
        return File(dir, activeLogName)
    }

    private fun priorityName(priority: Int): String {
        return when (priority) {
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> priority.toString()
        }
    }
}
