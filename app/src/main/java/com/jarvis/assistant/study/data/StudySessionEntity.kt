package com.jarvis.assistant.study.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted Study Board lesson stored locally in Room (`jarvis_study_board.db`).
 * Organized by [subject], searchable by [question], [topic], and [chapter], and stores
 * the validated [solutionJson] payload plus [lastStepIndex] for seamless resume/restore.
 */
@Entity(
    tableName = "study_sessions",
    indices = [
        Index(value = ["subject"]),
        Index(value = ["updatedAt"]),
        Index(value = ["isSaved"])
    ]
)
data class StudySessionEntity(
    @PrimaryKey
    val id: String,
    val subject: String,
    val topic: String,
    val chapter: String,
    val educationLevel: String,
    val language: String,
    val depth: String,
    val question: String,
    val finalAnswer: String,
    val verificationStatus: String,
    val inputKind: String,
    val attachmentRef: String?,
    val stepCount: Int,
    val bookmarkedStepCount: Int,
    val lastStepIndex: Int,
    val isCompleted: Boolean,
    val isSaved: Boolean,
    val isOffline: Boolean,
    val solutionJson: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Tracks a learner's topic familiarity and practice frequency per subject so JARVIS can
 * adapt explanations to previous learning progress.
 */
@Entity(
    tableName = "study_topic_progress",
    indices = [Index(value = ["subject"]), Index(value = ["lastStudiedAt"])]
)
data class StudyTopicProgressEntity(
    @PrimaryKey
    val topicKey: String,
    val subject: String,
    val topicLabel: String,
    val educationLevel: String,
    val sessionCount: Int,
    val lastStudiedAt: Long
)
