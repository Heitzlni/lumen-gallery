package org.fossify.gallery.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "image_texts",
    indices = [
        Index(value = ["media_path"], unique = true),
    ],
)
data class ImageText(
    @PrimaryKey(autoGenerate = true) var id: Long?,
    @ColumnInfo(name = "media_path") var mediaPath: String,
    @ColumnInfo(name = "text") var text: String,
    @ColumnInfo(name = "indexed_at") var indexedAt: Long,
)
