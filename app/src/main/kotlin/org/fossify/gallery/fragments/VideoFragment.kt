@file:androidx.annotation.OptIn(markerClass = [UnstableApi::class])

package org.fossify.gallery.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.util.DisplayMetrics
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.updateLayoutParams
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.bumptech.glide.Glide
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.fadeIn
import org.fossify.commons.extensions.fadeOut
import org.fossify.commons.extensions.getDuration
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getVideoResolution
import org.fossify.commons.extensions.isGone
import org.fossify.commons.extensions.isVisible
import org.fossify.commons.extensions.onGlobalLayout
import org.fossify.commons.extensions.setDrawablesRelativeWithIntrinsicBounds
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.DEFAULT_ANIMATION_DURATION
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.activities.BaseViewerActivity
import org.fossify.gallery.activities.VideoActivity
import org.fossify.gallery.databinding.PagerVideoItemBinding
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.getActionBarHeight
import org.fossify.gallery.extensions.getBottomActionsHeight
import org.fossify.gallery.extensions.getFormattedDuration
import org.fossify.gallery.extensions.getFriendlyMessage
import org.fossify.gallery.extensions.launchGesturePlayer
import org.fossify.gallery.extensions.parseFileChannel
import org.fossify.gallery.helpers.Config
import org.fossify.gallery.helpers.EXOPLAYER_MAX_BUFFER_MS
import org.fossify.gallery.helpers.EXOPLAYER_MIN_BUFFER_MS
import org.fossify.gallery.helpers.FAST_FORWARD_VIDEO_MS
import org.fossify.gallery.helpers.MEDIUM
import org.fossify.gallery.helpers.SHOULD_INIT_FRAGMENT
import org.fossify.gallery.interfaces.PlaybackSpeedListener
import org.fossify.gallery.models.Medium
import org.fossify.gallery.views.MediaSideScroll
import java.io.File
import java.io.FileInputStream
import java.text.DecimalFormat
import kotlin.math.abs

