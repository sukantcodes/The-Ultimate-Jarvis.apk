package com.jarvis.assistant.study.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [StudySessionEntity::class, StudyTopicProgressEntity::class],
    version = 1,
    exportSchema = false
)
abstract class StudyBoardDatabase : RoomDatabase() {
    abstract fun studySessionDao(): StudySessionDao

    companion object {
        @Volatile
        private var INSTANCE: StudyBoardDatabase? = null

        fun getInstance(context: Context): StudyBoardDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StudyBoardDatabase::class.java,
                    "jarvis_study_board.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
