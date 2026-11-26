package com.jarvis.assistant.study.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * One saved Study Board session. The lesson is stored once, as compact JSON (text and diagram descriptions only), and the
 * board is drawn again from it, so no board screenshots or photo copies are kept.
 */
@Entity(tableName = "study_sessions", indices = [Index("updatedAt"), Index("subject")])
data class StudySessionEntity(
    @PrimaryKey val id: String,
    val question: String,
    val subject: String,
    val topic: String,
    val level: String,
    /** COMPLETE, INTERRUPTED or FAILED. */
    val status: String,
    /** The student saved this lesson on purpose. */
    @ColumnInfo(defaultValue = "0") val saved: Boolean,
    val lessonJson: String,
    val createdAt: Long,
    val updatedAt: Long
)

/** A history row without the lesson body, so listing and searching never load large JSON. */
data class StudySessionRow(
    val id: String,
    val question: String,
    val subject: String,
    val topic: String,
    val status: String,
    val saved: Boolean,
    val updatedAt: Long
)

@Dao
interface StudySessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StudySessionEntity)

    @Query("SELECT * FROM study_sessions WHERE id = :id LIMIT 1")
    suspend fun get(id: String): StudySessionEntity?

    @Query(
        "SELECT id, question, subject, topic, status, saved, updatedAt FROM study_sessions " +
            "WHERE (:query = '' OR question LIKE '%' || :query || '%' OR topic LIKE '%' || :query || '%') " +
            "AND (:subject = '' OR subject = :subject) AND (:savedOnly = 0 OR saved = 1) " +
            "ORDER BY updatedAt DESC LIMIT :limit"
    )
    suspend fun search(query: String, subject: String, savedOnly: Int, limit: Int): List<StudySessionRow>

    @Query("SELECT * FROM study_sessions WHERE status = 'INTERRUPTED' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestInterrupted(): StudySessionEntity?

    @Query("UPDATE study_sessions SET saved = :saved, updatedAt = :now WHERE id = :id")
    suspend fun setSaved(id: String, saved: Boolean, now: Long)

    @Query("DELETE FROM study_sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT DISTINCT subject FROM study_sessions ORDER BY subject")
    suspend fun subjects(): List<String>
}

@Database(entities = [StudySessionEntity::class], version = 1, exportSchema = false)
abstract class StudyDatabase : RoomDatabase() {
    abstract fun sessions(): StudySessionDao

    companion object {
        private const val NAME = "jarvis_study.db"

        @Volatile
        private var instance: StudyDatabase? = null

        fun get(context: Context): StudyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, StudyDatabase::class.java, NAME)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
