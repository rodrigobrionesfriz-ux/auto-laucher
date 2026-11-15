package cl.rodrigo.autolauncher

import android.content.Context
import android.graphics.Color
import android.location.Location
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.io.File

/**
 * Mapa circular del mismo tamaño que el velocímetro.
 * Gira con la dirección de avance (el auto siempre apunta hacia arriba) y sigue el GPS.
 */
class MapPanel(ctx: Context) : FrameLayout(ctx) {

    private val map: MapView
    private val speedBadge: SpeedBadge
    private val heading: HeadingOverlay
    private var lastZoom = 0.0
    private var hasFix = false
    private var darkFilter: android.graphics.ColorFilter? = null

    /** true = mapa oscuro (colores invertidos), false = colores normales para el día. */
    fun setDark(dark: Boolean) {
        val o = map.overlayManager.tilesOverlay
        o.setColorFilter(if (dark) darkFilter else null)
        o.loadingBackgroundColor = if (dark) Color.parseColor("#0B0B0C") else Color.parseColor("#E8E6E1")
        o.loadingLineColor = if (dark) Color.parseColor("#1A1B1E") else Color.parseColor("#D5D3CE")
        map.invalidate()
    }

    init {
        val conf = Configuration.getInstance()
        conf.userAgentValue = "AutoLauncher/1.0 (" + ctx.packageName + ")"
        conf.osmdroidBasePath = File(ctx.filesDir, "osmdroid")
        conf.osmdroidTileCache = File(ctx.cacheDir, "tiles")
        conf.tileFileSystemCacheMaxBytes = 200L * 1024 * 1024

        map = MapView(ctx).apply {
            setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            setBackgroundColor(Color.parseColor("#0B0B0C"))
            controller.setZoom(16.5)
            controller.setCenter(GeoPoint(-37.795, -72.716))   // Angol, hasta tener GPS
        }
        // Mapa estándar de OpenStreetMap con colores invertidos y atenuados: queda oscuro
        val m = android.graphics.ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f))
        val tone = android.graphics.ColorMatrix().apply { setSaturation(0.35f) }
        m.postConcat(tone)
        m.postConcat(android.graphics.ColorMatrix(floatArrayOf(
            0.85f, 0f, 0f, 0f, 0f,
            0f, 0.9f, 0f, 0f, 4f,
            0f, 0f, 0.95f, 0f, 10f,
            0f, 0f, 0f, 1f, 0f)))
        darkFilter = android.graphics.ColorMatrixColorFilter(m)
        map.overlayManager.tilesOverlay.setColorFilter(darkFilter)
        map.overlayManager.tilesOverlay.loadingBackgroundColor = Color.parseColor("#0B0B0C")
        map.overlayManager.tilesOverlay.loadingLineColor = Color.parseColor("#1A1B1E")
        addView(map, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val dp = resources.displayMetrics.density
        // Camioneta al centro, siempre apuntando hacia arriba
        addView(ImageView(ctx).apply { setImageResource(R.drawable.car_base) },
            LayoutParams((30 * dp).toInt(), (70 * dp).toInt(), Gravity.CENTER).apply { topMargin = (40 * dp).toInt() })

        // Anillo con puntos cardinales alrededor del auto + rumbo arriba
        heading = HeadingOverlay(ctx, 40 * dp)
        addView(heading, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Velocidad en círculo de color (verde / ámbar / rojo)
        speedBadge = SpeedBadge(ctx)
        addView(speedBadge, LayoutParams((84 * dp).toInt(), (84 * dp).toInt(),
            Gravity.BOTTOM or Gravity.START).apply { leftMargin = (14 * dp).toInt(); bottomMargin = (14 * dp).toInt() })

        addView(TextView(ctx).apply {
            text = "© OpenStreetMap"; textSize = 8f; setTextColor(Color.argb(150, 200, 200, 200))
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { bottomMargin = (6 * dp).toInt(); rightMargin = (12 * dp).toInt() })
    }

    fun onLocation(loc: Location, kmh: Int) {
        if (visibility != View.VISIBLE) return
        val p = GeoPoint(loc.latitude, loc.longitude)
        // Zoom según la velocidad: más cerca en ciudad, más lejos en carretera
        val z = when { kmh > 90 -> 15.0; kmh > 50 -> 16.0; else -> 17.0 }
        if (!hasFix) { map.controller.setZoom(z); map.controller.setCenter(p); hasFix = true; lastZoom = z }
        else {
            if (z != lastZoom) { map.controller.zoomTo(z, 800L); lastZoom = z }
            map.controller.animateTo(p, null, 900L)
        }
        if (loc.hasBearing() && kmh >= 3) {
            map.mapOrientation = -loc.bearing
            heading.setBearing(loc.bearing)
        }
        speedBadge.setSpeed(kmh)
    }

    fun resume() = map.onResume()
    fun pause() = map.onPause()
}

/** Círculo con la velocidad: verde hasta 70, ámbar hasta 100 y rojo sobre 100 km/h. */
class SpeedBadge(ctx: Context) : View(ctx) {
    private var kmh = 0
    private val pFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val pRing = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
    }
    private val pText = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
        color = Color.WHITE
    }
    private val tfBold = Fonts.bold(ctx)
    private val tfMid = Fonts.medium(ctx)

    fun setSpeed(v: Int) { if (v != kmh) { kmh = v; invalidate() } }

    override fun onDraw(c: android.graphics.Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) - 4f
        val col = when {
            kmh > 100 -> Color.parseColor("#E5322B")
            kmh > 70 -> Color.parseColor("#F2A21B")
            else -> Color.parseColor("#1FA463")
        }
        // Sombra suave
        pFill.color = Color.argb(90, 0, 0, 0)
        c.drawCircle(cx, cy + 3f, r, pFill)
        pFill.shader = android.graphics.RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.5f,
            lighten(col), col, android.graphics.Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, pFill)
        pFill.shader = null
        pRing.strokeWidth = r * 0.09f
        pRing.color = Color.argb(200, 255, 255, 255)
        c.drawCircle(cx, cy, r - pRing.strokeWidth / 2, pRing)
        pText.typeface = tfBold
        pText.textSize = r * (if (kmh >= 100) 0.62f else 0.72f)
        c.drawText(kmh.toString(), cx, cy + r * 0.18f, pText)
        pText.typeface = tfMid
        pText.textSize = r * 0.24f
        c.drawText("km/h", cx, cy + r * 0.52f, pText)
    }

    private fun lighten(c: Int): Int = Color.rgb(
        minOf(255, Color.red(c) + 40), minOf(255, Color.green(c) + 40), minOf(255, Color.blue(c) + 40))
}

