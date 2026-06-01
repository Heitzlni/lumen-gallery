package org.fossify.gallery.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.fossify.gallery.helpers.OcrRecognizer

/**
 * Apple-style Live Text overlay. Sits transparently above the photo, draws
 * faint outlines around every detected line of text, and highlights the
 * tapped line. Coordinate translation from source-image pixels to view
 * pixels is plugged in from outside — the overlay does not know whether
 * the image is rendered by `SubsamplingScaleImageView` or a plain
 * `GestureImageView`.
 *
 * Touch behavior is dead simple by design: any single tap inside a line
 * box selects that line; any tap outside reports a miss to the caller so
 * the host can drop out of Live Text mode.
 */
class LiveTextOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private data class Slot(
        val source: OcrRecognizer.Line,
        val viewBounds: RectF,
    )

    private var slots: List<Slot> = emptyList()
    private var projector: ((Rect) -> RectF?)? = null
    private val selectedLineIds = HashSet<Int>()

    /**
     * Called after a tap is consumed by the overlay. Caller can use this to
     * update an action bar's enabled state etc. Receives the number of
     * currently-selected lines.
     */
    var onSelectionChanged: (selectedCount: Int) -> Unit = {}

    /** Called when the user tapped outside any text line. */
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

    fun setRecognition(lines: List<OcrRecognizer.Line>, projector: (Rect) -> RectF?) {
        this.slots = lines.map { Slot(it, RectF()) }
        this.projector = projector
        selectedLineIds.clear()
        onSelectionChanged(0)
        refreshProjection()
    }

    fun clear() {
        slots = emptyList()
        projector = null
        selectedLineIds.clear()
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
        selectedLineIds.clear()
        for (slot in slots) selectedLineIds.add(slot.source.lineId)
        onSelectionChanged(selectedLineIds.size)
        invalidate()
    }

    fun selectedText(): String {
        if (selectedLineIds.isEmpty()) return ""
        // Preserve original reading order by walking slots, then group by block
        // so newlines slip in where the OCR found a block break.
        val byBlock = LinkedHashMap<Int, MutableList<String>>()
        for (slot in slots) {
            if (slot.source.lineId in selectedLineIds) {
                byBlock.getOrPut(slot.source.blockId) { ArrayList() }
                    .add(slot.source.text)
            }
        }
        return byBlock.values
            .joinToString("\n\n") { it.joinToString("\n") }
    }

    override fun onDraw(canvas: Canvas) {
        if (slots.isEmpty()) return
        // First pass: outline everything so user sees what's tappable.
        for (slot in slots) {
            if (slot.viewBounds.isEmpty) continue
            canvas.drawRoundRect(slot.viewBounds, cornerRadius, cornerRadius, outlinePaint)
        }
        // Second pass: fill + thicker outline on selected lines.
        if (selectedLineIds.isNotEmpty()) {
            for (slot in slots) {
                if (slot.source.lineId !in selectedLineIds) continue
                if (slot.viewBounds.isEmpty) continue
                canvas.drawRoundRect(slot.viewBounds, cornerRadius, cornerRadius, fillPaint)
                canvas.drawRoundRect(slot.viewBounds, cornerRadius, cornerRadius, selectedOutlinePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (slots.isEmpty()) return false
        if (event.actionMasked != MotionEvent.ACTION_UP) {
            // Consume DOWN so the host pager doesn't start a horizontal drag,
            // but only commit selection on UP.
            return event.actionMasked == MotionEvent.ACTION_DOWN
        }
        // Slight padding so finger-fat doesn't miss a small line.
        val pad = dp(6f)
        val x = event.x
        val y = event.y
        val hit = slots.firstOrNull {
            val b = it.viewBounds
            !b.isEmpty &&
                x >= b.left - pad && x <= b.right + pad &&
                y >= b.top - pad && y <= b.bottom + pad
        }
        if (hit == null) {
            onMissTap()
            return true
        }
        // Toggle selection on tap so a second tap on the same line clears it.
        val id = hit.source.lineId
        if (id in selectedLineIds) {
            selectedLineIds.remove(id)
        } else {
            selectedLineIds.add(id)
        }
        onSelectionChanged(selectedLineIds.size)
        invalidate()
        return true
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
