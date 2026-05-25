package org.fossify.gallery.fragments

import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.fossify.commons.extensions.*
import org.fossify.commons.views.MySeekBar
import org.fossify.commons.views.MyTextView
import org.fossify.gallery.R
import org.fossify.gallery.databinding.FragmentPlaybackSpeedBinding
import org.fossify.gallery.extensions.config
import org.fossify.gallery.helpers.Config
import org.fossify.gallery.interfaces.PlaybackSpeedListener

class PlaybackSpeedFragment : BottomSheetDialogFragment() {
    private val MIN_PLAYBACK_SPEED = 0.25f
    private val MAX_PLAYBACK_SPEED = 3f
    private val MAX_PROGRESS = (MAX_PLAYBACK_SPEED * 100 + MIN_PLAYBACK_SPEED * 100).toInt()
    private val HALF_PROGRESS = MAX_PROGRESS / 2
    private val STEP = 0.05f

    private var seekBar: MySeekBar? = null
    private var listener: PlaybackSpeedListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.CustomBottomSheetDialogTheme)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val config = requireContext().config
        val binding = FragmentPlaybackSpeedBinding.inflate(inflater, container, false)
        val background = ResourcesCompat.getDrawable(resources, org.fossify.commons.R.drawable.bottom_sheet_bg, requireContext().theme)
        (background as LayerDrawable).findDrawableByLayerId(org.fossify.commons.R.id.bottom_sheet_background)
            .applyColorFilter(requireContext().getProperBackgroundColor())

        binding.apply {
            seekBar = playbackSpeedSeekbar
            root.setBackgroundDrawable(background)
            requireContext().updateTextColors(playbackSpeedHolder)
            playbackSpeedSlow.applyColorFilter(requireContext().getProperTextColor())
            playbackSpeedFast.applyColorFilter(requireContext().getProperTextColor())
            playbackSpeedSlow.setOnClickListener { reduceSpeed() }
            playbackSpeedFast.setOnClickListener { increaseSpeed() }
            initSeekbar(playbackSpeedSeekbar, playbackSpeedLabel, config)
            populatePresets(playbackSpeedPresets)
        }

        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
        return binding.root
    }

    private fun initSeekbar(seekbar: MySeekBar, speedLabel: MyTextView, config: Config) {
        val formattedValue = formatPlaybackSpeed(config.playbackSpeed)
        speedLabel.text = "${formattedValue}x"
        seekbar.max = MAX_PROGRESS

        val playbackSpeedProgress = config.playbackSpeedProgress
        if (playbackSpeedProgress == -1) {
            config.playbackSpeedProgress = HALF_PROGRESS
        }
        seekbar.progress = config.playbackSpeedProgress

        var lastUpdatedProgress = config.playbackSpeedProgress
        var lastUpdatedFormattedValue = formattedValue

        seekbar.onSeekBarChangeListener { progress ->
            val playbackSpeed = getPlaybackSpeed(progress)
            if (playbackSpeed.toString() != lastUpdatedFormattedValue) {
                lastUpdatedProgress = progress
                lastUpdatedFormattedValue = playbackSpeed.toString()
                config.playbackSpeed = playbackSpeed
                config.playbackSpeedProgress = progress

                speedLabel.text = "${formatPlaybackSpeed(playbackSpeed)}x"
                listener?.updatePlaybackSpeed(playbackSpeed)
            } else {
                seekbar.progress = lastUpdatedProgress
            }
        }
    }

    private fun getPlaybackSpeed(progress: Int): Float {
        var playbackSpeed = when {
            progress < HALF_PROGRESS -> {
                val lowerProgressPercent = progress / HALF_PROGRESS.toFloat()
                val lowerProgress = (1 - MIN_PLAYBACK_SPEED) * lowerProgressPercent + MIN_PLAYBACK_SPEED
                lowerProgress
            }

            progress > HALF_PROGRESS -> {
                val upperProgressPercent = progress / HALF_PROGRESS.toFloat() - 1
                val upperDiff = MAX_PLAYBACK_SPEED - 1
                upperDiff * upperProgressPercent + 1
            }

            else -> 1f
        }
        playbackSpeed = Math.min(Math.max(playbackSpeed, MIN_PLAYBACK_SPEED), MAX_PLAYBACK_SPEED)
        val stepMultiplier = 1 / STEP
        return Math.round(playbackSpeed * stepMultiplier) / stepMultiplier
    }

    private fun reduceSpeed() {
        var currentProgress = seekBar?.progress ?: return
        val currentSpeed = requireContext().config.playbackSpeed
        while (currentProgress > 0) {
            val newSpeed = getPlaybackSpeed(--currentProgress)
            if (newSpeed != currentSpeed) {
                seekBar!!.progress = currentProgress
                break
            }
        }
    }

    private fun increaseSpeed() {
        var currentProgress = seekBar?.progress ?: return
        val currentSpeed = requireContext().config.playbackSpeed
        while (currentProgress < MAX_PROGRESS) {
            val newSpeed = getPlaybackSpeed(++currentProgress)
            if (newSpeed != currentSpeed) {
                seekBar!!.progress = currentProgress
                break
            }
        }
    }

    private fun formatPlaybackSpeed(value: Float) = String.format("%.2f", value)

    private fun progressForSpeed(speed: Float): Int {
        val s = speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        return when {
            s < 1f -> ((s - MIN_PLAYBACK_SPEED) / (1f - MIN_PLAYBACK_SPEED) * HALF_PROGRESS).toInt()
            s > 1f -> ((s - 1f) / (MAX_PLAYBACK_SPEED - 1f) * HALF_PROGRESS + HALF_PROGRESS).toInt()
            else -> HALF_PROGRESS
        }
    }

    private fun populatePresets(container: android.widget.LinearLayout) {
        val presets = floatArrayOf(
            0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f
        )
        val ctx = container.context
        val textColor = ctx.getProperTextColor()
        val density = ctx.resources.displayMetrics.density
        val hPad = (10 * density).toInt()
        val vPad = (6 * density).toInt()
        val margin = (4 * density).toInt()

        container.removeAllViews()
        presets.forEach { speed ->
            val chip = android.widget.TextView(ctx).apply {
                text = if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
                setTextColor(textColor)
                setPadding(hPad, vPad, hPad, vPad)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 24f * density
                    setStroke((1 * density).toInt(), textColor)
                    setColor(android.graphics.Color.TRANSPARENT)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    seekBar?.progress = progressForSpeed(speed)
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(margin, 0, margin, 0)
            container.addView(chip, lp)
        }
    }

    fun setListener(playbackSpeedListener: PlaybackSpeedListener) {
        listener = playbackSpeedListener
    }
}
