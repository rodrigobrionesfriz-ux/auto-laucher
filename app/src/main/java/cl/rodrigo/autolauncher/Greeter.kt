package cl.rodrigo.autolauncher

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Calendar
import java.util.Locale

/** Saludo editable y voz de bienvenida al iniciar el launcher. */
object Greeter {
    private fun p(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun name(ctx: Context): String = p(ctx).getString("greet_name", "") ?: ""
    fun setName(ctx: Context, v: String) = p(ctx).edit().putString("greet_name", v.trim()).apply()

    fun voice(ctx: Context): Boolean = p(ctx).getBoolean("greet_voice", true)
    fun setVoice(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("greet_voice", v).apply()

    /** "Buenos días", "Buenas tardes" o "Buenas noches", más el nombre si está configurado. */
    fun text(ctx: Context): String {
        val base = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Buenos días"
            in 12..19 -> "Buenas tardes"
            else -> "Buenas noches"
        }
        val n = name(ctx)
        return if (n.isEmpty()) base else "$base, $n"
    }

    /** Solo una vez por arranque del proceso (no en cada vuelta al inicio ni al cambiar diseño). */
    private var spokenThisProcess = false
    private var tts: TextToSpeech? = null

    fun speakOnStart(ctx: Context) {
        if (spokenThisProcess || !voice(ctx)) return
        spokenThisProcess = true
        speak(ctx.applicationContext, text(ctx))
    }

    fun speak(ctx: Context, msg: String) {
        try {
            tts?.shutdown()
            var engine: TextToSpeech? = null
            engine = TextToSpeech(ctx) { status ->
                val t = engine ?: return@TextToSpeech
                if (status != TextToSpeech.SUCCESS) return@TextToSpeech
                val langs = listOf(Locale("es", "CL"), Locale("es", "ES"), Locale("es"), Locale.getDefault())
                for (l in langs) {
                    val r = t.setLanguage(l)
                    if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) break
                }
                t.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                t.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "greet")
            }
            tts = engine
        } catch (_: Throwable) { }
    }
}
