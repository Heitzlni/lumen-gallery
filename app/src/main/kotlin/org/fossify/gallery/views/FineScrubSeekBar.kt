package org.fossify.gallery.views

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.SeekBar
import org.fossify.commons.views.MySeekBar
import kotlin.math.abs

/**
 * SeekBar with iOS-style variable-speed scrubbing. While dragging, the further
 * the finger moves vertically away from the bar, the smaller the horizontal
 * delta gets — so a tiny portion of a long clip can be scrubbed precisely.
 *
 * Exposes [fineModeListener] so the UI can show the current scrub scale
 * (e.g. "0.5x"). Manually drives the standard [SeekBar.OnSeekBarChangeListener]
 * so existing video-player code (which seeks ExoPlayer on every progress
 * change) keeps working — calls onStartTrackingTouch/onProgressChanged/
 * onStopTrackingTouch ourselves with fromUser=true.
 */
class FineScrubSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet,
    defStyleAttr: Int = android.R.attr.seekBarStyle,
) : MySeekBar(context, attrs, defStyleAttr) {

    private var downX = 0f
    private var downY = 0f
    private var startProgress = 0
    private var tracking = false
    private var currentScale = 1f
    private var listener: SeekBar.OnSeekBarChangeListener? = null

    /** Called when the scrub scale changes (1f = normal, < 1f = fine). */
    var fineModeListener: ((scale: Float) -> Unit)? = null

    override fun setOnSeekBarChangeListener(l: SeekBar.OnSeekBarChangeListener?) {
        listener = l
        super.setOnSeekBarChangeListener(l)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val usable = (width - paddingLeft - paddingRight).coerceAtLeast(1)
                val ratio = ((event.x - paddingLeft) / usable).coerceIn(0f, 1f)
                val snapped = (ratio * max).toInt()
                progress = snapped

                downX = event.x
                downY = event.y
                startProgress = snapped
                tracking = true
                currentScale = 1f
                fineModeListener?.invoke(1f)
                listener?.onStartTrackingTouch(this)
                listener?.onProgressChanged(this, snapped, true)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false

                val absDy = abs(event.y - downY)
                val newScale = when {
                    absDy < 40f -> 1.0f
                    absDy < 120f -> 0.5f
                    absDy < 240f -> 0.2f
                    absDy < 400f -> 0.1f
                    else -> 0.05f
                }
                if (newScale != currentScale) {
                    // Rebase: scale just changed. If we kept startProgress +
                    // downX from the original touch-down, the same rawDx
                    // would re-multiply by the new scale and the bar would
                    // jump to a different position. Reset the reference
                    // point to NOW so subsequent movement uses the new
                    // scale relative to the current position.
                    startProgress = progress
                    downX = event.x
                    currentScale = newScale
                    fineModeListener?.invoke(newScale)
                }

                val rawDx = event.x - downX
                val usable = (width - paddingLeft - paddingRight).coerceAtLeast(1)
                val delta = (rawDx * newScale / usable * max).toInt()
                val newProgress = (startProgress + delta).coerceIn(0, max)

                if (newProgress != progress) {
                    progress = newProgress
                    listener?.onProgressChanged(this, newProgress, true)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tracking) {
                    tracking = false
                    currentScale = 1f
                    fineModeListener?.invoke(1f)
                    listener?.onStopTrackingTouch(this)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }
        return false
    }
}
