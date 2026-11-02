package cl.rodrigo.autolauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Contrato común de los velocímetros (diseño clásico y cockpit). */
interface Gauge {
    var limit: Int
    var heading: String
    var status: String
    fun setSpeed(kmh: Int)
    fun setLights(on: Boolean)
    fun setBearing(deg: Float) {}
}

/** Fuentes incluidas en la app. */
object Fonts {
    private var bold: Typeface? = null
    private var medium: Typeface? = null
    fun bold(ctx: Context): Typeface = bold ?: (try {
        Typeface.createFromAsset(ctx.assets, "fonts/orbitron_700.ttf")
    } catch (e: Exception) { Typeface.DEFAULT_BOLD }).also { bold = it }
    fun medium(ctx: Context): Typeface = medium ?: (try {
        Typeface.createFromAsset(ctx.assets, "fonts/orbitron_500.ttf")
    } catch (e: Exception) { Typeface.DEFAULT }).also { medium = it }
}

/**
 * Velocímetro del diseño cockpit: 0 a 220 km/h, túnel y camino en perspectiva
 * y la camioneta vista desde atrás con sus luces.
 */
class CockpitGauge @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs), Gauge {

    private val maxSpeed = 220
    override var limit = 100
        set(v) { field = v; invalidate() }
    override var heading = "--"
        set(v) { if (field != v) { field = v; invalidate() } }
    override var status = ""
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Se llama en cada cuadro con la velocidad mostrada (para las barras LED del marco). */
    var onShown: ((Float) -> Unit)? = null

    private var shown = 0f
    private var target = 0f
    private var lights = 0f
    private var anim: ValueAnimator? = null
    private var lightAnim: ValueAnimator? = null

    private val colText = context.getColor(R.color.text)
    private val colAccent = context.getColor(R.color.accent)
    private val colWarn = context.getColor(R.color.warn)
    private val colDanger = context.getColor(R.color.danger)
    private val colDim = Color.parseColor("#5E6B7A")
    private val colLabel = Color.parseColor("#9AA6B4")
    private val colStatus = context.getColor(R.color.text_dim)

    private val pFace = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pRoad = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBeam = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pArc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pTick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tfBold = Fonts.bold(context)
    private val tfNormal = Fonts.medium(context)
    private val tfPlain = Typeface.create("sans-serif", Typeface.NORMAL)

    // Brújula
    private var bearing = 0f
    private var bearingAnim: ValueAnimator? = null
    private val compass = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")

    private val arcRect = RectF()
    private val path = Path()
    private val car: Drawable? = context.getDrawable(R.drawable.suv_rear)
    private val carLights: Drawable? = context.getDrawable(R.drawable.suv_rear_lights)

    private var cx = 0f
    private var cy = 0f
    private var r = 0f

    init {
        post {
            anim = ValueAnimator.ofFloat(0f, maxSpeed.toFloat(), 0f).apply {
                duration = 1800
                interpolator = DecelerateInterpolator()
                addUpdateListener { shown = it.animatedValue as Float; changed() }
                start()
            }
        }
    }

    private fun changed() {
        onShown?.invoke(shown / maxSpeed)
        invalidate()
    }

