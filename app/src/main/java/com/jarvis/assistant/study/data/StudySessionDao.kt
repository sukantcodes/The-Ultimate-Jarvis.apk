package com.jarvis.assistant.study.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StudySessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(entity: StudySessionEntity)

    @Query("SELECT * FROM study_sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionById(id: String): StudySessionEntity?

    @Query("SELECT * FROM study_sessions ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecentSessions(limit: Int = 100): Flow<List<StudySessionEntity>>

    @Query("SELECT * FROM study_sessions WHERE subject = :subject ORDER BY updatedAt DESC LIMIT :limit")
    fun observeSessionsBySubject(subject: String, limit: Int = 100): Flow<List<StudySessionEntity>>

    @Query("SELECT * FROM study_sessions WHERE bookmarkedStepCount > 0 ORDER BY updatedAt DESC LIMIT :limit")
    fun observeBookmarkedSessions(limit: Int = 100): Flow<List<StudySessionEntity>>

    @Query(
        """
        SELECT * FROM study_sessions
        WHERE (:subject IS NULL OR subject = :subject)
          AND (
              question LIKE '%' || :query || '%'
              OR topic LIKE '%' || :query || '%'
              OR chapter LIKE '%' || :query || '%'
              OR finalAnswer LIKE '%' || :query || '%'
          )
        ORDER BY updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun searchSessions(
        query: String,
        subject: String? = null,
        limit: Int = 80
    ): List<StudySessionEntity>

    @Query("SELECT * FROM study_sessions ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun listRecentSessionsOnce(limit: Int = 80): List<StudySessionEntity>

    @Query("SELECT * FROM study_sessions WHERE isCompleted = 0 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestInterruptedSession(): StudySessionEntity?

    @Query("SELECT * FROM study_sessions ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestSession(): StudySessionEntity?

    @Query("UPDATE study_sessions SET lastStepIndex = :stepIndex, isCompleted = :completed, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePlaybackProgress(id: String, stepIndex: Int, completed: Boolean, updatedAt: Long)

    @Query("DELETE FROM study_sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("DELETE FROM study_sessions")
    suspend fun clearAllSessions()

    @Query("SELECT COUNT(*) FROM study_sessions WHERE isCompleted = 1")
    suspend fun countCompletedSessions(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTopicProgress(progress: StudyTopicProgressEntity)

    @Query("SELECT * FROM study_topic_progress WHERE topicKey = :key LIMIT 1")
    suspend fun getTopicProgress(key: String): StudyTopicProgressEntity?

    @Query("SELECT * FROM study_topic_progress ORDER BY lastStudiedAt DESC LIMIT :limit")
    suspend fun getRecentTopicProgress(limit: Int = 10): List<StudyTopicProgressEntity>
}
