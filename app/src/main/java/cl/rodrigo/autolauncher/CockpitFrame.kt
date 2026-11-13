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

    /** Modo día: fondo y paneles en gris claro con textos oscuros. */
    var day = false
        set(v) {
            if (field != v) {
                field = v
                if (width > 0) buildShaders(width.toFloat(), height.toFloat())
                invalidate()
            }
        }

    /** Fondo aurora: degradado violeta y azul con destellos (usa los paneles claros). */
    var aurora = false
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
            buildDayBg(w.toInt(), h.toInt())
        } else {
            pBg.shader = LinearGradient(0f, 0f, 0f, h,
                Color.parseColor("#0B0B0C"), Color.parseColor("#000000"), Shader.TileMode.CLAMP)
            // Luz gris suave detrás del velocímetro
            pSpot.shader = android.graphics.RadialGradient(w / 2f, h * 0.48f, h * 0.62f,
                Color.argb(46, 160, 166, 174), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            bgDay?.recycle(); bgDay = null
        }
    }

    /** Fondo claro con textura cepillada en diagonal y viñeta, pre-renderizado una sola vez. */
    private var bgDay: android.graphics.Bitmap? = null
    private fun buildDayBg(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (aurora) { buildAuroraBg(w, h); return }
        bgDay?.recycle()
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val wf = w.toFloat(); val hf = h.toFloat()
        val p = Paint()
        // Degradado base
        p.shader = LinearGradient(0f, 0f, 0f, hf,
            Color.parseColor("#DEE1E6"), Color.parseColor("#BCC0C7"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, wf, hf, p)
        p.shader = null
        // Trama cepillada en diagonal: surco claro + sombra, efecto relieve
        val pl = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
        var k = -hf
        while (k < wf + hf) {
            pl.color = Color.argb(22, 255, 255, 255)
            c.drawLine(k, 0f, k + hf, hf, pl)
            pl.color = Color.argb(14, 40, 44, 50)
            c.drawLine(k + 1.6f, 0f, k + hf + 1.6f, hf, pl)
            k += 7f
        }
        // Luz amplia arriba al centro
        p.shader = android.graphics.RadialGradient(wf / 2f, hf * 0.30f, hf * 0.85f,
            Color.argb(60, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, wf, hf, p)
        // Viñeta: bordes y esquinas levemente más oscuros
        p.shader = android.graphics.RadialGradient(wf / 2f, hf * 0.45f, wf * 0.62f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(56, 30, 34, 40)),
            floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, wf, hf, p)
        p.shader = null
        bgDay = bmp
    }

    /** Fondo aurora: violeta a azul en diagonal, con brillos rosa y celeste y haces de luz. */
    private fun buildAuroraBg(w: Int, h: Int) {
        bgDay?.recycle()
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val wf = w.toFloat(); val hf = h.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Degradado base en diagonal
        p.shader = LinearGradient(0f, 0f, wf, hf,
            intArrayOf(Color.parseColor("#6E4FD4"), Color.parseColor("#4C5BDA"), Color.parseColor("#2E7FD8")),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, wf, hf, p)
        // Brillo rosado arriba a la izquierda
        p.shader = android.graphics.RadialGradient(wf * 0.18f, hf * 0.12f, hf * 0.75f,
            Color.argb(95, 255, 160, 235), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, wf, hf, p)
        // Brillo celeste abajo a la derecha
        p.shader = android.graphics.RadialGradient(wf * 0.86f, hf * 0.82f, hf * 0.85f,
            Color.argb(85, 90, 220, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, wf, hf, p)
        p.shader = null
        // Haces de luz diagonales
        val pl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        c.save()
        c.rotate(-20f, wf * 0.5f, hf * 0.5f)
        for ((i, fx) in floatArrayOf(0.22f, 0.46f, 0.68f, 0.86f).withIndex()) {
            pl.strokeWidth = hf * (if (i % 2 == 0) 0.10f else 0.045f)
            pl.color = Color.argb(if (i % 2 == 0) 20 else 34, 255, 255, 255)
            c.drawLine(wf * fx - hf * 0.3f, -hf * 0.3f, wf * fx + hf * 0.3f, hf * 1.3f, pl)
        }
        c.restore()
        // Penumbra suave abajo para asentar los controles
        p.shader = LinearGradient(0f, hf * 0.72f, 0f, hf,
            Color.TRANSPARENT, Color.argb(70, 18, 16, 48), Shader.TileMode.CLAMP)
        c.drawRect(0f, hf * 0.72f, wf, hf, p)
        p.shader = null
        bgDay = bmp
    }

    private val pSpot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLines = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f; color = Color.argb(10, 255, 255, 255) }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val bd = bgDay
        if (day && bd != null) canvas.drawBitmap(bd, 0f, 0f, null)
        else canvas.drawRect(0f, 0f, w, h, pBg)
        canvas.drawRect(0f, 0f, w, h, pSpot)
        val pw = w * 0.29f
        panel(canvas, w, h, pw, true)
        panel(canvas, w, h, pw, false)

        // La hora y los controles del reproductor van flotando, sin marco.
    }

    private fun panel(canvas: Canvas, w: Float, h: Float, pw: Float, left: Boolean) {
        fun x(v: Float) = if (left) v else w - v
        path.reset()
        path.moveTo(x(18f), h * 0.13f); path.lineTo(x(pw * 0.78f), h * 0.10f)
        path.lineTo(x(pw), h * 0.20f); path.lineTo(x(pw), h * 0.80f)
        path.lineTo(x(pw * 0.78f), h * 0.88f); path.lineTo(x(18f), h * 0.86f); path.close()
        pPanel.shader = if (aurora)
            // Panel "vidrio": blanco translúcido que deja ver la aurora
            LinearGradient(x(0f), 0f, x(pw), 0f,
                Color.argb(222, 246, 248, 252), Color.argb(208, 232, 236, 245), Shader.TileMode.CLAMP)
        else if (day)
            LinearGradient(x(0f), 0f, x(pw), 0f,
                Color.parseColor("#F0F2F5"), Color.parseColor("#E1E4E9"), Shader.TileMode.CLAMP)
        else
            LinearGradient(x(0f), 0f, x(pw), 0f,
                Color.parseColor("#1C1D20"), Color.parseColor("#0D0D0F"), Shader.TileMode.CLAMP)
        canvas.drawPath(path, pPanel)
        // Textura de líneas diagonales muy suaves dentro del panel
        pLines.color = if (day) Color.argb(12, 0, 0, 0) else Color.argb(10, 255, 255, 255)
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
