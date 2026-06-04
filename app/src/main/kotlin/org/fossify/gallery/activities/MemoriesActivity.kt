package org.fossify.gallery.activities

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
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
 * Full-screen memory slideshow. Cross-fades between photos every
 * [PHOTO_DURATION_MS] and plays the user's chosen soundtrack on loop in
 * the background (if any has been set in Settings).
 *
 * Touch:
 *   - tap once to pause/resume auto-advance
 *   - swipe left to skip, swipe right to go back
 *   - back button closes
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
    /** Which ImageView currently displays the visible photo. We swap A↔B. */
    private var showingA = true

    private val handler = Handler(Looper.getMainLooper())
    private val advance: Runnable = object : Runnable {
        override fun run() {
            if (!paused && photos.isNotEmpty()) {
                index = (index + 1) % photos.size
                showAt(index, animate = true)
                handler.postDelayed(this, PHOTO_DURATION_MS)
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

        binding.memoriesSubtitle.text = subtitleText
        binding.memoriesTitle.text = titleText
        binding.memoriesClose.setOnClickListener { finish() }
        binding.memoriesMute.setOnClickListener { toggleMute() }

        // Gesture handling on the root: single tap = pause/resume,
        // horizontal swipe = next/prev photo.
        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                togglePaused()
                return true
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velX: Float, velY: Float
            ): Boolean {
                if (kotlin.math.abs(velX) < 600f) return false
                if (velX < 0) {
                    // Swipe left → next.
                    index = (index + 1) % photos.size
                } else {
                    index = (index - 1 + photos.size) % photos.size
                }
                showAt(index, animate = true)
                resetAdvance()
                return true
            }
        })
        binding.memoriesRoot.setOnTouchListener { _, ev ->
            gesture.onTouchEvent(ev)
        }

        startSoundtrack()
        showAt(0, animate = false)
        handler.postDelayed(advance, PHOTO_DURATION_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(advance)
        mediaPlayer?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {
            }
        }
        mediaPlayer = null
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

    private fun togglePaused() {
        paused = !paused
        if (paused) {
            handler.removeCallbacks(advance)
        } else {
            handler.postDelayed(advance, PHOTO_DURATION_MS)
        }
    }

    private fun resetAdvance() {
        handler.removeCallbacks(advance)
        if (!paused) handler.postDelayed(advance, PHOTO_DURATION_MS)
    }

    companion object {
        const val EXTRA_PHOTOS = "memories_photos"
        const val EXTRA_TITLE = "memories_title"
        const val EXTRA_SUBTITLE = "memories_subtitle"
        private const val PHOTO_DURATION_MS = 3500L
        private const val CROSSFADE_MS = 700
    }
}
