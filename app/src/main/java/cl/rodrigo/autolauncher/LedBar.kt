package cl.rodrigo.autolauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/**
 * Barra LED vertical.
 * modo velocidad: 0..220 km/h con los colores de los números (blanco, ámbar cerca del límite, rojo al pasarlo).
 * modo música: vúmetro verde / amarillo / rojo con marca de pico.
 */
class LedBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var vu = false
    var limit = 100
    var maxSpeed = 220

    /** 0..1 */
    var level = 0f
        set(v) {
            val nv = v.coerceIn(0f, 1f)
            val changed = (nv * SEGMENTS).roundToInt() != (field * SEGMENTS).roundToInt()
            field = nv
            if (nv >= peak) { peak = nv; peakAt = System.currentTimeMillis() }
            if (changed || vu) invalidate()
        }

    private var peak = 0f
    private var peakAt = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val off = Color.argb(20, 255, 255, 255)
    private val white = Color.parseColor("#F2F5F8")
    private val warn = context.getColor(R.color.warn)
    private val danger = context.getColor(R.color.danger)

    private fun segColor(i: Int): Int {
        val f = (i + 1f) / SEGMENTS
        if (vu) return when {
            f <= 0.6f -> Color.parseColor("#2BD96B")
            f <= 0.82f -> Color.parseColor("#F5D33A")
            else -> Color.parseColor("#FF4A3D")
        }
        val v = f * maxSpeed
        return when {
            v > limit -> danger
            v >= limit - 10 -> warn
            else -> white
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val seg = h / SEGMENTS
        val lit = (level * SEGMENTS).roundToInt()
        // la marca de pico baja despacio después de 600 ms
        if (vu) {
            val age = System.currentTimeMillis() - peakAt
            if (age > 600) peak = (peak - 0.02f).coerceAtLeast(level)
        }
        val peakSeg = (peak * SEGMENTS).roundToInt() - 1
        for (i in 0 until SEGMENTS) {
            val y = h - (i + 1) * seg + 2
            rect.set(0f, y, w, y + seg - 4f)
            p.color = when {
                i < lit -> segColor(i)
                vu && i == peakSeg && peak > 0.05f -> segColor(i)
                else -> off
            }
            canvas.drawRoundRect(rect, 2f, 2f, p)
        }
    }

    companion object { const val SEGMENTS = 18 }
}
