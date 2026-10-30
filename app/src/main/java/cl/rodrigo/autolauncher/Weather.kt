package cl.rodrigo.autolauncher

import android.content.Context
import android.location.Geocoder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

data class WeatherInfo(val temp: Int, val desc: String, val place: String?, val min: Int?, val max: Int?, val icon: Int)

/** Clima actual desde Open-Meteo (gratis, sin clave). Llamar fuera del hilo principal. */
object Weather {

    fun fetch(ctx: Context, lat: Double, lon: Double): WeatherInfo? {
        return try {
            val url = URL(String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                    "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto", lat, lon))
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(body)
            val cur = json.getJSONObject("current")
            val temp = cur.getDouble("temperature_2m").roundToInt()
            val code = cur.getInt("weather_code")
            val isDay = cur.optInt("is_day", 1) == 1
            val daily = json.optJSONObject("daily")
            val max = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.takeIf { !it.isNaN() }?.roundToInt()
            val min = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.takeIf { !it.isNaN() }?.roundToInt()
            WeatherInfo(temp, describe(code), place(ctx, lat, lon), min, max, icon(code, isDay))
        } catch (e: Exception) {
            null
        }
    }

    private fun place(ctx: Context, lat: Double, lon: Double): String? = try {
        if (!Geocoder.isPresent()) null
        else {
            @Suppress("DEPRECATION")
            val a = Geocoder(ctx, Locale("es", "CL")).getFromLocation(lat, lon, 1)?.firstOrNull()
            a?.locality ?: a?.subAdminArea ?: a?.adminArea
        }
    } catch (e: Exception) {
        null
    }

    private fun icon(code: Int, day: Boolean): Int = when (code) {
        0 -> if (day) R.drawable.wx_sun else R.drawable.wx_moon
        1, 2 -> if (day) R.drawable.wx_partly else R.drawable.wx_partly_night
        3 -> R.drawable.wx_cloud
        45, 48 -> R.drawable.wx_fog
        71, 73, 75, 77, 85, 86 -> R.drawable.wx_snow
        95, 96, 99 -> R.drawable.wx_storm
        in 51..67, 80, 81, 82 -> R.drawable.wx_rain
        else -> R.drawable.wx_cloud
    }

    private fun describe(code: Int): String = when (code) {
        0 -> "Despejado"
        1 -> "Mayormente despejado"
        2 -> "Parcial nublado"
        3 -> "Nublado"
        45, 48 -> "Neblina"
        51, 53, 55, 56, 57 -> "Llovizna"
        61, 63, 66 -> "Lluvia"
        65, 67 -> "Lluvia fuerte"
        71, 73, 75, 77 -> "Nieve"
        80, 81 -> "Chubascos"
        82 -> "Chubascos fuertes"
        85, 86 -> "Chubascos de nieve"
        95, 96, 99 -> "Tormenta"
        else -> "Sin datos"
    }
}
