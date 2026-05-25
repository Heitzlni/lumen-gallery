package org.fossify.gallery.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.gallery.models.VaultItem

@Dao
interface VaultItemDao {
    // Sort by date_taken when known (we record EXIF/MediaStore "taken"
    // since v13), falling back to date_added for items moved into the
    // vault by older builds.
    @Query("SELECT * FROM vault_items ORDER BY CASE WHEN date_taken > 0 THEN date_taken ELSE date_added END DESC")
    fun getAll(): List<VaultItem>

    @Query("SELECT * FROM vault_items WHERE vault_album_name = :albumName ORDER BY CASE WHEN date_taken > 0 THEN date_taken ELSE date_added END DESC")
    fun getByAlbum(albumName: String): List<VaultItem>

    @Query("SELECT DISTINCT vault_album_name FROM vault_items ORDER BY vault_album_name COLLATE NOCASE ASC")
    fun getDistinctAlbumNames(): List<String>

    @Query("SELECT COUNT(*) FROM vault_items WHERE vault_album_name = :albumName")
    fun countByAlbum(albumName: String): Int

    @Query("SELECT thumbnail_filename FROM vault_items WHERE vault_album_name = :albumName AND thumbnail_filename != '' ORDER BY CASE WHEN date_taken > 0 THEN date_taken ELSE date_added END DESC LIMIT 1")
    fun newestThumbnailForAlbum(albumName: String): String?

    @Query("SELECT * FROM vault_items WHERE id = :id")
    fun getById(id: Long): VaultItem?

    @Query("SELECT COUNT(*) FROM vault_items")
    fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: VaultItem): Long

    @Query("DELETE FROM vault_items WHERE id = :id")
    fun deleteById(id: Long)
}
