package io.github.afuwellandscale.storage

import android.content.Context
import org.json.JSONObject

class AppLogStore(context: Context) {
    private val appContext = context.applicationContext
    private val fileName = "app-log.jsonl"

    fun append(level: String, message: String, details: String? = null) {
        trimIfNeeded()
        val obj = JSONObject()
            .put("timeMillis", System.currentTimeMillis())
            .put("level", level)
            .put("message", message)
        if (details != null) obj.put("details", details)

        appContext.openFileOutput(fileName, Context.MODE_APPEND).bufferedWriter().use { writer ->
            writer.append(obj.toString())
            writer.newLine()
        }
    }

    fun latestLines(limit: Int = 30): List<String> {
        val file = appContext.getFileStreamPath(fileName)
        if (!file.exists()) return emptyList()
        return file.readLines().takeLast(limit).map { line ->
            runCatching {
                val obj = JSONObject(line)
                "${obj.optString("level", "INFO")}  ${obj.optString("message", line)}"
            }.getOrDefault(line)
        }
    }

    fun readAll(): String {
        val file = appContext.getFileStreamPath(fileName)
        return if (file.exists()) file.readText() else ""
    }

    fun clear() {
        appContext.deleteFile(fileName)
    }

    private fun trimIfNeeded() {
        val file = appContext.getFileStreamPath(fileName)
        if (!file.exists() || file.length() <= MAX_BYTES) return
        val retained = file.readLines().takeLast(RETAINED_LINES)
        file.bufferedWriter().use { writer ->
            retained.forEach {
                writer.append(it)
                writer.newLine()
            }
        }
    }

    private companion object {
        const val MAX_BYTES = 256 * 1024L
        const val RETAINED_LINES = 300
    }
}
