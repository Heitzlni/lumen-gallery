package org.fossify.gallery.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.gallery.R
import org.fossify.gallery.models.PaintOptions

/**
 * The editor's annotation surface — hosts BOTH freehand strokes and
 * text overlays on top of the background image. A single ordered action
 * log lets undo pop the most recent thing the user added regardless of
 * which mode they were in.
 *
 * Mode is set externally via [setMode]. In DRAW mode touches paint
 * strokes the way they always have; in TEXT mode touches pick up the
 * nearest annotation and drag it around (text is added via [addText],
 * not by tapping the canvas).
 */
class EditorDrawCanvas(context: Context, attrs: AttributeSet) : View(context, attrs) {

    enum class Mode { DRAW, TEXT }

    sealed class Action {
        data class Stroke(val path: Path, val paint: PaintOptions) : Action()
        data class Text(
            var text: String,
            var x: Float,
            var y: Float,
            var color: Int,
            var sizePx: Float,
        ) : Action()
    }

    private var mCurX = 0f
    private var mCurY = 0f
    private var mStartX = 0f
    private var mStartY = 0f
    private var mWasMultitouch = false

    /** Ordered log of every annotation the user has placed; drawn back-to-front. */
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

    private var mPath = Path()
    private var mPaintOptions = PaintOptions()

    private var backgroundBitmap: Bitmap? = null

    private var mMode: Mode = Mode.DRAW
    private var mPendingTextColor: Int = Color.WHITE
    private var mPendingTextSizePx: Float = 0f

    private var mDraggingText: Action.Text? = null
    private var mDragOffsetX = 0f
    private var mDragOffsetY = 0f

    init {
        val primary = context.getProperPrimaryColor()
        mPaintOptions = PaintOptions(primary, 40f)
        mPendingTextColor = primary
        mPendingTextSizePx = resources.getDimension(R.dimen.full_brush_size) * 1.5f
    }

    fun setMode(mode: Mode) {
        mMode = mode
        mDraggingText = null
        invalidate()
    }

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
            }
            MotionEvent.ACTION_POINTER_DOWN -> mWasMultitouch = true
        }
    }

    private fun handleTextTouch(event: MotionEvent) {
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                val hit = findTextAt(event.x, event.y)
                if (hit != null) {
                    mDraggingText = hit
                    mDragOffsetX = event.x - hit.x
                    mDragOffsetY = event.y - hit.y
                }
            }
            MotionEvent.ACTION_MOVE -> {
                mDraggingText?.let {
                    it.x = event.x - mDragOffsetX
                    it.y = event.y - mDragOffsetY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mDraggingText = null
            }
        }
    }

    /** Walk back-to-front so the topmost annotation wins. */
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

    fun updateTextColor(newColor: Int) {
        mPendingTextColor = newColor
    }

    /** [percent] is 0..100 → maps to ~0.5x .. 4x of the full-brush-size resource. */
    fun updateTextSize(percent: Int) {
        val base = resources.getDimension(R.dimen.full_brush_size)
        val factor = 0.5f + (percent.coerceIn(0, 100) / 100f) * 3.5f
        mPendingTextSizePx = base * factor
    }

    /** Place a new text annotation at the centre of the canvas. */
    fun addText(text: String) {
        if (text.isBlank() || width == 0 || height == 0) return
        mActions.add(
            Action.Text(
                text = text,
                x = width / 2f - 50f,
                y = height / 2f,
                color = mPendingTextColor,
                sizePx = mPendingTextSizePx,
            )
        )
        invalidate()
    }

    fun updateBackgroundBitmap(bitmap: Bitmap) {
        backgroundBitmap = bitmap
        invalidate()
    }

    fun getBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        draw(canvas)
        return bitmap
    }

    fun undo() {
        if (mActions.isEmpty()) return
        mActions.removeAt(mActions.size - 1)
        invalidate()
    }
}
