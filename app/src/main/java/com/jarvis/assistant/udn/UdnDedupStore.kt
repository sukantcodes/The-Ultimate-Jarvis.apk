package com.jarvis.assistant.udn

import android.content.Context
import org.json.JSONObject

/**
 * Remembers which command ids this device has already executed (and the result it produced), so a re-delivered
 * command (network retry, server redelivery after a lost ack) is never executed twice: the stored result is
 * simply reported again. Survives process death.
 */
class UdnDedupStore(private val backing: Backing, private val nowMs: () -> Long = System::currentTimeMillis) {

    interface Backing { fun read(): String?; fun write(value: String) }

    data class Entry(val status: String, val result: JSONObject?, val errorCode: String?, val errorMessage: String?, val reported: Boolean, val atMs: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        val raw = backing.read() ?: return
        runCatching {
            val o = JSONObject(raw)
            for (id in o.keys()) {
                val e = o.getJSONObject(id)
                entries[id] = Entry(
                    e.getString("s"), e.optJSONObject("r"), e.optString("c").ifEmpty { null },
                    e.optString("m").ifEmpty { null }, e.optBoolean("p", false), e.optLong("t")
                )
            }
        }
    }

    private fun save() {
        val o = JSONObject()
        for ((id, e) in entries) {
            o.put(id, JSONObject().apply {
                put("s", e.status); e.result?.let { put("r", it) }; e.errorCode?.let { put("c", it) }
                e.errorMessage?.let { put("m", it) }; put("p", e.reported); put("t", e.atMs)
            })
        }
        backing.write(o.toString())
    }

    private fun prune() {
        val cutoff = nowMs() - TTL_MS
        val it = entries.entries.iterator()
        while (it.hasNext()) if (it.next().value.atMs < cutoff) it.remove()
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    fun get(id: String): Entry? = synchronized(lock) { load(); entries[id] }

    /** Marks a command as started. Returns false if it was already seen, i.e. it must not run again. */
    fun begin(id: String): Boolean = synchronized(lock) {
        load()
        if (entries.containsKey(id)) return false
        entries[id] = Entry("running", null, null, null, false, nowMs())
        prune(); save()
        true
    }

    fun finish(id: String, status: String, result: JSONObject?, errorCode: String?, errorMessage: String?) = synchronized(lock) {
        load()
        entries[id] = Entry(status, result, errorCode, errorMessage, false, nowMs())
        save()
    }

    fun markReported(id: String) = synchronized(lock) {
        load()
        entries[id]?.let { entries[id] = it.copy(reported = true); save() }
    }

    fun unreported(): List<Pair<String, Entry>> = synchronized(lock) {
        load()
        entries.filter { it.value.status != "running" && !it.value.reported }.map { it.key to it.value }
    }

    companion object {
        const val TTL_MS = 2 * 60 * 60_000L
        const val MAX_ENTRIES = 300

        fun forContext(context: Context): UdnDedupStore {
            val prefs = context.applicationContext.getSharedPreferences("jarvis_udn_dedup", Context.MODE_PRIVATE)
            return UdnDedupStore(object : Backing {
                override fun read(): String? = prefs.getString("entries", null)
                override fun write(value: String) { prefs.edit().putString("entries", value).apply() }
            })
        }
    }
}
