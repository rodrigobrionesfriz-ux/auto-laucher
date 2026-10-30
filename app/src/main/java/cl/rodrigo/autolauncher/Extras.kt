package cl.rodrigo.autolauncher

import android.content.Context
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** Ajustes simples guardados en la radio. */
object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    const val NIGHT_AUTO = "auto"
    const val NIGHT_DAY = "day"
    const val NIGHT_ON = "night"

    fun nightMode(ctx: Context): String = p(ctx).getString("night_mode", NIGHT_AUTO) ?: NIGHT_AUTO
    fun setNightMode(ctx: Context, v: String) = p(ctx).edit().putString("night_mode", v).apply()

    fun speedLimit(ctx: Context): Int = p(ctx).getInt("speed_limit", 100)
    fun setSpeedLimit(ctx: Context, v: Int) = p(ctx).edit().putInt("speed_limit", v).apply()
}

/** Calcula si es de noche según la posición del sol (amanecer / atardecer). */
object Sun {
    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    /** Hora UTC (0..24) de salida o puesta del sol, o null si no ocurre ese día. */
    private fun utcHour(lat: Double, lon: Double, dayOfYear: Int, rising: Boolean): Double? {
        val lngHour = lon / 15.0
        val t = dayOfYear + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0
        val m = 0.9856 * t - 3.289
        var l = m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634
        l = ((l % 360) + 360) % 360
        var ra = deg(atan(0.91764 * tan(rad(l))))
        ra = ((ra % 360) + 360) % 360
        ra += floor(l / 90) * 90 - floor(ra / 90) * 90
        ra /= 15.0
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(90.833)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
        if (cosH > 1 || cosH < -1) return null
        val h = (if (rising) 360 - deg(acos(cosH)) else deg(acos(cosH))) / 15.0
        val tt = h + ra - 0.06571 * t - 6.622
        return ((tt - lngHour) % 24 + 24) % 24
    }

    fun isNight(lat: Double?, lon: Double?): Boolean {
        val local = Calendar.getInstance()
        if (lat == null || lon == null) {
            val h = local.get(Calendar.HOUR_OF_DAY)
            return h >= 20 || h < 7
        }
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val now = utc.get(Calendar.HOUR_OF_DAY) + utc.get(Calendar.MINUTE) / 60.0
        val day = utc.get(Calendar.DAY_OF_YEAR)
        val rise = utcHour(lat, lon, day, true) ?: return false
        val set = utcHour(lat, lon, day, false) ?: return false
        val isDay = if (rise < set) now in rise..set else now >= rise || now <= set
        return !isDay
    }
}
