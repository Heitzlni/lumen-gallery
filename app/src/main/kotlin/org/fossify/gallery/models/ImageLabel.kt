package org.fossify.gallery.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "image_labels",
    indices = [
        Index(value = ["media_path"]),
        Index(value = ["label"]),
    ],
)
data class ImageLabel(
    @PrimaryKey(autoGenerate = true) var id: Long?,
    @ColumnInfo(name = "media_path") var mediaPath: String,
    @ColumnInfo(name = "label") var label: String,
    @ColumnInfo(name = "confidence") var confidence: Float,
    @ColumnInfo(name = "indexed_at") var indexedAt: Long,
)
