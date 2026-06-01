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
 * On-demand OCR that preserves per-line bounding boxes so the Live Text
 * overlay can highlight the actual words in the photo. Synchronous; call
 * from a background thread.
 */
object OcrRecognizer {

    data class Line(
        val text: String,
        val bounds: Rect,
        val lineId: Int,
        val blockId: Int,
    )

    data class Result(
        val lines: List<Line>,
        val fullText: String,
    )

    fun recognize(context: Context, path: String): Result? {
        return try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) return null
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val raw = Tasks.await(recognizer.process(image))
                val lines = ArrayList<Line>(64)
                raw.textBlocks.forEachIndexed { blockIdx, block ->
                    block.lines.forEachIndexed { lineIdx, line ->
                        val box = line.boundingBox ?: return@forEachIndexed
                        if (box.width() <= 0 || box.height() <= 0) return@forEachIndexed
                        lines.add(
                            Line(
                                text = line.text,
                                bounds = Rect(box),
                                lineId = blockIdx * 10000 + lineIdx,
                                blockId = blockIdx,
                            )
                        )
                    }
                }
                Result(lines = lines, fullText = raw.text)
            } finally {
                recognizer.close()
            }
        } catch (_: Exception) {
            null
        }
    }
}
