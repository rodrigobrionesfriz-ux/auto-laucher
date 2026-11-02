package cl.rodrigo.autolauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/** Marco del diseño cockpit: paneles laterales, muesca de la hora y barras LED de velocidad. */
class CockpitFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    /** 0 … 1, enciende las barras LED de los costados. */
    var level = 0f
        set(v) {
            val n = (v.coerceIn(0f, 1f) * SEGMENTS).roundToInt()
            if (n != (field * SEGMENTS).roundToInt()) { field = v; invalidate() } else field = v
        }

    private val pBg = Paint()
    private val pPanel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.parseColor("#2E3A4A")
    }
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(140, 62, 224, 180)
    }
    private val pSeg = Paint()
    private val path = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pBg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            Color.parseColor("#0A0F16"), Color.parseColor("#04060A"), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, pBg)
        val pw = w * 0.29f
        panel(canvas, w, h, pw, true)
        panel(canvas, w, h, pw, false)

        // Muesca superior (hora)
        path.reset()
        path.moveTo(w * 0.36f, 0f); path.lineTo(w * 0.40f, h * 0.085f)
        path.lineTo(w * 0.60f, h * 0.085f); path.lineTo(w * 0.64f, 0f)
        pPanel.shader = null; pPanel.color = Color.parseColor("#0F1620")
        canvas.drawPath(path, pPanel); canvas.drawPath(path, pStroke)
        canvas.drawLine(w * 0.40f, h * 0.085f, w * 0.60f, h * 0.085f, pGlow)

        // Base inferior (controles)
        path.reset()
        path.moveTo(w * 0.30f, h); path.lineTo(w * 0.34f, h * 0.875f)
        path.lineTo(w * 0.66f, h * 0.875f); path.lineTo(w * 0.70f, h)
        pPanel.color = Color.parseColor("#0D131B")
        canvas.drawPath(path, pPanel); canvas.drawPath(path, pStroke)
    }

    private fun panel(canvas: Canvas, w: Float, h: Float, pw: Float, left: Boolean) {
        fun x(v: Float) = if (left) v else w - v
        path.reset()
        path.moveTo(x(18f), h * 0.13f); path.lineTo(x(pw * 0.78f), h * 0.10f)
        path.lineTo(x(pw), h * 0.20f); path.lineTo(x(pw), h * 0.80f)
        path.lineTo(x(pw * 0.78f), h * 0.88f); path.lineTo(x(18f), h * 0.86f); path.close()
        pPanel.shader = LinearGradient(x(0f), 0f, x(pw), 0f,
            Color.parseColor("#111823"), Color.parseColor("#0B1017"), Shader.TileMode.CLAMP)
        canvas.drawPath(path, pPanel)
        canvas.drawPath(path, pStroke)
        path.reset()
        path.moveTo(x(pw * 0.78f), h * 0.10f); path.lineTo(x(pw), h * 0.20f)
        path.lineTo(x(pw), h * 0.80f); path.lineTo(x(pw * 0.78f), h * 0.88f)
        canvas.drawPath(path, pGlow)

        // Barra LED
        val top = h * 0.2f; val bot = h * 0.8f
        val seg = (bot - top) / SEGMENTS
        val lit = (level * SEGMENTS).roundToInt()
        val x0 = if (left) 6f else w - 12f
        for (i in 0 until SEGMENTS) {
            val y = bot - (i + 1) * seg + 2
            val f = i.toFloat() / SEGMENTS
            pSeg.color = if (i < lit) when {
                f < 0.55f -> Color.parseColor("#2BD96B")
                f < 0.8f -> Color.parseColor("#F5D33A")
                else -> Color.parseColor("#FF4A3D")
            } else Color.argb(18, 255, 255, 255)
            canvas.drawRect(x0, y, x0 + 6f, y + seg - 4f, pSeg)
        }
    }

    companion object { private const val SEGMENTS = 16 }
}
