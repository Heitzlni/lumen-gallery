package org.fossify.gallery.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.gallery.R
import org.fossify.gallery.models.PaintOptions
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The editor's annotation surface — hosts BOTH freehand strokes and text
 * overlays on top of the background image. A single ordered action log
 * lets undo / redo step linearly through every meaningful change.
 *
 * Modes:
 *  - [Mode.DRAW]  paint freehand strokes; long-press a stroke for the
 *                 edit/delete dialog.
 *  - [Mode.TEXT]  tap a placed text to select it (sliders mirror its
 *                 props), drag to move, long-press to edit/delete; the
 *                 selection box exposes a rotation handle above it.
 *  - [Mode.LASSO] drag a closed polygon to multi-select strokes; sliders
 *                 then bulk-edit every selected stroke. Tap empty to
 *                 clear the selection. Long-press a stroke for the
 *                 dialog (applies to the selection if one exists, else
 *                 just to that one stroke).
 *
 * Undo is a linear snapshot history (default cap 50). Redo walks the
 * other direction until the user makes a new commit, which truncates
 * the redo tail. `clearAll()` is itself just a snapshot operation, so
 * undoing it brings back everything that was on the canvas.
 */
class EditorDrawCanvas(context: Context, attrs: AttributeSet) : View(context, attrs) {

