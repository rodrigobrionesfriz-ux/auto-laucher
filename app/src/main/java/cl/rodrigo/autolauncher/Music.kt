package cl.rodrigo.autolauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent

/** Servicio vacío: solo existe para que Android nos deje leer qué canción suena. */
class MediaListener : NotificationListenerService()

data class NowPlaying(val title: String?, val artist: String?, val source: String?, val playing: Boolean)

object Music {

    fun hasAccess(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        return enabled?.contains(ctx.packageName) == true
    }

    fun openAccessSettings(ctx: Context): Boolean = try {
        ctx.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    private fun controller(ctx: Context): MediaController? {
        if (!hasAccess(ctx)) return null
        return try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = msm.getActiveSessions(ComponentName(ctx, MediaListener::class.java))
            sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    fun nowPlaying(ctx: Context): NowPlaying? {
        val c = controller(ctx) ?: return null
        val meta = c.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val source = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        } catch (e: Exception) {
            c.packageName
        }
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        return NowPlaying(title, artist, source, playing)
    }

    /** Envía una tecla multimedia a la app que esté sonando (o a la última que sonó). */
    fun key(ctx: Context, code: Int) {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0)
        val c = controller(ctx)
        if (c != null) {
            c.dispatchMediaButtonEvent(down)
            c.dispatchMediaButtonEvent(up)
        } else {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(down)
            am.dispatchMediaKeyEvent(up)
        }
    }
}
