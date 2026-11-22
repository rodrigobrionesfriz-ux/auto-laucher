package cl.rodrigo.autolauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.ProgressBar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Tarjeta "Neón": trapecio con la esquina superior izquierda inclinada, fondo azul
 * profundo y borde luminoso azul, como los paneles en perspectiva del diseño de referencia.
 */
class NeonCard @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    private val dp = resources.displayMetrics.density
    private val slant: Float
    private val path = Path()
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 9 * dp; color = Color.argb(60, 61, 123, 255)
        pathEffect = CornerPathEffect(22 * dp)
    }
    private val pEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.2f * dp
        pathEffect = CornerPathEffect(22 * dp)
    }
    private val pShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0); pathEffect = CornerPathEffect(22 * dp)
    }

    init {
        setWillNotDraw(false)
        val a = ctx.obtainStyledAttributes(attrs, R.styleable.NeonCard)
        slant = a.getDimension(R.styleable.NeonCard_slant, 18 * dp)
        a.recycle()
        pFill.pathEffect = CornerPathEffect(22 * dp)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val i = 5 * dp
        path.reset()
        path.moveTo(slant + i, i)
        path.lineTo(w - i, i + slant * 0.25f)
        path.lineTo(w - i, h - i)
        path.lineTo(i, h - i)
        path.close()
        pFill.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(Color.parseColor("#F21C2550"), Color.parseColor("#F2111735"), Color.parseColor("#F20B0F24")),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        pEdge.shader = LinearGradient(0f, h.toFloat(), w.toFloat(), 0f,
            intArrayOf(Color.parseColor("#5B8CFF"), Color.parseColor("#2E4FD8"), Color.parseColor("#7FA6FF")),
            null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        c.save(); c.translate(0f, 7 * dp); c.drawPath(path, pShadow); c.restore()
        c.drawPath(path, pGlow)
        c.drawPath(path, pFill)
        c.drawPath(path, pEdge)
        super.onDraw(c)
    }
}

/** Barra de progreso de la canción dibujada como anillo alrededor de la carátula. */
class ProgressRing @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    ProgressBar(ctx, attrs, 0) {
    private val dp = resources.displayMetrics.density
    private val pTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4 * dp; color = Color.argb(60, 255, 255, 255)
    }
    private val pArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5 * dp; strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FF6A3D")
    }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF6A3D") }
    private val rect = RectF()

    init { isIndeterminate = false; max = 1000 }

    @Synchronized
    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val m = 6 * dp
        rect.set((width - s) / 2 + m, (height - s) / 2 + m, (width + s) / 2 - m, (height + s) / 2 - m)
        c.drawArc(rect, 0f, 360f, false, pTrack)
        val sweep = 360f * progress / max.coerceAtLeast(1)
        if (sweep > 0.5f) {
            c.drawArc(rect, -90f, sweep, false, pArc)
            val a = Math.toRadians((sweep - 90).toDouble())
            val r = rect.width() / 2
            c.drawCircle(rect.centerX() + r * cos(a).toFloat(), rect.centerY() + r * sin(a).toFloat(), 5 * dp, pDot)
        }
    }
}

/**
 * Velocímetro compacto del diseño Neón: arco de 240° (0-220 km/h), aguja naranja,
 * velocidad digital al centro y la camioneta debajo (luces según las del vehículo).
 */
class NeonGauge @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    android.view.View(ctx, attrs), Gauge {
    override var limit = 100
    override var heading = ""
        set(v) { field = v; invalidate() }
    override var status = ""
        set(v) { field = v; invalidate() }

    private val dp = resources.displayMetrics.density
    private var target = 0f
    private var shown = 0f
    private var lights = 0f
    private var lightsOn = false
    private var photoOn: Bitmap? = null
    private var photoOff: Bitmap? = null

    private val tfBold = Fonts.bold(ctx)
    private val tfMid = Fonts.medium(ctx)
    private val pArc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pTick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pImg = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val MAX = 220f
    private val START = 150f
    private val SWEEP = 240f

    override fun setSpeed(kmh: Int) { target = kmh.toFloat().coerceIn(0f, MAX); postInvalidateOnAnimation() }
    override fun setLights(on: Boolean) { lightsOn = on; postInvalidateOnAnimation() }

    fun setCarPhotos(on: Bitmap?, off: Bitmap?) { photoOn = on; photoOff = off ?: on; invalidate() }
    fun setCarPhoto(b: Bitmap?) { photoOn = b; photoOff = b; invalidate() }

    private fun ang(v: Float) = START + SWEEP * v / MAX

