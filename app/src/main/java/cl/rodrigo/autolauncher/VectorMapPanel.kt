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

        private var httpReady = false

        /**
         * Cliente HTTP con identificador propio y máximo 4 conexiones simultáneas,
         * como pide OpenFreeMap para las descargas sin conexión.
         */
        fun setupHttp(ctx: Context) {
            if (httpReady) return
            httpReady = true
            try {
                val ua = "AutoLauncher/1.0 (${ctx.packageName}; Android ${android.os.Build.VERSION.SDK_INT})"
                val client = okhttp3.OkHttpClient.Builder()
                    .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 4 })
                    .addNetworkInterceptor { chain ->
                        chain.proceed(chain.request().newBuilder().header("User-Agent", ua).build())
                    }
                    .build()
                org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(client)
            } catch (e: Throwable) { Diag.add("MAPA http: $e") }
        }

        /**
         * Zoom continuo según la velocidad: detenido 17,5 (muy cerca) y baja de a poco
         * hasta 14 a 120 km/h o más. Se redondea a 0,25 para no reajustar por cada km/h.
         */
        fun zoomFor(kmh: Int): Double {
            val f = (kmh.coerceIn(0, 120)) / 120.0
            return Math.round((17.5 - 3.5 * f) * 4) / 4.0
        }

        private var glOk: Boolean? = null

        /** MapLibre 11 necesita OpenGL ES 3.0. */
        fun supported(ctx: Context): Boolean = glOk ?: try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
        } catch (_: Throwable) { false }.also { glOk = it }
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
    private val status: android.widget.TextView
    private var lastLoc: Location? = null

    init {
        MapLibre.getInstance(ctx.applicationContext)
        setupHttp(ctx)
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
        addView(speedBadge, LayoutParams((100 * dp).toInt(), (100 * dp).toInt(),
            Gravity.BOTTOM or Gravity.START).apply { leftMargin = (14 * dp).toInt(); bottomMargin = (14 * dp).toInt() })
        // Estado de la descarga sin conexión (abajo a la derecha)
        status = android.widget.TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * dp; setColor(Color.argb(190, 16, 18, 21))
            }
            visibility = View.GONE
        }
        addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { rightMargin = (12 * dp).toInt(); bottomMargin = (30 * dp).toInt() })
        OfflineMaps.onStatus = { msg ->
            status.text = msg ?: ""
            status.visibility = if (msg == null) View.GONE else View.VISIBLE
        }
        setBackgroundColor(Color.parseColor("#0B0B0C"))
    }


    /** Estilo en uso (para descargar la zona con el mismo aspecto). */
    fun currentStyle(): String = if (dark) STYLE_DARK else STYLE_LIGHT

    fun downloadNow() = OfflineMaps.downloadNow(context, lastLoc, currentStyle())

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
        val z = zoomFor(kmh)
        val pos = CameraPosition.Builder()
            .target(LatLng(loc.latitude, loc.longitude))
            .zoom(z).bearing(bearing.toDouble()).tilt(30.0).build()
        if (!hasFix) { m.moveCamera(CameraUpdateFactory.newCameraPosition(pos)); hasFix = true }
        else m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 900)
        speedBadge.setSpeed(kmh)
        lastLoc = loc
    }

    override fun resume() = mapView.onResume()
    override fun pause() = mapView.onPause()
    override fun destroy() {
        OfflineMaps.onStatus = null
        try { mapView.onPause(); mapView.onStop(); mapView.onDestroy() } catch (_: Throwable) { }
    }
}
