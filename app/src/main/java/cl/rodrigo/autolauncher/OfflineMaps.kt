package cl.rodrigo.autolauncher

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.json.JSONObject
import kotlin.math.cos

/**
 * Descarga automática del mapa vectorial para usarlo sin conexión:
 * baja un cuadrado de ±20 km alrededor del auto y, cuando el auto se aleja más de
 * 15 km del centro de la última zona, baja la nueva. Guarda las 3 zonas más recientes.
 *
 * OpenFreeMap permite descargas de hasta 3.000 piezas o 50 MB; ±20 km con zoom 8–14
 * son ~600 piezas.
 */
object OfflineMaps {
    private const val RADIUS_KM = 20.0
    private const val REDOWNLOAD_KM = 15f
    private const val KEEP_REGIONS = 3
    private const val MIN_ZOOM = 8.0
    private const val MAX_ZOOM = 14.0

    /** Texto de estado para mostrar en el mapa (null = nada que mostrar). */
    var onStatus: ((String?) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var downloading = false
    private var startedAt = 0L
    private var lastCheck = 0L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("offline_maps", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("enabled", v).apply()

    private fun online(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION") cm.activeNetworkInfo?.isConnected == true
    } catch (_: Throwable) { false }

    /** Se llama con cada posición GPS; decide si hace falta bajar una zona nueva. */
    fun onLocation(ctx: Context, loc: Location, styleUrl: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheck < 30_000) return
        lastCheck = now
        if (!enabled(ctx)) return
        // Una descarga colgada más de 15 min se da por perdida y se reintenta
        if (downloading && now - startedAt < 15 * 60_000) return
        downloading = false
        val p = prefs(ctx)
        if (p.contains("lat")) {
            val d = FloatArray(1)
            Location.distanceBetween(p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble(),
                loc.latitude, loc.longitude, d)
            if (d[0] < REDOWNLOAD_KM * 1000 && p.getString("style", null) == styleUrl) return
        }
        if (!online(ctx)) return
        download(ctx, loc.latitude, loc.longitude, styleUrl)
    }

    /** Fuerza la descarga de la zona actual (desde el menú). */
    fun downloadNow(ctx: Context, loc: Location?, styleUrl: String) {
        if (loc == null) { onStatus?.invoke("Sin posición GPS todavía"); return }
        if (!online(ctx)) { onStatus?.invoke("Sin conexión para descargar"); return }
        download(ctx, loc.latitude, loc.longitude, styleUrl)
    }

    private fun download(ctx: Context, lat: Double, lon: Double, styleUrl: String) {
        val app = ctx.applicationContext
        downloading = true
        startedAt = SystemClock.elapsedRealtime()
        val dLat = RADIUS_KM / 111.32
        val dLon = RADIUS_KM / (111.32 * cos(Math.toRadians(lat)))
        val bounds = LatLngBounds.Builder()
            .include(LatLng(lat + dLat, lon + dLon))
            .include(LatLng(lat - dLat, lon - dLon))
            .build()
        val def = OfflineTilePyramidRegionDefinition(styleUrl, bounds, MIN_ZOOM, MAX_ZOOM,
            app.resources.displayMetrics.density)
        val meta = JSONObject().put("lat", lat).put("lon", lon).put("style", styleUrl)
            .put("t", System.currentTimeMillis()).toString().toByteArray()
        onStatus?.invoke("Descargando mapa 20 km…")
        try {
            OfflineManager.getInstance(app).createOfflineRegion(def, meta,
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) {
                        offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                            private var lastPct = -1
                            override fun onStatusChanged(status: OfflineRegionStatus) {
                                if (status.isComplete) {
                                    offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                    offlineRegion.setObserver(null)
                                    finished(app, lat, lon, styleUrl, status)
                                    return
                                }
                                if (status.requiredResourceCount > 0) {
                                    val pct = (100 * status.completedResourceCount / status.requiredResourceCount).toInt()
                                    if (pct != lastPct && pct % 5 == 0) {
                                        lastPct = pct
                                        post("Descargando mapa 20 km… $pct%")
                                    }
                                }
                            }
                            override fun onError(error: OfflineRegionError) {
                                Diag.add("MAPA offline error: ${error.reason} ${error.message}")
                            }
                            override fun mapboxTileCountLimitExceeded(limit: Long) {
                                Diag.add("MAPA offline: límite de piezas $limit")
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                downloading = false
                                post(null)
                            }
                        })
                        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    }
                    override fun onError(error: String) {
                        Diag.add("MAPA offline no se pudo crear: $error")
                        downloading = false
                        post(null)
                    }
                })
        } catch (e: Throwable) {
            Diag.add("MAPA offline falló: $e")
            downloading = false
            post(null)
        }
    }

    private fun finished(ctx: Context, lat: Double, lon: Double, styleUrl: String, st: OfflineRegionStatus) {
        downloading = false
        prefs(ctx).edit().putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat())
            .putString("style", styleUrl).putLong("bytes", st.completedResourceSize).apply()
        val mb = st.completedResourceSize / (1024 * 1024)
        Diag.add("MAPA offline listo: ${st.completedTileCount} piezas, $mb MB")
        post("Mapa sin conexión listo ($mb MB)")
        main.postDelayed({ onStatus?.invoke(null) }, 6000)
        pruneOld(ctx)
    }

    /** Deja solo las zonas más recientes. */
    private fun pruneOld(ctx: Context) {
        try {
            OfflineManager.getInstance(ctx.applicationContext).listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) {
                        val list = offlineRegions?.sortedByDescending { it.id } ?: return
                        list.drop(KEEP_REGIONS).forEach { r ->
                            r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                                override fun onDelete() {}
                                override fun onError(error: String) {}
                            })
                        }
                    }
                    override fun onError(error: String) {}
                })
        } catch (_: Throwable) { }
    }

    /** Borra todas las zonas descargadas (desde el menú). */
    fun deleteAll(ctx: Context) {
        prefs(ctx).edit().remove("lat").remove("lon").remove("style").apply()
        try {
            OfflineManager.getInstance(ctx.applicationContext).listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) {
                        offlineRegions?.forEach { r ->
                            r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                                override fun onDelete() {}
                                override fun onError(error: String) {}
                            })
                        }
                        post("Mapas descargados borrados")
                        main.postDelayed({ onStatus?.invoke(null) }, 4000)
                    }
                    override fun onError(error: String) {}
                })
        } catch (_: Throwable) { }
    }

    private fun post(msg: String?) = main.post { onStatus?.invoke(msg) }
}