    override fun setSpeed(kmh: Int) {
        val t = kmh.coerceIn(0, maxSpeed).toFloat()
        if (t == target && anim?.isRunning != true) return
        target = t
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, t).apply {
            duration = 950
            interpolator = LinearInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; changed() }
            start()
        }
    }

    override fun setLights(on: Boolean) {
        val to = if (on) 1f else 0f
        if (to == lights && lightAnim?.isRunning != true) return
        lightAnim?.cancel()
        lightAnim = ValueAnimator.ofFloat(lights, to).apply {
            duration = 450
            addUpdateListener { lights = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun setBearing(deg: Float) {
        var to = ((deg % 360) + 360) % 360
        val from = bearing
        // Girar por el camino más corto
        var diff = to - from
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        if (kotlin.math.abs(diff) < 1f) return
        to = from + diff
        bearingAnim?.cancel()
        bearingAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener { bearing = ((it.animatedValue as Float) % 360 + 360) % 360; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        r = min(w / 2f, h * 0.47f) - 4f
        cy = r + 6f
        pFace.shader = RadialGradient(cx, cy, r,
            intArrayOf(Color.parseColor("#0E1622"), Color.parseColor("#080C12"), Color.parseColor("#05070A")),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        pRing.shader = LinearGradient(0f, cy - r, 0f, cy + r,
            intArrayOf(Color.parseColor("#7E8B9C"), Color.parseColor("#232B35"), Color.parseColor("#4A5565")),
            null, Shader.TileMode.CLAMP)
        pRing.strokeWidth = r * 0.025f
        val vy = cy + r * 0.28f
        pRoad.shader = LinearGradient(0f, vy, 0f, h.toFloat(),
            Color.argb(0, 20, 28, 38), Color.argb(242, 30, 40, 52), Shader.TileMode.CLAMP)
        pBeam.shader = LinearGradient(0f, h - r * 0.45f, 0f, vy,
            Color.argb(77, 255, 243, 196), Color.argb(0, 255, 243, 196), Shader.TileMode.CLAMP)
    }

    private fun ang(v: Float) = 135f + 270f * v / maxSpeed

    private fun speedColor(v: Float, base: Int) = when {
        v > limit -> colDanger
        v >= limit - 10 -> colWarn
        else -> base
    }

    /** Anillo exterior de brújula: gira para que la dirección de avance quede arriba. */
    private fun drawCompass(canvas: Canvas) {
        pLine.color = Color.parseColor("#2A3442"); pLine.strokeWidth = 1.5f
        canvas.drawCircle(cx, cy, r * 0.875f, pLine)
        pText.typeface = tfNormal
        pText.textSize = r * 0.058f
        var d = 0
        while (d < 360) {
            val rel = Math.toRadians((d - bearing - 90).toDouble())
            val ca = cos(rel).toFloat(); val sa = sin(rel).toFloat()
            if (d % 45 == 0) {
                val name = compass[d / 45]
                var diff = kotlin.math.abs(((d - bearing) % 360 + 360) % 360)
                if (diff > 180) diff = 360 - diff
                pText.color = when {
                    diff < 22.5f -> colAccent
                    name == "N" -> colDanger
                    else -> colLabel
                }
                val rr = r * 0.93f
                val ly = cy + rr * sa - (pText.descent() + pText.ascent()) / 2
                canvas.save()
                canvas.rotate((d - bearing), cx + rr * ca, ly + (pText.descent() + pText.ascent()) / 2)
                canvas.drawText(name, cx + rr * ca, ly, pText)
                canvas.restore()
            } else {
                val major = d % 15 == 0
                pTick.color = if (major) colLabel else colDim
                pTick.strokeWidth = if (major) r * 0.008f else r * 0.005f
                val r1 = r * 0.965f; val r2 = if (major) r * 0.905f else r * 0.935f
                canvas.drawLine(cx + r1 * ca, cy + r1 * sa, cx + r2 * ca, cy + r2 * sa, pTick)
            }
            d += 5
        }
        // Marcador fijo arriba
        path.reset()
        path.moveTo(cx, cy - r * 0.885f)
        path.lineTo(cx - r * 0.03f, cy - r * 0.84f)
        path.lineTo(cx + r * 0.03f, cy - r * 0.84f)
        path.close()
        pFill.color = colAccent
        canvas.drawPath(path, pFill)
    }

    override fun onDraw(canvas: Canvas) {
        if (r <= 0) return
        val h = height.toFloat()
        val col = speedColor(shown, colAccent)

        // Cara y anillo
        canvas.drawCircle(cx, cy, r, pFace)
        canvas.drawCircle(cx, cy, r * 0.988f, pRing)
        drawCompass(canvas)

        // Túnel
        val vy = cy + r * 0.28f
        canvas.save()
        path.reset(); path.addCircle(cx, cy, r * 0.54f, Path.Direction.CW)
        canvas.clipPath(path)
        pLine.strokeWidth = 1f
        for (k in 1..7) {
            pLine.color = Color.argb(((0.05f + 0.02f * k) * 255).toInt(), 90, 140, 200)
            canvas.drawCircle(cx, vy, r * 0.09f * k * k / 3.2f, pLine)
        }
        pLine.color = Color.argb(18, 90, 140, 200)
        for (k in 0 until 24) {
            val a = k * Math.PI / 12
            canvas.drawLine(cx, vy, cx + cos(a).toFloat() * r, vy + sin(a).toFloat() * r, pLine)
        }
        canvas.restore()

        // Camino
        val bw = r * 0.72f
        val by = h - 2f
        path.reset()
        path.moveTo(cx - bw, by); path.lineTo(cx - r * 0.02f, vy)
        path.lineTo(cx + r * 0.02f, vy); path.lineTo(cx + bw, by); path.close()
        canvas.drawPath(path, pRoad)
        pLine.color = Color.argb(128, 62, 224, 180); pLine.strokeWidth = 1.5f
        canvas.drawLine(cx - bw, by, cx - r * 0.02f, vy, pLine)
        canvas.drawLine(cx + bw, by, cx + r * 0.02f, vy, pLine)

        // Haz de los focos hacia adelante
        if (lights > 0.01f) {
            pBeam.alpha = (lights * 255).roundToInt()
            path.reset()
            path.moveTo(cx - r * 0.32f, by - r * 0.42f); path.lineTo(cx - r * 0.05f, vy)
            path.lineTo(cx + r * 0.05f, vy); path.lineTo(cx + r * 0.32f, by - r * 0.42f); path.close()
            canvas.drawPath(path, pBeam)
        }

        // Pista y arco de velocidad
        val ra = r * 0.81f
        arcRect.set(cx - ra, cy - ra, cx + ra, cy + ra)
        pArc.color = Color.parseColor("#1A222D"); pArc.strokeWidth = r * 0.035f
        canvas.drawArc(arcRect, 135f, 270f, false, pArc)
        val sw = 270f * shown / maxSpeed
        if (sw > 0.5f) {
            pArc.color = col; pArc.alpha = 51; pArc.strokeWidth = r * 0.085f
            canvas.drawArc(arcRect, 135f, sw, false, pArc)
            pArc.alpha = 255; pArc.strokeWidth = r * 0.035f
            canvas.drawArc(arcRect, 135f, sw, false, pArc)
        }

        // Marcas y números
        pText.typeface = tfNormal
        pText.textSize = r * 0.062f
        var v = 0
        while (v <= maxSpeed) {
            val major = v % 20 == 0
            val a = Math.toRadians(ang(v.toFloat()).toDouble())
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val r1 = r * 0.765f; val r2 = if (major) r * 0.69f else r * 0.725f
            pTick.color = if (v <= shown) col else if (major) colText else colDim
            pTick.strokeWidth = if (major) r * 0.016f else r * 0.009f
            canvas.drawLine(cx + r1 * ca, cy + r1 * sa, cx + r2 * ca, cy + r2 * sa, pTick)
            if (major) {
                pText.color = if (v > limit) colDanger else colLabel
                val ly = cy + r * 0.61f * sa - (pText.descent() + pText.ascent()) / 2
                canvas.drawText(v.toString(), cx + r * 0.61f * ca, ly, pText)
            }
            v += 10
        }

        // Aguja
        val a = Math.toRadians(ang(shown).toDouble())
        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        val px = -sa; val py = ca
        val ri = r * 0.55f; val ro = r * 0.83f; val wi = r * 0.028f; val wo = r * 0.007f
        path.reset()
        path.moveTo(cx + ri * ca + wi * px, cy + ri * sa + wi * py)
        path.lineTo(cx + ro * ca + wo * px, cy + ro * sa + wo * py)
        path.lineTo(cx + ro * ca - wo * px, cy + ro * sa - wo * py)
        path.lineTo(cx + ri * ca - wi * px, cy + ri * sa - wi * py)
        path.close()
        pFill.color = Color.parseColor("#FF5A3D")
        canvas.drawPath(path, pFill)

        // Textos
        pText.typeface = tfNormal
        pText.color = colAccent; pText.textSize = r * 0.06f
        canvas.drawText("${bearing.roundToInt() % 360}° ${heading}", cx, cy - r * 0.36f, pText)
        pText.typeface = tfBold
        pText.color = speedColor(shown, colText); pText.textSize = r * 0.33f
        canvas.drawText(shown.roundToInt().toString(), cx, cy + r * 0.07f, pText)
        pText.typeface = tfNormal
        pText.color = colAccent; pText.textSize = r * 0.06f
        canvas.drawText("km/h", cx, cy + r * 0.18f, pText)
        pText.typeface = tfPlain
        pText.color = colStatus; pText.textSize = r * 0.055f
        canvas.drawText(status, cx, cy + r * 0.28f, pText)

        // Camioneta
        val cw = r * 0.86f
        val ch = cw * 168f / 200f
        val l = (cx - cw / 2).toInt()
        val t = (h - ch - 2).toInt()
        car?.setBounds(l, t, (l + cw).toInt(), (t + ch).toInt())
        car?.draw(canvas)
        if (lights > 0.01f) {
            carLights?.setBounds(l, t, (l + cw).toInt(), (t + ch).toInt())
            carLights?.alpha = (lights * 255).roundToInt()
            carLights?.draw(canvas)
        }
    }
}
