package com.frandroidlabs.playthenews

import android.content.Intent
import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())

    // Saves the current track's position every second while playing. This runs
    // independently of MainActivity so positions are recorded even when the
    // activity is in the background or fully stopped.
    private val savePositionRunnable = object : Runnable {
        override fun run() {
            val player = mediaSession?.player
            if (player != null && player.isPlaying) {
                val key = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
                    ?: player.currentMediaItem?.localConfiguration?.uri?.toString()
                val pos = player.currentPosition
                if (key != null && pos > 0) {
                    val saved = PositionStore.savePosition(
                        context = this@PlaybackService,
                        url = key,
                        positionMs = pos,
                        source = "service_periodic",
                        allowBackward = false
                    )
                    if (saved) {
                        PositionStore.saveLastActiveUrl(this@PlaybackService, key)
                    }
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()

        player.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                super.onMediaMetadataChanged(mediaMetadata)
            }

            // Save immediately when playback pauses or stops so the position is
            // not lost between the last periodic tick and when playing resumes.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    val p = mediaSession?.player ?: return
                    val key = p.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
                        ?: p.currentMediaItem?.localConfiguration?.uri?.toString()
                    val pos = p.currentPosition
                    if (key != null && pos > 0) {
                        PositionStore.savePosition(
                            context = this@PlaybackService,
                            url = key,
                            positionMs = pos,
                            source = "service_pause",
                            allowBackward = false
                        )
                    }
                }
            }
        })

        val intent = packageManager.getLaunchIntentForPackage(packageName)!!
            .apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()

        handler.postDelayed(savePositionRunnable, 1000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
