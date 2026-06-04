package org.fossify.gallery.helpers

import android.content.Context
import android.provider.MediaStore
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.extensions.vaultItemDB
import org.fossify.gallery.models.VaultItem
import java.io.File

/**
 * Scans MediaStore for images and videos whose [MediaStore.Files.FileColumns.DATE_ADDED]
 * is within the last [withinMinutes] minutes, then moves them into the
 * vault. Used after the Google Photos Locked Folder migration where the
 * user "Move"s photos out — they reappear in whichever folder Google
 * Photos originally got them from, so we have to find them by recency
 * rather than location.
 */
object RecentlyAddedMover {

    data class Match(val path: String, val name: String, val mime: String, val sizeBytes: Long)

    fun findRecent(context: Context, withinMinutes: Int): List<Match> {
        val cutoff = (System.currentTimeMillis() / 1000) - (withinMinutes * 60L)
        val out = ArrayList<Match>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED,
        )
        // MEDIA_TYPE_IMAGE=1, MEDIA_TYPE_VIDEO=3.
        val selection =
            "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?) " +
                "AND ${MediaStore.Files.FileColumns.DATE_ADDED} >= ?"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            cutoff.toString(),
        )
        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection, selection, args,
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
            )?.use { c ->
                val dataIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                while (c.moveToNext()) {
                    val path = c.getString(dataIdx) ?: continue
                    if (path.isEmpty() || !File(path).exists()) continue
                    out.add(
                        Match(
                            path = path,
                            name = c.getString(nameIdx) ?: File(path).name,
                            mime = c.getString(mimeIdx) ?: "application/octet-stream",
                            sizeBytes = c.getLong(sizeIdx),
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /**
     * Encrypts + moves [matches] into the vault under [albumName], deleting
     * the originals after each successful encrypt. Calls [onDone] with the
     * count of moved items on the main thread.
     */
    fun moveAll(
        context: Context,
        matches: List<Match>,
        albumName: String,
        onProgress: (current: Int, total: Int) -> Unit,
        onDone: (movedCount: Int) -> Unit,
    ) {
        ensureBackgroundThread {
            var moved = 0
            val total = matches.size
            matches.forEachIndexed { idx, m ->
                val encryptedName = VaultCrypto.encryptFromPath(context, m.path) ?: run {
                    onProgress(idx + 1, total)
                    return@forEachIndexed
                }
                val thumbnailName = VaultCrypto.generateAndEncryptThumbnail(
                    context, m.path, m.mime
                ) ?: ""
                val src = File(m.path)
                val item = VaultItem(
                    id = null,
                    encryptedFilename = encryptedName,
                    originalFilename = m.name,
                    mimeType = m.mime,
                    originalSizeBytes = m.sizeBytes,
                    dateAdded = System.currentTimeMillis(),
                    thumbnailFilename = thumbnailName,
                    originalFolderPath = src.parent.orEmpty(),
                    dateTaken = if (src.exists()) src.lastModified() else System.currentTimeMillis(),
                    vaultAlbumName = albumName,
                )
                try {
                    context.vaultItemDB.insert(item)
                    // Best-effort delete of the original. If the file is on
                    // primary storage it usually just works; on scoped paths
                    // the user will see it stick around — they can clean up
                    // manually then.
                    src.delete()
                    moved++
                } catch (_: Exception) {
                    VaultCrypto.deleteEncrypted(context, encryptedName)
                    if (thumbnailName.isNotEmpty()) {
                        VaultCrypto.deleteEncrypted(context, thumbnailName)
                    }
                }
                onProgress(idx + 1, total)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(moved) }
        }
    }
}
