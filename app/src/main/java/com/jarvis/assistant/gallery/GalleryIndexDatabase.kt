package com.jarvis.assistant.gallery

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Dao
interface GalleryIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<GalleryIndexEntity>)

    @Query("SELECT * FROM gallery_media ORDER BY dateAddedSeconds DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<GalleryIndexEntity>

    @Query("SELECT * FROM gallery_media WHERE displayName LIKE '%' || :query || '%' ORDER BY dateAddedSeconds DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<GalleryIndexEntity>

    @Query("SELECT COUNT(*) FROM gallery_media")
    suspend fun count(): Int
}

@Database(entities = [GalleryIndexEntity::class], version = 1, exportSchema = false)
abstract class GalleryIndexDatabase : RoomDatabase() {
    abstract fun galleryDao(): GalleryIndexDao

    companion object {
        @Volatile private var instance: GalleryIndexDatabase? = null

        fun getInstance(context: Context): GalleryIndexDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GalleryIndexDatabase::class.java,
                    "jarvis_gallery_index.db"
                ).build().also { instance = it }
            }
    }
}
