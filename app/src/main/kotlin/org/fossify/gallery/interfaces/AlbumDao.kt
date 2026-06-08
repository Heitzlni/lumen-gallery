package org.fossify.gallery.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import org.fossify.gallery.models.Album
import org.fossify.gallery.models.AlbumItem

data class AlbumSummary(
    val id: Long,
    val name: String,
    val coverPath: String?,
    val createdAt: Long,
    val photoCount: Int,
    val latestItemPath: String?,
)

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums ORDER BY created_at DESC")
    fun allAlbums(): List<Album>

    @Query("SELECT * FROM albums WHERE id = :id LIMIT 1")
    fun getById(id: Long): Album?

    @Query("SELECT * FROM albums WHERE name = :name LIMIT 1")
    fun getByName(name: String): Album?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAlbum(album: Album): Long

    @Update
    fun updateAlbum(album: Album)

    @Query("DELETE FROM albums WHERE id = :id")
    fun deleteAlbumById(id: Long)

    /**
     * Get every album with its photo count + the most-recently-added item's
     * path (used as a fallback cover when [Album.coverPath] is null). Order
     * is newest album first, matching what [allAlbums] returns.
     */
    @Query(
        """
        SELECT a.id AS id, a.name AS name, a.cover_path AS coverPath,
               a.created_at AS createdAt,
               (SELECT COUNT(*) FROM album_items WHERE album_id = a.id) AS photoCount,
               (SELECT media_path FROM album_items WHERE album_id = a.id
                  ORDER BY added_at DESC LIMIT 1) AS latestItemPath
        FROM albums a
        ORDER BY a.created_at DESC
        """
    )
    fun allAlbumSummaries(): List<AlbumSummary>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertItems(items: List<AlbumItem>)

    @Query("SELECT media_path FROM album_items WHERE album_id = :albumId ORDER BY added_at DESC")
    fun pathsForAlbum(albumId: Long): List<String>

    @Query("DELETE FROM album_items WHERE album_id = :albumId AND media_path IN (:paths)")
    fun removePathsFromAlbum(albumId: Long, paths: List<String>)

    @Query("DELETE FROM album_items WHERE album_id = :albumId")
    fun clearAlbumItems(albumId: Long)

    @Query("DELETE FROM album_items WHERE media_path = :path")
    fun deletePathEverywhere(path: String)
}
