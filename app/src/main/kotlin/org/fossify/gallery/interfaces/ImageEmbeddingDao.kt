package org.fossify.gallery.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.gallery.models.ImageEmbedding

@Dao
interface ImageEmbeddingDao {
    @Query("SELECT media_path, vec FROM image_embeddings")
    fun allForSearch(): List<EmbeddingRow>

    @Query("SELECT COUNT(*) FROM image_embeddings")
    fun count(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM image_embeddings WHERE media_path = :path LIMIT 1)")
    fun hasFor(path: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(row: ImageEmbedding)

    @Query("DELETE FROM image_embeddings WHERE media_path = :path")
    fun deleteByPath(path: String)

    @Query("DELETE FROM image_embeddings")
    fun clearAll()
}

data class EmbeddingRow(
    @androidx.room.ColumnInfo(name = "media_path") val mediaPath: String,
    @androidx.room.ColumnInfo(name = "vec") val vec: ByteArray,
)
