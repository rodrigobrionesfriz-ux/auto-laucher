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

    /** Corrección: el GPS medía 231 km cuando el auto marcaba 223 km. */
    const val DEFAULT_FACTOR = 223.0 / 231.0
    var factor = DEFAULT_FACTOR
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        val p = prefs(ctx)
        totalM = p.getLong("total_m", 0).toDouble()
        tripM = p.getLong("trip_m", 0).toDouble()
        isSet = p.getBoolean("set", false)
        factor = p.getFloat("factor", DEFAULT_FACTOR.toFloat()).toDouble()
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

    /**
     * Calibra con un viaje: el usuario dice cuántos km marcó el auto para el viaje actual.
     * El nuevo factor corrige la diferencia y el viaje se ajusta a ese valor.
     */
    @Synchronized
    fun calibrate(ctx: Context, carTripKm: Double): Boolean {
        load(ctx)
        if (tripM < 5000 || carTripKm <= 0) return false   // se necesita un viaje de al menos 5 km
        val newFactor = (factor * carTripKm * 1000.0 / tripM).coerceIn(0.8, 1.2)
        factor = newFactor
        tripM = carTripKm * 1000.0
        prefs(ctx).edit().putFloat("factor", factor.toFloat()).apply()
        save(ctx)
        return true
    }

    @Synchronized
    fun resetFactor(ctx: Context) {
        load(ctx)
        factor = DEFAULT_FACTOR
        prefs(ctx).edit().putFloat("factor", factor.toFloat()).apply()
    }

    @Synchronized
    fun resetTrip(ctx: Context) {
        load(ctx)
        tripM = 0.0
        save(ctx)
    }

    // ---- Robustez ante cortes del GPS ----
    private var anchor: Location? = null       // última posición buena antes de un corte
    private var anchorAt = 0L
    private var warmup = 0                      // lecturas a descartar tras recuperar señal
    private var lastFixNs = 0L
    var gapsBridged = 0; private set
    var rejected = 0; private set
    var lastIssue = ""; private set

    private fun fixTime(loc: Location): Long =
        if (Build.VERSION.SDK_INT >= 17 && loc.elapsedRealtimeNanos > 0) loc.elapsedRealtimeNanos / 1_000_000
        else SystemClock.elapsedRealtime()

    private fun add(ctx: Context, meters: Double) {
        val dc = meters * factor
        totalM += dc
        tripM += dc
        unsavedM += dc
        val now = SystemClock.elapsedRealtime()
        if (unsavedM >= 100 || now - lastSaveAt > 60_000) save(ctx)
    }

    /**
     * Suma la distancia recorrida. Usa sobre todo la velocidad del GPS (medida por efecto
     * Doppler, mucho más estable que comparar posiciones) y protege el contador cuando el
     * GPS de la radio se corta: descarta las primeras lecturas al recuperar la señal (suelen
     * venir corridas) y rellena el tramo perdido con la distancia en línea recta, si es creíble.
     */
    @Synchronized
    fun onLocation(ctx: Context, loc: Location) {
        load(ctx)
        val at = fixTime(loc)
        // Lectura repetida o congelada (mismo instante): se ignora
        val ns = if (Build.VERSION.SDK_INT >= 17) loc.elapsedRealtimeNanos else loc.time
        if (ns == lastFixNs) return
        lastFixNs = ns

        val prev = last
        val prevAt = lastAt
        val bad = loc.hasAccuracy() && loc.accuracy > 25f
        if (bad) {
            // Señal débil: se guarda dónde estábamos para rellenar después
            if (prev != null && anchor == null) { anchor = prev; anchorAt = prevAt }
            last = null; warmup = 3
            return
        }
        last = loc
        lastAt = at
        if (prev == null) {
            if (anchor == null) return
        }
        val dt = if (prev != null) (at - prevAt) / 1000.0 else 0.0

        // Corte: más de 5 s sin lecturas buenas
        if (prev == null || dt > 5) {
            if (anchor == null && prev != null) { anchor = prev; anchorAt = prevAt }
            if (warmup == 0) warmup = 3
        }
        if (warmup > 0) { warmup--; if (warmup > 0) return }

        // Recién recuperada la señal: rellenar el tramo perdido
        val a = anchor
        if (a != null) {
            anchor = null
            val gap = (at - anchorAt) / 1000.0
            val d = a.distanceTo(loc).toDouble()
            if (gap in 1.0..900.0 && d / gap <= 45.0 && d >= 20) {
                add(ctx, d)                     // línea recta: algo menos que lo real en curvas
                gapsBridged++
                lastIssue = "Corte de GPS de ${gap.toInt()} s: se sumaron ${d.toInt()} m"
                Diag.add("ODO $lastIssue")
            } else if (gap > 900.0 || d / gap > 45.0) {
                rejected++
                lastIssue = "Corte de GPS de ${gap.toInt()} s descartado (salto de ${d.toInt()} m)"
                Diag.add("ODO $lastIssue")
            }
            return
        }
        if (prev == null || dt <= 0) return

        val dPos = prev.distanceTo(loc).toDouble()
        val vPos = dPos / dt
        val hasV = loc.hasSpeed() && prev.hasSpeed()
        val vGps = if (hasV) (loc.speed + prev.speed) / 2.0 else vPos

        if (vGps < 1.0 && vPos < 2.0) return    // detenido: deriva del GPS
        if (vPos > 70 && (!hasV || vGps > 70)) { // salto imposible
            rejected++
            lastIssue = "Salto de ${dPos.toInt()} m en ${"%.0f".format(dt)} s descartado"
            return
        }
        // Distancia del tramo: por velocidad Doppler si existe; si la posición salta mucho
        // más que la velocidad (posición corrida), se confía en la velocidad.
        val d = if (hasV) {
            if (vPos > vGps * 1.6 + 3) vGps * dt else (vGps * dt + dPos) / 2.0
        } else dPos
        add(ctx, d)
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
