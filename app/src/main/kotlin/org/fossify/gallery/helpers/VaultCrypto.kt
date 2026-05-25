package org.fossify.gallery.helpers

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.bumptech.glide.Glide
import org.fossify.gallery.extensions.vaultDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

object VaultCrypto {

    private fun masterKey(context: Context): MasterKey =
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    private fun encryptedFile(context: Context, file: File): EncryptedFile =
        EncryptedFile.Builder(
            context,
            file,
            masterKey(context),
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()

    fun encryptFromPath(context: Context, sourcePath: String): String? {
        val src = File(sourcePath)
        if (!src.exists() || !src.canRead()) return null

        val encryptedName = UUID.randomUUID().toString()
        val dest = File(context.vaultDir, encryptedName)
        if (dest.exists()) dest.delete()

        try {
            encryptedFile(context, dest).openFileOutput().use { out ->
                FileInputStream(src).use { input ->
                    input.copyTo(out)
                }
            }
        } catch (e: Exception) {
            if (dest.exists()) dest.delete()
            return null
        }

        return encryptedName
    }

    fun decryptToCache(context: Context, encryptedName: String, originalName: String): File? {
        val src = File(context.vaultDir, encryptedName)
        if (!src.exists()) return null

        val outDir = File(context.cacheDir, "vault_decrypted")
        if (!outDir.exists()) outDir.mkdirs()
        val safeName = originalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(outDir, "${System.currentTimeMillis()}_$safeName")
        if (out.exists()) out.delete()

        return try {
            encryptedFile(context, src).openFileInput().use { input ->
                FileOutputStream(out).use { fos ->
                    input.copyTo(fos)
                }
            }
            out
        } catch (e: Exception) {
            if (out.exists()) out.delete()
            null
        }
    }

    fun deleteEncrypted(context: Context, encryptedName: String): Boolean {
        return File(context.vaultDir, encryptedName).delete()
    }

    fun wipeCacheDecrypted(context: Context) {
        val dir = File(context.cacheDir, "vault_decrypted")
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    private const val THUMB_MAX_DIM = 320

    fun generateAndEncryptThumbnail(
        context: Context,
        sourcePath: String,
        mimeType: String
    ): String? {
        val bitmap: Bitmap? = try {
            if (mimeType.startsWith("video/")) {
                extractVideoFrame(sourcePath)
            } else {
                // Glide handles HEIC, RAW, oversized JPEG, etc. — BitmapFactory
                // silently returns null on many of those formats.
                Glide.with(context.applicationContext)
                    .asBitmap()
                    .load(sourcePath)
                    .override(THUMB_MAX_DIM, THUMB_MAX_DIM)
                    .centerCrop()
                    .submit()
                    .get(15, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
            null
        }
        if (bitmap == null) return null

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        bitmap.recycle()

        val encryptedName = "${UUID.randomUUID()}.thumb"
        val dest = File(context.vaultDir, encryptedName)
        if (dest.exists()) dest.delete()

        try {
            encryptedFile(context, dest).openFileOutput().use { out ->
                out.write(baos.toByteArray())
            }
        } catch (_: Exception) {
            if (dest.exists()) dest.delete()
            return null
        }
        return encryptedName
    }

    fun decryptThumbnailToCache(context: Context, thumbnailFilename: String): File? {
        if (thumbnailFilename.isEmpty()) return null
        val src = File(context.vaultDir, thumbnailFilename)
        if (!src.exists()) return null

        val outDir = File(context.cacheDir, "vault_thumbs")
        if (!outDir.exists()) outDir.mkdirs()
        val out = File(outDir, thumbnailFilename)
        // Skip re-decrypting if we already have a fresh copy. Encrypted blobs
        // never change after creation, so cached plaintext stays valid until
        // the activity wipes it.
        if (out.exists() && out.length() > 0) return out

        return try {
            encryptedFile(context, src).openFileInput().use { input ->
                FileOutputStream(out).use { fos ->
                    input.copyTo(fos)
                }
            }
            out
        } catch (_: Exception) {
            if (out.exists()) out.delete()
            null
        }
    }

    fun wipeCacheThumbnails(context: Context) {
        val dir = File(context.cacheDir, "vault_thumbs")
        if (dir.exists()) dir.listFiles()?.forEach { it.delete() }
    }

    private fun extractVideoFrame(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    fun bestEffortSecureDelete(file: File): Boolean {
        try {
            if (file.exists() && file.canWrite()) {
                val length = file.length()
                FileOutputStream(file).use { fos ->
                    val buf = ByteArray(8192)
                    var written = 0L
                    while (written < length) {
                        val toWrite = minOf(8192L, length - written).toInt()
                        fos.write(buf, 0, toWrite)
                        written += toWrite
                    }
                    fos.fd.sync()
                }
            }
        } catch (_: Exception) {
            // best-effort
        }
        return file.delete()
    }
}
