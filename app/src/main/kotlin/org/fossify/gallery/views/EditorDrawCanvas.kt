package org.fossify.gallery.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.gallery.R
import org.fossify.gallery.models.PaintOptions

/**
 * The editor's annotation surface — hosts BOTH freehand strokes and
 * text overlays on top of the background image. A single ordered action
 * log lets undo pop the most recent thing the user added regardless of
 * which mode they were in.
 *
 * In TEXT mode the user can tap a placed text to select it; the host
 * activity uses [onTextSelectionChanged] to mirror the size slider and
 * color swatch against the selected annotation, and any subsequent
 * change to those controls mutates the selected text in place.
 *
 * Undo is a linear history of action-list snapshots. Each "commit"
 * (stroke finished, text placed, text dragged, color or size changed
 * via the action bar) pushes a snapshot; [undo] restores the previous
 * one — so the user can step back through edits, not just deletions.
 */
class EditorDrawCanvas(context: Context, attrs: AttributeSet) : View(context, attrs) {

    enum class Mode { DRAW, TEXT }

    sealed class Action {
        abstract fun deepCopy(): Action

        data class Stroke(val path: Path, val paint: PaintOptions) : Action() {
            override fun deepCopy(): Action = Stroke(Path(path), paint.copy())
        }

        data class Text(
            var text: String,
            var x: Float,
            var y: Float,
            var color: Int,
            var sizePx: Float,
        ) : Action() {
            override fun deepCopy(): Action = copy()
        }
    }

    private var mCurX = 0f
    private var mCurY = 0f
    private var mStartX = 0f
    private var mStartY = 0f
    private var mWasMultitouch = false

    private val mActions = ArrayList<Action>()

