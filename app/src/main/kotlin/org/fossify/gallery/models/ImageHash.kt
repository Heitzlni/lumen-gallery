package org.fossify.gallery.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "image_hashes",
    indices = [
        Index(value = ["media_path"], unique = true),
        Index(value = ["phash"]),
    ],
)
data class ImageHash(
    @PrimaryKey(autoGenerate = true) var id: Long?,
    @ColumnInfo(name = "media_path") var mediaPath: String,
    /** 64-bit average-hash. Compared via Long.bitCount(a xor b) for hamming distance. */
    @ColumnInfo(name = "phash") var phash: Long,
    @ColumnInfo(name = "file_size") var fileSize: Long,
    @ColumnInfo(name = "indexed_at") var indexedAt: Long,
)
