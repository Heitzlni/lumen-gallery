package org.fossify.gallery.helpers

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.extensions.imageTextDB
import org.fossify.gallery.models.ImageText
import java.io.File

/**
 * On-device OCR via ML Kit Text Recognition. Stores one row per image in
 * `image_texts` so search can grep across all detected text.
 *
 * Empty results are still inserted (with `text = ""`) so we don't re-scan
 * the same image on every reindex.
 */
object TextIndexer {

    @Volatile
    private var cancelled = false

    @Volatile
    var isRunning: Boolean = false
        private set

    fun cancel() {
        cancelled = true
    }

    fun indexAll(
        context: Context,
        onProgress: (current: Int, total: Int) -> Unit,
        onDone: (indexedCount: Int, cancelled: Boolean) -> Unit,
    ) {
        if (isRunning) {
            onDone(0, false)
            return
        }
        isRunning = true
        cancelled = false

        ensureBackgroundThread {
            var indexed = 0
            try {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

                val allPaths = enumerateImages(context)
                val pending = ArrayList<String>(allPaths.size)
                for (p in allPaths) {
                    if (!context.imageTextDB.hasTextFor(p)) pending.add(p)
                }
                val total = pending.size

                pending.forEachIndexed { idx, path ->
                    if (cancelled) return@forEachIndexed
                    val row = readOne(context, recognizer, path)
                    if (row != null) {
                        context.imageTextDB.insert(row)
                        indexed++
                    }
                    onProgress(idx + 1, total)
                }

                recognizer.close()
            } catch (_: Exception) {
                // Stop quietly — caller sees the count.
            } finally {
                val wasCancelled = cancelled
                isRunning = false
                cancelled = false
                onDone(indexed, wasCancelled)
            }
        }
    }

    private fun readOne(
        context: Context,
        recognizer: TextRecognizer,
        path: String,
    ): ImageText? {
        return try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) return null
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            val result = Tasks.await(recognizer.process(image))
            // Collapse whitespace + lowercase so LIKE matching is forgiving.
            val text = result.text
                .replace('\n', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
                .lowercase()
            ImageText(
                id = null,
                mediaPath = path,
                text = text,
                indexedAt = System.currentTimeMillis(),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun enumerateImages(context: Context): List<String> {
        val paths = ArrayList<String>()
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                while (c.moveToNext()) {
                    val p = c.getString(idx)
                    if (!p.isNullOrEmpty()) paths.add(p)
                }
            }
        } catch (_: Exception) {
        }
        return paths
    }
}