    private val mStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 40f
        isAntiAlias = true
    }
    private val mTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isSubpixelText = true
    }
    private val mSelectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private var mPath = Path()
    private var mPaintOptions = PaintOptions()

    private var backgroundBitmap: Bitmap? = null

    private var mMode: Mode = Mode.DRAW
    private var mPendingTextColor: Int = Color.WHITE
    private var mPendingTextSizePx: Float = 0f

    private var mDraggingText: Action.Text? = null
    private var mDragOffsetX = 0f
    private var mDragOffsetY = 0f
    private var mDownX = 0f
    private var mDownY = 0f
    private var mDragMovedBeyondSlop = false
    private val mTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var mSelectedText: Action.Text? = null

    /** Notified when a text is selected (or deselected with null). The host
     *  activity uses this to mirror its size slider / colour swatch against
     *  the selected annotation. */
    var onTextSelectionChanged: (selected: Action.Text?) -> Unit = {}

    private val mHistory = ArrayList<List<Action>>()
    private var mHistoryIndex = -1
    private val mHistoryMax = 50

    init {
        val primary = context.getProperPrimaryColor()
        mPaintOptions = PaintOptions(primary, 40f)
        mPendingTextColor = primary
        mPendingTextSizePx = resources.getDimension(R.dimen.full_brush_size) * 1.5f
        mSelectionPaint.color = primary
        // Seed history with the empty starting state.
        mHistory.add(emptyList())
        mHistoryIndex = 0
    }

    fun setMode(mode: Mode) {
        mMode = mode
        mDraggingText = null
        if (mode == Mode.DRAW && mSelectedText != null) {
            mSelectedText = null
            onTextSelectionChanged(null)
        }
        invalidate()
    }

    fun selectedText(): Action.Text? = mSelectedText

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()

        backgroundBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        for (action in mActions) {
            when (action) {
                is Action.Stroke -> {
                    mStrokePaint.color = action.paint.color
                    mStrokePaint.strokeWidth = action.paint.strokeWidth
                    canvas.drawPath(action.path, mStrokePaint)
                }
                is Action.Text -> {
                    mTextPaint.color = action.color
                    mTextPaint.textSize = action.sizePx
                    canvas.drawText(action.text, action.x, action.y, mTextPaint)
                }
            }
        }

        // In-progress stroke (only meaningful in DRAW mode).
        mStrokePaint.color = mPaintOptions.color
        mStrokePaint.strokeWidth = mPaintOptions.strokeWidth
        canvas.drawPath(mPath, mStrokePaint)

        // Selection indicator — dashed bounding rect around the selected text.
        mSelectedText?.let { sel ->
            mTextPaint.textSize = sel.sizePx
            val w = mTextPaint.measureText(sel.text)
            val metrics = mTextPaint.fontMetrics
            val pad = sel.sizePx * 0.15f
            val rect = RectF(
                sel.x - pad,
                sel.y + metrics.ascent - pad,
                sel.x + w + pad,
                sel.y + metrics.descent + pad,
            )
            canvas.drawRoundRect(rect, 8f, 8f, mSelectionPaint)
        }

        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (mMode) {
            Mode.DRAW -> handleStrokeTouch(event)
            Mode.TEXT -> handleTextTouch(event)
        }
        invalidate()
        return true
    }

    private fun handleStrokeTouch(event: MotionEvent) {
        val x = event.x
        val y = event.y
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                mWasMultitouch = false
                mStartX = x
                mStartY = y
                mPath.reset()
                mPath.moveTo(x, y)
                mCurX = x
                mCurY = y
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !mWasMultitouch) {
                    mPath.quadTo(mCurX, mCurY, (x + mCurX) / 2, (y + mCurY) / 2)
                    mCurX = x
                    mCurY = y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!mWasMultitouch) {
                    mPath.lineTo(mCurX, mCurY)
                    // draw a dot on click
                    if (mStartX == mCurX && mStartY == mCurY) {
                        mPath.lineTo(mCurX, mCurY + 2)
                        mPath.lineTo(mCurX + 1, mCurY + 2)
                        mPath.lineTo(mCurX + 1, mCurY)
                    }
                }
                mActions.add(Action.Stroke(mPath, mPaintOptions))
                mPath = Path()
                mPaintOptions = PaintOptions(mPaintOptions.color, mPaintOptions.strokeWidth)
                pushHistory()
            }
            MotionEvent.ACTION_POINTER_DOWN -> mWasMultitouch = true
        }
    }

    private fun handleTextTouch(event: MotionEvent) {
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                mDownX = event.x
                mDownY = event.y
                mDragMovedBeyondSlop = false
                val hit = findTextAt(event.x, event.y)
                if (hit != null) {
                    mDraggingText = hit
                    mDragOffsetX = event.x - hit.x
                    mDragOffsetY = event.y - hit.y
                    if (mSelectedText !== hit) {
                        mSelectedText = hit
                        onTextSelectionChanged(hit)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                mDraggingText?.let { dragging ->
                    val dx = event.x - mDownX
                    val dy = event.y - mDownY
                    if (!mDragMovedBeyondSlop &&
                        dx * dx + dy * dy > mTouchSlop * mTouchSlop
                    ) {
                        mDragMovedBeyondSlop = true
                    }
                    if (mDragMovedBeyondSlop) {
                        dragging.x = event.x - mDragOffsetX
                        dragging.y = event.y - mDragOffsetY
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val moved = mDragMovedBeyondSlop
                val dragged = mDraggingText
                mDraggingText = null
                mDragMovedBeyondSlop = false

                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (dragged == null) {
                        // Tap landed on empty area — deselect.
                        if (mSelectedText != null) {
                            mSelectedText = null
                            onTextSelectionChanged(null)
                        }
                    }
                }
                if (moved && dragged != null) {
                    pushHistory()
                }
            }
        }
    }

    private fun findTextAt(x: Float, y: Float): Action.Text? {
        for (i in mActions.indices.reversed()) {
            val a = mActions[i]
            if (a is Action.Text) {
                mTextPaint.textSize = a.sizePx
                val w = mTextPaint.measureText(a.text)
                val metrics = mTextPaint.fontMetrics
                val top = a.y + metrics.ascent
                val bottom = a.y + metrics.descent
                val pad = a.sizePx * 0.25f
                if (x >= a.x - pad && x <= a.x + w + pad &&
                    y >= top - pad && y <= bottom + pad
                ) return a
            }
        }
        return null
    }

    fun updateColor(newColor: Int) {
        mPaintOptions.color = newColor
    }

    fun updateBrushSize(newBrushSize: Int) {
        mPaintOptions.strokeWidth = resources.getDimension(R.dimen.full_brush_size) * (newBrushSize / 100f)
    }

    /** Set the colour used for the NEXT text annotation. */
    fun updateTextColor(newColor: Int) {
        mPendingTextColor = newColor
        mSelectionPaint.color = newColor
    }

    /** Set the size (0..100 → ~0.5x..4x of full_brush_size) used for the NEXT text. */
    fun updateTextSize(percent: Int) {
        mPendingTextSizePx = sizePxFromPercent(percent)
    }

    /** Apply a colour change to the currently-selected text without committing
     *  to undo history yet. Call [commitChange] when the gesture is finalised
     *  (e.g. after the colour picker dialog confirms). */
    fun applyColorToSelected(color: Int) {
        mSelectedText?.let {
            it.color = color
            mSelectionPaint.color = color
            invalidate()
        }
    }

    /** Apply a size change (in percent 0..100) to the currently-selected text
     *  without committing to undo history. Call [commitChange] when the slider
     *  is released. */
    fun applySizePercentToSelected(percent: Int) {
        mSelectedText?.let {
            it.sizePx = sizePxFromPercent(percent)
            invalidate()
        }
    }

    /** Snapshot the current action list as a new undo step. Use after applying
     *  a slider release or colour pick to a selected text. */
    fun commitChange() {
        pushHistory()
    }

    private fun sizePxFromPercent(percent: Int): Float {
        val base = resources.getDimension(R.dimen.full_brush_size)
        val factor = 0.5f + (percent.coerceIn(0, 100) / 100f) * 3.5f
        return base * factor
    }

    /** Inverse — derive the 0..100 percent that yields [sizePx]. */
    fun percentFromSizePx(sizePx: Float): Int {
        val base = resources.getDimension(R.dimen.full_brush_size)
        val factor = sizePx / base
        val percent = ((factor - 0.5f) / 3.5f) * 100f
        return percent.coerceIn(0f, 100f).toInt()
    }

    /** Place a new text annotation at the centre of the canvas. */
    fun addText(text: String) {
        if (text.isBlank() || width == 0 || height == 0) return
        val annotation = Action.Text(
            text = text,
            x = width / 2f - 50f,
            y = height / 2f,
            color = mPendingTextColor,
            sizePx = mPendingTextSizePx,
        )
        mActions.add(annotation)
        mSelectedText = annotation
        onTextSelectionChanged(annotation)
        pushHistory()
        invalidate()
    }

    fun updateBackgroundBitmap(bitmap: Bitmap) {
        backgroundBitmap = bitmap
        invalidate()
    }

    fun getBitmap(): Bitmap {
        // Suspend the selection indicator while compositing so the dashed
        // box doesn't get baked into the saved image.
        val savedSel = mSelectedText
        mSelectedText = null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        draw(canvas)
        mSelectedText = savedSel
        return bitmap
    }

    fun undo() {
        if (mHistoryIndex <= 0) return
        mHistoryIndex--
        mActions.clear()
        mActions.addAll(mHistory[mHistoryIndex].map { it.deepCopy() })
        mDraggingText = null
        if (mSelectedText != null) {
            mSelectedText = null
            onTextSelectionChanged(null)
        }
        invalidate()
    }

    private fun pushHistory() {
        // Drop any redo branch (we don't support redo yet, but the index
        // arithmetic still expects a clean trailing snapshot).
        while (mHistory.size > mHistoryIndex + 1) {
            mHistory.removeAt(mHistory.size - 1)
        }
        mHistory.add(mActions.map { it.deepCopy() })
        mHistoryIndex = mHistory.size - 1
        if (mHistory.size > mHistoryMax) {
            mHistory.removeAt(0)
            mHistoryIndex--
        }
    }
}
