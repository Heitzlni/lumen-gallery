package org.fossify.gallery.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-defined virtual album that groups media paths regardless of
 * filesystem location. A photo can belong to many albums.
 */
@Entity(
    tableName = "albums",
    indices = [Index(value = ["name"], unique = true)],
)
data class Album(
    @PrimaryKey(autoGenerate = true) var id: Long?,
    @ColumnInfo(name = "name") var name: String,
    @ColumnInfo(name = "cover_path") var coverPath: String?,
    @ColumnInfo(name = "created_at") var createdAt: Long,
)

/** Link row — many-to-many between albums and media paths. */
@Entity(
    tableName = "album_items",
    indices = [
        Index(value = ["album_id"]),
        Index(value = ["media_path"]),
        Index(value = ["album_id", "media_path"], unique = true),
    ],
)
data class AlbumItem(
    @PrimaryKey(autoGenerate = true) var id: Long?,
    @ColumnInfo(name = "album_id") var albumId: Long,
    @ColumnInfo(name = "media_path") var mediaPath: String,
    @ColumnInfo(name = "added_at") var addedAt: Long,
)
