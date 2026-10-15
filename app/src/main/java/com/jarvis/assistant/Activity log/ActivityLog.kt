package com.jarvis.assistant.activitylog

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Jarvis's rolling memory of the last two days: conversations, actions it performed, messages received and
 * battery events, each with a timestamp. It is stored only on this phone, in a private database, and entries
 * older than the memory window are deleted automatically.
 */
class ActivityLog private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "jarvis_activity_log.db", null, 1) {

    data class Entry(val time: Long, val type: String, val text: String)

    private val settings = ActivityLogSettings(context)
    private val writer = Executors.newSingleThreadExecutor()

    @Volatile
    private var lastPruneAt = 0L

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, type TEXT NOT NULL, text TEXT NOT NULL)")
        db.execSQL("CREATE INDEX idx_events_ts ON events(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS events")
        onCreate(db)
    }

    /** Saves one entry in the background. Does nothing when activity memory is switched off. */
    fun log(type: String, text: String) {
        if (!settings.enabled) return
        val clean = text.trim().replace(Regex("\\s+"), " ").take(MAX_TEXT_CHARS)
        if (clean.isEmpty()) return
        val now = System.currentTimeMillis()
        try {
            writer.execute {
                try {
                    val values = ContentValues()
                    values.put("ts", now)
                    values.put("type", type)
                    values.put("text", clean)
                    writableDatabase.insert("events", null, values)
                    pruneIfDue(now)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not save activity: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not queue activity: ${e.message}")
        }
    }

    fun logConversation(userText: String, jarvisText: String) {
        val user = userText.trim()
        val reply = jarvisText.trim()
        if (user.isEmpty() && reply.isEmpty()) return
        log(TYPE_CONVERSATION, "You: " + user.take(220) + " | Jarvis: " + reply.take(220))
    }

    private fun pruneIfDue(now: Long) {
        if (now - lastPruneAt < 10 * 60 * 1000L) return
        lastPruneAt = now
        val cutoff = now - settings.windowHours * HOUR_MS
        writableDatabase.delete("events", "ts < ?", arrayOf(cutoff.toString()))
    }

    /** Deletes everything Jarvis remembers. */
    fun clear() {
        try {
            writer.execute {
                try {
                    writableDatabase.delete("events", null, null)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not clear activity: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not queue clear: ${e.message}")
        }
    }

    private fun escapeLike(word: String): String =
        word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** Entries from the last [hours] hours, oldest first, optionally only those mentioning every word in [search]. */
    private fun query(hours: Int, search: String?, limit: Int): List<Entry> {
        val cutoff = System.currentTimeMillis() - hours.toLong() * HOUR_MS
        val where = StringBuilder("ts >= ?")
        val args = ArrayList<String>()
        args.add(cutoff.toString())
        val words = (search ?: "").split(Regex("\\s+")).filter { it.length >= 2 }.take(4)
        for (word in words) {
            where.append(" AND (text LIKE ? ESCAPE '\\' OR type LIKE ? ESCAPE '\\')")
            val pattern = "%" + escapeLike(word) + "%"
            args.add(pattern)
            args.add(pattern)
        }
        val out = ArrayList<Entry>()
        try {
            readableDatabase.query(
                "events", arrayOf("ts", "type", "text"), where.toString(), args.toTypedArray(),
                null, null, "ts DESC", limit.toString()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    out.add(Entry(cursor.getLong(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read activity: ${e.message}")
        }
        out.reverse()
        return out
    }

    /**
     * The block added to the end of the system prompt so the assistant starts every session already knowing
     * what happened recently. Returns an empty string when memory is off or empty.
     */
    fun promptSection(maxChars: Int = 3500): String {
        if (!settings.enabled) return ""
        val entries = query(settings.windowHours, null, 400)
        if (entries.isEmpty()) return ""
        val format = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
        val lines = ArrayList<String>()
        var used = 0
        var omitted = false
        for (entry in entries.asReversed()) {
            val line = format.format(Date(entry.time)) + " [" + entry.type + "] " + entry.text
            if (used + line.length + 1 > maxChars) {
                omitted = true
                break
            }
            lines.add(line)
            used += line.length + 1
        }
        lines.reverse()
        val out = StringBuilder()
        out.append("\n\n=== ACTIVITY LOG (last ").append(settings.windowHours).append(" hours) ===\n")
        out.append("Current phone time: ")
            .append(SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date())).append(". ")
        out.append("This is your private on-device log of what happened recently: conversations, actions you did, ")
        out.append("messages received and battery events. Use it when the user asks what happened earlier, who messaged, ")
        out.append("what they said or asked before, what you did, or about the charger and battery. ")
        out.append("Never invent events that are not listed. Oldest first, newest last.\n")
        if (omitted) out.append("(Older entries are not shown here. Use recall_activity to search them.)\n")
        for (line in lines) out.append(line).append('\n')
        out.append("=== END OF ACTIVITY LOG ===\n")
        return out.toString()
    }

    /** Text returned to the assistant by the recall_activity tool. */
    fun recall(search: String, hours: Int, maxChars: Int = 6000): String {
        if (!settings.enabled) return "Activity memory is turned off in Jarvis Settings."
        val window = if (hours in 1..settings.windowHours) hours else settings.windowHours
        val entries = query(window, search, 600)
        if (entries.isEmpty()) {
            return if (search.isBlank()) {
                "Nothing is recorded for the last $window hours."
            } else {
                "Nothing matching \"" + search + "\" was found in the last " + window + " hours."
            }
        }
        val format = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
        val lines = ArrayList<String>()
        var used = 0
        var omitted = false
        for (entry in entries.asReversed()) {
            val line = format.format(Date(entry.time)) + " [" + entry.type + "] " + entry.text
            if (used + line.length + 1 > maxChars) {
                omitted = true
                break
            }
            lines.add(line)
            used += line.length + 1
        }
        lines.reverse()
        val header = "Activity from the last $window hours (oldest first)" + (if (omitted) ", most recent entries only" else "") + ":\n"
        return header + lines.joinToString("\n")
    }

    companion object {
        private const val TAG = "ActivityLog"
        private const val HOUR_MS = 60 * 60 * 1000L
        private const val MAX_TEXT_CHARS = 500

        const val TYPE_CONVERSATION = "Conversation"
        const val TYPE_ACTION = "Action"
        const val TYPE_BATTERY = "Battery"
        const val TYPE_MESSAGE = "Message"

        @Volatile
        private var instance: ActivityLog? = null

        fun get(context: Context): ActivityLog =
            instance ?: synchronized(this) {
                instance ?: ActivityLog(context.applicationContext).also { instance = it }
            }
    }
}
