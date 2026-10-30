package cl.rodrigo.autolauncher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Servicio vacío: solo existe para que Android nos deje leer qué canción suena. */
class MediaListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        try {
            val ex = sbn.notification.extras
            val title = ex.getCharSequence("android.title")?.toString()
            val text = ex.getCharSequence("android.text")?.toString()
            val sub = ex.getCharSequence("android.subText")?.toString()
            Diag.add("NOTIF ${sbn.packageName} | título=$title | texto=$text | sub=$sub")
            Music.fromNotification(sbn.packageName, sbn.notification, title, text)
        } catch (e: Exception) {
        }
    }
}

data class NowPlaying(
    val title: String?,
    val artist: String?,
    val album: String?,
    val source: String,
    val playing: Boolean,
    val durationMs: Long,   // -1 si no se conoce
    val positionMs: Long,   // -1 si no se conoce
    val art: Bitmap?,
)

object Music {

    // ---------- Datos recibidos por broadcast (Bluetooth AVRCP y reproductores) ----------

    private const val BT_TRACK_EVENT = "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT"
    private const val BT_EXTRA_META = "android.bluetooth.avrcp-controller.profile.extra.METADATA"
    private const val BT_EXTRA_PLAYBACK = "android.bluetooth.avrcp-controller.profile.extra.PLAYBACK"

