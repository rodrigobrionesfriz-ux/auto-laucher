package cl.rodrigo.autolauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Parlante de estudio con cono amarillo que "hace boom" al ritmo de la música.
 * Se muestra en el círculo del álbum cuando no hay carátula disponible.
 */
class SpeakerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    /** 0 … 1, intensidad del golpe. El cono se infla y el aro brilla. */
    fun setLevel(v: Float) {
        // Ataque inmediato, caída suave: se siente como un golpe de bajo
        val nv = v.coerceIn(0f, 1f)
        level = if (nv > level) nv else level * 0.82f
        invalidate()
    }

    private var level = 0f
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 2f
        if (r <= 0) return
        val boom = 1f + 0.055f * level

        // Caja del monitor (cara frontal oscura)
        pFill.shader = RadialGradient(cx - r * 0.25f, cy - r * 0.3f, r * 1.7f,
            intArrayOf(Color.parseColor("#33363B"), Color.parseColor("#1D1F23"), Color.parseColor("#101114")),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, pFill)
        pFill.shader = null
        pStroke.strokeWidth = r * 0.02f
        pStroke.color = Color.parseColor("#4A4E55")
        canvas.drawCircle(cx, cy, r * 0.99f, pStroke)

        // Tornillos
        pFill.color = Color.parseColor("#55595F")
        for (i in 0 until 4) {
            val a = Math.toRadians(45.0 + i * 90)
            canvas.drawCircle(cx + r * 0.9f * cos(a).toFloat(), cy + r * 0.9f * sin(a).toFloat(), r * 0.032f, pFill)
        }

        // Suspensión de goma (aro exterior del woofer)
        val rw = r * 0.72f * boom
        pStroke.strokeWidth = r * 0.085f
        pStroke.color = Color.parseColor("#0B0C0E")
        canvas.drawCircle(cx, cy, rw, pStroke)
        // Brillo del aro según el golpe
        pStroke.strokeWidth = r * 0.02f
        pStroke.color = Color.argb((40 + 160 * level).toInt(), 255, 204, 42)
        canvas.drawCircle(cx, cy, rw + r * 0.055f, pStroke)

        // Cono amarillo tejido (degradado radial + anillos)
        val rc = rw - r * 0.04f
        pFill.shader = RadialGradient(cx, cy, rc,
            intArrayOf(Color.parseColor("#8F6E10"), Color.parseColor("#E8B425"), Color.parseColor("#FFD24A")),
            floatArrayOf(0.18f, 0.62f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rc, pFill)
        pFill.shader = null
        // Anillos del tejido del cono
        pStroke.strokeWidth = r * 0.008f
        var ri = rc * 0.32f
        while (ri < rc * 0.97f) {
            pStroke.color = Color.argb(46, 60, 44, 6)
            canvas.drawCircle(cx, cy, ri, pStroke)
            ri += rc * 0.09f
        }
        // Sombra del cono hacia el centro (profundidad)
        pFill.shader = RadialGradient(cx, cy, rc,
            intArrayOf(Color.argb(115, 20, 15, 2), Color.TRANSPARENT, Color.TRANSPARENT),
            floatArrayOf(0.2f, 0.6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rc, pFill)
        pFill.shader = null

        // Capuchón central (domo oscuro con brillo)
        val rd = rc * 0.30f * (1f + 0.05f * level)
        pFill.shader = RadialGradient(cx - rd * 0.35f, cy - rd * 0.4f, rd * 1.9f,
            intArrayOf(Color.parseColor("#3A3D42"), Color.parseColor("#17181B"), Color.parseColor("#0A0B0C")),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rd, pFill)
        pFill.shader = null
        pStroke.strokeWidth = r * 0.012f
        pStroke.color = Color.parseColor("#C79A1E")
        canvas.drawCircle(cx, cy, rd, pStroke)

        // Destello superior de la caja
        pFill.shader = RadialGradient(cx - r * 0.4f, cy - r * 0.55f, r * 0.8f,
            Color.argb(26, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, pFill)
        pFill.shader = null
    }
}
