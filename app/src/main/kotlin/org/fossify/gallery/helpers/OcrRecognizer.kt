package org.fossify.gallery.helpers

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/**
 * On-demand OCR that preserves per-word bounding boxes (and a parent
 * lineId so the overlay can offer "select whole line" on a double tap).
 * Synchronous; call from a background thread.
 *
 * Results are cached by absolute path + file mtime so re-entering Live
 * Text on the same image is instant. The cache holds the most recent
 * recognition — that's all Live Text ever needs since it's strictly
 * per-image-while-viewing.
 */
object OcrRecognizer {

    data class Word(
        val text: String,
        val bounds: Rect,
        val lineId: Int,
        val blockId: Int,
        /** Index within the parent line — preserves reading order. */
        val orderInLine: Int,
    )

    data class Result(
        val words: List<Word>,
        val fullText: String,
    )

    @Volatile private var cachedKey: String? = null
    @Volatile private var cachedResult: Result? = null

    fun recognize(context: Context, path: String): Result? {
        val key = cacheKey(path) ?: return null
        val hit = synchronized(this) {
            if (cachedKey == key) cachedResult else null
        }
        if (hit != null) return hit

        return try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) return null
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val raw = Tasks.await(recognizer.process(image))
                val words = ArrayList<Word>(128)
                raw.textBlocks.forEachIndexed { blockIdx, block ->
                    block.lines.forEachIndexed { lineIdx, line ->
                        val lineId = blockIdx * 10000 + lineIdx
                        line.elements.forEachIndexed { elIdx, el ->
                            val box = el.boundingBox ?: return@forEachIndexed
                            if (box.width() <= 0 || box.height() <= 0) return@forEachIndexed
                            words.add(
                                Word(
                                    text = el.text,
                                    bounds = Rect(box),
                                    lineId = lineId,
                                    blockId = blockIdx,
                                    orderInLine = elIdx,
                                )
                            )
                        }
                    }
                }
                val result = Result(words = words, fullText = raw.text)
                synchronized(this) {
                    cachedKey = key
                    cachedResult = result
                }
                result
            } finally {
                recognizer.close()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun invalidate() {
        synchronized(this) {
            cachedKey = null
            cachedResult = null
        }
    }

    /** Key includes mtime so a file edit invalidates the cached OCR. */
    private fun cacheKey(path: String): String? {
        val f = File(path)
        if (!f.exists()) return null
        return "$path|${f.lastModified()}|${f.length()}"
    }
}
