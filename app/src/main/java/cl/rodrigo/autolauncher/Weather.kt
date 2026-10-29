package cl.rodrigo.autolauncher

import android.content.Context
import android.location.Geocoder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

data class WeatherInfo(val temp: Int, val desc: String, val place: String?)

/** Clima actual desde Open-Meteo (gratis, sin clave). Llamar fuera del hilo principal. */
object Weather {

    fun fetch(ctx: Context, lat: Double, lon: Double): WeatherInfo? {
        return try {
            val url = URL(String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                    "&current=temperature_2m,weather_code&timezone=auto", lat, lon))
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val cur = JSONObject(body).getJSONObject("current")
            val temp = cur.getDouble("temperature_2m").roundToInt()
            val code = cur.getInt("weather_code")
            WeatherInfo(temp, describe(code), place(ctx, lat, lon))
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
