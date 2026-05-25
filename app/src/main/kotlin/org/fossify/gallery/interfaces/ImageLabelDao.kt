package org.fossify.gallery.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.gallery.models.ImageLabel

@Dao
interface ImageLabelDao {
    @Query("SELECT DISTINCT media_path FROM image_labels WHERE label LIKE :pattern ORDER BY confidence DESC")
    fun pathsMatching(pattern: String): List<String>

    @Query("SELECT * FROM image_labels WHERE media_path = :path")
    fun forPath(path: String): List<ImageLabel>

    @Query("SELECT COUNT(DISTINCT media_path) FROM image_labels")
    fun indexedPathCount(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM image_labels WHERE media_path = :path LIMIT 1)")
    fun hasLabelsFor(path: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(labels: List<ImageLabel>)

    @Query("DELETE FROM image_labels WHERE media_path = :path")
    fun deleteByPath(path: String)

    @Query("DELETE FROM image_labels")
    fun clearAll()
}
