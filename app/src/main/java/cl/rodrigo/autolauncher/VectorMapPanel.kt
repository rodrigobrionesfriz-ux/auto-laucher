package cl.rodrigo.autolauncher

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.location.Location
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Mapa vectorial (MapLibre + OpenFreeMap). Se dibuja en la radio, así que al girar con
 * el rumbo los nombres de calles se reacomodan y siempre quedan derechos y legibles.
 */
class VectorMapPanel(ctx: Context) : FrameLayout(ctx), MapUi {

    companion object {
        const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
        const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

        /** MapLibre 11 necesita OpenGL ES 3.0. */
        fun supported(ctx: Context): Boolean = try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
        } catch (_: Throwable) { false }
    }

    private val mapView: MapView
    private var map: MapLibreMap? = null
    private val speedBadge: SpeedBadge
    private val heading: HeadingOverlay
    private var dark = true
    private var styleUrl: String? = null
    private var hasFix = false
    private var bearing = 0f
    private var pending: Location? = null
    private var pendingKmh = 0

    init {
        MapLibre.getInstance(ctx.applicationContext)
        mapView = MapView(ctx)
        mapView.onCreate(null)
        mapView.onStart()
        addView(mapView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isCompassEnabled = false
                isLogoEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                attributionGravity = Gravity.BOTTOM or Gravity.END
            }
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(-37.795, -72.716))   // Angol, hasta tener GPS
                .zoom(15.5).tilt(30.0).build()
            applyStyle()
            pending?.let { onLocation(it, pendingKmh) }
        }

        val dp = resources.displayMetrics.density
        // Camioneta al centro, siempre apuntando hacia arriba
        addView(ImageView(ctx).apply { setImageResource(R.drawable.car_base) },
            LayoutParams((30 * dp).toInt(), (70 * dp).toInt(), Gravity.CENTER))

        heading = HeadingOverlay(ctx, 0f)
        addView(heading, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        speedBadge = SpeedBadge(ctx)
        addView(speedBadge, LayoutParams((84 * dp).toInt(), (84 * dp).toInt(),
            Gravity.BOTTOM or Gravity.START).apply { leftMargin = (14 * dp).toInt(); bottomMargin = (14 * dp).toInt() })
        setBackgroundColor(Color.parseColor("#0B0B0C"))
    }

    private fun applyStyle() {
        val m = map ?: return
        val url = if (dark) STYLE_DARK else STYLE_LIGHT
        if (url == styleUrl) return
        styleUrl = url
        m.setStyle(Style.Builder().fromUri(url))
    }

    override fun setDark(dark: Boolean) {
        this.dark = dark
        setBackgroundColor(if (dark) Color.parseColor("#0B0B0C") else Color.parseColor("#E8E6E1"))
        applyStyle()
    }

    override fun onLocation(loc: Location, kmh: Int) {
        val m = map
        if (m == null) { pending = loc; pendingKmh = kmh; return }
        if (visibility != View.VISIBLE) return
        if (loc.hasBearing() && kmh >= 3) {
            bearing = loc.bearing
            heading.setBearing(loc.bearing)
        }
        // Zoom según la velocidad: más cerca en ciudad, más lejos en carretera
        val z = when { kmh > 90 -> 14.5; kmh > 50 -> 15.5; else -> 16.5 }
        val pos = CameraPosition.Builder()
            .target(LatLng(loc.latitude, loc.longitude))
            .zoom(z).bearing(bearing.toDouble()).tilt(30.0).build()
        if (!hasFix) { m.moveCamera(CameraUpdateFactory.newCameraPosition(pos)); hasFix = true }
        else m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 900)
        speedBadge.setSpeed(kmh)
    }

    override fun resume() = mapView.onResume()
    override fun pause() = mapView.onPause()
    override fun destroy() {
        try { mapView.onPause(); mapView.onStop(); mapView.onDestroy() } catch (_: Throwable) { }
    }
}
