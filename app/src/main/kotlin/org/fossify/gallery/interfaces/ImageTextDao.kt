package org.fossify.gallery.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.gallery.models.ImageText

@Dao
interface ImageTextDao {
    @Query("SELECT DISTINCT media_path FROM image_texts WHERE text LIKE :pattern AND text != ''")
    fun pathsMatching(pattern: String): List<String>

    @Query("SELECT * FROM image_texts WHERE media_path = :path LIMIT 1")
    fun forPath(path: String): ImageText?

    @Query("SELECT COUNT(*) FROM image_texts")
    fun indexedPathCount(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM image_texts WHERE media_path = :path LIMIT 1)")
    fun hasTextFor(path: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(row: ImageText)

    @Query("DELETE FROM image_texts WHERE media_path = :path")
    fun deleteByPath(path: String)

    @Query("DELETE FROM image_texts")
    fun clearAll()
}
