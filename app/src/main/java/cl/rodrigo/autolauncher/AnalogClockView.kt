package cl.rodrigo.autolauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Reloj analógico con marcas finas, números 12/3/6/9 y segundero rojo. */
class AnalogClockView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    /** true = tarjeta clara (trazos oscuros), false = tarjeta oscura (trazos claros). */
    var day = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tf = Fonts.bold(context)

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            val now = System.currentTimeMillis()
            postDelayed(this, 1000 - now % 1000)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(cx, cy) - 2f
        if (r <= 0) return
        val fg = if (day) Color.parseColor("#2A2F36") else Color.parseColor("#EEF2F6")
        val dim = if (day) Color.argb(120, 42, 47, 54) else Color.argb(120, 238, 242, 246)

        // Aro
        pLine.style = Paint.Style.STROKE
        pLine.strokeWidth = r * 0.035f
        pLine.color = dim
        c.drawCircle(cx, cy, r * 0.97f, pLine)

        // Marcas de minutos
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90)
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val major = i % 5 == 0
            pLine.strokeWidth = if (major) r * 0.035f else r * 0.016f
            pLine.color = if (major) fg else dim
            val r1 = r * 0.88f; val r2 = if (major) r * 0.76f else r * 0.82f
            c.drawLine(cx + r1 * ca, cy + r1 * sa, cx + r2 * ca, cy + r2 * sa, pLine)
        }

        // Números
        pText.typeface = tf
        pText.textSize = r * 0.2f
        pText.color = fg
        val off = -(pText.descent() + pText.ascent()) / 2
        val rn = r * 0.58f
        c.drawText("12", cx, cy - rn + off, pText)
        c.drawText("3", cx + rn, cy + off, pText)
        c.drawText("6", cx, cy + rn + off, pText)
        c.drawText("9", cx - rn, cy + off, pText)

        // Agujas
        val cal = Calendar.getInstance()
        val s = cal.get(Calendar.SECOND)
        val m = cal.get(Calendar.MINUTE) + s / 60f
        val h = cal.get(Calendar.HOUR) + m / 60f
        fun hand(deg: Float, len: Float, w: Float, col: Int, tail: Float = 0.12f) {
            val a = Math.toRadians(deg - 90.0)
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            pLine.strokeWidth = w
            pLine.color = col
            c.drawLine(cx - r * tail * ca, cy - r * tail * sa, cx + len * ca, cy + len * sa, pLine)
        }
        hand(h * 30f, r * 0.48f, r * 0.075f, fg)
        hand(m * 6f, r * 0.7f, r * 0.05f, fg)
        hand(s * 6f, r * 0.8f, r * 0.022f, Color.parseColor("#FF3B30"), 0.18f)
        pFill.color = Color.parseColor("#FF3B30")
        c.drawCircle(cx, cy, r * 0.06f, pFill)
    }
}
