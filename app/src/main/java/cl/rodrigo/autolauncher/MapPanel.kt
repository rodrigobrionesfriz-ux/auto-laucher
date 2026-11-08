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
    private val speed: TextView
    private var lastZoom = 0.0
    private var hasFix = false

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
        map.overlayManager.tilesOverlay.setColorFilter(android.graphics.ColorMatrixColorFilter(m))
        map.overlayManager.tilesOverlay.loadingBackgroundColor = Color.parseColor("#0B0B0C")
        map.overlayManager.tilesOverlay.loadingLineColor = Color.parseColor("#1A1B1E")
        addView(map, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val dp = resources.displayMetrics.density
        // Camioneta al centro, siempre apuntando hacia arriba
        addView(ImageView(ctx).apply { setImageResource(R.drawable.car_base) },
            LayoutParams((30 * dp).toInt(), (70 * dp).toInt(), Gravity.CENTER).apply { topMargin = (40 * dp).toInt() })

        speed = TextView(ctx).apply {
            typeface = Fonts.bold(ctx)
            textSize = 26f
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            gravity = Gravity.CENTER
        }
        addView(speed, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (28 * dp).toInt() })

        addView(TextView(ctx).apply {
            text = "© OpenStreetMap"; textSize = 8f; setTextColor(Color.argb(150, 200, 200, 200))
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (12 * dp).toInt() })
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
        if (loc.hasBearing() && kmh >= 3) map.mapOrientation = -loc.bearing
        speed.text = "$kmh km/h"
    }

    fun resume() = map.onResume()
    fun pause() = map.onPause()
}
