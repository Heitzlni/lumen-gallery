package org.fossify.gallery.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import org.fossify.gallery.helpers.OcrRecognizer

/**
 * Apple-style Live Text overlay. Sits transparently above the photo,
 * draws faint outlines around every recognized *word*, and highlights
 * the tapped words.
 *
 * Granularity:
 *   - Single tap on a word → toggle just that word.
 *   - Double tap on a word → select the entire line that word belongs to.
 *   - Tap outside any word → exit Live Text (reported via [onMissTap]).
 *
 * Coordinate translation from source-image pixels to view pixels is
 * plugged in from outside — the overlay does not know whether the
 * image is rendered by `SubsamplingScaleImageView` or `GestureImageView`.
 */
class LiveTextOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private data class Slot(
        val source: OcrRecognizer.Word,
        val viewBounds: RectF,
    )

    private var slots: List<Slot> = emptyList()
    private var projector: ((Rect) -> RectF?)? = null
    private val selectedWordKeys = HashSet<Int>()

    /**
     * Called after a tap is consumed by the overlay. Receives the number
     * of currently-selected words.
     */
    var onSelectionChanged: (selectedCount: Int) -> Unit = {}

    /** Called when the user tapped outside any text element. */
    var onMissTap: () -> Unit = {}

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0x66FFFFFF.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#66FFD740") // amber, ~40% opacity
    }
    private val selectedOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.parseColor("#FFFFD740")
    }

    private val cornerRadius = dp(4f)

    // Word identity within a session = its order in [slots]. Stable
    // because we never mutate the list after setRecognition.
    private val Slot.key: Int get() = slots.indexOf(this)

    private val gestureDetector: GestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleTap(e.x, e.y, selectWholeLine = false)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                handleTap(e.x, e.y, selectWholeLine = true)
                return true
            }
        },
    )

    fun setRecognition(words: List<OcrRecognizer.Word>, projector: (Rect) -> RectF?) {
        this.slots = words.map { Slot(it, RectF()) }
        this.projector = projector
        selectedWordKeys.clear()
        onSelectionChanged(0)
        refreshProjection()
    }

    fun clear() {
        slots = emptyList()
        projector = null
        selectedWordKeys.clear()
        invalidate()
    }

    fun refreshProjection() {
        val p = projector ?: return
        for (slot in slots) {
            val r = p(slot.source.bounds) ?: continue
            slot.viewBounds.set(r)
        }
        invalidate()
    }

    fun selectAll() {
        selectedWordKeys.clear()
        for (i in slots.indices) selectedWordKeys.add(i)
        onSelectionChanged(selectedWordKeys.size)
        invalidate()
    }

    /**
     * Build copied text in reading order: walk slots in OCR order, group
     * by line, then by block. Inserts a single newline between lines and
     * a blank line between blocks.
     */
    fun selectedText(): String {
        if (selectedWordKeys.isEmpty()) return ""
        val sb = StringBuilder()
        var lastLineId = Int.MIN_VALUE
        var lastBlockId = Int.MIN_VALUE
        var firstWord = true
        for ((idx, slot) in slots.withIndex()) {
            if (idx !in selectedWordKeys) continue
            val w = slot.source
            if (firstWord) {
                firstWord = false
            } else if (w.blockId != lastBlockId) {
                sb.append("\n\n")
            } else if (w.lineId != lastLineId) {
                sb.append('\n')
            } else {
                sb.append(' ')
            }
            sb.append(w.text)
            lastLineId = w.lineId
            lastBlockId = w.blockId
        }
        return sb.toString()
    }

    override fun onDraw(canvas: Canvas) {
        if (slots.isEmpty()) return
        // First pass: thin outline around every word so user sees what's
        // tappable.
        for (slot in slots) {
            if (slot.viewBounds.isEmpty) continue
            canvas.drawRoundRect(slot.viewBounds, cornerRadius, cornerRadius, outlinePaint)
        }
        // Second pass: filled highlight + thicker outline on selected.
        if (selectedWordKeys.isEmpty()) return
        for ((idx, slot) in slots.withIndex()) {
            if (idx !in selectedWordKeys) continue
            if (slot.viewBounds.isEmpty) continue
            canvas.drawRoundRect(slot.viewBounds, cornerRadius, cornerRadius, fillPaint)
            canvas.drawRoundRect(slot.viewBounds, cornerRadius, cornerRadius, selectedOutlinePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (slots.isEmpty()) return false
        gestureDetector.onTouchEvent(event)
        // Consume the stream so the underlying photo view doesn't pan/zoom.
        return true
    }

    private fun handleTap(x: Float, y: Float, selectWholeLine: Boolean) {
        // Slight padding so finger-fat doesn't miss a small word.
        val pad = dp(6f)
        val hitIdx = slots.indexOfFirst {
            val b = it.viewBounds
            !b.isEmpty &&
                x >= b.left - pad && x <= b.right + pad &&
                y >= b.top - pad && y <= b.bottom + pad
        }
        if (hitIdx < 0) {
            onMissTap()
            return
        }
        val hit = slots[hitIdx]
        if (selectWholeLine) {
            // Add every word in the same line. Don't deselect anything —
            // double-tap-to-extend feels broken if it also strips state.
            val lineId = hit.source.lineId
            for ((idx, s) in slots.withIndex()) {
                if (s.source.lineId == lineId) selectedWordKeys.add(idx)
            }
        } else {
            // Single tap toggles the individual word.
            if (hitIdx in selectedWordKeys) {
                selectedWordKeys.remove(hitIdx)
            } else {
                selectedWordKeys.add(hitIdx)
            }
        }
        onSelectionChanged(selectedWordKeys.size)
        invalidate()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
