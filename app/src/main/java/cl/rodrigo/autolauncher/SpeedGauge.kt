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

/**
 * Velocímetro circular: aguja animada, arco de avance, números al centro,
 * el auto con sus luces arriba y rumbo / GPS abajo.
 */
class SpeedGauge @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var maxSpeed = 180
    var limit = 100
        set(v) { field = v; invalidate() }
    var heading = "--"
        set(v) { if (field != v) { field = v; invalidate() } }
    var status = ""
        set(v) { if (field != v) { field = v; invalidate() } }

    private var shown = 0f          // valor que dibuja la aguja (animado)
    private var target = 0f
    private var lights = 0f         // 0 apagadas … 1 encendidas
    private var anim: ValueAnimator? = null
    private var lightAnim: ValueAnimator? = null

    private val startAngle = 135f
    private val sweep = 270f

    private val colText = context.getColor(R.color.text)
    private val colDim = context.getColor(R.color.text_dim)
    private val colAccent = context.getColor(R.color.accent)
    private val colWarn = context.getColor(R.color.warn)
    private val colDanger = context.getColor(R.color.danger)

    private val pBezel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pFace = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#1E2733")
    }
    private val pArc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pTick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val pNumber = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val pSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pNeedle = Paint(Paint.ANTI_ALIAS_FLAG)

    private val arcRect = RectF()
    private val needle = Path()
    private val carBase: Drawable? = context.getDrawable(R.drawable.car_base)
    private val carLights: Drawable? = context.getDrawable(R.drawable.car_lights)

    private var cx = 0f
    private var cy = 0f
    private var r = 0f

    init {
        // Barrido inicial, como el tablero al dar contacto
        post {
            val sweepUp = ValueAnimator.ofFloat(0f, maxSpeed.toFloat(), 0f).apply {
                duration = 1600
                interpolator = DecelerateInterpolator()
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            }
            anim = sweepUp
            sweepUp.start()
        }
    }

    /** Nueva velocidad: la aguja se mueve suave hasta ella (las lecturas GPS llegan cada 1 s). */
    fun setSpeed(kmh: Int) {
        val t = kmh.coerceIn(0, maxSpeed).toFloat()
        if (t == target && anim?.isRunning != true) return
        target = t
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, t).apply {
            duration = 950
            interpolator = LinearInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun setLights(on: Boolean) {
        val to = if (on) 1f else 0f
        if (to == lights && lightAnim?.isRunning != true) return
        lightAnim?.cancel()
        lightAnim = ValueAnimator.ofFloat(lights, to).apply {
            duration = 450
            addUpdateListener { lights = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        r = min(w, h) / 2f - 2f
        pBezel.strokeWidth = r * 0.05f
        pBezel.shader = LinearGradient(0f, cy - r, 0f, cy + r,
            intArrayOf(Color.parseColor("#8A97A8"), Color.parseColor("#2A323D"), Color.parseColor("#5C6878")),
            null, Shader.TileMode.CLAMP)
        pFace.shader = RadialGradient(cx, cy - r * 0.15f, r,
            intArrayOf(Color.parseColor("#1C2633"), Color.parseColor("#0C1118"), Color.parseColor("#06090D")),
            floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
        pTrack.strokeWidth = r * 0.045f
        pArc.strokeWidth = r * 0.045f
        pGlow.strokeWidth = r * 0.11f
        pLabel.textSize = r * 0.1f
        pNumber.textSize = r * 0.44f
        pSmall.textSize = r * 0.085f
    }

    private fun angleFor(v: Float) = startAngle + sweep * (v / maxSpeed)

    private fun colorFor(v: Float) = when {
        v > limit -> colDanger
        v >= limit - 10 -> colWarn
        else -> colAccent
    }

    override fun onDraw(canvas: Canvas) {
        if (r <= 0) return
        val col = colorFor(shown)

        // Cara y bisel
        canvas.drawCircle(cx, cy, r * 0.97f, pFace)
        canvas.drawCircle(cx, cy, r * 0.975f, pBezel)

        // Pista y arco de avance
        val ra = r * 0.84f
        arcRect.set(cx - ra, cy - ra, cx + ra, cy + ra)
        canvas.drawArc(arcRect, startAngle, sweep, false, pTrack)
        val sw = sweep * (shown / maxSpeed)
        if (sw > 0.5f) {
            pGlow.color = col; pGlow.alpha = 45
            canvas.drawArc(arcRect, startAngle, sw, false, pGlow)
            pArc.color = col
            canvas.drawArc(arcRect, startAngle, sw, false, pArc)
        }

        // Marcas y números
        var v = 0
        while (v <= maxSpeed) {
            val major = v % 20 == 0
            val a = Math.toRadians(angleFor(v.toFloat()).toDouble())
            val r1 = r * 0.765f
            val r2 = if (major) r * 0.68f else r * 0.72f
            val passed = v <= shown
            pTick.color = if (passed) col else if (major) colText else colDim
            pTick.strokeWidth = if (major) r * 0.022f else r * 0.012f
            canvas.drawLine(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(),
                cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), pTick)
            if (major) {
                val rl = r * 0.57f
                pLabel.color = if (v > limit) colDanger else colDim
                val ly = cy + rl * sin(a).toFloat() - (pLabel.descent() + pLabel.ascent()) / 2
                canvas.drawText(v.toString(), cx + rl * cos(a).toFloat(), ly, pLabel)
            }
            v += 10
        }

        // Aguja (solo el tramo exterior, para dejar libre el centro)
        val a = Math.toRadians(angleFor(shown).toDouble())
        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        val px = -sa; val py = ca
        val rIn = r * 0.47f; val rOut = r * 0.86f
        val wIn = r * 0.035f; val wOut = r * 0.008f
        needle.reset()
        needle.moveTo(cx + rIn * ca + wIn * px, cy + rIn * sa + wIn * py)
        needle.lineTo(cx + rOut * ca + wOut * px, cy + rOut * sa + wOut * py)
        needle.lineTo(cx + rOut * ca - wOut * px, cy + rOut * sa - wOut * py)
        needle.lineTo(cx + rIn * ca - wIn * px, cy + rIn * sa - wIn * py)
        needle.close()
        pNeedle.color = Color.parseColor("#FF5A3D"); pNeedle.alpha = 70
        canvas.save(); canvas.translate(0f, r * 0.012f); canvas.drawPath(needle, pNeedle); canvas.restore()
        pNeedle.alpha = 255
        canvas.drawPath(needle, pNeedle)

        // Auto con luces, arriba del número
        val carH = r * 0.36f
        val carW = carH * 90f / 210f
        val top = (cy - r * 0.5f).toInt()
        val left = (cx - carW / 2).toInt()
        carBase?.setBounds(left, top, (left + carW).toInt(), (top + carH).toInt())
        carBase?.draw(canvas)
        if (lights > 0.01f) {
            carLights?.setBounds(left, top, (left + carW).toInt(), (top + carH).toInt())
            carLights?.alpha = (lights * 255).roundToInt()
            carLights?.draw(canvas)
        }

        // Velocidad en números
        val shownInt = shown.roundToInt()
        pNumber.color = when {
            shownInt > limit -> colDanger
            shownInt >= limit - 10 -> colWarn
            else -> colText
        }
        canvas.drawText(shownInt.toString(), cx, cy + r * 0.24f, pNumber)
        pSmall.color = colAccent
        canvas.drawText("km/h", cx, cy + r * 0.37f, pSmall)

        // Rumbo y estado del GPS en la abertura inferior
        pSmall.color = colText
        pSmall.isFakeBoldText = true
        canvas.drawText(heading, cx, cy + r * 0.62f, pSmall)
        pSmall.isFakeBoldText = false
        pSmall.color = colDim
        val st = pSmall.textSize
        pSmall.textSize = st * 0.8f
        canvas.drawText(status, cx, cy + r * 0.74f, pSmall)
        pSmall.textSize = st
    }
}