/** Puntos cardinales alrededor del auto, girando con el rumbo, y rumbo actual arriba. */
class HeadingOverlay(ctx: Context, private val carOffsetY: Float) : View(ctx) {
    private var bearing = 0f
    private var shown = 0f
    private val names = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
    private val pLine = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
    }
    private val pText = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
    }
    private val pFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val tfBold = Fonts.bold(ctx)
    private val dp = resources.displayMetrics.density

    fun setBearing(b: Float) { bearing = b; invalidate() }

    override fun onDraw(c: android.graphics.Canvas) {
        // Giro suave por el camino más corto
        var d = bearing - shown
        while (d > 180) d -= 360
        while (d < -180) d += 360
        shown = (shown + d * 0.25f + 360f) % 360f
        if (kotlin.math.abs(d) > 0.5f) postInvalidateDelayed(33)

        val cx = width / 2f
        val cy = height / 2f + carOffsetY
        val r = minOf(width, height) * 0.30f
        // Anillo
        pLine.strokeWidth = 2f * dp
        pLine.color = Color.argb(70, 255, 255, 255)
        c.drawCircle(cx, cy, r, pLine)
        pLine.color = Color.argb(60, 0, 0, 0)
        pLine.strokeWidth = 1f * dp
        c.drawCircle(cx, cy, r + 2f * dp, pLine)
        // Marcas cada 15° y letras cada 45°
        for (deg in 0 until 360 step 15) {
            val a = Math.toRadians((deg - shown - 90).toDouble())
            val ca = kotlin.math.cos(a).toFloat(); val sa = kotlin.math.sin(a).toFloat()
            if (deg % 45 == 0) {
                val name = names[deg / 45]
                var diff = kotlin.math.abs(((deg - shown) % 360 + 360) % 360)
                if (diff > 180) diff = 360 - diff
                val active = diff < 22.5f
                val tr = r + 16f * dp
                pText.typeface = android.graphics.Typeface.DEFAULT_BOLD   // la O de Orbitron parece un 0
                pText.textSize = (if (name.length == 1) 16f else 13f) * dp
                // Pastilla oscura detrás de la letra para que se lea sobre cualquier mapa
                pFill.color = when {
                    active -> Color.parseColor("#E61FA463")
                    else -> Color.argb(170, 16, 18, 21)
                }
                c.drawCircle(cx + tr * ca, cy + tr * sa, 13f * dp, pFill)
                pText.color = when {
                    active -> Color.WHITE
                    name == "N" -> Color.parseColor("#FF5A5F")
                    else -> Color.parseColor("#E6ECF2")
                }
                c.drawText(name, cx + tr * ca, cy + tr * sa - (pText.descent() + pText.ascent()) / 2, pText)
            } else {
                pLine.strokeWidth = 2f * dp
                pLine.color = Color.argb(130, 255, 255, 255)
                c.drawLine(cx + (r - 5f * dp) * ca, cy + (r - 5f * dp) * sa, cx + r * ca, cy + r * sa, pLine)
            }
        }
        // Rumbo actual arriba, en pastilla
        val label = names[(((shown + 22.5f) % 360) / 45).toInt() % 8] + "  " + shown.toInt() + "°"
        pText.typeface = tfBold
        pText.textSize = 15f * dp
        val tw = pText.measureText(label)
        val top = 12f * dp
        pFill.color = Color.argb(185, 16, 18, 21)
        c.drawRoundRect(cx - tw / 2 - 14f * dp, top, cx + tw / 2 + 14f * dp, top + 32f * dp, 16f * dp, 16f * dp, pFill)
        pText.color = Color.WHITE
        c.drawText(label, cx, top + 16f * dp - (pText.descent() + pText.ascent()) / 2, pText)
    }
}