class VideoFragment : ViewPagerFragment(), TextureView.SurfaceTextureListener,
    SeekBar.OnSeekBarChangeListener, PlaybackSpeedListener {
    companion object {
        private const val PROGRESS = "progress"
        private const val UPDATE_INTERVAL_MS = 250L
        private const val TOUCH_HOLD_DURATION_MS = 500L
        private const val TOUCH_HOLD_SPEED_MULTIPLIER = 2.0f
        private const val TOUCH_SLOP_DIVIDER = 3
    }

    private var mIsFullscreen = false
    private var mWasFragmentInit = false
    private var mIsPanorama = false
    private var mIsFragmentVisible = false
    private var mIsDragged = false
    private var mWasVideoStarted = false
    private var mWasPlayerInited = false
    private var mWasLastPositionRestored = false
    private var mPlayOnPrepared = false
    private var mIsPlayerPrepared = false
    private var mCurrTime = 0L
    private var mDuration = 0L
    private var mPositionWhenInit = 0L
    private var mPositionAtPause = 0L
    var mIsPlaying = false

    private var mExoPlayer: ExoPlayer? = null
    private var mVideoSize = Point(1, 1)
    private var mTimerHandler = Handler()

    // Throttle scrub-induced seeks: ExoPlayer can't render a fresh frame on
    // every pixel of finger movement. Faster cadence during fine-scrub so
    // the user sees more intermediate frames (matched with EXACT seek
    // mode), normal cadence otherwise.
    private var mLastSeekTime = 0L
    private var mIsFineScrubbing = false
    private val SCRUB_SEEK_INTERVAL_NORMAL_MS = 50L
    private val SCRUB_SEEK_INTERVAL_FINE_MS = 30L
    private var mPendingScrubTarget: Long = -1L
    private val mScrubFlushHandler = Handler(android.os.Looper.getMainLooper())
    private val mScrubFlushRunnable = Runnable { flushPendingScrub() }

    // Saved player state for the "keep playing muted while scrubbing"
    // technique — the decoder pipeline stays warm and renders frames
    // continuously to the real surface as the user scrubs, instead of
    // decode-from-keyframe-per-seek.
    private var mScrubSavedVolume: Float = 1f
    private var mScrubSavedPlayWhenReady: Boolean = false
    private var mScrubSavedRepeatMode: Int = Player.REPEAT_MODE_OFF

    // Chase-style seek coalescing — one outstanding seek at a time.
    // While scrubbing, each finger move updates mPendingScrubTarget;
    // we only call seekTo() if no seek is in flight. The Player.Listener
    // onPositionDiscontinuity (reason=SEEK) clears the in-flight flag and
    // fires the next seek if the target has moved. Avoids spamming the
    // decoder with mid-seek cancellations that leave a stale frame on
    // screen while the next seek is starting.
    private var mScrubSeekInFlight = false
    private val mScrubSeekTimeoutRunnable = Runnable {
        // Safety net: if onPositionDiscontinuity never fires (codec stall,
        // seek-to-same-position, certain edge cases) we'd be stuck with
        // mScrubSeekInFlight=true forever and no further seeks. After
        // ~250 ms force-clear and chase the latest target.
        if (mScrubSeekInFlight) {
            mScrubSeekInFlight = false
            val pending = mPendingScrubTarget
            val player = mExoPlayer
            if (mIsDragged && player != null && pending >= 0L && pending != player.currentPosition) {
                fireScrubSeek(pending)
            }
        }
    }

    /**
     * Generous safety margin from end-of-stream. Seeking to the actual
     * duration puts the player in STATE_ENDED and the transition out of
     * that state has been crashing on the user's device (auto-loop kicks
     * in, decoder re-preps, all on the main thread → ANR). Staying 250 ms
     * back is imperceptible to the user but completely avoids end-of-
     * stream entry.
     */
    private val SCRUB_END_SAFETY_MS = 250L

    private fun fireScrubSeek(target: Long) {
        val player = mExoPlayer ?: return
        val maxSafe = (mDuration - SCRUB_END_SAFETY_MS).coerceAtLeast(0L)
        val safeTarget = target.coerceIn(0L, maxSafe)
        mScrubSeekInFlight = true
        mLastSeekTime = android.os.SystemClock.elapsedRealtime()
        try {
            player.seekTo(safeTarget)
        } catch (_: Exception) {
            mScrubSeekInFlight = false
            return
        }
        mScrubFlushHandler.removeCallbacks(mScrubSeekTimeoutRunnable)
        mScrubFlushHandler.postDelayed(mScrubSeekTimeoutRunnable, 250L)
    }

    // Pre-extracted thumbnails for instant scrub preview — ExoPlayer's
    // real-time decode is hardware-limited (~5–20 fps for EXACT seek), so
    // for smooth scrubbing we show the nearest cached frame from this
    // strip instead of waiting for the player to decode. The real seek
    // happens once when the user releases.
    // Thumbnails are persisted to disk (per video file path + size + mtime)
    // so a re-open of the same video is instant — matches Google Photos.
    private val SCRUB_THUMB_COUNT = 16
    private val SCRUB_THUMB_MAX_DIM = 360
    @Volatile
    private var mScrubThumbnails: Array<android.graphics.Bitmap?>? = null

    private var mStoredShowExtendedDetails = false
    private var mStoredHideExtendedDetails = false
    private var mStoredBottomActions = true
    private var mStoredExtendedDetails = 0
    private var mStoredRememberLastVideoPosition = false
    private var mOriginalPlaybackSpeed = 1f
    private var mIsLongPressActive = false
    private var mHasAudio = true

    private val mTouchHoldRunnable = Runnable {
        mView.parent.requestDisallowInterceptTouchEvent(true)
        // This code runs after the delay, only if the user is still holding down.
        mIsLongPressActive = true
        mOriginalPlaybackSpeed = mExoPlayer?.playbackParameters?.speed ?: mConfig.playbackSpeed
        mView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        updatePlaybackSpeed(TOUCH_HOLD_SPEED_MULTIPLIER)

        mPlaybackSpeedPill.fadeIn()
    }

    private lateinit var mTimeHolder: View
    private lateinit var mBrightnessSideScroll: MediaSideScroll
    private lateinit var mVolumeSideScroll: MediaSideScroll
    private lateinit var binding: PagerVideoItemBinding
    private lateinit var mView: View
    private lateinit var mMedium: Medium
    private lateinit var mConfig: Config
    private lateinit var mTextureView: TextureView
    private lateinit var mCurrTimeView: TextView
    private lateinit var mPlayPauseButton: ImageView
    private lateinit var mSeekBar: SeekBar
    private lateinit var mPlaybackSpeedPill: TextView
    private var mTouchSlop = 0
    private var mInitialX = 0f
    private var mInitialY = 0f

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val activity = requireActivity()
        val arguments = requireArguments()

        mMedium = arguments.getSerializable(MEDIUM) as Medium
        mConfig = context.config
        mTouchSlop = (ViewConfiguration.get(context).scaledTouchSlop) / TOUCH_SLOP_DIVIDER
        binding = PagerVideoItemBinding.inflate(inflater, container, false).apply {
            panoramaOutline.setOnClickListener { openPanorama() }
            bottomVideoTimeHolder.videoCurrTime.setOnClickListener { skip(false) }
            bottomVideoTimeHolder.videoDuration.setOnClickListener { skip(true) }
            videoHolder.setOnClickListener { toggleFullscreen() }
            videoPreview.setOnClickListener { toggleFullscreen() }
            bottomVideoTimeHolder.videoPlaybackSpeed.setOnClickListener { showPlaybackSpeedPicker() }
            bottomVideoTimeHolder.videoToggleMute.setOnClickListener {
                mConfig.muteVideos = !mConfig.muteVideos
                updatePlayerMuteState(showToast = true)
            }

            videoSurfaceFrame.controller.settings.swallowDoubleTaps = true

            videoPlayOutline.setOnClickListener {
                if (mConfig.gestureVideoPlayer) activity.launchGesturePlayer(mMedium.path) else togglePlayPause()
            }

            mPlayPauseButton = bottomVideoTimeHolder.videoTogglePlayPause
            mPlayPauseButton.setOnClickListener {
                togglePlayPause()
            }

            mSeekBar = bottomVideoTimeHolder.videoSeekbar
            mPlaybackSpeedPill = playbackSpeedPill
            mSeekBar.setOnSeekBarChangeListener(this@VideoFragment)
            // adding an empty click listener just to avoid ripple animation at toggling fullscreen
            mSeekBar.setOnClickListener { }

            val scrubSpeedView = bottomVideoTimeHolder.videoScrubSpeed
            bottomVideoTimeHolder.videoSeekbar.fineModeListener = { scale ->
                if (scale >= 0.99f) {
                    scrubSpeedView.visibility = android.view.View.GONE
                    // Normal-speed scrubbing: snap to keyframes for speed.
                    mExoPlayer?.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                    mIsFineScrubbing = false
                } else {
                    scrubSpeedView.text = getString(R.string.scrub_speed_label, scale)
                    scrubSpeedView.visibility = android.view.View.VISIBLE
                    // Fine scrubbing: the user is moving slowly to see specific
                    // frames, so switch to EXACT seek (decode intermediate
                    // frames) — gives true per-frame precision instead of
                    // keyframe-only.
                    mExoPlayer?.setSeekParameters(SeekParameters.EXACT)
                    mIsFineScrubbing = true
                }
            }

            mTimeHolder = bottomVideoTimeHolder.videoTimeHolder
            mCurrTimeView = bottomVideoTimeHolder.videoCurrTime
            mBrightnessSideScroll = videoBrightnessController
            mVolumeSideScroll = videoVolumeController
            mBrightnessSideScroll.onVerticalScroll = {
                mTimerHandler.removeCallbacks(mTouchHoldRunnable)
            }
            mVolumeSideScroll.onVerticalScroll = {
                mTimerHandler.removeCallbacks(mTouchHoldRunnable)
            }
            mTextureView = videoSurface
            mTextureView.surfaceTextureListener = this@VideoFragment

            val gestureDetector =
                GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        val viewWidth = root.width
                        val viewHeight = root.height
                        val clickedX = e.rawX
                        val clickedY = e.rawY

                        if (mConfig.allowInstantChange) {
                            val instantWidth = viewWidth / 7
                            if (clickedX <= instantWidth) {
                                listener?.goToPrevItem()
                                return true
                            }
                            if (clickedX >= viewWidth - instantWidth) {
                                listener?.goToNextItem()
                                return true
                            }
                        }

                        // Any single tap should reveal the chrome (toolbar /
                        // bottom actions) if it's currently hidden — Google
                        // Photos-style. Calling toggleFullscreen() while in
                        // fullscreen exits fullscreen (shows chrome).
                        if (mIsFullscreen) toggleFullscreen()

                        // Bottom ~18% is a "show controls only" zone — don't
                        // pause when the user taps near the bottom edge, that
                        // was the over-eager pause complaint.
                        val bottomNoPauseZone = viewHeight * 0.82f
                        if (clickedY < bottomNoPauseZone) {
                            togglePlayPause()
                        }
                        return true
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        handleDoubleTap(e.rawX)
                        return true
                    }
                })

            videoPreview.setOnTouchListener { view, event ->
                handleEvent(event)
                false
            }

            videoSurfaceFrame.setOnTouchListener { view, event ->
                if (videoSurfaceFrame.controller.state.zoom == 1f) {
                    handleEvent(event)
                }
                handleTouchHoldEvent(event)
                if (mIsLongPressActive) {
                    return@setOnTouchListener true
                }

                gestureDetector.onTouchEvent(event)
                false
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.videoHolder) { _, insets ->
            val system = insets.getInsetsIgnoringVisibility(Type.systemBars())

            val pillTopMargin = system.top + resources.getActionBarHeight(context) +
                resources.getDimension(org.fossify.commons.R.dimen.normal_margin).toInt()
            (mPlaybackSpeedPill.layoutParams as? RelativeLayout.LayoutParams)?.apply {
                setMargins(
                    0, pillTopMargin, 0, 0
                )
            }

            binding.bottomActionsDummy.updateLayoutParams<ViewGroup.LayoutParams> {
                height = resources.getBottomActionsHeight() + system.bottom
            }
            insets
        }

        mView = binding.root

        if (!arguments.getBoolean(SHOULD_INIT_FRAGMENT, true)) {
            return mView
        }

        storeStateVariables()
        Glide.with(context).load(mMedium.path).into(binding.videoPreview)

        // setMenuVisibility is not called at VideoActivity (third party intent)
        if (!mIsFragmentVisible && activity is VideoActivity) {
            mIsFragmentVisible = true
        }

        mIsFullscreen = listener?.isFullScreen() == true
        initTimeHolder()
        // checkIfPanorama() TODO: Implement panorama using a FOSS library

        ensureBackgroundThread {
            activity.getVideoResolution(mMedium.path)?.apply {
                mVideoSize.x = x
                mVideoSize.y = y
            }
        }

        if (mIsPanorama) {
            binding.apply {
                panoramaOutline.beVisible()
                videoPlayOutline.beGone()
                mVolumeSideScroll.beGone()
                mBrightnessSideScroll.beGone()
                Glide.with(context).load(mMedium.path).into(videoPreview)
            }
        }

        if (!mIsPanorama) {
            if (savedInstanceState != null) {
                mCurrTime = savedInstanceState.getLong(PROGRESS, 0L)
            }

            mWasFragmentInit = true
            setVideoSize()

            binding.apply {
                mBrightnessSideScroll.initialize(
                    activity,
                    slideInfo,
                    true,
                    container,
                    singleTap = { x, y ->
                        if (mConfig.allowInstantChange) {
                            listener?.goToPrevItem()
                        } else {
                            toggleFullscreen()
                        }
                    },
                    doubleTap = { x, y ->
                        doSkip(false)
                    })
                mVolumeSideScroll.initialize(
                    activity,
                    slideInfo,
                    false,
                    container,
                    singleTap = { x, y ->
                        if (mConfig.allowInstantChange) {
                            listener?.goToNextItem()
                        } else {
                            toggleFullscreen()
                        }
                    },
                    doubleTap = { x, y ->
                        doSkip(true)
                    })

                videoSurface.onGlobalLayout {
                    if (mIsFragmentVisible && mConfig.autoplayVideos && !mConfig.gestureVideoPlayer) {
                        playVideo()
                    }
                }
            }
        }

        setupVideoDuration()
        if (mStoredRememberLastVideoPosition) {
            restoreLastVideoSavedPosition()
        }

        return mView
    }

    override fun onResume() {
        super.onResume()
        mConfig =
            requireContext().config      // make sure we get a new config, in case the user changed something in the app settings
        requireActivity().updateTextColors(binding.videoHolder)
        val allowVideoGestures = mConfig.allowVideoGestures
        mTextureView.beGoneIf(mConfig.gestureVideoPlayer || mIsPanorama)
        binding.videoSurfaceFrame.beGoneIf(mTextureView.isGone())

        mVolumeSideScroll.beVisibleIf(allowVideoGestures && !mIsPanorama)
        mBrightnessSideScroll.beVisibleIf(allowVideoGestures && !mIsPanorama)

        checkExtendedDetails()
        initTimeHolder()
        storeStateVariables()
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
        // Keep playing when the activity is going into Picture-in-Picture mode
        // — that's the whole point of PiP.
        val isInPip = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            activity?.isInPictureInPictureMode == true
        if (!isInPip) {
            pauseVideo()
        }
        if (mStoredRememberLastVideoPosition && mIsFragmentVisible && mWasVideoStarted) {
            saveVideoProgress()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activity?.isChangingConfigurations == false) {
            cleanup()
        }
    }

    override fun setMenuVisibility(menuVisible: Boolean) {
        super.setMenuVisibility(menuVisible)
        if (mIsFragmentVisible && !menuVisible) {
            pauseVideo()
        }

        mIsFragmentVisible = menuVisible
        val shouldPlayVideo = mWasFragmentInit && menuVisible && mConfig.autoplayVideos && !mConfig.gestureVideoPlayer
        if (shouldPlayVideo) playVideo()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setVideoSize()
        binding.videoSurfaceFrame.onGlobalLayout {
            binding.videoSurfaceFrame.controller.resetState()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(PROGRESS, mCurrTime)
    }

    private fun storeStateVariables() {
        mConfig.apply {
            mStoredShowExtendedDetails = showExtendedDetails
            mStoredHideExtendedDetails = hideExtendedDetails
            mStoredExtendedDetails = extendedDetails
            mStoredBottomActions = bottomActions
            mStoredRememberLastVideoPosition = rememberLastVideoPosition
        }
    }

    private fun saveVideoProgress() {
        if (!videoEnded()) {
            if (mExoPlayer != null) {
                mConfig.saveLastVideoPosition(
                    mMedium.path,
                    mExoPlayer!!.currentPosition.toInt() / 1000
                )
            } else {
                mConfig.saveLastVideoPosition(mMedium.path, mPositionAtPause.toInt() / 1000)
            }
        }
    }

    private fun restoreLastVideoSavedPosition() {
        val seconds = mConfig.getLastVideoPosition(mMedium.path)
        if (seconds > 0) {
            mPositionAtPause = seconds * 1000L
            setPosition(seconds * 1000L)
        }
    }

    private fun setupTimeHolder() {
        mSeekBar.max = mDuration.toInt()
        // Hide the duration label until we actually know the duration — avoids
        // a "0:00" flash while metadata is being read on first load.
        binding.bottomVideoTimeHolder.videoDuration.apply {
            if (mDuration > 0L) {
                text = mDuration.getFormattedDuration()
                visibility = android.view.View.VISIBLE
            } else {
                visibility = android.view.View.INVISIBLE
            }
        }
        setupTimer()
    }

    private fun setupTimer() {
        activity?.runOnUiThread(object : Runnable {
            override fun run() {
                if (mExoPlayer != null && !mIsDragged && mIsPlaying) {
                    mCurrTime = mExoPlayer!!.currentPosition
                    mSeekBar.progress = mCurrTime.toInt()
                    mCurrTimeView.text = mCurrTime.getFormattedDuration()
                }

                mTimerHandler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        })
    }

    private fun initExoPlayer() {
        val shouldSkipInit = activity == null || mConfig.gestureVideoPlayer || mIsPanorama || mExoPlayer != null
        if (shouldSkipInit) return

        val isContentUri = mMedium.path.startsWith("content://")
        val uri = if (isContentUri) Uri.parse(mMedium.path) else Uri.fromFile(File(mMedium.path))
        val dataSpec = DataSpec(uri)
        val fileDataSource = if (isContentUri) {
            ContentDataSource(requireContext())
        } else {
            FileDataSource()
        }

        try {
            fileDataSource.open(dataSpec)
        } catch (e: Exception) {
            fileDataSource.close()
            activity?.showErrorToast(e)
            return
        }

        val factory = DataSource.Factory { fileDataSource }
        val mediaSource: MediaSource = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(fileDataSource.uri!!))

        fileDataSource.close()

        mPlayOnPrepared = true

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                EXOPLAYER_MIN_BUFFER_MS,
                EXOPLAYER_MAX_BUFFER_MS,
                EXOPLAYER_MIN_BUFFER_MS,
                EXOPLAYER_MIN_BUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        mExoPlayer = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(DefaultMediaSourceFactory(requireContext()))
            .setSeekParameters(SeekParameters.EXACT)
            .setLoadControl(loadControl)
            .build()
            .apply {
                // REPEAT_MODE_ONE makes ExoPlayer loop the same clip
                // internally — STATE_ENDED never fires, so playlist
                // auto-advance can't see the end. Force OFF whenever a
                // playback queue is active.
                if (mConfig.loopVideos &&
                    listener?.isSlideShowActive() == false &&
                    !org.fossify.gallery.helpers.PlaybackQueue.isActive
                ) {
                    repeatMode = Player.REPEAT_MODE_ONE
                }
                setPlaybackSpeed(mConfig.playbackSpeed)
                setMediaSource(mediaSource)
                setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), false
                )
                prepare()

                if (mTextureView.surfaceTexture != null) {
                    setVideoSurface(Surface(mTextureView.surfaceTexture))
                }

                initListeners()
            }

        updatePlayerMuteState()
    }

    private fun ExoPlayer.initListeners() {
        addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                @Player.DiscontinuityReason reason: Int,
            ) {
                // Reset progress views when video loops.
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    // The loop fired naturally. Don't yank the seekbar to
                    // 0 while the user is actively dragging — they're
                    // controlling it, and the bar fighting back makes the
                    // scrub feel broken / can race the chase.
                    if (!mIsDragged) {
                        mSeekBar.progress = 0
                        mCurrTimeView.text = 0.getFormattedDuration()
                    }
                }
                // (Chase pattern removed in v6.8 — was triggering a
                // codec stop/release/restart storm on some hardware
                // decoders and ANRing the app. Now we just time-throttle
                // scrub seeks in setPosition().)
            }

            override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> videoPrepared()
                    Player.STATE_ENDED -> videoCompleted()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // The poster (a thumbnail from the scrub strip we drop in
                // when the surface re-attaches on resume) stays up until
                // the user actually starts playback. onRenderedFirstFrame
                // would also fire but proved unreliable — ExoPlayer claims
                // the frame was pushed but the surface stayed black on
                // some setups. Waiting for real continuous playback is
                // more robust.
                if (isPlaying) {
                    hideScrubThumbnail()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                mVideoSize.x = videoSize.width
                mVideoSize.y = (videoSize.height / videoSize.pixelWidthHeightRatio).toInt()
                setVideoSize()
            }

            override fun onPlayerErrorChanged(error: PlaybackException?) {
                binding.errorMessageHolder.errorMessage.apply {
                    if (error != null) {
                        binding.videoPreview.beGone()
                        binding.videoPlayOutline.beGone()
                        text = error.getFriendlyMessage(context)
                        setTextColor(if (context.config.blackBackground) Color.WHITE else context.getProperTextColor())
                        fadeIn()
                    } else {
                        beGone()
                        binding.videoPlayOutline.beVisible()
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                super.onTracksChanged(tracks)
                mHasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO)
                updatePlayerMuteState()
            }
        })
    }

    private fun toggleFullscreen() {
        listener?.fragmentClicked()
    }

    private fun handleDoubleTap(x: Float) {
        // YouTube-style: double-tap left half = -10s, right half = +10s.
        if (x < mView.width / 2f) doSkip(false) else doSkip(true)
    }

    private fun checkExtendedDetails() {
        if (mConfig.showExtendedDetails) {
            binding.videoDetails.apply {
                text = getMediumExtendedDetails(mMedium)
                beVisibleIf(text.isNotEmpty())
                alpha = if (!mConfig.hideExtendedDetails || !mIsFullscreen) 1f else 0f
                (activity as? BaseViewerActivity)?.applyProperHorizontalInsets(this)
            }
        } else {
            binding.videoDetails.beGone()
        }
    }

    private fun initTimeHolder() {
        mTimeHolder.beGoneIf(mIsFullscreen)
        mTimeHolder.alpha = if (mIsFullscreen) 0f else 1f
        (activity as? BaseViewerActivity)?.applyProperHorizontalInsets(mTimeHolder)
    }

    private fun checkIfPanorama() {
        try {
            val fis = FileInputStream(File(mMedium.path))
            fis.use {
                requireContext().parseFileChannel(mMedium.path, it.channel, 0, 0, 0) {
                    mIsPanorama = true
                }
            }
        } catch (ignored: Exception) {
        } catch (ignored: OutOfMemoryError) {
        }
    }

    private fun openPanorama() {
        TODO("Panorama is not yet implemented.")
    }

    override fun fullscreenToggled(isFullscreen: Boolean) {
        mIsFullscreen = isFullscreen

        mSeekBar.setOnSeekBarChangeListener(if (mIsFullscreen) null else this)
        arrayOf(
            binding.bottomVideoTimeHolder.videoCurrTime,
            binding.bottomVideoTimeHolder.videoDuration,
            binding.bottomVideoTimeHolder.videoTogglePlayPause,
            binding.bottomVideoTimeHolder.videoPlaybackSpeed,
            binding.bottomVideoTimeHolder.videoToggleMute
        ).forEach {
            it.isClickable = !mIsFullscreen
        }

        if (isFullscreen) {
            mTimeHolder.fadeOut(DEFAULT_ANIMATION_DURATION)
            binding.bottomActionsDummy.fadeOut(DEFAULT_ANIMATION_DURATION)
        } else {
            binding.bottomActionsDummy.beVisible()
            mTimeHolder.fadeIn(DEFAULT_ANIMATION_DURATION)
        }

        binding.videoDetails.apply {
            if (mStoredShowExtendedDetails && isVisible() && context != null && resources != null) {
                if (mStoredHideExtendedDetails) {
                    animate().alpha(if (isFullscreen) 0f else 1f).start()
                }
            }
        }
    }

    private fun showPlaybackSpeedPicker() {
        val fragment = PlaybackSpeedFragment()
        childFragmentManager.beginTransaction().add(fragment, fragment::class.java.simpleName)
            .commit()
        fragment.setListener(this)
    }

    override fun updatePlaybackSpeed(speed: Float) {
        val isSlow = speed < 1f
        if (isSlow != binding.bottomVideoTimeHolder.videoPlaybackSpeed.tag as? Boolean) {
            binding.bottomVideoTimeHolder.videoPlaybackSpeed.tag = isSlow

            val drawableId =
                if (isSlow) R.drawable.ic_playback_speed_slow_vector else R.drawable.ic_playback_speed_vector
            context?.let {
                binding.bottomVideoTimeHolder.videoPlaybackSpeed
                    .setDrawablesRelativeWithIntrinsicBounds(
                        AppCompatResources.getDrawable(it, drawableId)
                    )
            }
        }

        @SuppressLint("SetTextI18n")
        binding.bottomVideoTimeHolder.videoPlaybackSpeed.text =
            "${DecimalFormat("#.##").format(speed)}x"
        mExoPlayer?.setPlaybackSpeed(speed)
    }

    private fun skip(forward: Boolean) {
        if (mIsPanorama) {
            return
        } else if (mExoPlayer == null) {
            playVideo()
            return
        }

        mPositionAtPause = 0L
        doSkip(forward)
    }

    private fun doSkip(forward: Boolean) {
        if (mExoPlayer == null) {
            return
        }

        val curr = mExoPlayer!!.currentPosition
        var newPosition =
            if (forward) curr + FAST_FORWARD_VIDEO_MS else curr - FAST_FORWARD_VIDEO_MS
        newPosition = newPosition.coerceIn(0, maxOf(mExoPlayer!!.duration, 0))
        setPosition(newPosition)
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (fromUser) {
            val newPosition = progress.toLong()
            if (mExoPlayer != null) {
                if (!mWasPlayerInited) {
                    mPositionWhenInit = newPosition
                }
                setPosition(newPosition)
            }

            if (mExoPlayer == null) {
                mPositionAtPause = newPosition
                playVideo()
            }
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        val player = mExoPlayer ?: return

        // We tried keeping the player playing (muted) during scrub for
        // "live" decode, but on MediaTek hardware decoders the rapid seek
        // storm triggered a stop/release/restart cycle every ~15ms which
        // ate the binder/IPC layer that responds to touch events and ANRd
        // the app. Back to paused-during-scrub. Each seek decodes ONE
        // frame to the surface. We time-throttle seeks to 100 ms so we
        // don't overwhelm the codec.
        player.playWhenReady = false
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        // Kill any pending watchdog/runnable from the previous drag.
        mScrubFlushHandler.removeCallbacksAndMessages(null)
        mLastSeekTime = 0L
        mPendingScrubTarget = -1L
        mIsDragged = true
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        if (mIsPanorama) {
            openPanorama()
            return
        }

        if (mExoPlayer == null) {
            return
        }

        mExoPlayer!!.setSeekParameters(SeekParameters.EXACT)
        mScrubFlushHandler.removeCallbacksAndMessages(null)
        // Re-seek precisely to the visible scrubber position. Clamp away
        // from duration so we never land on end-of-stream (that's been
        // what crashed the player).
        val rawTarget =
            if (mPendingScrubTarget >= 0L) mPendingScrubTarget else mSeekBar.progress.toLong()
        val maxSafe = (mDuration - SCRUB_END_SAFETY_MS).coerceAtLeast(0L)
        val finalTarget = rawTarget.coerceIn(0L, maxSafe)
        mPendingScrubTarget = -1L
        try {
            mExoPlayer!!.seekTo(finalTarget)
        } catch (_: Exception) {
        }
        // Hide the thumbnail overlay shortly after the final seek so the
        // user briefly sees the overlay, then the real ExoPlayer-decoded
        // frame.
        mScrubFlushHandler.postDelayed({ hideScrubThumbnail() }, 150L)

        if (mIsPlaying) {
            mExoPlayer!!.playWhenReady = true
        }

        mIsDragged = false
    }

    private fun togglePlayPause() {
        if (activity == null || !isAdded) {
            return
        }

        if (mIsPlaying) {
            pauseVideo()
        } else {
            playVideo()
        }
    }

    private fun updatePlayerMuteState(showToast: Boolean = false) {
        val isMuted = mConfig.muteVideos
        if (mHasAudio) {
            if (isMuted) mExoPlayer?.mute() else mExoPlayer?.unmute()
        } else if (showToast && mWasVideoStarted) {
            activity?.toast(R.string.video_no_sound)
        }

        val drawableId = when {
            !mHasAudio -> R.drawable.ic_vector_no_sound
            isMuted -> R.drawable.ic_vector_speaker_off
            else -> R.drawable.ic_vector_speaker_on
        }

        binding.bottomVideoTimeHolder.videoToggleMute.setImageResource(drawableId)
    }

    fun playVideo() {
        if (mExoPlayer == null) {
            initExoPlayer()
            return
        }

        if (binding.videoPreview.isVisible()) {
            binding.videoPreview.beGone()
            initExoPlayer()
        }

        val wasEnded = videoEnded()
        if (wasEnded) {
            setPosition(0)
        }

        if (mStoredRememberLastVideoPosition && !mWasLastPositionRestored) {
            mWasLastPositionRestored = true
            restoreLastVideoSavedPosition()
        }

        if (!wasEnded || !mConfig.loopVideos) {
            mPlayPauseButton.setImageResource(org.fossify.commons.R.drawable.ic_pause_outline_vector)
        }

        if (!mWasVideoStarted) {
            binding.videoPlayOutline.beGone()
            mPlayPauseButton.beVisible()
            binding.bottomVideoTimeHolder.videoToggleMute.beVisible()
            binding.bottomVideoTimeHolder.videoPlaybackSpeed.beVisible()
            binding.bottomVideoTimeHolder.videoPlaybackSpeed.text =
                "${DecimalFormat("#.##").format(mConfig.playbackSpeed)}x"
        }

        mWasVideoStarted = true
        if (mIsPlayerPrepared) {
            mIsPlaying = true
        }
        mExoPlayer?.playWhenReady = true
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        notifyPipPlayStateChanged()
    }

    private fun pauseVideo() {
        if (mExoPlayer == null) {
            return
        }

        mIsPlaying = false
        if (!videoEnded()) {
            mExoPlayer?.playWhenReady = false
        }

        mPlayPauseButton.setImageResource(org.fossify.commons.R.drawable.ic_play_outline_vector)
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mPositionAtPause = mExoPlayer?.currentPosition ?: 0L
        notifyPipPlayStateChanged()
    }

    /** Public entry point so [ViewPagerActivity]'s PiP RemoteAction
     *  broadcast receiver can toggle playback from outside the fragment. */
    fun togglePlayPauseFromPip() {
        if (activity == null || !isAdded) return
        if (mIsPlaying) pauseVideo() else playVideo()
    }

    private fun notifyPipPlayStateChanged() {
        (activity as? org.fossify.gallery.activities.ViewPagerActivity)?.updatePipActions()
    }

    private fun videoEnded(): Boolean {
        val currentPos = mExoPlayer?.currentPosition ?: 0
        val duration = mExoPlayer?.duration ?: 0
        return currentPos != 0L && currentPos >= duration
    }

    private fun setPosition(milliseconds: Long) {
        // Always update the visible scrubber + time label immediately so the
        // finger movement feels live.
        mSeekBar.progress = milliseconds.toInt()
        mCurrTimeView.text = milliseconds.getFormattedDuration()
        if (!mIsPlaying) {
            mPositionAtPause = milliseconds
        }

        if (mIsDragged) {
            // Show the nearest pre-extracted scrub thumbnail on the
            // overlay for instant visual feedback, and fire one ExoPlayer
            // seek at most every 100ms so the codec has time between
            // requests. The auto-hide pulls the overlay off 200ms after
            // the last move so the real ExoPlayer-decoded frame peeks
            // through when the finger pauses.
            showScrubThumbnailAt(milliseconds)
            mPendingScrubTarget = milliseconds
            val now = android.os.SystemClock.elapsedRealtime()
            val timeSinceLast = now - mLastSeekTime
            val throttleMs = 100L
            if (timeSinceLast >= throttleMs) {
                mLastSeekTime = now
                try { mExoPlayer?.seekTo(milliseconds) } catch (_: Exception) {}
            } else {
                mScrubFlushHandler.removeCallbacks(mScrubFlushRunnable)
                mScrubFlushHandler.postDelayed(
                    mScrubFlushRunnable,
                    throttleMs - timeSinceLast
                )
            }
        } else {
            mExoPlayer?.seekTo(milliseconds)
        }
    }

    private fun flushPendingScrub() {
        val target = mPendingScrubTarget
        if (target >= 0L) {
            mLastSeekTime = android.os.SystemClock.elapsedRealtime()
            try { mExoPlayer?.seekTo(target) } catch (_: Exception) {}
        }
    }

    private fun setupVideoDuration() {
        ensureBackgroundThread {
            mDuration = context?.getDuration(mMedium.path)?.times(1000L)?.coerceAtLeast(0L) ?: 0L

            activity?.runOnUiThread {
                setupTimeHolder()
                setPosition(0)
            }
            extractScrubThumbnails()
        }
    }

    private fun extractScrubThumbnails() {
        if (mDuration <= 0L) return
        if (mScrubThumbnails != null) return

        val ctx = context ?: return
        val cacheDir = File(ctx.cacheDir, "scrub_thumbs/${scrubCacheKeyForCurrentVideo()}")

        // Disk cache hit — load and we're done. Instant on every re-open of
        // the same video.
        if (cacheDir.isDirectory) {
            val loaded = loadScrubThumbnailsFromDisk(cacheDir)
            if (loaded != null) {
                mScrubThumbnails = loaded
                return
            }
        }

        val thumbs = arrayOfNulls<android.graphics.Bitmap>(SCRUB_THUMB_COUNT)
        val durationUs = mDuration * 1000L
        // Single MediaMetadataRetriever. Earlier 4-thread parallel approach
        // ended up serializing through the hardware codec anyway and made
        // it WORSE on real devices (30s+ for an 18s clip). Single thread
        // + keyframe-only seek (OPTION_CLOSEST_SYNC) + hw-accelerated
        // getScaledFrameAtTime is the fastest realistic combo. Disk cache
        // makes subsequent opens instant.
        val mmr = android.media.MediaMetadataRetriever()
        try {
            mmr.setDataSource(mMedium.path)
            for (i in 0 until SCRUB_THUMB_COUNT) {
                if (!isAdded || activity?.isDestroyed == true) return
                val timeUs = (durationUs * i) / SCRUB_THUMB_COUNT
                val bitmap = extractOneScrubFrame(mmr, timeUs)
                if (bitmap != null) thumbs[i] = bitmap
            }
        } catch (_: Exception) {
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }

        mScrubThumbnails = thumbs
        try {
            saveScrubThumbnailsToDisk(cacheDir, thumbs)
        } catch (_: Exception) {
        }
    }

    private fun extractOneScrubFrame(
        mmr: android.media.MediaMetadataRetriever,
        timeUs: Long
    ): android.graphics.Bitmap? {
        // CLOSEST_SYNC = nearest keyframe — fast, hardware-accelerated.
        // Several of our timestamps collapse to the same keyframe on a
        // short video (so the scrub preview is coarser than per-frame),
        // but extraction is dramatically faster than OPTION_CLOSEST.
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            try {
                val scaled = mmr.getScaledFrameAtTime(
                    timeUs,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    SCRUB_THUMB_MAX_DIM,
                    SCRUB_THUMB_MAX_DIM
                )
                if (scaled != null) return scaled
            } catch (_: Exception) {
            }
        }
        val raw = try {
            mmr.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } ?: return null
        val scaled = scaleBitmapForThumbnail(raw, SCRUB_THUMB_MAX_DIM)
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    private fun scrubCacheKeyForCurrentVideo(): String {
        val f = File(mMedium.path)
        val raw = "${mMedium.path}|${f.length()}|${f.lastModified()}|v${SCRUB_THUMB_COUNT}d${SCRUB_THUMB_MAX_DIM}"
        // Simple SHA1 hex — short enough for a path, doesn't expose the
        // user's file path in the directory name.
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-1")
            digest.update(raw.toByteArray())
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            raw.hashCode().toString()
        }
    }

    private fun loadScrubThumbnailsFromDisk(dir: File): Array<android.graphics.Bitmap?>? {
        return try {
            val arr = arrayOfNulls<android.graphics.Bitmap>(SCRUB_THUMB_COUNT)
            var loaded = 0
            for (i in 0 until SCRUB_THUMB_COUNT) {
                val file = File(dir, "$i.jpg")
                if (file.exists()) {
                    val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) {
                        arr[i] = bmp
                        loaded++
                    }
                }
            }
            if (loaded > 0) arr else null
        } catch (_: Exception) {
            null
        }
    }

    private fun saveScrubThumbnailsToDisk(dir: File, thumbs: Array<android.graphics.Bitmap?>) {
        if (!dir.exists() && !dir.mkdirs()) return
        thumbs.forEachIndexed { i, bmp ->
            if (bmp == null) return@forEachIndexed
            try {
                val out = File(dir, "$i.jpg")
                java.io.FileOutputStream(out).use { fos ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, fos)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun scaleBitmapForThumbnail(src: android.graphics.Bitmap, maxDim: Int): android.graphics.Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val ratio = maxDim.toFloat() / maxOf(w, h)
        val newW = (w * ratio).toInt().coerceAtLeast(1)
        val newH = (h * ratio).toInt().coerceAtLeast(1)
        return android.graphics.Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    private fun showScrubThumbnailAt(positionMs: Long) {
        val thumbs = mScrubThumbnails ?: return
        if (mDuration <= 0L) return
        val ratio = positionMs.toFloat() / mDuration
        val idx = (ratio * thumbs.size).toInt().coerceIn(0, thumbs.size - 1)
        val bitmap = thumbs[idx] ?: return
        val view = binding.scrubPreview
        view.setImageBitmap(bitmap)
        if (view.visibility != android.view.View.VISIBLE) {
            view.visibility = android.view.View.VISIBLE
        }
        // Auto-hide ~200 ms after the last move so when the user pauses
        // their finger, ExoPlayer has time to render and the real (full-
        // resolution) surface peeks through. New moves cancel + reschedule.
        mOverlayAutoHideHandler.removeCallbacks(mOverlayAutoHideRunnable)
        mOverlayAutoHideHandler.postDelayed(mOverlayAutoHideRunnable, OVERLAY_AUTO_HIDE_MS)
    }

    private val mOverlayAutoHideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val OVERLAY_AUTO_HIDE_MS = 200L
    private val mOverlayAutoHideRunnable = Runnable {
        // Only auto-hide if the user is still actively dragging — if they
        // released, onStopTrackingTouch already scheduled the hide.
        if (mIsDragged) {
            try { binding.scrubPreview.visibility = android.view.View.GONE } catch (_: Exception) {}
        }
    }

    private fun hideScrubThumbnail() {
        mOverlayAutoHideHandler.removeCallbacks(mOverlayAutoHideRunnable)
        try {
            binding.scrubPreview.visibility = android.view.View.GONE
            binding.scrubPreview.setImageDrawable(null)
        } catch (_: Exception) {
        }
    }

    private fun videoPrepared() {
        if (mDuration == 0L) {
            mDuration = mExoPlayer!!.duration
            setupTimeHolder()
            setPosition(mCurrTime)

            if (mIsFragmentVisible && (mConfig.autoplayVideos)) {
                playVideo()
            }
        }

        if (mPositionWhenInit != 0L && !mWasPlayerInited) {
            setPosition(mPositionWhenInit)
            mPositionWhenInit = 0
        }

        mIsPlayerPrepared = true
        if (mPlayOnPrepared && !mIsPlaying) {
            if (mPositionAtPause != 0L) {
                mExoPlayer?.seekTo(mPositionAtPause)
                mPositionAtPause = 0L
            }
            updatePlaybackSpeed(mConfig.playbackSpeed)
            playVideo()
        }
        mWasPlayerInited = true
        mPlayOnPrepared = false
    }

    private fun videoCompleted() {
        if (!isAdded || mExoPlayer == null) {
            return
        }

        mCurrTime = mExoPlayer!!.duration
        if (listener?.videoEnded() == false &&
            mConfig.loopVideos &&
            !org.fossify.gallery.helpers.PlaybackQueue.isActive
        ) {
            playVideo()
        } else {
            mSeekBar.progress = mSeekBar.max
            mCurrTimeView.text = mDuration.getFormattedDuration()
            pauseVideo()
        }
    }

    /** Public accessor so the activity can gate PiP entry on actual playback. */
    fun isCurrentlyPlaying(): Boolean = mIsPlaying

    /** Public accessor so the activity can defer auto-hide while scrubbing. */
    fun isScrubbing(): Boolean = mIsDragged

    /**
     * When the activity is brought back to the foreground (e.g. user
     * tapped the launcher icon while a paused video was on screen), the
     * TextureView surface gets re-attached but ExoPlayer doesn't push a
     * new frame to it until something forces a render. Result: black
     * window. Re-seeking to the current position forces a re-render of
     * the existing paused frame.
     */
    fun redrawCurrentFrame() {
        val player = mExoPlayer ?: return
        try {
            val pos = player.currentPosition
            if (pos >= 0L) {
                player.seekTo(pos)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Drop the nearest pre-extracted thumbnail into the scrub_preview
     * overlay as a static poster while the video is paused. Called from
     * ViewPagerActivity.onResume regardless of whether the texture
     * surface fired its re-attach callback (it sometimes doesn't if
     * Android kept the SurfaceTexture alive across the pause). The
     * poster stays up until onIsPlayingChanged(true) hides it.
     */
    fun showPosterAtCurrentPosition() {
        if (mIsPlaying) return
        val pos = mExoPlayer?.currentPosition ?: 0L
        // Fast path: drop the nearest cached strip thumbnail in.
        if (mScrubThumbnails != null) {
            try { showScrubThumbnailAt(pos) } catch (_: Exception) {}
            return
        }
        // Fallback: strip isn't extracted yet (user backgrounded too quick).
        // Extract a single frame on-demand at the current position so the
        // viewport isn't black while we wait for the strip.
        val path = mMedium.path
        ensureBackgroundThread {
            val mmr = android.media.MediaMetadataRetriever()
            val bitmap = try {
                mmr.setDataSource(path)
                mmr.getFrameAtTime(pos * 1000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
                null
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
            if (bitmap != null && isAdded && activity?.isDestroyed == false) {
                activity?.runOnUiThread {
                    if (mIsPlaying) return@runOnUiThread
                    binding.scrubPreview.setImageBitmap(bitmap)
                    binding.scrubPreview.visibility = android.view.View.VISIBLE
                }
            }
        }
    }

    /**
     * Force the player to stop and release. Called by ViewPagerActivity in
     * onStop when we're not actually in PiP anymore — so audio doesn't keep
     * playing in the background after the user closes the PiP window.
     */
    fun releaseFromPip() {
        try {
            pauseVideo()
            releaseExoPlayer()
        } catch (_: Exception) {
        }
    }

    private fun cleanup() {
        pauseVideo()
        releaseExoPlayer()
        mScrubThumbnails?.forEach { it?.recycle() }
        mScrubThumbnails = null

        if (mWasFragmentInit) {
            mCurrTimeView.text = 0.getFormattedDuration()
            mSeekBar.progress = 0
            mTimerHandler.removeCallbacksAndMessages(null)
        }
    }

    private fun releaseExoPlayer() {
        mIsPlayerPrepared = false
        mExoPlayer?.apply {
            stop()
            release()
        }
        mExoPlayer = null
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = false

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        mExoPlayer?.let { player ->
            // Belt-and-suspenders for the "black viewport after returning to
            // the app on a paused video" problem:
            //   1) Drop the nearest pre-extracted scrub thumbnail into the
            //      overlay as a poster — guaranteed to show *something*.
            //   2) Re-attach the new surface to the player.
            //   3) Force a real seek (position - 1) so ExoPlayer decodes
            //      and renders to the freshly attached surface. The
            //      onRenderedFirstFrame listener will hide the poster
            //      once the real frame is on screen.
            try {
                showScrubThumbnailAt(player.currentPosition)
            } catch (_: Exception) {
            }
            player.setVideoSurface(Surface(mTextureView.surfaceTexture))
            mTextureView.post {
                try {
                    val pos = player.currentPosition
                    val target = if (pos > 0L) (pos - 1L).coerceAtLeast(0L) else 1L
                    player.setSeekParameters(SeekParameters.EXACT)
                    player.seekTo(target)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun setVideoSize() {
        if (activity == null || mConfig.gestureVideoPlayer) return

        val videoProportion = mVideoSize.x.toFloat() / mVideoSize.y.toFloat()
        val display = requireActivity().windowManager.defaultDisplay
        val screenWidth: Int
        val screenHeight: Int

        val realMetrics = DisplayMetrics()
        display.getRealMetrics(realMetrics)
        screenWidth = realMetrics.widthPixels
        screenHeight = realMetrics.heightPixels

        val screenProportion = screenWidth.toFloat() / screenHeight.toFloat()

        mTextureView.layoutParams.apply {
            if (videoProportion > screenProportion) {
                width = screenWidth
                height = (screenWidth.toFloat() / videoProportion).toInt()
            } else {
                width = (videoProportion * screenHeight.toFloat()).toInt()
                height = screenHeight
            }
            mTextureView.layoutParams = this
        }
    }

    private fun handleTouchHoldEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (mIsPlaying && event.pointerCount == 1) {
                    mInitialX = event.x
                    mInitialY = event.y
                    mTimerHandler.postDelayed(mTouchHoldRunnable, TOUCH_HOLD_DURATION_MS)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = abs(event.x - mInitialX)
                val deltaY = abs(event.y - mInitialY)
                if (!mIsLongPressActive && (deltaX > mTouchSlop || deltaY > mTouchSlop)) {
                    mTimerHandler.removeCallbacks(mTouchHoldRunnable)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!mIsLongPressActive) {
                    mTimerHandler.removeCallbacks(mTouchHoldRunnable)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mTimerHandler.removeCallbacks(mTouchHoldRunnable)
                stopHoldSpeedMultiplierGesture()
            }
        }
    }

    private fun stopHoldSpeedMultiplierGesture() {
        if (mIsLongPressActive) {
            updatePlaybackSpeed(mOriginalPlaybackSpeed)
            mIsLongPressActive = false
            mPlaybackSpeedPill.fadeOut()
        }
    }
}
