package org.fossify.gallery.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Instagram-style segmented progress bar. Each segment represents one
 * slide. The current segment fills horizontally as time progresses, past
 * segments are fully filled, future segments are stubs.
 *
 * The host activity owns the timing — it pushes [currentIndex] and a
 * fractional [currentProgress] in [0..1] each frame.
 */
class StoryProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    var segmentCount: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var currentIndex: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    /** 0..1 fill fraction of the current segment. */
    var currentProgress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val gapPx = dp(3f)
    private val cornerRadius = dp(2f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segmentCount <= 0) return
        val totalGap = gapPx * (segmentCount - 1)
        val segmentWidth = (width - totalGap) / segmentCount.toFloat()
        val h = height.toFloat()
        val rect = RectF()
        for (i in 0 until segmentCount) {
            val left = i * (segmentWidth + gapPx)
            rect.set(left, 0f, left + segmentWidth, h)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, trackPaint)
            val fillFraction = when {
                i < currentIndex -> 1f
                i == currentIndex -> currentProgress
                else -> 0f
            }
            if (fillFraction > 0f) {
                rect.set(left, 0f, left + segmentWidth * fillFraction, h)
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
            }
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
