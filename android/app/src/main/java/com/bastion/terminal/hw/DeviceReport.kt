package com.bastion.terminal.hw

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class DeviceReport(
    val facts: DeviceFacts,
    val verdict: DeviceVerdict,
    val measurements: DeviceMeasurementResult? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("facts", facts.toJson())
        .put("verdict", verdict.toJson())
        .put("measurements", measurements?.toJson() ?: JSONObject.NULL)

    fun summary(): String = buildString {
        for (line in facts.describeLines()) appendLine(line)
        appendLine()
        for (line in verdict.describeLines()) appendLine(line)
        appendLine()
        for (line in measurements?.describeLines() ?: listOf("Optional measurements have not been run.")) {
            appendLine(line)
        }
    }.trimEnd()
}

internal object DeviceReportStore {
    private const val DIRECTORY = "device-check"
    private const val TEXT = "device-check.txt"
    private const val JSON = "device-check.json"
    private val lock = Mutex()

    suspend fun save(context: Context, report: DeviceReport, onlyIfNew: Boolean = false): Unit =
        withContext(Dispatchers.IO) {
            lock.withLock {
                @Suppress("DEPRECATION")
                val media = context.externalMediaDirs.firstOrNull() ?: throw IOException("No app media folder")
                val folder = File(media, DIRECTORY)
                if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create report folder")
                val json = File(folder, JSON)
                val summary = File(folder, TEXT)
                if (onlyIfNew && json.isFile && summary.isFile) return@withLock
                write(summary, report.summary() + "\n")
                write(json, report.toJson().toString(2) + "\n")
            }
        }

    private fun write(file: File, content: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(content.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: IOException) {
            atomic.failWrite(stream)
            throw failure
        }
    }
}
