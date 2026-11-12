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

    /** Modo día: fondo gris claro (los paneles siguen oscuros para el contraste). */
    var day = false
        set(v) {
            if (field != v) {
                field = v
                if (width > 0) buildShaders(width.toFloat(), height.toFloat())
                invalidate()
            }
        }

    private val pBg = Paint()
    private val pPanel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val pSeg = Paint()
    private val path = Path()

    /** Borde estilo tubo LED gris: sombra abajo + halo difuso + línea brillante. */
    private fun ledEdge(canvas: Canvas, p: Path) {
        // Sombra proyectada
        canvas.save(); canvas.translate(0f, 3f)
        pLed.strokeWidth = 9f; pLed.color = Color.argb(if (day) 50 else 70, 0, 0, 0); canvas.drawPath(p, pLed)
        canvas.restore()
        if (day) {
            // Sobre fondo claro: halo gris medio y núcleo blanco
            pLed.strokeWidth = 11f; pLed.color = Color.argb(30, 70, 76, 84); canvas.drawPath(p, pLed)
            pLed.strokeWidth = 6f; pLed.color = Color.argb(70, 86, 92, 100); canvas.drawPath(p, pLed)
            pLed.strokeWidth = 2.8f; pLed.color = Color.argb(200, 122, 128, 136); canvas.drawPath(p, pLed)
            pLed.strokeWidth = 1.3f; pLed.color = Color.parseColor("#FBFCFE"); canvas.drawPath(p, pLed)
        } else {
            // Halo del LED
            pLed.strokeWidth = 11f; pLed.color = Color.argb(22, 170, 178, 188); canvas.drawPath(p, pLed)
            pLed.strokeWidth = 6f; pLed.color = Color.argb(55, 185, 192, 202); canvas.drawPath(p, pLed)
            // Núcleo
            pLed.strokeWidth = 2.8f; pLed.color = Color.argb(170, 203, 209, 218); canvas.drawPath(p, pLed)
            pLed.strokeWidth = 1.3f; pLed.color = Color.parseColor("#F2F5F9"); canvas.drawPath(p, pLed)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        buildShaders(w.toFloat(), h.toFloat())
    }

    private fun buildShaders(w: Float, h: Float) {
        if (day) {
            pBg.shader = LinearGradient(0f, 0f, 0f, h,
                Color.parseColor("#DCDFE4"), Color.parseColor("#BDC1C8"), Shader.TileMode.CLAMP)
            pSpot.shader = android.graphics.RadialGradient(w / 2f, h * 0.48f, h * 0.62f,
                Color.argb(110, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        } else {
            pBg.shader = LinearGradient(0f, 0f, 0f, h,
                Color.parseColor("#0B0B0C"), Color.parseColor("#000000"), Shader.TileMode.CLAMP)
            // Luz gris suave detrás del velocímetro
            pSpot.shader = android.graphics.RadialGradient(w / 2f, h * 0.48f, h * 0.62f,
                Color.argb(46, 160, 166, 174), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
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
        canvas.drawPath(path, pPanel)
        ledEdge(canvas, path)

        // Base inferior (controles)
        path.reset()
        path.moveTo(w * 0.30f, h); path.lineTo(w * 0.34f, h * 0.875f)
        path.lineTo(w * 0.66f, h * 0.875f); path.lineTo(w * 0.70f, h)
        pPanel.shader = LinearGradient(0f, h * 0.875f, 0f, h, Color.parseColor("#1A1B1E"), Color.parseColor("#0C0C0D"), Shader.TileMode.CLAMP)
        canvas.drawPath(path, pPanel)
        ledEdge(canvas, path)
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
        ledEdge(canvas, path)
    }

    companion object { private const val SEGMENTS = 16 }
}
