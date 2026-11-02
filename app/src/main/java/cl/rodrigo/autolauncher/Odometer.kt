package cl.rodrigo.autolauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Odómetro total y cuentakilómetros de viaje, sumando la distancia recorrida según el GPS. */
object Odo {
    private const val PREFS = "odometer"
    private var loaded = false
    private var totalM = 0.0
    private var tripM = 0.0
    private var unsavedM = 0.0
    private var lastSaveAt = 0L
    private var last: Location? = null
    private var lastAt = 0L
    var isSet = false
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        val p = prefs(ctx)
        totalM = p.getLong("total_m", 0).toDouble()
        tripM = p.getLong("trip_m", 0).toDouble()
        isSet = p.getBoolean("set", false)
        loaded = true
    }

    @Synchronized
    fun save(ctx: Context) {
        prefs(ctx).edit().putLong("total_m", totalM.toLong()).putLong("trip_m", tripM.toLong()).apply()
        unsavedM = 0.0
        lastSaveAt = SystemClock.elapsedRealtime()
    }

    fun totalKm(ctx: Context): Double { load(ctx); return totalM / 1000.0 }
    fun tripKm(ctx: Context): Double { load(ctx); return tripM / 1000.0 }

    @Synchronized
    fun setTotalKm(ctx: Context, km: Double) {
        load(ctx)
        totalM = km * 1000.0
        isSet = true
        prefs(ctx).edit().putBoolean("set", true).apply()
        save(ctx)
    }

    @Synchronized
    fun resetTrip(ctx: Context) {
        load(ctx)
        tripM = 0.0
        save(ctx)
    }

    /** Suma el tramo entre la lectura anterior y esta, descartando ruido del GPS. */
    @Synchronized
    fun onLocation(ctx: Context, loc: Location) {
        load(ctx)
        val now = SystemClock.elapsedRealtime()
        val prev = last
        val prevAt = lastAt
        if (loc.hasAccuracy() && loc.accuracy > 30f) return   // lectura poco precisa: se ignora
        last = loc
        lastAt = now
        if (prev == null) return
        val dt = (now - prevAt) / 1000.0
        if (dt <= 0 || dt > 30) return                         // pausa larga: no inventar distancia
        val d = prev.distanceTo(loc).toDouble()
        val moving = (loc.hasSpeed() && loc.speed >= 1.0f) || d / dt >= 1.5
        if (!moving) return                                    // detenido: deriva del GPS
        if (d / dt > 70) return                                // salto imposible (> 250 km/h)
        totalM += d
        tripM += d
        unsavedM += d
        if (unsavedM >= 100 || now - lastSaveAt > 60_000) save(ctx)
    }

    private val miles = DecimalFormat("#,##0", DecimalFormatSymbols(Locale("es", "CL")))
    private val oneDec = DecimalFormat("#,##0.0", DecimalFormatSymbols(Locale("es", "CL")))
    fun fmtTotal(km: Double): String = miles.format(Math.floor(km)) + " km"
    fun fmtTrip(km: Double): String = oneDec.format(km) + " km"
}

/** Servicio en segundo plano: mantiene el GPS para que el odómetro cuente aunque uses otra app. */
class OdoService : Service() {
    private var lm: LocationManager? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = Odo.onLocation(this@OdoService, loc)
        @Deprecated("API antigua")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notification())
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        try { lm?.removeUpdates(listener) } catch (e: Exception) {}
        Odo.save(this)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun notification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("odo", "Odómetro", NotificationManager.IMPORTANCE_MIN))
            Notification.Builder(this, "odo")
        } else Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        return b.setSmallIcon(R.drawable.ic_nav)
            .setContentTitle("Odómetro activo")
            .setContentText("Registrando los kilómetros recorridos")
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 23 &&
                ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            val i = Intent(ctx, OdoService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
            }
        }
    }
}
