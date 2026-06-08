package org.fossify.gallery.activities

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityMemoriesBinding
import org.fossify.gallery.extensions.config

/**
 * Instagram-/WhatsApp-style "story" memory player. Each photo gets a
 * segment in the top progress bar; the active segment fills smoothly
 * as the photo is shown. Tap the left third / right third to jump,
 * tap the centre to pause/resume, tap-and-hold to pause + reveal full
 * chrome. Background soundtrack plays from the user's chosen URI if
 * one is set in Settings.
 */
class MemoriesActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityMemoriesBinding::inflate)

    private lateinit var photos: List<String>
    private var subtitleText: String = ""
    private var titleText: String = ""
    private var mediaPlayer: MediaPlayer? = null
    private var muted = false
    private var paused = false
    private var index = 0
    private var showingA = true
    private var photoStartedAt = 0L
    private var elapsedBeforePause = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable: Runnable = object : Runnable {
        override fun run() {
            if (paused || photos.isEmpty()) return
            val elapsed = SystemClock.uptimeMillis() - photoStartedAt + elapsedBeforePause
            val progress = elapsed.toFloat() / PHOTO_DURATION_MS
            if (progress >= 1f) {
                advance(forward = true)
            } else {
                binding.memoriesProgress.currentProgress = progress
                handler.postDelayed(this, 16L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        setContentView(binding.root)

        photos = intent.getStringArrayExtra(EXTRA_PHOTOS)?.toList().orEmpty()
        subtitleText = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        titleText = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (photos.isEmpty()) {
            toast(R.string.memories_empty)
            finish()
            return
        }
        if (photos.size > MAX_STORY_PHOTOS) {
            photos = photos.take(MAX_STORY_PHOTOS)
        }

        binding.memoriesSubtitle.text = subtitleText
        binding.memoriesTitle.text = titleText
        binding.memoriesProgress.segmentCount = photos.size
        binding.memoriesClose.setOnClickListener { finish() }
        binding.memoriesMute.setOnClickListener { toggleMute() }
        binding.memoriesRoot.setOnTouchListener(touchListener)

        startSoundtrack()
        showAt(0, animate = false)
        beginTicking()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {
            }
        }
        mediaPlayer = null
    }

    private val touchListener = object : View.OnTouchListener {
        private var downTime = 0L
        private var downX = 0f
        private var downY = 0f
        private var heldRunnable: Runnable? = null
        private val touchSlop = android.view.ViewConfiguration.get(this@MemoriesActivity).scaledTouchSlop.toFloat()
        private val longPressMs = 300L

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = SystemClock.uptimeMillis()
                    downX = event.x
                    downY = event.y
                    // Long-press style: if user holds for >300ms without
                    // releasing or moving, pause auto-advance.
                    heldRunnable = Runnable { pauseAdvance() }
                    handler.postDelayed(heldRunnable!!, longPressMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        heldRunnable?.let { handler.removeCallbacks(it) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    heldRunnable?.let { handler.removeCallbacks(it) }
                    val held = paused
                    resumeAdvance()
                    if (held) return true
                    val w = v.width
                    when {
                        event.x < w * 0.33f -> advance(forward = false)
                        event.x > w * 0.66f -> advance(forward = true)
                        else -> { /* central tap = nothing — held already resumed */ }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    heldRunnable?.let { handler.removeCallbacks(it) }
                    resumeAdvance()
                }
            }
            return true
        }
    }

    private fun showAt(idx: Int, animate: Boolean) {
        if (photos.isEmpty()) return
        val path = photos[idx % photos.size]
        val target: ImageView
        val previous: ImageView
        if (showingA) {
            target = binding.memoriesImageB
            previous = binding.memoriesImageA
        } else {
            target = binding.memoriesImageA
            previous = binding.memoriesImageB
        }
        val req = Glide.with(this)
            .load(path)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .apply(RequestOptions().centerInside())
        if (animate) req.transition(DrawableTransitionOptions.withCrossFade(CROSSFADE_MS))
        req.into(target)
        target.animate().alpha(1f).setDuration(CROSSFADE_MS.toLong()).start()
        previous.animate().alpha(0f).setDuration(CROSSFADE_MS.toLong()).start()
        showingA = !showingA
        binding.memoriesProgress.currentIndex = idx
        binding.memoriesProgress.currentProgress = 0f
    }

    private fun advance(forward: Boolean) {
        if (photos.isEmpty()) return
        val next = if (forward) index + 1 else index - 1
        if (next < 0) {
            // At start; just reset current photo's timer.
            beginTicking()
            return
        }
        if (next >= photos.size) {
            // End of story — close.
            finish()
            return
        }
        index = next
        showAt(index, animate = true)
        beginTicking()
    }

    private fun beginTicking() {
        handler.removeCallbacks(tickRunnable)
        elapsedBeforePause = 0L
        photoStartedAt = SystemClock.uptimeMillis()
        paused = false
        handler.postDelayed(tickRunnable, 16L)
    }

    private fun pauseAdvance() {
        if (paused) return
        paused = true
        elapsedBeforePause += SystemClock.uptimeMillis() - photoStartedAt
        handler.removeCallbacks(tickRunnable)
        mediaPlayer?.let { if (it.isPlaying) it.pause() }
    }

    private fun resumeAdvance() {
        if (!paused) return
        paused = false
        photoStartedAt = SystemClock.uptimeMillis()
        handler.postDelayed(tickRunnable, 16L)
        mediaPlayer?.let { if (!it.isPlaying && !muted) it.start() }
    }

    private fun startSoundtrack() {
        val uriStr = config.memoriesSoundtrackUri
        if (uriStr.isEmpty()) return
        try {
            val uri = Uri.parse(uriStr)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MemoriesActivity, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            mediaPlayer = null
        }
    }

    private fun toggleMute() {
        muted = !muted
        mediaPlayer?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
        binding.memoriesMute.setImageResource(
            if (muted) android.R.drawable.ic_lock_silent_mode
            else android.R.drawable.ic_lock_silent_mode_off
        )
    }

    companion object {
        const val EXTRA_PHOTOS = "memories_photos"
        const val EXTRA_TITLE = "memories_title"
        const val EXTRA_SUBTITLE = "memories_subtitle"
        private const val PHOTO_DURATION_MS = 3500L
        private const val CROSSFADE_MS = 500
        private const val MAX_STORY_PHOTOS = 24
    }
}
