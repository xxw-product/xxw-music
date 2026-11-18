package com.musicdl.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Media3 owns the foreground notification, transport controls and lock-screen session. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = PlayerHolder.player ?: ExoPlayer.Builder(this).build().also {
            PlayerHolder.player = it
        }
        val activity = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Audio items need individual headers; the app owns the queue outside ExoPlayer.
        val transport = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()

            override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
            override fun seekToNext() { PlayerHolder.next?.invoke() }
            override fun seekToNextMediaItem() { PlayerHolder.next?.invoke() }
            override fun seekToPrevious() { PlayerHolder.previous?.invoke() }
            override fun seekToPreviousMediaItem() { PlayerHolder.previous?.invoke() }
        }
        session = MediaSession.Builder(this, transport).setSessionActivity(activity).build()
        PlayerHolder.session = session
        addSession(requireNotNull(session))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (PlayerHolder.player?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        PlayerHolder.session = null
        if (!PlayerHolder.uiAttached) {
            PlayerHolder.player?.release()
            PlayerHolder.player = null
        }
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            // Called while the activity is visible. Media3 promotes it when playback starts.
            context.startService(Intent(context, PlaybackService::class.java))
        }
    }
}

object PlayerHolder {
    @Volatile var player: ExoPlayer? = null
    @Volatile var session: MediaSession? = null
    var uiAttached: Boolean = false
    var next: (() -> Unit)? = null
    var previous: (() -> Unit)? = null
}
