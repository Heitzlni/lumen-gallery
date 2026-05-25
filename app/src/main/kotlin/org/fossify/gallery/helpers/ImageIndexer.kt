package org.fossify.gallery.helpers

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.extensions.imageLabelDB
import org.fossify.gallery.models.ImageLabel
import java.io.File

/**
 * Runs Google ML Kit's on-device image labeling against every image in
 * MediaStore that hasn't been processed yet, persisting the labels to
 * [ImageLabel] so they can drive content-based search.
 *
 * No network calls — the model is bundled with the APK via the
 * `com.google.mlkit:image-labeling` artifact. Images in app-private
 * vault storage are not reachable through MediaStore, so they're
 * automatically excluded.
 */
object ImageIndexer {

    /** Minimum confidence required for ML Kit to return a label. Default is 0.5. */
    private const val CONFIDENCE_THRESHOLD = 0.6f

    /** Sentinel label used so we don't keep re-processing an image whose model returned no labels. */
    private const val SENTINEL_LABEL = "_no_labels_"

    @Volatile
    private var cancelled = false

    @Volatile
    var isRunning: Boolean = false
        private set

    fun cancel() {
        cancelled = true
    }

    /**
     * Walk MediaStore, label every image not yet present in image_labels.
     *
     * @param onProgress called with (current, total) — current = images processed so far
     * @param onDone called when finished (or cancelled) with the count of newly-indexed images
     */
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
                val options = ImageLabelerOptions.Builder()
                    .setConfidenceThreshold(CONFIDENCE_THRESHOLD)
                    .build()
                val labeler = ImageLabeling.getClient(options)

                val allPaths = enumerateImages(context)
                val pending = ArrayList<String>(allPaths.size)
                for (p in allPaths) {
                    if (!context.imageLabelDB.hasLabelsFor(p)) pending.add(p)
                }
                val total = pending.size

                pending.forEachIndexed { idx, path ->
                    if (cancelled) return@forEachIndexed
                    val rows = labelOne(context, labeler, path)
                    if (rows != null) {
                        if (rows.isNotEmpty()) {
                            context.imageLabelDB.insertAll(rows)
                        } else {
                            // No labels above the threshold — store a sentinel so we
                            // don't keep retrying this image on every re-index.
                            context.imageLabelDB.insertAll(
                                listOf(
                                    ImageLabel(
                                        id = null,
                                        mediaPath = path,
                                        label = SENTINEL_LABEL,
                                        confidence = 0f,
                                        indexedAt = System.currentTimeMillis(),
                                    )
                                )
                            )
                        }
                        indexed++
                    }
                    onProgress(idx + 1, total)
                }

                labeler.close()
            } catch (_: Exception) {
                // Whatever blew up — stop quietly; callers will see the count.
            } finally {
                val wasCancelled = cancelled
                isRunning = false
                cancelled = false
                onDone(indexed, wasCancelled)
            }
        }
    }

    private fun labelOne(
        context: Context,
        labeler: com.google.mlkit.vision.label.ImageLabeler,
        path: String,
    ): List<ImageLabel>? {
        return try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) return null
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            val results = Tasks.await(labeler.process(image))
            val now = System.currentTimeMillis()
            results.map {
                ImageLabel(
                    id = null,
                    mediaPath = path,
                    label = it.text.lowercase(),
                    confidence = it.confidence,
                    indexedAt = now,
                )
            }
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
