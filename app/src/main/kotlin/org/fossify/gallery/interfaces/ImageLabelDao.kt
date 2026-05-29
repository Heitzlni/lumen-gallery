package org.fossify.gallery.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.gallery.models.ImageLabel

@Dao
interface ImageLabelDao {
    // SELECT DISTINCT + ORDER BY on a non-selected column was producing
    // empty results on some SQLite versions even when labels matched.
    // Removed the ORDER BY — for search filtering we don't need ranked
    // confidence, just whether each path matches at all.
    @Query("SELECT DISTINCT media_path FROM image_labels WHERE label LIKE :pattern")
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

    /**
     * Aggregate every distinct label with how many images carry it, hottest
     * first. Used by the "Show my labels" diagnostic so the user can see what
     * the on-device model actually called their photos.
     */
    @Query("SELECT label, COUNT(*) AS image_count FROM image_labels WHERE label != '_no_labels_' GROUP BY label ORDER BY image_count DESC, label ASC")
    fun labelCounts(): List<LabelCount>
}

data class LabelCount(
    val label: String,
    @androidx.room.ColumnInfo(name = "image_count") val imageCount: Int,
)
