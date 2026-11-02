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
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.parseColor("#3A3D42")
    }
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(150, 190, 196, 204)
    }
    private val pSeg = Paint()
    private val path = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pBg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            Color.parseColor("#0B0B0C"), Color.parseColor("#000000"), Shader.TileMode.CLAMP)
        // Luz gris suave detrás del velocímetro
        pSpot.shader = android.graphics.RadialGradient(w / 2f, h * 0.48f, h * 0.62f,
            Color.argb(46, 160, 166, 174), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }

    private val pSpot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLines = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f; color = Color.argb(10, 255, 255, 255) }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, pBg)
        canvas.drawRect(0f, 0f, w, h, pSpot)
        val pw = w * 0.29f
        panel(canvas, w, h, pw, true)
        panel(canvas, w, h, pw, false)

        // Muesca superior (hora)
        path.reset()
        path.moveTo(w * 0.33f, 0f); path.lineTo(w * 0.375f, h * 0.108f)
        path.lineTo(w * 0.625f, h * 0.108f); path.lineTo(w * 0.67f, 0f)
        pPanel.shader = LinearGradient(0f, 0f, 0f, h * 0.108f, Color.parseColor("#1E1F22"), Color.parseColor("#111214"), Shader.TileMode.CLAMP)
        canvas.drawPath(path, pPanel); canvas.drawPath(path, pStroke)
        canvas.drawLine(w * 0.375f, h * 0.108f, w * 0.625f, h * 0.108f, pGlow)

        // Base inferior (controles)
        path.reset()
        path.moveTo(w * 0.30f, h); path.lineTo(w * 0.34f, h * 0.875f)
        path.lineTo(w * 0.66f, h * 0.875f); path.lineTo(w * 0.70f, h)
        pPanel.shader = LinearGradient(0f, h * 0.875f, 0f, h, Color.parseColor("#1A1B1E"), Color.parseColor("#0C0C0D"), Shader.TileMode.CLAMP)
        canvas.drawPath(path, pPanel); canvas.drawPath(path, pStroke)
    }

    private fun panel(canvas: Canvas, w: Float, h: Float, pw: Float, left: Boolean) {
        fun x(v: Float) = if (left) v else w - v
        path.reset()
        path.moveTo(x(18f), h * 0.13f); path.lineTo(x(pw * 0.78f), h * 0.10f)
        path.lineTo(x(pw), h * 0.20f); path.lineTo(x(pw), h * 0.80f)
        path.lineTo(x(pw * 0.78f), h * 0.88f); path.lineTo(x(18f), h * 0.86f); path.close()
        pPanel.shader = LinearGradient(x(0f), 0f, x(pw), 0f,
            Color.parseColor("#1C1D20"), Color.parseColor("#0D0D0F"), Shader.TileMode.CLAMP)
        canvas.drawPath(path, pPanel)
        // Textura de líneas diagonales muy suaves dentro del panel
        canvas.save()
        canvas.clipPath(path)
        var k = -h
        while (k < pw + h) {
            canvas.drawLine(x(k), 0f, x(k + h), h, pLines)
            k += 6f
        }
        canvas.restore()
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