    enum class Mode { DRAW, TEXT, LASSO }

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
            var rotationDeg: Float = 0f,
        ) : Action() {
            override fun deepCopy(): Action = copy()
        }
    }

    // --- general state ----------------------------------------------------

    private val mActions = ArrayList<Action>()
    private var backgroundBitmap: Bitmap? = null

    private val mTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val mLongPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var mLongPressRunnable: Runnable? = null
    private var mLongPressTriggered = false

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
    private val mLassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#FFFFD740")
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }
    private val mHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // --- draw (paint) state -----------------------------------------------

    private var mCurX = 0f
    private var mCurY = 0f
    private var mStartX = 0f
    private var mStartY = 0f
    private var mWasMultitouch = false
    private var mPath = Path()
    private var mPaintOptions = PaintOptions()
    private var mPaintMovedBeyondSlop = false
    private var mLongPressedStroke: Action.Stroke? = null

    // --- text state -------------------------------------------------------

    private var mMode: Mode = Mode.DRAW
    private var mPendingTextColor: Int = Color.WHITE
    private var mPendingTextSizePx: Float = 0f
    private var mSelectedText: Action.Text? = null
    private var mDraggingText: Action.Text? = null
    private var mDragOffsetX = 0f
    private var mDragOffsetY = 0f
    private var mDownX = 0f
    private var mDownY = 0f
    private var mDragMovedBeyondSlop = false
    /** True while the rotation handle is being dragged. */
    private var mRotatingText: Action.Text? = null
    private var mRotateBaseAngle = 0f
    private var mRotateInitialDeg = 0f

    // --- lasso state ------------------------------------------------------

    private val mLassoPath = Path()
    private var mLassoActive = false
    private val mSelectedStrokes = HashSet<Action.Stroke>()

    // --- callbacks --------------------------------------------------------

    var onTextSelectionChanged: (selected: Action.Text?) -> Unit = {}
    var onTextLongPress: (text: Action.Text) -> Unit = {}
    var onStrokeLongPress: (stroke: Action.Stroke) -> Unit = {}
    /** Called whenever lasso selection size changes. Host updates UI. */
    var onStrokeSelectionChanged: (count: Int) -> Unit = {}

    // --- history (undo / redo) -------------------------------------------

    private val mHistory = ArrayList<List<Action>>()
    private var mHistoryIndex = -1
    private val mHistoryMax = 50

    init {
        val primary = context.getProperPrimaryColor()
        mPaintOptions = PaintOptions(primary, 40f)
        mPendingTextColor = primary
        mPendingTextSizePx = resources.getDimension(R.dimen.full_brush_size) * 1.5f
        mSelectionPaint.color = primary
        mHandlePaint.color = primary
        mHistory.add(emptyList())
        mHistoryIndex = 0
    }

    // ---------------------------------------------------------------------
    // Mode + selection accessors
    // ---------------------------------------------------------------------

    fun setMode(mode: Mode) {
        mMode = mode
        mDraggingText = null
        mRotatingText = null
        mLassoActive = false
        mLassoPath.reset()
        if (mode != Mode.TEXT && mSelectedText != null) {
            mSelectedText = null
            onTextSelectionChanged(null)
        }
        if (mode != Mode.LASSO && mSelectedStrokes.isNotEmpty()) {
            mSelectedStrokes.clear()
            onStrokeSelectionChanged(0)
        }
        invalidate()
    }

    fun currentMode(): Mode = mMode

    fun selectedText(): Action.Text? = mSelectedText
    fun selectedStrokeCount(): Int = mSelectedStrokes.size
    fun hasStrokeSelection(): Boolean = mSelectedStrokes.isNotEmpty()

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        backgroundBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        for (action in mActions) {
            when (action) {
                is Action.Stroke -> drawStroke(canvas, action)
                is Action.Text -> drawText(canvas, action)
            }
        }

        // In-progress freehand stroke (paint sub-mode only).
        mStrokePaint.style = Paint.Style.STROKE
        mStrokePaint.color = mPaintOptions.color
        mStrokePaint.strokeWidth = mPaintOptions.strokeWidth
        canvas.drawPath(mPath, mStrokePaint)

        // Lasso path while user is dragging it.
        if (mMode == Mode.LASSO && mLassoActive) {
            canvas.drawPath(mLassoPath, mLassoPaint)
        }

        // Selection indicators.
        mSelectedText?.let { drawTextSelection(canvas, it) }
        if (mSelectedStrokes.isNotEmpty()) {
            for (stroke in mSelectedStrokes) drawStrokeSelection(canvas, stroke)
        }

        canvas.restore()
    }

    private fun drawStroke(canvas: Canvas, stroke: Action.Stroke) {
        mStrokePaint.style = Paint.Style.STROKE
        mStrokePaint.color = stroke.paint.color
        mStrokePaint.strokeWidth = stroke.paint.strokeWidth
        canvas.drawPath(stroke.path, mStrokePaint)
    }

    private fun drawText(canvas: Canvas, action: Action.Text) {
        mTextPaint.textSize = action.sizePx
        val w = mTextPaint.measureText(action.text)
        val metrics = mTextPaint.fontMetrics
        val cx = action.x + w / 2f
        val cy = action.y + (metrics.ascent + metrics.descent) / 2f

        canvas.save()
        if (action.rotationDeg != 0f) canvas.rotate(action.rotationDeg, cx, cy)
        // Contrast outline first so the text is legible on any background.
        mTextPaint.style = Paint.Style.STROKE
        mTextPaint.strokeWidth = (action.sizePx * 0.07f).coerceAtLeast(2f)
        mTextPaint.strokeJoin = Paint.Join.ROUND
        mTextPaint.color = contrastingOutline(action.color)
        canvas.drawText(action.text, action.x, action.y, mTextPaint)
        // Fill on top.
        mTextPaint.style = Paint.Style.FILL
        mTextPaint.color = action.color
        canvas.drawText(action.text, action.x, action.y, mTextPaint)
        canvas.restore()
    }

    private fun drawTextSelection(canvas: Canvas, sel: Action.Text) {
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
        val cx = sel.x + w / 2f
        val cy = sel.y + (metrics.ascent + metrics.descent) / 2f
        canvas.save()
        if (sel.rotationDeg != 0f) canvas.rotate(sel.rotationDeg, cx, cy)
        canvas.drawRoundRect(rect, 8f, 8f, mSelectionPaint)
        // Rotation handle: small filled circle above the top-centre of the
        // (un-rotated) bounding box, plus a connector line so the user knows
        // what it does. After canvas.rotate, drawing in local coords is fine.
        val handleCenterX = (rect.left + rect.right) / 2f
        val handleCenterY = rect.top - ROTATE_HANDLE_GAP
        canvas.drawLine(handleCenterX, rect.top, handleCenterX, handleCenterY, mSelectionPaint)
        canvas.drawCircle(handleCenterX, handleCenterY, ROTATE_HANDLE_RADIUS, mHandlePaint)
        canvas.restore()
    }

    private fun drawStrokeSelection(canvas: Canvas, stroke: Action.Stroke) {
        val b = RectF()
        stroke.path.computeBounds(b, true)
        val pad = stroke.paint.strokeWidth / 2f + 6f
        b.inset(-pad, -pad)
        canvas.drawRoundRect(b, 8f, 8f, mSelectionPaint)
    }

    // ---------------------------------------------------------------------
    // Touch dispatch
    // ---------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (mMode) {
            Mode.DRAW -> handleDrawTouch(event)
            Mode.TEXT -> handleTextTouch(event)
            Mode.LASSO -> handleLassoTouch(event)
        }
        invalidate()
        return true
    }

    // ---------- DRAW (paint) mode ----------------------------------------

    private fun handleDrawTouch(event: MotionEvent) {
        val x = event.x
        val y = event.y
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                mWasMultitouch = false
                mStartX = x
                mStartY = y
                mDownX = x
                mDownY = y
                mPaintMovedBeyondSlop = false
                mLongPressTriggered = false
                mPath.reset()
                mPath.moveTo(x, y)
                mCurX = x
                mCurY = y

                // Arm long-press if the finger landed on an existing stroke.
                val hit = findStrokeAt(x, y)
                if (hit != null) {
                    mLongPressedStroke = hit
                    cancelPendingLongPress()
                    val runnable = Runnable {
                        if (!mPaintMovedBeyondSlop && mLongPressedStroke === hit) {
                            mLongPressTriggered = true
                            mPath.reset()
                            onStrokeLongPress(hit)
                        }
                    }
                    mLongPressRunnable = runnable
                    mLongPressHandler.postDelayed(runnable, LONG_PRESS_MS)
                } else {
                    mLongPressedStroke = null
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1 || mWasMultitouch || mLongPressTriggered) return
                if (!mPaintMovedBeyondSlop) {
                    val dx = x - mDownX
                    val dy = y - mDownY
                    if (dx * dx + dy * dy > mTouchSlop * mTouchSlop) {
                        mPaintMovedBeyondSlop = true
                        cancelPendingLongPress()
                    }
                }
                mPath.quadTo(mCurX, mCurY, (x + mCurX) / 2, (y + mCurY) / 2)
                mCurX = x
                mCurY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPendingLongPress()
                val long = mLongPressTriggered
                mLongPressTriggered = false
                mLongPressedStroke = null
                if (long) {
                    // Long-press handled — no stroke commit.
                    mPath = Path()
                    return
                }
                if (!mWasMultitouch) {
                    mPath.lineTo(mCurX, mCurY)
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
            MotionEvent.ACTION_POINTER_DOWN -> {
                mWasMultitouch = true
                cancelPendingLongPress()
            }
        }
    }

    // ---------- TEXT mode -------------------------------------------------

    private fun handleTextTouch(event: MotionEvent) {
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                mDownX = event.x
                mDownY = event.y
                mDragMovedBeyondSlop = false
                mLongPressTriggered = false

                // First: rotation handle on the currently-selected text?
                val sel = mSelectedText
                if (sel != null && isOnRotationHandle(sel, event.x, event.y)) {
                    mRotatingText = sel
                    val (cx, cy) = textCenter(sel)
                    mRotateBaseAngle = Math.toDegrees(
                        atan2((event.y - cy).toDouble(), (event.x - cx).toDouble())
                    ).toFloat()
                    mRotateInitialDeg = sel.rotationDeg
                    return
                }

                val hit = findTextAt(event.x, event.y)
                if (hit != null) {
                    mDraggingText = hit
                    mDragOffsetX = event.x - hit.x
                    mDragOffsetY = event.y - hit.y
                    if (mSelectedText !== hit) {
                        mSelectedText = hit
                        onTextSelectionChanged(hit)
                    }
                    cancelPendingLongPress()
                    val target = hit
                    val runnable = Runnable {
                        if (!mDragMovedBeyondSlop && mDraggingText === target) {
                            mLongPressTriggered = true
                            onTextLongPress(target)
                        }
                    }
                    mLongPressRunnable = runnable
                    mLongPressHandler.postDelayed(runnable, LONG_PRESS_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val rotating = mRotatingText
                if (rotating != null) {
                    val (cx, cy) = textCenter(rotating)
                    val angle = Math.toDegrees(
                        atan2((event.y - cy).toDouble(), (event.x - cx).toDouble())
                    ).toFloat()
                    rotating.rotationDeg = (mRotateInitialDeg + (angle - mRotateBaseAngle))
                        .let { ((it % 360f) + 360f) % 360f }
                    return
                }
                mDraggingText?.let { dragging ->
                    val dx = event.x - mDownX
                    val dy = event.y - mDownY
                    if (!mDragMovedBeyondSlop &&
                        dx * dx + dy * dy > mTouchSlop * mTouchSlop
                    ) {
                        mDragMovedBeyondSlop = true
                        cancelPendingLongPress()
                    }
                    if (mDragMovedBeyondSlop) {
                        dragging.x = event.x - mDragOffsetX
                        dragging.y = event.y - mDragOffsetY
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPendingLongPress()
                val rotated = mRotatingText
                val moved = mDragMovedBeyondSlop
                val dragged = mDraggingText
                val longPressed = mLongPressTriggered
                mDraggingText = null
                mRotatingText = null
                mDragMovedBeyondSlop = false
                mLongPressTriggered = false

                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (dragged == null && rotated == null) {
                        if (mSelectedText != null) {
                            mSelectedText = null
                            onTextSelectionChanged(null)
                        }
                    }
                }
                if ((moved && dragged != null && !longPressed) || rotated != null) {
                    pushHistory()
                }
            }
        }
    }

    // ---------- LASSO mode -----------------------------------------------

    private fun handleLassoTouch(event: MotionEvent) {
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                mDownX = event.x
                mDownY = event.y
                mLongPressTriggered = false

                val hit = findStrokeAt(event.x, event.y)
                if (hit != null) {
                    cancelPendingLongPress()
                    val runnable = Runnable {
                        mLongPressTriggered = true
                        onStrokeLongPress(hit)
                    }
                    mLongPressRunnable = runnable
                    mLongPressHandler.postDelayed(runnable, LONG_PRESS_MS)
                }

                mLassoActive = true
                mLassoPath.reset()
                mLassoPath.moveTo(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (mLassoActive && !mLongPressTriggered) {
                    mLassoPath.lineTo(event.x, event.y)
                }
                if (!mLongPressTriggered) {
                    val dx = event.x - mDownX
                    val dy = event.y - mDownY
                    if (dx * dx + dy * dy > mTouchSlop * mTouchSlop) {
                        cancelPendingLongPress()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPendingLongPress()
                val long = mLongPressTriggered
                mLongPressTriggered = false
                if (long) {
                    mLassoPath.reset()
                    mLassoActive = false
                    return
                }
                if (mLassoActive) {
                    mLassoPath.close()
                    val moved = (event.x - mDownX).let { it * it } +
                        (event.y - mDownY).let { it * it } >
                        mTouchSlop * mTouchSlop
                    if (moved) {
                        selectStrokesInside(mLassoPath)
                    } else {
                        // Tap = clear selection.
                        if (mSelectedStrokes.isNotEmpty()) {
                            mSelectedStrokes.clear()
                            onStrokeSelectionChanged(0)
                        }
                    }
                    mLassoPath.reset()
                    mLassoActive = false
                }
            }
        }
    }

    private fun selectStrokesInside(lasso: Path) {
        val clip = Region(0, 0, width, height)
        val region = Region()
        region.setPath(lasso, clip)
        mSelectedStrokes.clear()
        val b = RectF()
        for (action in mActions) {
            if (action is Action.Stroke) {
                action.path.computeBounds(b, true)
                val cx = b.centerX().toInt()
                val cy = b.centerY().toInt()
                if (region.contains(cx, cy)) {
                    mSelectedStrokes.add(action)
                }
            }
        }
        onStrokeSelectionChanged(mSelectedStrokes.size)
    }

    // ---------------------------------------------------------------------
    // Hit-tests
    // ---------------------------------------------------------------------

    private fun findStrokeAt(x: Float, y: Float): Action.Stroke? {
        val b = RectF()
        val tolerance = 18f
        for (i in mActions.indices.reversed()) {
            val a = mActions[i] as? Action.Stroke ?: continue
            a.path.computeBounds(b, true)
            val pad = a.paint.strokeWidth / 2f + tolerance
            if (x >= b.left - pad && x <= b.right + pad &&
                y >= b.top - pad && y <= b.bottom + pad
            ) return a
        }
        return null
    }

    private fun findTextAt(x: Float, y: Float): Action.Text? {
        for (i in mActions.indices.reversed()) {
            val a = mActions[i] as? Action.Text ?: continue
            mTextPaint.textSize = a.sizePx
            val w = mTextPaint.measureText(a.text)
            val metrics = mTextPaint.fontMetrics
            val cx = a.x + w / 2f
            val cy = a.y + (metrics.ascent + metrics.descent) / 2f
            // Inverse-rotate the touch point into the text's local frame.
            val (lx, ly) = if (a.rotationDeg != 0f) inverseRotate(x, y, cx, cy, a.rotationDeg)
            else x to y
            val top = a.y + metrics.ascent
            val bottom = a.y + metrics.descent
            val pad = a.sizePx * 0.25f
            if (lx >= a.x - pad && lx <= a.x + w + pad &&
                ly >= top - pad && ly <= bottom + pad
            ) return a
        }
        return null
    }

    private fun isOnRotationHandle(sel: Action.Text, x: Float, y: Float): Boolean {
        mTextPaint.textSize = sel.sizePx
        val w = mTextPaint.measureText(sel.text)
        val metrics = mTextPaint.fontMetrics
        val pad = sel.sizePx * 0.15f
        val rectTop = sel.y + metrics.ascent - pad
        val handleLocalX = sel.x + w / 2f
        val handleLocalY = rectTop - ROTATE_HANDLE_GAP
        val (cx, cy) = textCenter(sel)
        // Rotate the handle's local coords into world coords.
        val (hx, hy) = if (sel.rotationDeg != 0f)
            forwardRotate(handleLocalX, handleLocalY, cx, cy, sel.rotationDeg)
        else handleLocalX to handleLocalY
        val dx = x - hx
        val dy = y - hy
        return dx * dx + dy * dy <= ROTATE_HIT_RADIUS * ROTATE_HIT_RADIUS
    }

    private fun textCenter(t: Action.Text): Pair<Float, Float> {
        mTextPaint.textSize = t.sizePx
        val w = mTextPaint.measureText(t.text)
        val metrics = mTextPaint.fontMetrics
        return (t.x + w / 2f) to (t.y + (metrics.ascent + metrics.descent) / 2f)
    }

    private fun inverseRotate(
        x: Float, y: Float, cx: Float, cy: Float, deg: Float,
    ): Pair<Float, Float> {
        val rad = Math.toRadians(-deg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val dx = x - cx
        val dy = y - cy
        return (cx + dx * c - dy * s) to (cy + dx * s + dy * c)
    }

    private fun forwardRotate(
        x: Float, y: Float, cx: Float, cy: Float, deg: Float,
    ): Pair<Float, Float> {
        val rad = Math.toRadians(deg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val dx = x - cx
        val dy = y - cy
        return (cx + dx * c - dy * s) to (cy + dx * s + dy * c)
    }

    private fun cancelPendingLongPress() {
        mLongPressRunnable?.let { mLongPressHandler.removeCallbacks(it) }
        mLongPressRunnable = null
    }

    // ---------------------------------------------------------------------
    // Property setters / mutations
    // ---------------------------------------------------------------------

    fun updateColor(newColor: Int) {
        mPaintOptions.color = newColor
        if (mSelectedStrokes.isNotEmpty()) {
            for (s in mSelectedStrokes) s.paint.color = newColor
            invalidate()
        }
    }

    fun updateBrushSize(newBrushSize: Int) {
        val width = resources.getDimension(R.dimen.full_brush_size) * (newBrushSize / 100f)
        mPaintOptions.strokeWidth = width
        if (mSelectedStrokes.isNotEmpty()) {
            for (s in mSelectedStrokes) s.paint.strokeWidth = width
            invalidate()
        }
    }

    fun updateTextColor(newColor: Int) {
        mPendingTextColor = newColor
        mSelectionPaint.color = newColor
        mHandlePaint.color = newColor
    }

    fun updateTextSize(percent: Int) {
        mPendingTextSizePx = sizePxFromPercent(percent)
    }

    fun applyColorToSelected(color: Int) {
        mSelectedText?.let {
            it.color = color
            mSelectionPaint.color = color
            mHandlePaint.color = color
            invalidate()
        }
    }

    fun applySizePercentToSelected(percent: Int) {
        mSelectedText?.let {
            it.sizePx = sizePxFromPercent(percent)
            invalidate()
        }
    }

    fun updateTextContent(annotation: Action.Text, newContent: String) {
        if (newContent.isBlank()) return
        if (annotation.text == newContent) return
        if (annotation !in mActions) return
        annotation.text = newContent
        invalidate()
        pushHistory()
    }

    fun deleteText(annotation: Action.Text) {
        val removed = mActions.remove(annotation)
        if (!removed) return
        if (mSelectedText === annotation) {
            mSelectedText = null
            onTextSelectionChanged(null)
        }
        if (mDraggingText === annotation) mDraggingText = null
        if (mRotatingText === annotation) mRotatingText = null
        invalidate()
        pushHistory()
    }

    /** Recolour a specific stroke (long-press dialog). */
    fun recolorStroke(stroke: Action.Stroke, color: Int) {
        if (stroke !in mActions) return
        stroke.paint.color = color
        invalidate()
        pushHistory()
    }

    /** Resize a specific stroke. */
    fun resizeStroke(stroke: Action.Stroke, percent: Int) {
        if (stroke !in mActions) return
        stroke.paint.strokeWidth =
            resources.getDimension(R.dimen.full_brush_size) * (percent / 100f)
        invalidate()
        pushHistory()
    }

    fun deleteStroke(stroke: Action.Stroke) {
        if (mActions.remove(stroke)) {
            mSelectedStrokes.remove(stroke)
            onStrokeSelectionChanged(mSelectedStrokes.size)
            invalidate()
            pushHistory()
        }
    }

    /** Apply [color] to every stroke in the lasso selection. */
    fun recolorSelectedStrokes(color: Int) {
        if (mSelectedStrokes.isEmpty()) return
        for (s in mSelectedStrokes) s.paint.color = color
        invalidate()
        pushHistory()
    }

    /** Apply [percent]-based stroke width to every stroke in the lasso selection. */
    fun resizeSelectedStrokes(percent: Int) {
        if (mSelectedStrokes.isEmpty()) return
        val width = resources.getDimension(R.dimen.full_brush_size) * (percent / 100f)
        for (s in mSelectedStrokes) s.paint.strokeWidth = width
        invalidate()
        pushHistory()
    }

    fun deleteSelectedStrokes() {
        if (mSelectedStrokes.isEmpty()) return
        mActions.removeAll(mSelectedStrokes)
        mSelectedStrokes.clear()
        onStrokeSelectionChanged(0)
        invalidate()
        pushHistory()
    }

    fun commitChange() {
        pushHistory()
    }

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
        val savedSel = mSelectedText
        val savedStrokeSel = HashSet(mSelectedStrokes)
        mSelectedText = null
        mSelectedStrokes.clear()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        draw(canvas)
        mSelectedText = savedSel
        mSelectedStrokes.addAll(savedStrokeSel)
        return bitmap
    }

    fun canUndo(): Boolean = mHistoryIndex > 0
    fun canRedo(): Boolean = mHistoryIndex < mHistory.size - 1

    fun undo() {
        if (!canUndo()) return
        mHistoryIndex--
        restoreFromHistory()
    }

    fun redo() {
        if (!canRedo()) return
        mHistoryIndex++
        restoreFromHistory()
    }

    fun clearAll() {
        if (mActions.isEmpty()) return
        mActions.clear()
        mSelectedText = null
        mDraggingText = null
        mRotatingText = null
        mSelectedStrokes.clear()
        mLassoActive = false
        mLassoPath.reset()
        onTextSelectionChanged(null)
        onStrokeSelectionChanged(0)
        pushHistory()
        invalidate()
    }

    private fun restoreFromHistory() {
        mActions.clear()
        mActions.addAll(mHistory[mHistoryIndex].map { it.deepCopy() })
        mDraggingText = null
        mRotatingText = null
        if (mSelectedText != null) {
            mSelectedText = null
            onTextSelectionChanged(null)
        }
        if (mSelectedStrokes.isNotEmpty()) {
            mSelectedStrokes.clear()
            onStrokeSelectionChanged(0)
        }
        invalidate()
    }

    private fun pushHistory() {
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

    private fun sizePxFromPercent(percent: Int): Float {
        val base = resources.getDimension(R.dimen.full_brush_size)
        val factor = 0.5f + (percent.coerceIn(0, 100) / 100f) * 3.5f
        return base * factor
    }

    fun percentFromSizePx(sizePx: Float): Int {
        val base = resources.getDimension(R.dimen.full_brush_size)
        val factor = sizePx / base
        val percent = ((factor - 0.5f) / 3.5f) * 100f
        return percent.coerceIn(0f, 100f).toInt()
    }

    /** Convert a stroke width in pixels back into the 0..100 percent the
     *  size slider uses. Inverse of the formula in [updateBrushSize]. */
    fun strokePercentFromWidth(widthPx: Float): Int {
        val base = resources.getDimension(R.dimen.full_brush_size)
        if (base <= 0f) return 50
        return ((widthPx / base) * 100f).coerceIn(0f, 100f).toInt()
    }

    private fun contrastingOutline(textColor: Int): Int {
        val r = Color.red(textColor)
        val g = Color.green(textColor)
        val b = Color.blue(textColor)
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        return if (luminance > 127f) Color.BLACK else Color.WHITE
    }

    companion object {
        private const val LONG_PRESS_MS = 500L
        private const val ROTATE_HANDLE_GAP = 48f
        private const val ROTATE_HANDLE_RADIUS = 16f
        private const val ROTATE_HIT_RADIUS = 56f
    }
}
