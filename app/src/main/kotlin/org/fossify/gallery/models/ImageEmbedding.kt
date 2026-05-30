package org.fossify.gallery.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "image_embeddings",
    indices = [
        Index(value = ["media_path"], unique = true),
    ],
)
data class ImageEmbedding(
    @PrimaryKey(autoGenerate = true) var id: Long?,
    @ColumnInfo(name = "media_path") var mediaPath: String,
    @ColumnInfo(name = "vec", typeAffinity = ColumnInfo.BLOB) var vec: ByteArray,
    @ColumnInfo(name = "indexed_at") var indexedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageEmbedding) return false
        return id == other.id && mediaPath == other.mediaPath
    }

    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + mediaPath.hashCode()
        return result
    }
}
