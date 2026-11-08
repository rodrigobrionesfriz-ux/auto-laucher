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
    private val colDim = Color.parseColor("#5F6369")
    private val colLabel = Color.parseColor("#D2D6DB")
    private val colStatus = context.getColor(R.color.text_dim)

    private val pFace = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pRoad = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBeam = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBand = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pInner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colBand = Color.parseColor("#7FCFA8")
    private val colBandHi = Color.parseColor("#A6E8C6")
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

    /** Foto propia del auto (vista trasera). null = usar el dibujo. */
    var carPhoto: android.graphics.Bitmap? = null
        set(v) { field = v; preparePhoto(v); invalidate() }

    /** Foto encendida + su versión apagada ya preparada (imagen incluida en la app). */
    fun setCarPhotos(on: android.graphics.Bitmap?, off: android.graphics.Bitmap?) {
        carPhoto = on
        if (on != null && off != null) photoOff = off
        invalidate()
    }

    private var photoOff: android.graphics.Bitmap? = null   // misma foto con los focos atenuados
    private var photoTransparent = false
    private val redSpots = ArrayList<FloatArray>()           // centros de luces rojas (x, y relativos, radio)

    /** Analiza la foto: fondo transparente, dónde están los focos rojos y genera la versión "apagada". */
    private fun preparePhoto(bmp: android.graphics.Bitmap?) {
        photoOff = null; redSpots.clear(); photoTransparent = false
        if (bmp == null) return
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        photoTransparent = Color.alpha(px[0]) < 20 || Color.alpha(px[w - 1]) < 20
        val off = px.copyOf()
        // Acumuladores por mitad (izquierda / derecha) para ubicar los focos
        val sx = DoubleArray(2); val sy = DoubleArray(2); val n = IntArray(2)
        for (i in px.indices) {
            val c = px[i]
            val a = Color.alpha(c); if (a < 40) continue
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            if (r > 120 && r > g * 1.7f && r > b * 1.7f) {
                // Rojo encendido: se oscurece para la versión apagada
                val f = 0.32f
                off[i] = Color.argb(a, (r * f + 20).toInt().coerceAtMost(255), (g * 0.6f).toInt(), (b * 0.6f).toInt())
                if (r > 170) {
                    val x = i % w; val y = i / w
                    val side = if (x < w / 2) 0 else 1
                    sx[side] += x.toDouble(); sy[side] += y.toDouble(); n[side]++
                }
            }
        }
        val total = n[0] + n[1]
        if (total < w * h / 400) return   // casi sin rojo: la foto no trae luces encendidas
        photoOff = android.graphics.Bitmap.createBitmap(off, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        for (side in 0..1) if (n[side] > 0) {
            redSpots.add(floatArrayOf((sx[side] / n[side] / w).toFloat(), (sy[side] / n[side] / h).toFloat()))
        }
    }

    private val pPhoto = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pMask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }
    private val pGlowRed = Paint(Paint.ANTI_ALIAS_FLAG)
    private val photoRect = RectF()

    /** Dibuja la foto (con bordes difuminados si no trae fondo transparente) y sus luces. */
    private fun drawPhoto(canvas: Canvas, bmp: android.graphics.Bitmap, l: Float, t: Float, w: Float, h: Float) {
        photoRect.set(l, t, l + w, t + h)
        val off = photoOff
        val layer = canvas.saveLayer(photoRect, null)
        if (off != null) {
            // Mezcla entre la versión apagada y la encendida según el estado de las luces
            pPhoto.alpha = 255
            canvas.drawBitmap(off, null, photoRect, pPhoto)
            if (lights > 0.01f) {
                pPhoto.alpha = (lights * 255).roundToInt()
                canvas.drawBitmap(bmp, null, photoRect, pPhoto)
                pPhoto.alpha = 255
            }
        } else {
            canvas.drawBitmap(bmp, null, photoRect, pPhoto)
        }
        if (!photoTransparent) {
            val mcx = l + w / 2; val mcy = t + h * 0.55f
            pMask.shader = RadialGradient(mcx, mcy, w * 0.52f,
                intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply { setScale(1f, (h * 0.58f) / (w * 0.52f), mcx, mcy) })
            }
            canvas.drawRect(photoRect, pMask)
        }
        canvas.restoreToCount(layer)
        if (lights > 0.01f) {
            val a = lights
            pGlowRed.shader = RadialGradient(l + w / 2, t + h * 0.97f, w * 0.55f,
                Color.argb((a * 80).toInt(), 255, 32, 32), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawOval(l - w * 0.05f, t + h * 0.88f, l + w * 1.05f, t + h * 1.06f, pGlowRed)
            val spots = if (redSpots.isNotEmpty()) redSpots else listOf(floatArrayOf(0.12f, 0.62f), floatArrayOf(0.88f, 0.62f))
            for (sp in spots) {
                val gx = l + w * sp[0]; val gy = t + h * sp[1]
                pGlowRed.shader = RadialGradient(gx, gy, w * 0.2f,
                    Color.argb((a * 110).toInt(), 255, 40, 40), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawCircle(gx, gy, w * 0.2f, pGlowRed)
            }
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
            intArrayOf(Color.parseColor("#1B1C1F"), Color.parseColor("#0B0B0C"), Color.parseColor("#000000")),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        pRing.shader = LinearGradient(0f, cy - r, 0f, cy + r,
            intArrayOf(Color.parseColor("#A4A9B0"), Color.parseColor("#2A2C30"), Color.parseColor("#6B7078")),
            null, Shader.TileMode.CLAMP)
        pRing.strokeWidth = r * 0.025f
        pInner.shader = RadialGradient(cx, cy - r * 0.2f, r * 0.75f,
            intArrayOf(Color.parseColor("#34363A"), Color.parseColor("#222326"), Color.parseColor("#151618")),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        val vy = cy + r * 0.28f
        pRoad.shader = LinearGradient(0f, vy, 0f, h.toFloat(),
            Color.argb(0, 30, 31, 34), Color.argb(242, 40, 42, 46), Shader.TileMode.CLAMP)
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
        pLine.color = Color.parseColor("#34363A"); pLine.strokeWidth = 1.5f
        canvas.drawCircle(cx, cy, r * 0.875f, pLine)
        pText.typeface = tfBold
        pText.textSize = r * 0.105f
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
                val rr = r * 0.925f
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

        // Cara interior gris oscura (estilo tablero)
        canvas.drawCircle(cx, cy, r * 0.705f, pInner)
        val vy = cy + r * 0.28f

        // Camino
        val bw = r * 0.72f
        val by = h - 2f
        path.reset()
        path.moveTo(cx - bw, by); path.lineTo(cx - r * 0.02f, vy)
        path.lineTo(cx + r * 0.02f, vy); path.lineTo(cx + bw, by); path.close()
        canvas.drawPath(path, pRoad)
        pLine.color = Color.argb(90, 200, 205, 212); pLine.strokeWidth = 1.5f
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

        // Banda ancha iluminada color menta
        val rb = r * 0.78f
        arcRect.set(cx - rb, cy - rb, cx + rb, cy + rb)
        pBand.strokeWidth = r * 0.15f
        pBand.color = colBand
        canvas.drawArc(arcRect, 135f, 270f, false, pBand)
        pBand.strokeWidth = r * 0.09f
        pBand.color = colBandHi
        canvas.drawArc(arcRect, 135f, 270f, false, pBand)
        // tramo recorrido: un poco más brillante
        val sw = 270f * shown / maxSpeed
        if (sw > 0.5f) {
            pBand.strokeWidth = r * 0.15f
            pBand.color = Color.argb(70, 255, 255, 255)
            canvas.drawArc(arcRect, 135f, sw, false, pBand)
        }
        // bordes de la banda
        pLine.color = Color.parseColor("#0C2219"); pLine.strokeWidth = r * 0.008f
        canvas.drawArc(RectF(cx - r * 0.705f, cy - r * 0.705f, cx + r * 0.705f, cy + r * 0.705f), 135f, 270f, false, pLine)
        canvas.drawArc(RectF(cx - r * 0.855f, cy - r * 0.855f, cx + r * 0.855f, cy + r * 0.855f), 135f, 270f, false, pLine)

        // Marcas blancas dentro de la banda y números
        pText.typeface = tfBold
        pText.textSize = r * 0.095f
        pText.color = colText
        pTick.strokeCap = Paint.Cap.BUTT
        var v = 0
        while (v <= maxSpeed) {
            val major = v % 20 == 0
            val a = Math.toRadians(ang(v.toFloat()).toDouble())
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val r1 = r * 0.84f; val r2 = if (major) r * 0.722f else r * 0.765f
            val tw = if (major) r * 0.034f else r * 0.022f
            pTick.color = Color.parseColor("#123326"); pTick.strokeWidth = tw + r * 0.012f
            canvas.drawLine(cx + (r1 + r * 0.004f) * ca, cy + (r1 + r * 0.004f) * sa,
                cx + (r2 - r * 0.004f) * ca, cy + (r2 - r * 0.004f) * sa, pTick)
            pTick.color = Color.WHITE; pTick.strokeWidth = tw
            canvas.drawLine(cx + r1 * ca, cy + r1 * sa, cx + r2 * ca, cy + r2 * sa, pTick)
            if (major) {
                val ly = cy + r * 0.6f * sa - (pText.descent() + pText.ascent()) / 2
                canvas.drawText(v.toString(), cx + r * 0.6f * ca, ly, pText)
            }
            v += 10
        }

        // Aguja roja con brillo
        val a = Math.toRadians(ang(shown).toDouble())
        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        val px = -sa; val py = ca
        fun needle(ri: Float, ro: Float, wi: Float, wo: Float) {
            path.reset()
            path.moveTo(cx + ri * ca + wi * px, cy + ri * sa + wi * py)
            path.lineTo(cx + ro * ca + wo * px, cy + ro * sa + wo * py)
            path.lineTo(cx + ro * ca - wo * px, cy + ro * sa - wo * py)
            path.lineTo(cx + ri * ca - wi * px, cy + ri * sa - wi * py)
            path.close()
            canvas.drawPath(path, pFill)
        }
        pFill.color = Color.argb(60, 255, 40, 20)
        needle(r * 0.40f, r * 0.88f, r * 0.06f, r * 0.025f)
        pFill.color = Color.parseColor("#FF2A14")
        needle(r * 0.42f, r * 0.86f, r * 0.03f, r * 0.008f)
        pFill.color = Color.parseColor("#FF7A66")
        needle(r * 0.45f, r * 0.84f, r * 0.008f, r * 0.003f)

        // Textos
        pText.typeface = tfNormal
        pText.color = colAccent; pText.textSize = r * 0.06f
        canvas.drawText("${bearing.roundToInt() % 360}° ${heading}", cx, cy - r * 0.36f, pText)
        pText.typeface = tfBold
        pText.color = speedColor(shown, colText); pText.textSize = r * 0.33f
        canvas.drawText(shown.roundToInt().toString(), cx, cy + r * 0.07f, pText)
        pText.typeface = tfNormal
        pText.color = colText; pText.textSize = r * 0.065f
        canvas.drawText("km/h", cx, cy + r * 0.18f, pText)
        pText.typeface = tfPlain
        pText.color = colStatus; pText.textSize = r * 0.055f
        canvas.drawText(status, cx, cy + r * 0.28f, pText)

        // Camioneta (dibujo o foto propia)
        val photo = carPhoto
        // Ancho máximo 0.86r, pero si la imagen es alta se achica para no tapar los números del dial
        val ratio = if (photo != null) photo.height.toFloat() / photo.width.coerceAtLeast(1) else 168f / 200f
        val cw = minOf(r * 0.86f, r * 0.68f / ratio)
        val ch = cw * ratio
        val l = (cx - cw / 2).toInt()
        val t = (h - ch - 2).toInt()
        if (photo != null) {
            drawPhoto(canvas, photo, l.toFloat(), t.toFloat(), cw, ch)
        } else {
            car?.setBounds(l, t, (l + cw).toInt(), (t + ch).toInt())
            car?.draw(canvas)
            if (lights > 0.01f) {
                carLights?.setBounds(l, t, (l + cw).toInt(), (t + ch).toInt())
                carLights?.alpha = (lights * 255).roundToInt()
                carLights?.draw(canvas)
            }
        }
    }
}
