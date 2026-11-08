package cl.rodrigo.autolauncher

import android.media.audiofx.Visualizer
import kotlin.math.max
import kotlin.math.sin

/**
 * Nivel de la música que está sonando (0..1).
 * Usa el Visualizer de Android sobre la salida general; si la radio no lo entrega
 * (audio Bluetooth por hardware), simula un ritmo mientras haya música reproduciéndose.
 */
class AudioLevel {
    private var vis: Visualizer? = null
    private var smooth = 0f
    private var silentSince = 0L
    private var t = 0.0
    var simulated = false
        private set

    fun start() {
        if (vis != null) return
        try {
            vis = Visualizer(0).apply {
                enabled = false
                captureSize = Visualizer.getCaptureSizeRange()[0]
                measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS
                enabled = true
            }
        } catch (e: Throwable) {
            vis = null
        }
    }

    fun stop() {
        try { vis?.release() } catch (e: Throwable) {}
        vis = null
    }

    /** Llamar ~30 veces por segundo. playing = hay música sonando según el reproductor. */
    fun read(playing: Boolean): Float {
        val now = System.currentTimeMillis()
        var raw = 0f
        val v = vis
        if (v != null) {
            try {
                val m = Visualizer.MeasurementPeakRms()
                if (v.getMeasurementPeakRms(m) == Visualizer.SUCCESS) {
                    // rms en milibeles: -9600 (silencio) … 0 (máximo)
                    raw = ((m.mRms + 4200f) / 4200f).coerceIn(0f, 1f)
                    val peak = ((m.mPeak + 3000f) / 3000f).coerceIn(0f, 1f)
                    raw = max(raw, peak * 0.85f)
                }
            } catch (e: Throwable) {}
        }
        if (raw > 0.02f) silentSince = 0L else if (silentSince == 0L) silentSince = now
        simulated = playing && (v == null || (silentSince != 0L && now - silentSince > 2500))
        if (simulated) {
            // ritmo simulado ~ 118 bpm con variación
            t += 0.033
            val beat = (t * 118 / 60.0) % 1.0
            val kick = Math.exp(-beat * 6.0)
            val wobble = 0.5 + 0.25 * sin(t * 7.3) + 0.15 * sin(t * 13.1)
            raw = (0.25 + 0.55 * kick + 0.25 * wobble * Math.random()).toFloat().coerceIn(0f, 1f)
        } else if (!playing && v == null) raw = 0f
        // subida rápida, bajada suave
        smooth = if (raw > smooth) smooth + (raw - smooth) * 0.6f else smooth + (raw - smooth) * 0.15f
        return smooth
    }
}