    private var bTitle: String? = null
    private var bArtist: String? = null
    private var bAlbum: String? = null
    private var bSource = "Bluetooth"
    private var bPlaying = false
    private var bDuration = -1L
    private var bPosition = -1L
    private var bPositionAt = 0L
    private var bAt = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            try {
                handle(intent)
            } catch (e: Exception) {
            }
        }
    }

    private fun handle(intent: Intent) {
        val now = SystemClock.elapsedRealtime()
        if (intent.action == BT_TRACK_EVENT) {
            bSource = "Bluetooth"
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<MediaMetadata>(BT_EXTRA_META))?.let { m ->
                bTitle = m.getString(MediaMetadata.METADATA_KEY_TITLE)
                bArtist = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
                bAlbum = m.getString(MediaMetadata.METADATA_KEY_ALBUM)
                bDuration = m.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 } ?: -1L
            }
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<PlaybackState>(BT_EXTRA_PLAYBACK))?.let { p ->
                bPlaying = p.state == PlaybackState.STATE_PLAYING
                bPosition = p.position
                bPositionAt = now
            }
            bAt = now
            return
        }
        // Cualquier otro mensaje: buscar campos que parezcan canción/artista
        val ex = intent.extras
        Diag.add("BCAST ${intent.action} | " + dump(ex))
        if (ex == null) return
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var dur = -1L
        var pos = -1L
        var playing: Boolean? = null
        for (k in ex.keySet()) {
            val key = k.lowercase()
            @Suppress("DEPRECATION")
            val v = ex.get(k)
            when {
                v is String && v.isNotBlank() && title == null &&
                    (key.contains("title") || key.contains("song") || key == "track" ||
                        key.contains("trackname") || key == "name" || key.contains("music_name")) -> title = v
                v is String && v.isNotBlank() && artist == null &&
                    (key.contains("artist") || key.contains("singer")) -> artist = v
                v is String && v.isNotBlank() && album == null && key.contains("album") -> album = v
                (key.contains("duration") || key.contains("total") || key.contains("length")) &&
                    readLong(v) > 0 -> dur = readLong(v)
                (key.contains("position") || key.contains("current") || key.contains("progress") ||
                    key.contains("elapsed")) && readLong(v) >= 0 -> pos = readLong(v)
                v is Boolean && key.contains("play") -> playing = v
            }
        }
        if (title == null) {
            if (playing != null) { bPlaying = playing; bAt = now }
            return
        }
        bTitle = title
        bArtist = artist
        bAlbum = album
        playing?.let { bPlaying = it }
        if (title != null && playing == null) bPlaying = true
        // Algunas radios mandan segundos en vez de milisegundos
        bDuration = if (dur in 1..36_000) dur * 1000 else dur
        bPosition = if (pos in 0..36_000 && bDuration > 0 && dur in 1..36_000) pos * 1000 else pos
        bPositionAt = now
        bSource = sourceFromAction(intent.action)
        bAt = now
    }

    /** Usa notificaciones con estilo multimedia como fuente de la canción. */
    fun fromNotification(pkg: String, n: android.app.Notification, title: String?, text: String?) {
        if (title.isNullOrBlank()) return
        val isMedia = n.extras.containsKey("android.mediaSession") ||
            n.category == android.app.Notification.CATEGORY_TRANSPORT
        if (!isMedia) return
        bTitle = title
        bArtist = text
        bAlbum = null
        bPlaying = true
        bDuration = -1
        bPosition = -1
        bSource = if (pkg.contains("bt", true) || pkg.contains("bluetooth", true)) "Bluetooth" else pkg
        bAt = SystemClock.elapsedRealtime()
    }

    fun dump(b: android.os.Bundle?): String {
        if (b == null) return "(sin datos)"
        return b.keySet().joinToString(", ") { k ->
            @Suppress("DEPRECATION")
            "$k=${b.get(k)}"
        }
    }

    private fun readLong(v: Any?): Long = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is String -> v.toLongOrNull() ?: -1L
        else -> -1L
    }

    private fun sourceFromAction(action: String?): String {
        val a = action?.lowercase() ?: return "Música"
        return when {
            "spotify" in a -> "Spotify"
            "tidal" in a -> "TIDAL"
            "bluetooth" in a || "bt" in a -> "Bluetooth"
            else -> "Música"
        }
    }

    fun register(ctx: Context) {
        val f = IntentFilter().apply {
            addAction(BT_TRACK_EVENT)
            addAction("com.android.music.metachanged")
            addAction("com.android.music.playstatechanged")
            addAction("com.spotify.music.metadatachanged")
            addAction("com.spotify.music.playbackstatechanged")
            addAction("com.htc.music.metachanged")
            addAction("com.sec.android.app.music.metachanged")
            addAction("com.miui.player.metachanged")
            Discovery.cached(ctx).forEach { addAction(it) }
        }
        try {
            ctx.registerReceiver(receiver, f)
        } catch (e: Exception) {
        }
    }

    fun reRegister(ctx: Context) {
        unregister(ctx)
        register(ctx)
    }

    fun unregister(ctx: Context) {
        try {
            ctx.unregisterReceiver(receiver)
        } catch (e: Exception) {
        }
    }

    // ---------- Sesiones multimedia (requiere acceso a notificaciones) ----------

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
                ?: sessions.firstOrNull { !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() }
                ?: sessions.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /** Texto con todas las sesiones multimedia y sus datos, para diagnóstico. */
    fun sessionsReport(ctx: Context): String {
        if (!hasAccess(ctx)) return "Sin acceso a notificaciones"
        return try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = msm.getActiveSessions(ComponentName(ctx, MediaListener::class.java))
            if (list.isEmpty()) "No hay sesiones activas"
            else list.joinToString("\n\n") { c ->
                val m = c.metadata
                val keys = m?.keySet()?.joinToString("\n   ") { k ->
                    val v = try { m.getString(k) } catch (e: Exception) { null }
                        ?: try { m.getLong(k).toString() } catch (e: Exception) { "?" }
                    "$k = $v"
                } ?: "(sin metadatos)"
                "• ${c.packageName}  estado=${c.playbackState?.state} pos=${c.playbackState?.position}\n   $keys"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun appLabel(ctx: Context, pkg: String): String {
        if (pkg == "com.android.bluetooth" || pkg.contains("bt", true) || pkg.contains("bluetooth", true)) {
            return "Bluetooth"
        }
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun livePosition(state: PlaybackState?): Long {
        if (state == null || state.position < 0) return -1L
        if (state.state != PlaybackState.STATE_PLAYING) return state.position
        val delta = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return state.position + (delta * state.playbackSpeed).toLong()
    }

    // ---------- Datos del servicio interno SYU (música Bluetooth) ----------
    @Volatile var syuTitle: String? = null
    @Volatile var syuArtist: String? = null
    @Volatile var syuAlbum: String? = null
    @Volatile var syuDevice: String? = null

    private fun btSession(ctx: Context): MediaController? {
        if (!hasAccess(ctx)) return null
        return try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.getActiveSessions(ComponentName(ctx, MediaListener::class.java))
                .firstOrNull { it.packageName == "com.android.bluetooth" }
        } catch (e: Exception) {
            null
        }
    }

    private fun fromSyu(ctx: Context): NowPlaying? {
        val title = syuTitle?.takeIf { it.isNotBlank() } ?: return null
        val bt = btSession(ctx)
        // Sin sesión Bluetooth activa (teléfono desconectado) no mostramos datos viejos
        if (bt == null && hasAccess(ctx)) return null
        val state = bt?.playbackState
        val device = syuDevice?.takeIf { it.isNotBlank() }
        return NowPlaying(
            title = title,
            artist = syuArtist?.takeIf { it.isNotBlank() },
            album = syuAlbum?.takeIf { it.isNotBlank() && it != title },
            source = if (device != null) "Bluetooth · $device" else "Bluetooth",
            playing = state == null || state.state == PlaybackState.STATE_PLAYING,
            durationMs = -1,
            positionMs = livePosition(state),
            art = null,
        )
    }

    fun nowPlaying(ctx: Context): NowPlaying? {
        val c = controller(ctx)
        val meta = c?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (c != null && meta != null && !title.isNullOrBlank()) {
            val state = c.playbackState
            val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 } ?: -1L
            val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            // Algunas radios ponen el nombre de la app del teléfono como "escritor" o "compositor"
            val extraSource = meta.getString(MediaMetadata.METADATA_KEY_WRITER)
            val label = appLabel(ctx, c.packageName)
            return NowPlaying(
                title = title,
                artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM),
                source = if (label == "Bluetooth" && !extraSource.isNullOrBlank()) "Bluetooth · $extraSource" else label,
                playing = state?.state == PlaybackState.STATE_PLAYING,
                durationMs = duration,
                positionMs = livePosition(state),
                art = art,
            )
        }
        // Música Bluetooth en radios FYT/SYU
        fromSyu(ctx)?.let { return it }
        // Respaldo: lo recibido por broadcast en los últimos 10 minutos
        if (bTitle.isNullOrBlank() || SystemClock.elapsedRealtime() - bAt > 600_000L) {
            return if (c != null) NowPlaying(null, null, null, appLabel(ctx, c.packageName),
                false, -1, -1, null) else null
        }
        val pos = when {
            bPosition < 0 -> -1L
            bPlaying -> bPosition + (SystemClock.elapsedRealtime() - bPositionAt)
            else -> bPosition
        }
        return NowPlaying(bTitle, bArtist, bAlbum, bSource, bPlaying, bDuration, pos, null)
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

/** Busca la carátula del álbum por internet (iTunes, sin clave) y la guarda en memoria. */
object AlbumArt {
    private val cache = HashMap<String, Bitmap?>()
    private val pending = HashSet<String>()
    private val failedAt = HashMap<String, Long>()

    private fun key(artist: String?, title: String) = "${artist.orEmpty().lowercase()}|${title.lowercase()}"

    /** Devuelve la carátula si ya está; si no, la pide en segundo plano y llama a onReady. */
    fun get(artist: String?, title: String, onReady: () -> Unit): Bitmap? {
        val k = key(artist, title)
        synchronized(this) {
            if (cache.containsKey(k)) return cache[k]
            if (k in pending) return null
            val failed = failedAt[k]
            if (failed != null && SystemClock.elapsedRealtime() - failed < 120_000L) return null
            pending.add(k)
        }
        Thread {
            val bmp = fetch(artist, title)
            synchronized(this) {
                pending.remove(k)
                if (bmp != null) cache[k] = bmp else failedAt[k] = SystemClock.elapsedRealtime()
                if (cache.size > 40) cache.keys.firstOrNull()?.let { cache.remove(it) }
            }
            if (bmp != null) onReady()
        }.start()
        return null
    }

    private fun fetch(artist: String?, title: String): Bitmap? {
        val mainArtist = artist?.split(",", "&", " feat", " ft.")?.firstOrNull()?.trim()
        val cleanTitle = title.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").trim()
        val terms = listOf(
            listOfNotNull(mainArtist, title).joinToString(" "),
            listOfNotNull(mainArtist, cleanTitle).joinToString(" "),
            cleanTitle,
        ).distinct()
        for (t in terms) {
            if (t.isBlank()) continue
            try {
                val term = URLEncoder.encode(t, "UTF-8")
                val search = read(URL("https://itunes.apple.com/search?term=$term&entity=song&limit=1&country=CL"))
                val results = JSONObject(String(search)).getJSONArray("results")
                if (results.length() == 0) continue
                val url = results.getJSONObject(0).getString("artworkUrl100")
                    .replace("100x100bb", "300x300bb")
                val bytes = read(URL(url))
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
            } catch (e: Exception) {
            }
        }
        return null
    }

    private fun read(url: URL): ByteArray {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        return try {
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
