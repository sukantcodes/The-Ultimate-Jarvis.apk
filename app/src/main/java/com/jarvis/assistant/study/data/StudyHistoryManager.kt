package com.jarvis.assistant.study.data

import android.content.Context
import com.jarvis.assistant.study.StudyDoubt
import com.jarvis.assistant.study.StudyLesson
import com.jarvis.assistant.study.StudyLessonCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Saves and reopens Study Board sessions. All database work runs off the main thread. */
class StudyHistoryManager(context: Context) {

    private val dao = StudyDatabase.get(context).sessions()

    enum class Status { COMPLETE, INTERRUPTED, FAILED }

    class Loaded(val id: String, val restored: StudyLessonCodec.Restored, val status: String, val saved: Boolean)

    suspend fun save(
        id: String, lesson: StudyLesson, doubts: List<StudyDoubt>, bookmarks: Set<Int>,
        status: Status, createdAt: Long, saved: Boolean? = null
    ) = withContext(Dispatchers.IO) {
        val existing = dao.get(id)
        dao.upsert(
            StudySessionEntity(
                id = id,
                question = lesson.question.take(2000),
                subject = lesson.subject.label,
                topic = lesson.topic,
                level = lesson.level,
                status = status.name,
                saved = saved ?: existing?.saved ?: false,
                lessonJson = StudyLessonCodec.toJson(lesson, doubts, bookmarks),
                createdAt = existing?.createdAt ?: createdAt,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun setSaved(id: String, saved: Boolean) = withContext(Dispatchers.IO) { dao.setSaved(id, saved, System.currentTimeMillis()) }

    suspend fun load(id: String): Loaded? = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext null
        val restored = StudyLessonCodec.fromJson(e.lessonJson) ?: return@withContext null
        Loaded(e.id, restored, e.status, e.saved)
    }

    suspend fun latestInterrupted(): Pair<String, String>? = withContext(Dispatchers.IO) {
        dao.latestInterrupted()?.let { it.id to it.question }
    }

    suspend fun search(query: String, subject: String, savedOnly: Boolean): List<StudySessionRow> = withContext(Dispatchers.IO) {
        // % and _ typed by the student are literal characters, not wildcards.
        val clean = query.trim().replace("%", "").replace("_", "")
        dao.search(clean, subject, if (savedOnly) 1 else 0, 200)
    }

    suspend fun subjects(): List<String> = withContext(Dispatchers.IO) { dao.subjects() }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { dao.delete(id) }
}
