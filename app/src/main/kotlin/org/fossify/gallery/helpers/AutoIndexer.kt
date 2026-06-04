package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.gallery.extensions.config

/**
 * Single entry point that boots / cancels the three background indexers
 * (ML Kit image labels, OCR text, CLIP embeddings) based on the user's
 * settings. Called from MainActivity's onStart / onStop so indexing
 * happens while the user is browsing and pauses when the app is moved
 * to the background.
 *
 * Each indexer skips already-indexed paths internally, so cancelling
 * mid-run and re-starting later is just "resume from where we left off"
 * — no extra bookkeeping needed here.
 */
object AutoIndexer {

    fun startIfEnabled(context: Context) {
        val app = context.applicationContext
        val cfg = app.config

        if (cfg.autoIndexLabels && !ImageIndexer.isRunning) {
            ImageIndexer.indexAll(
                context = app,
                onProgress = { _, _ -> },
                onDone = { _, _ -> },
            )
        }
        if (cfg.autoIndexOcr && !TextIndexer.isRunning) {
            TextIndexer.indexAll(
                context = app,
                onProgress = { _, _ -> },
                onDone = { _, _ -> },
            )
        }
        if (cfg.autoIndexClip && !EmbeddingIndexer.isRunning) {
            EmbeddingIndexer.indexAll(
                context = app,
                onProgress = { _, _ -> },
                onDone = { _, _ -> EmbeddingSearch.invalidate() },
            )
        }
        if (cfg.autoIndexDuplicates && !HashIndexer.isRunning) {
            HashIndexer.indexAll(
                context = app,
                onProgress = { _, _ -> },
                onDone = { _, _ -> },
            )
        }
    }

    /**
     * Cancel any indexers we kicked off. The next [startIfEnabled] will
     * resume them — each indexer queries MediaStore fresh and skips
     * paths that are already in its DB table.
     */
    fun cancelAll() {
        ImageIndexer.cancel()
        TextIndexer.cancel()
        EmbeddingIndexer.cancel()
        HashIndexer.cancel()
    }
}