    override fun onDraw(c: Canvas) {
        // Animación suave de aguja y luces
        shown += (target - shown) * 0.15f
        lights += ((if (lightsOn) 1f else 0f) - lights) * 0.15f
        if (kotlin.math.abs(target - shown) > 0.2f || kotlin.math.abs((if (lightsOn) 1f else 0f) - lights) > 0.01f)
            postInvalidateOnAnimation()

        val w = width.toFloat(); val h = height.toFloat()
        val gaugeH = h * 0.56f
        val r = min(w * 0.40f, gaugeH * 0.52f)
        val cx = w / 2; val cy = r + 8 * dp
        rect.set(cx - r, cy - r, cx + r, cy + r)

        // Arco exterior blanco fino y arco de velocidad con degradado
        pArc.strokeWidth = 2.5f * dp; pArc.shader = null; pArc.color = Color.argb(200, 230, 236, 245)
        c.drawArc(rect, START, SWEEP, false, pArc)
        val inner = RectF(rect); inner.inset(r * 0.09f, r * 0.09f)
        pArc.strokeWidth = r * 0.07f; pArc.color = Color.argb(35, 255, 255, 255)
        c.drawArc(inner, START, SWEEP, false, pArc)
        if (shown > 0.5f) {
            pArc.shader = android.graphics.SweepGradient(cx, cy,
                intArrayOf(Color.parseColor("#5B8CFF"), Color.parseColor("#FF6A3D"), Color.parseColor("#5B8CFF")),
                floatArrayOf(0f, 0.6f, 1f))
            c.save(); c.rotate(START - 10, cx, cy)
            c.drawArc(inner, 10f, SWEEP * shown / MAX, false, pArc)
            c.restore(); pArc.shader = null
        }

        // Marcas y números
        pText.typeface = tfBold; pText.textSize = r * 0.13f; pText.color = Color.parseColor("#E6ECF5")
        var v = 0
        while (v <= MAX) {
            val a = Math.toRadians(ang(v.toFloat()).toDouble())
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val major = v % 20 == 0
            pTick.strokeWidth = if (major) 2.2f * dp else 1.2f * dp
            pTick.color = if (major) Color.parseColor("#E6ECF5") else Color.argb(140, 230, 236, 245)
            val r1 = r * 0.94f; val r2 = if (major) r * 0.80f else r * 0.86f
            c.drawLine(cx + r1 * ca, cy + r1 * sa, cx + r2 * ca, cy + r2 * sa, pTick)
            if (major && v % 40 == 0 || v == 220) {
                val rn = r * 0.64f
                c.drawText(v.toString(), cx + rn * ca, cy + rn * sa - (pText.descent() + pText.ascent()) / 2, pText)
            }
            v += 10
        }

        // Velocidad digital
        pText.typeface = tfBold; pText.textSize = r * 0.42f
        pText.color = when {
            shown > limit -> Color.parseColor("#FF5A5F")
            shown > limit - 10 -> Color.parseColor("#FFB547")
            else -> Color.WHITE
        }
        c.drawText(shown.roundToInt().toString(), cx, cy + r * 0.12f, pText)
        pText.typeface = tfMid; pText.textSize = r * 0.12f; pText.color = Color.parseColor("#9FB0CC")
        c.drawText("km/h", cx, cy + r * 0.32f, pText)
        if (heading.isNotEmpty()) {
            pText.textSize = r * 0.11f; pText.color = Color.parseColor("#7FA6FF")
            c.drawText(heading, cx, cy - r * 0.32f, pText)
        }

        // Aguja naranja
        val a = Math.toRadians(ang(shown).toDouble())
        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        pTick.strokeWidth = 4 * dp; pTick.color = Color.argb(70, 255, 106, 61)
        c.drawLine(cx + r * 0.50f * ca, cy + r * 0.50f * sa, cx + r * 0.90f * ca, cy + r * 0.90f * sa, pTick)
        pTick.strokeWidth = 2.2f * dp; pTick.color = Color.parseColor("#FF6A3D")
        c.drawLine(cx + r * 0.50f * ca, cy + r * 0.50f * sa, cx + r * 0.90f * ca, cy + r * 0.90f * sa, pTick)

        // Estado GPS bajo el arco
        if (status.isNotEmpty()) {
            pText.typeface = null; pText.textSize = 10 * dp; pText.color = Color.parseColor("#7C8AA5")
            c.drawText(status, cx, cy + r * 0.62f, pText)
        }

        // Camioneta
        val on = photoOn; val off = photoOff ?: on
        if (on != null) {
            val top = gaugeH + 4 * dp
            val availH = h - top
            val ratio = on.height.toFloat() / on.width
            var pw = w * 0.80f; var ph = pw * ratio
            if (ph > availH) { ph = availH; pw = ph / ratio }
            val dst = RectF(cx - pw / 2, h - ph, cx + pw / 2, h)
            pImg.alpha = 255
            c.drawBitmap(off!!, null, dst, pImg)
            if (lights > 0.01f && on !== off) { pImg.alpha = (lights * 255).roundToInt(); c.drawBitmap(on, null, dst, pImg) }
        }
    }
}
