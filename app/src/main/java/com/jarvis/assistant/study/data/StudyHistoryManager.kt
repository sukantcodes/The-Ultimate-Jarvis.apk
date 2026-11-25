package com.jarvis.assistant.study.data

import android.content.Context
import android.content.SharedPreferences
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.study.model.EducationLevel
import com.jarvis.assistant.study.model.ExplanationDepth
import com.jarvis.assistant.study.model.StudyJsonCodec
import com.jarvis.assistant.study.model.StudyLanguage
import com.jarvis.assistant.study.model.StudySolution
import com.jarvis.assistant.study.model.StudySubject
import com.jarvis.assistant.study.model.TeacherProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Manages persisted Study Board lessons, subject organization, search, bookmarked steps,
 * interrupted session recovery, and learner [TeacherProfile] personalization.
 */
class StudyHistoryManager(
    private val dao: StudySessionDao,
    private val prefs: SharedPreferences,
    private val defaultGlobalLanguageProvider: () -> String = { "Auto" }
) {

    fun loadTeacherProfile(): TeacherProfile {
        val level = EducationLevel.fromId(prefs.getString(KEY_EDUCATION_LEVEL, EducationLevel.GENERAL.id))
        val storedLang = prefs.getString(KEY_LANGUAGE, null)
        val lang = if (storedLang.isNullOrBlank()) {
            StudyLanguage.fromGlobalLanguagePref(defaultGlobalLanguageProvider())
        } else {
            StudyLanguage.fromId(storedLang)
        }
        val depth = ExplanationDepth.fromId(prefs.getString(KEY_DEPTH, ExplanationDepth.DETAILED.id))
        val voiceLangRaw = prefs.getString(KEY_VOICE_LANGUAGE, null)
        val voiceLang = if (voiceLangRaw.isNullOrBlank()) lang else StudyLanguage.fromId(voiceLangRaw)
        return TeacherProfile(
            educationLevel = level,
            preferredLanguage = lang,
            preferredDepth = depth,
            autoAdaptDepth = prefs.getBoolean(KEY_AUTO_ADAPT, true),
            voiceEnabled = prefs.getBoolean(KEY_VOICE_ENABLED, false),
            voiceLanguage = voiceLang,
            writingSpeed = prefs.getFloat(KEY_WRITING_SPEED, 1.0f).coerceIn(0.5f, 3.0f),
            showExplanations = prefs.getBoolean(KEY_SHOW_EXPLANATIONS, true),
            showGrid = prefs.getBoolean(KEY_SHOW_GRID, true)
        )
    }

    suspend fun loadEnrichedProfile(): TeacherProfile = withContext(Dispatchers.IO) {
        val base = loadTeacherProfile()
        val recent = runCatching {
            dao.getRecentTopicProgress(6).map { "${it.subject}: ${it.topicLabel}" }
        }.getOrDefault(emptyList())
        val completedCount = runCatching { dao.countCompletedSessions() }.getOrDefault(0)
        base.copy(
            recentTopics = recent,
            completedSessionsCount = completedCount
        )
    }

    fun saveTeacherProfile(profile: TeacherProfile) {
        prefs.edit()
            .putString(KEY_EDUCATION_LEVEL, profile.educationLevel.id)
            .putString(KEY_LANGUAGE, profile.preferredLanguage.id)
            .putString(KEY_DEPTH, profile.preferredDepth.id)
            .putBoolean(KEY_AUTO_ADAPT, profile.autoAdaptDepth)
            .putBoolean(KEY_VOICE_ENABLED, profile.voiceEnabled)
            .putString(KEY_VOICE_LANGUAGE, profile.voiceLanguage.id)
            .putFloat(KEY_WRITING_SPEED, profile.writingSpeed.coerceIn(0.5f, 3.0f))
            .putBoolean(KEY_SHOW_EXPLANATIONS, profile.showExplanations)
            .putBoolean(KEY_SHOW_GRID, profile.showGrid)
            .apply()
    }

    fun setActiveSessionId(sessionId: String?) {
        prefs.edit().putString(KEY_LAST_ACTIVE_SESSION_ID, sessionId).apply()
    }

    fun getActiveSessionId(): String? = prefs.getString(KEY_LAST_ACTIVE_SESSION_ID, null)

    fun observeAllSessions(limit: Int = 100): Flow<List<StudySessionEntity>> =
        dao.observeRecentSessions(limit)

    fun observeBySubject(subject: StudySubject, limit: Int = 100): Flow<List<StudySessionEntity>> =
        dao.observeSessionsBySubject(subject.name, limit)

    fun observeBookmarked(limit: Int = 100): Flow<List<StudySessionEntity>> =
        dao.observeBookmarkedSessions(limit)

    suspend fun saveSession(
        solution: StudySolution,
        lastStepIndex: Int,
        isCompleted: Boolean,
        markSaved: Boolean = solution.isSaved
    ): StudySolution = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val updated = solution.copy(
            isSaved = markSaved,
            updatedAt = now
        )
        val json = StudyJsonCodec.encodeSolution(updated)
        val entity = StudySessionEntity(
            id = updated.id,
            subject = updated.subject.name,
            topic = updated.topic.ifBlank { updated.subject.displayName },
            chapter = updated.chapter,
            educationLevel = updated.educationLevel.id,
            language = updated.language.id,
            depth = updated.depth.id,
            question = updated.question,
            finalAnswer = updated.finalAnswer,
            verificationStatus = updated.verificationStatus.name,
            inputKind = updated.inputKind.name,
            attachmentRef = updated.attachmentRef,
            stepCount = updated.steps.size,
            bookmarkedStepCount = updated.steps.count { it.isBookmarked },
            lastStepIndex = lastStepIndex.coerceAtLeast(0),
            isCompleted = isCompleted,
            isSaved = markSaved,
            isOffline = updated.isOffline,
            solutionJson = json,
            createdAt = updated.createdAt,
            updatedAt = now
        )
        dao.upsertSession(entity)
        recordTopicProgress(updated, now)
        setActiveSessionId(updated.id)
        updated
    }

    suspend fun updatePlaybackProgress(
        sessionId: String,
        lastStepIndex: Int,
        isCompleted: Boolean
    ) = withContext(Dispatchers.IO) {
        dao.updatePlaybackProgress(sessionId, lastStepIndex.coerceAtLeast(0), isCompleted, System.currentTimeMillis())
    }

    suspend fun loadSession(sessionId: String): Pair<StudySolution, StudySessionEntity>? =
        withContext(Dispatchers.IO) {
            val entity = dao.getSessionById(sessionId) ?: return@withContext null
            val solution = StudyJsonCodec.decodeSolution(entity.solutionJson) ?: return@withContext null
            solution to entity
        }

    suspend fun loadLastRecoverableSession(): Pair<StudySolution, StudySessionEntity>? =
        withContext(Dispatchers.IO) {
            val activeId = getActiveSessionId()
            val entity = if (!activeId.isNullOrBlank()) {
                dao.getSessionById(activeId) ?: dao.getLatestInterruptedSession()
            } else {
                dao.getLatestInterruptedSession()
            } ?: return@withContext null
            val solution = StudyJsonCodec.decodeSolution(entity.solutionJson) ?: return@withContext null
            solution to entity
        }

    suspend fun searchSessions(
        query: String,
        subject: StudySubject? = null,
        bookmarkedOnly: Boolean = false
    ): List<StudySessionEntity> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        val list = if (clean.isBlank()) {
            dao.listRecentSessionsOnce(100).filter { entity ->
                subject == null || entity.subject == subject.name
            }
        } else {
            dao.searchSessions(clean, subject?.name, 100)
        }
        if (bookmarkedOnly) list.filter { it.bookmarkedStepCount > 0 } else list
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        dao.deleteSession(sessionId)
        if (getActiveSessionId() == sessionId) {
            setActiveSessionId(null)
        }
    }

    private suspend fun recordTopicProgress(solution: StudySolution, now: Long) {
        val topicLabel = solution.topic.ifBlank { solution.chapter }.ifBlank { return }
        val key = "${solution.subject.name}:${topicLabel.lowercase(Locale.ROOT).take(60)}"
        val existing = dao.getTopicProgress(key)
        dao.upsertTopicProgress(
            StudyTopicProgressEntity(
                topicKey = key,
                subject = solution.subject.name,
                topicLabel = topicLabel,
                educationLevel = solution.educationLevel.id,
                sessionCount = (existing?.sessionCount ?: 0) + 1,
                lastStudiedAt = now
            )
        )
    }

    companion object {
        private const val PREFS_NAME = "jarvis_study_board_prefs"
        private const val KEY_EDUCATION_LEVEL = "education_level"
        private const val KEY_LANGUAGE = "study_language"
        private const val KEY_DEPTH = "explanation_depth"
        private const val KEY_AUTO_ADAPT = "auto_adapt_depth"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_VOICE_LANGUAGE = "voice_language"
        private const val KEY_WRITING_SPEED = "writing_speed"
        private const val KEY_SHOW_EXPLANATIONS = "show_explanations"
        private const val KEY_SHOW_GRID = "show_grid"
        private const val KEY_LAST_ACTIVE_SESSION_ID = "last_active_session_id"

        fun create(context: Context): StudyHistoryManager {
            val appCtx = context.applicationContext
            val db = StudyBoardDatabase.getInstance(appCtx)
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val appPrefs = AppPreferences(appCtx)
            return StudyHistoryManager(
                dao = db.studySessionDao(),
                prefs = prefs,
                defaultGlobalLanguageProvider = { appPrefs.conversationLanguage }
            )
        }
    }
}
