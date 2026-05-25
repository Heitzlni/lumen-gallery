package org.fossify.gallery.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "vault_items", indices = [(Index(value = ["encrypted_filename"], unique = true))])
data class VaultItem(
    @PrimaryKey(autoGenerate = true) var id: Long?,
    @ColumnInfo(name = "encrypted_filename") var encryptedFilename: String,
    @ColumnInfo(name = "original_filename") var originalFilename: String,
    @ColumnInfo(name = "mime_type") var mimeType: String,
    @ColumnInfo(name = "original_size_bytes") var originalSizeBytes: Long,
    @ColumnInfo(name = "date_added") var dateAdded: Long,
    @ColumnInfo(name = "thumbnail_filename", defaultValue = "") var thumbnailFilename: String = "",
    @ColumnInfo(name = "original_folder_path", defaultValue = "") var originalFolderPath: String = "",
    @ColumnInfo(name = "date_taken", defaultValue = "0") var dateTaken: Long = 0L,
    @ColumnInfo(name = "vault_album_name", defaultValue = "") var vaultAlbumName: String = "",
)
