package cl.rodrigo.autolauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Tacómetro estimado (1 a 6 x1000 rpm) para caja automática de 5 marchas tipo Mercedes 722.6.
 * La radio no lee el motor, así que las rpm se calculan con la velocidad y la marcha simulada.
 * Referencia: 100 km/h en 5ª = 2.000 rpm.
 */
class TachView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    // Relaciones 722.6 (5 marchas)
    private val ratios = floatArrayOf(3.951f, 2.423f, 1.486f, 1.000f, 0.833f)
    // rpm por km/h por unidad de relación, calibrado con 100 km/h = 2000 rpm en 5ª
    private val k = 2000f / (100f * 0.833f)
    // Cambios ascendentes (km/h) a ritmo normal; bajada con 6 km/h de margen
    private val up = floatArrayOf(20f, 38f, 58f, 75f)
    private val idle = 750f
    private val maxRpm = 6000f

    var gear = 1
        private set
    var rpm = idle
        private set
    private var shownRpm = idle
    private var lastSpeed = 0f
    private var lastAt = 0L
    private var accel = 0f   // km/h por segundo, suavizado

    fun setSpeed(kmh: Float) {
        val now = SystemClock.elapsedRealtime()
        if (lastAt != 0L) {
            val dt = (now - lastAt) / 1000f
            if (dt > 0.01f) {
                val a = (kmh - lastSpeed) / dt
                accel += (a - accel) * 0.1f
            }
        }
        lastSpeed = kmh; lastAt = now

        // Aceleración fuerte: la caja estira los cambios (hasta 60 % más)
        val factor = 1f + ((accel - 3f) / 6f).coerceIn(0f, 0.6f)
        if (kmh < 3f) gear = 1
        while (gear < 5 && kmh > up[gear - 1] * factor) gear++
        while (gear > 1 && kmh < up[gear - 2] - 6f) gear--

        rpm = max(idle, kmh * k * ratios[gear - 1]).coerceAtMost(maxRpm)
        shownRpm += (rpm - shownRpm) * 0.25f
        invalidate()
    }

    private val pSeg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pBox = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.parseColor("#3A3D42")
    }
    private val rect = RectF()
    private val bold = Fonts.bold(context)
    private val medium = Fonts.medium(context)
    private val dp = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val boxW = 50 * dp
        val barL = 4 * dp
        val barR = w - boxW - 10 * dp
        val barT = 8 * dp
        val barB = 30 * dp

        // Barra LED: 30 segmentos de 200 rpm
        val n = 30
        val segW = (barR - barL) / n
        val lit = (shownRpm / maxRpm * n).roundToInt()
        for (i in 0 until n) {
            val v = (i + 1) * 200f
            val on = i < lit
            pSeg.color = when {
                v > 5000 -> if (on) Color.parseColor("#FF4A3D") else Color.argb(70, 255, 74, 61)
                v > 4000 -> if (on) Color.parseColor("#FFB547") else Color.argb(26, 255, 255, 255)
                else -> if (on) Color.parseColor("#A6E8C6") else Color.argb(26, 255, 255, 255)
            }
            // los segmentos crecen un poco hacia la derecha
            val grow = (barB - barT) * (0.55f + 0.45f * i / n)
            rect.set(barL + i * segW + 1, barB - grow, barL + (i + 1) * segW - 1.5f, barB)
            canvas.drawRoundRect(rect, 1.5f, 1.5f, pSeg)
        }

        // Números 1..6
        pText.typeface = bold
        pText.textSize = 12 * dp
        for (i in 1..6) {
            val x = barL + (barR - barL) * i * 1000f / maxRpm
            pText.color = if (i >= 5) Color.parseColor("#FF5A5F") else Color.parseColor("#D2D6DB")
            canvas.drawText(i.toString(), x.coerceAtMost(barR - 4 * dp), barB + 15 * dp, pText)
        }

        // Texto inferior: rpm estimadas
        pText.typeface = medium
        pText.textSize = 10 * dp
        pText.color = Color.parseColor("#8996A5")
        pText.textAlign = Paint.Align.LEFT
        val r = (shownRpm / 10).roundToInt() * 10
        canvas.drawText(String.format(Locale("es", "CL"), "x1000 rpm · %,d (estimado)", r), barL, h - 6 * dp, pText)
        pText.textAlign = Paint.Align.CENTER

        // Caja de la marcha
        rect.set(w - boxW, 6 * dp, w - 2 * dp, h - 6 * dp)
        pBox.color = Color.parseColor("#141517")
        canvas.drawRoundRect(rect, 8 * dp, 8 * dp, pBox)
        canvas.drawRoundRect(rect, 8 * dp, 8 * dp, pStroke)
        pText.typeface = medium; pText.textSize = 11 * dp; pText.color = Color.parseColor("#3EE0B4")
        canvas.drawText("D", rect.centerX(), rect.top + 16 * dp, pText)
        pText.typeface = bold; pText.textSize = 28 * dp; pText.color = Color.parseColor("#F2F5F8")
        canvas.drawText(gear.toString(), rect.centerX(), rect.bottom - 9 * dp, pText)
    }
}
