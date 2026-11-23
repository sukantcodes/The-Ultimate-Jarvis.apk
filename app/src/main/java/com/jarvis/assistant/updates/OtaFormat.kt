package com.jarvis.assistant.updates

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Smooths download speed so the number on screen does not jump around on every poll. */
class SpeedMeter(private val smoothing: Double = 0.3) {
    private var lastBytes = -1L
    private var lastTimeMs = 0L

    var bytesPerSecond: Double = 0.0
        private set

    fun reset() {
        lastBytes = -1L
        lastTimeMs = 0L
        bytesPerSecond = 0.0
    }

    fun sample(bytes: Long, nowMs: Long): Double {
        if (lastBytes < 0L || bytes < lastBytes) {
            // First sample, or the download restarted from zero.
            lastBytes = bytes
            lastTimeMs = nowMs
            bytesPerSecond = 0.0
            return 0.0
        }
        val elapsed = nowMs - lastTimeMs
        if (elapsed < 250L) return bytesPerSecond
        val instant = (bytes - lastBytes) * 1000.0 / elapsed
        bytesPerSecond = if (bytesPerSecond <= 0.0) instant else bytesPerSecond + smoothing * (instant - bytesPerSecond)
        lastBytes = bytes
        lastTimeMs = nowMs
        return bytesPerSecond
    }

    fun etaSeconds(remainingBytes: Long): Long? =
        if (bytesPerSecond < 1.0 || remainingBytes <= 0L) null else (remainingBytes / bytesPerSecond).toLong()
}

object OtaFormat {
    fun percent(done: Long, total: Long): Int =
        if (total <= 0L) 0 else ((done.coerceAtLeast(0L) * 100L) / total).toInt().coerceIn(0, 100)

    fun bytes(value: Long): String {
        val v = value.coerceAtLeast(0L).toDouble()
        return when {
            v >= 1024.0 * 1024.0 * 1024.0 -> String.format(Locale.US, "%.2f GB", v / (1024.0 * 1024.0 * 1024.0))
            v >= 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f MB", v / (1024.0 * 1024.0))
            v >= 1024.0 -> String.format(Locale.US, "%.0f KB", v / 1024.0)
            else -> String.format(Locale.US, "%.0f B", v)
        }
    }

    fun speed(bytesPerSecond: Double): String =
        if (bytesPerSecond < 1.0) "— MB/s" else bytes(bytesPerSecond.toLong()) + "/s"

    fun eta(seconds: Long?): String = when {
        seconds == null -> ""
        seconds < 60L -> "${seconds}s left"
        seconds < 3600L -> "${seconds / 60}m ${seconds % 60}s left"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m left"
    }

    /** Shows a server timestamp in the person's own time zone; falls back to the raw date when it cannot be read. */
    fun published(iso: String, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        if (iso.isBlank()) return ""
        return try {
            OffsetDateTime.parse(iso).atZoneSameInstant(zone)
                .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale))
        } catch (_: Exception) {
            iso.take(10)
        }
    }

    fun shortHash(hash: String): String = if (hash.length >= 16) hash.take(8) + "…" + hash.takeLast(8) else hash
}

data class OtaHistoryEntry(
    val timeMs: Long,
    val versionCode: Long,
    val versionName: String,
    val branch: String,
    val event: String,
    val detail: String
)

/** Compact, size-capped storage of what the updater has done, kept on the device only. */
object OtaHistoryCodec {
    const val MAX_ENTRIES = 30

    fun encode(entries: List<OtaHistoryEntry>): String {
        val array = JSONArray()
        entries.takeLast(MAX_ENTRIES).forEach {
            array.put(
                JSONObject().put("t", it.timeMs).put("c", it.versionCode).put("n", it.versionName)
                    .put("b", it.branch).put("e", it.event).put("d", it.detail.take(200))
            )
        }
        return array.toString()
    }

    fun decode(json: String?): List<OtaHistoryEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                OtaHistoryEntry(
                    o.optLong("t", 0L), o.optLong("c", 0L), o.optString("n", ""),
                    o.optString("b", ""), o.optString("e", ""), o.optString("d", "")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun append(existing: List<OtaHistoryEntry>, entry: OtaHistoryEntry): List<OtaHistoryEntry> =
        (existing + entry).takeLast(MAX_ENTRIES)
}
