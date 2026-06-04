package org.fossify.gallery.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.gallery.models.ImageHash

@Dao
interface ImageHashDao {
    @Query("SELECT * FROM image_hashes")
    fun all(): List<ImageHash>

    @Query("SELECT COUNT(*) FROM image_hashes")
    fun count(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM image_hashes WHERE media_path = :path LIMIT 1)")
    fun hasFor(path: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(row: ImageHash)

    @Query("DELETE FROM image_hashes WHERE media_path = :path")
    fun deleteByPath(path: String)

    @Query("DELETE FROM image_hashes")
    fun clearAll()
}
