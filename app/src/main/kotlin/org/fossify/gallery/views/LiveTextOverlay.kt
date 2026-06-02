package org.fossify.gallery.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import org.fossify.gallery.helpers.OcrRecognizer

/**
 * Apple-style Live Text overlay — draw-only. Tap routing happens in
 * `PhotoFragment` via a [android.view.GestureDetector] attached to the
 * underlying image view, which calls back into [hitTestAndSelect].
 *
 * Why not handle touches in the overlay itself? Because the overlay
 * sits on top of the photo and Android touch dispatch is winner-takes-
 * all per gesture stream — if the overlay captures `ACTION_DOWN` it
 * also "owns" any subsequent `ACTION_POINTER_DOWN` (= second finger
 * for pinch-zoom). That killed zoom for any pinch that started on a
 * word. With the overlay non-clickable, all touches naturally reach
 * the image view; pan and pinch work normally. The host runs a
 * Choreographer loop while the overlay is visible so the word rects
 * follow the photo as it scales.
 *
 * Selection model — repeated taps on the SAME word within ~400 ms:
 *   - 1 tap → that word
 *   - 2 taps → expand to the whole line
 *   - 3 taps → expand to the whole block
 * Tapping a DIFFERENT word adds it to the selection. Tapping an
 * already-selected word AFTER the window expires toggles it off.
 */
class LiveTextOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    init {
        isClickable = false
        isFocusable = false
    }

    private data class Slot(
        val source: OcrRecognizer.Word,
        val viewBounds: RectF,
    )

    private var slots: List<Slot> = emptyList()
    private var projector: ((Rect) -> RectF?)? = null
    private val selectedWordKeys = HashSet<Int>()

    var onSelectionChanged: (selectedCount: Int) -> Unit = {}

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0x66FFFFFF.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#66FFD740")
    }
    private val selectedOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.parseColor("#FFFFD740")
    }

    private val cornerRadius = dp(4f)

    // Multi-tap state.
    private var lastTapWordIdx = -1
    private var lastTapTime = 0L
    private var tapCount = 0

    private val multiTapWindowMs = 400L

    // Choreographer frame-callback: while visible, keep the rects in sync
    // with whatever pan/zoom the underlying image view is doing.
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isAttachedToWindow || visibility != VISIBLE) return
            refreshProjection()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    fun setRecognition(words: List<OcrRecognizer.Word>, projector: (Rect) -> RectF?) {
        this.slots = words.map { Slot(it, RectF()) }
        this.projector = projector
        selectedWordKeys.clear()
        lastTapWordIdx = -1
        tapCount = 0
        onSelectionChanged(0)
        refreshProjection()
    }

    fun clear() {
        slots = emptyList()
        projector = null
        selectedWordKeys.clear()
        lastTapWordIdx = -1
        tapCount = 0
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
        for (slot in slots) {
            if (slot.viewBounds.isEmpty) continue
            canvas.drawRoundRect(slot.viewBounds, cornerRadius, cornerRadius, outlinePaint)
        }
        if (selectedWordKeys.isEmpty()) return
        for ((idx, slot) in slots.withIndex()) {
            if (idx !in selectedWordKeys) continue
            if (slot.viewBounds.isEmpty) continue
            canvas.drawRoundRect(slot.viewBounds, cornerRadius, cornerRadius, fillPaint)
            canvas.drawRoundRect(slot.viewBounds, cornerRadius, cornerRadius, selectedOutlinePaint)
        }
    }

    /**
     * Called by the host fragment when the user single-taps the underlying
     * image view while Live Text is active. Returns true if a word was
     * hit (caller should suppress the chrome-toggle click), false if the
     * tap missed every word.
     */
    fun hitTestAndSelect(x: Float, y: Float): Boolean {
        if (slots.isEmpty()) return false
        val idx = findWordAt(x, y)
        if (idx < 0) return false
        applyMultiTap(idx)
        return true
    }

    private fun findWordAt(x: Float, y: Float): Int {
        val pad = dp(6f)
        for (i in slots.indices) {
            val b = slots[i].viewBounds
            if (b.isEmpty) continue
            if (x >= b.left - pad && x <= b.right + pad &&
                y >= b.top - pad && y <= b.bottom + pad
            ) return i
        }
        return -1
    }

    /**
     * Selection model:
     *   - Tap a NEW word → add it to the current selection (prior picks stay).
     *   - Tap the SAME word again within ~400 ms → grow that word into its
     *     whole line; tap it once more → grow into its whole block. Other
     *     prior selections are preserved.
     *   - Tap a previously-selected word AFTER the multi-tap window has
     *     expired → toggle it off (so deselection is still possible).
     */
    private fun applyMultiTap(wordIdx: Int) {
        val now = SystemClock.uptimeMillis()
        val sameWord = wordIdx == lastTapWordIdx &&
            (now - lastTapTime) <= multiTapWindowMs

        if (sameWord) {
            tapCount = (tapCount + 1).coerceAtMost(3)
            val word = slots[wordIdx].source
            when (tapCount) {
                2 -> for ((i, s) in slots.withIndex()) {
                    if (s.source.lineId == word.lineId) selectedWordKeys.add(i)
                }
                3 -> for ((i, s) in slots.withIndex()) {
                    if (s.source.blockId == word.blockId) selectedWordKeys.add(i)
                }
            }
        } else {
            tapCount = 1
            if (wordIdx in selectedWordKeys) {
                // Same word, but the multi-tap window expired — treat as toggle-off.
                selectedWordKeys.remove(wordIdx)
            } else {
                selectedWordKeys.add(wordIdx)
            }
        }

        lastTapWordIdx = wordIdx
        lastTapTime = now
        onSelectionChanged(selectedWordKeys.size)
        invalidate()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
