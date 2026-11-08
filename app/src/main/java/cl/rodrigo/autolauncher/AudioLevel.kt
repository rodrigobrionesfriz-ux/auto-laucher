package cl.rodrigo.autolauncher

import android.media.audiofx.Visualizer
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Nivel de la música que está sonando (0..1).
 * Lee la forma de onda de la salida general con el Visualizer de Android. Si la radio no entrega
 * audio real (lectura plana o fija, típico cuando el Bluetooth va por hardware), simula un ritmo
 * mientras haya música reproduciéndose.
 */
class AudioLevel {
    private var vis: Visualizer? = null
    private var buf = ByteArray(0)
    private var smooth = 0f
    private var t = 0.0
    private val history = FloatArray(60)
    private var hi = 0
    private var filled = 0
    var simulated = false
        private set

    fun start() {
        if (vis != null) return
        try {
            vis = Visualizer(0).apply {
                enabled = false
                captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(128)
                enabled = true
            }
            buf = ByteArray(vis!!.captureSize)
        } catch (e: Throwable) {
            vis = null
        }
    }

    fun stop() {
        try { vis?.release() } catch (e: Throwable) {}
        vis = null
        filled = 0
    }

    private fun readReal(): Float? {
        val v = vis ?: return null
        return try {
            if (v.getWaveForm(buf) != Visualizer.SUCCESS) return null
            // Buffer vacío o todo igual (la radio no entrega audio): lectura inválida
            val first = buf[0]
            if (buf.all { it == first }) return null
            // muestras de 8 bits sin signo, centro en 128
            var sum = 0.0
            for (b in buf) {
                val s = ((b.toInt() and 0xFF) - 128) / 128.0
                sum += s * s
            }
            (sqrt(sum / buf.size) * 3.2).toFloat().coerceIn(0f, 1f)
        } catch (e: Throwable) {
            null
        }
    }

    /** ¿Las últimas ~2 s de lectura son planas (sin variación)? */
    private fun flat(): Boolean {
        if (filled < history.size) return false
        var mean = 0f
        for (x in history) mean += x
        mean /= history.size
        var dev = 0f
        for (x in history) dev += abs(x - mean)
        dev /= history.size
        return dev < 0.012f
    }

    /** "auto", "sim" (siempre simulado) u "off" (apagado). */
    var mode = "auto"

    /** Llamar ~30 veces por segundo. playing = hay música sonando según el reproductor. */
    fun read(playing: Boolean): Float {
        if (mode == "off") { smooth = 0f; return 0f }
        val real = if (mode == "sim") null else readReal()
        if (real != null) {
            history[hi] = real; hi = (hi + 1) % history.size
            if (filled < history.size) filled++
        }
        simulated = playing && (real == null || flat())
        val raw: Float = when {
            simulated -> {
                // ritmo simulado ~118 bpm con variación
                t += 0.033
                val beat = (t * 118 / 60.0) % 1.0
                val kick = Math.exp(-beat * 6.0)
                val wobble = 0.5 + 0.25 * sin(t * 7.3) + 0.15 * sin(t * 13.1)
                (0.22 + 0.55 * kick + 0.25 * wobble * Math.random()).toFloat().coerceIn(0f, 1f)
            }
            !playing && (real == null || flat()) -> 0f
            else -> real ?: 0f
        }
        // subida rápida, bajada suave
        smooth = if (raw > smooth) smooth + (raw - smooth) * 0.6f else smooth + (raw - smooth) * 0.15f
        return smooth
    }
}
