package org.fossify.gallery.helpers

import android.content.Context
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/**
 * Wraps a [MediaSessionCompat] so external sources — AirPods / wired
 * headset / Bluetooth car kit / lock-screen controls — can play/pause/skip
 * our video. Owned per-VideoFragment lifecycle.
 *
 * The fragment is responsible for routing the callbacks (in [setCallbacks])
 * back into its existing playVideo/pauseVideo/skip methods. We update the
 * PlaybackState as the player state changes so the system displays the
 * right play/pause icon and the correct position.
 */
class MediaSessionHolder(context: Context) {

    private val session = MediaSessionCompat(context, "FossifyVideo")

    private val supportedActions =
        PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP

    fun setCallbacks(
        onPlay: () -> Unit,
        onPause: () -> Unit,
        onSkipNext: () -> Unit,
        onSkipPrev: () -> Unit,
        onSeekTo: (positionMs: Long) -> Unit,
        onStop: () -> Unit,
    ) {
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = onPlay()
            override fun onPause() = onPause()
            override fun onSkipToNext() = onSkipNext()
            override fun onSkipToPrevious() = onSkipPrev()
            override fun onSeekTo(pos: Long) = onSeekTo(pos)
            override fun onStop() = onStop()
        })
    }

    fun activate() {
        if (!session.isActive) session.isActive = true
    }

    fun updateMetadata(title: String, durationMs: Long) {
        val md = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
            .build()
        session.setMetadata(md)
    }

    fun updateState(isPlaying: Boolean, positionMs: Long, speed: Float = 1f) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING
        else PlaybackStateCompat.STATE_PAUSED
        val pb = PlaybackStateCompat.Builder()
            .setActions(supportedActions)
            .setState(state, positionMs, speed, SystemClock.elapsedRealtime())
            .build()
        session.setPlaybackState(pb)
    }

    fun release() {
        try {
            session.isActive = false
            session.release()
        } catch (_: Exception) {
        }
    }
}
