package cl.rodrigo.autolauncher

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Pantalla completa del clima (Open-Meteo). */
class WeatherActivity : Activity() {

    private val dp get() = resources.displayMetrics.density
    private lateinit var content: LinearLayout
    private lateinit var updated: TextView
    private val text = Color.parseColor("#F2F5F8")
    private val dim = Color.parseColor("#8996A5")
    private val accent = Color.parseColor("#3EE0B4")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#05070A"))
            setPadding(px(14), px(8), px(14), px(8))
        }
        // Barra superior
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "‹  Volver"; textSize = 18f; setTextColor(this@WeatherActivity.text)
            setBackgroundResource(R.drawable.bg_button); gravity = Gravity.CENTER
            setPadding(px(20), 0, px(20), 0); setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-2, px(48)))
        top.addView(TextView(this).apply {
            text = "Clima"; textSize = 20f; setTextColor(this@WeatherActivity.text); typeface = Fonts.bold(context)
            setPadding(px(18), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        updated = TextView(this).apply { textSize = 12f; setTextColor(dim) }
        top.addView(updated)
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_reset); setColorFilter(this@WeatherActivity.text)
            setBackgroundResource(R.drawable.bg_round); setPadding(px(10), px(10), px(10), px(10))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { load() }
        }, LinearLayout.LayoutParams(px(46), px(46)))
        root.addView(top)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(6) })
        setContentView(root)
        load()
    }

    private fun px(v: Int) = (v * dp).toInt()

    private fun load() {
        val prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)
        val lat = intent.getDoubleExtra("lat", prefs.getFloat("last_lat", -37.795f).toDouble())
        val lon = intent.getDoubleExtra("lon", prefs.getFloat("last_lon", -72.716f).toDouble())
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = "Cargando clima…"; textSize = 18f; setTextColor(dim); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -1))
        Thread {
            val json = try {
                val url = URL(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day," +
                        "wind_speed_10m,wind_direction_10m,cloud_cover" +
                        "&hourly=temperature_2m,weather_code,is_day,precipitation_probability" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
                        "sunrise,sunset,uv_index_max&timezone=auto&forecast_days=6", lat, lon))
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 10000
                JSONObject(c.inputStream.bufferedReader().use { it.readText() }).also { c.disconnect() }
            } catch (e: Exception) { null }
            val place = Weather.place(applicationContext, lat, lon)
            runOnUiThread { if (json != null) show(json, place) else error() }
        }.start()
    }

    private fun error() {
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = "No se pudo cargar el clima. Revisa la conexión a internet."; textSize = 18f
            setTextColor(dim); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -1))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#12161C")); cornerRadius = 14 * dp
            setStroke(px(1), Color.parseColor("#232B35"))
        }
    }

    private fun tv(s: String, size: Float, color: Int = text, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); maxLines = 1
        if (bold) typeface = Fonts.bold(context)
    }

    private fun chip(icon: String, value: String): View = TextView(this).apply {
        text = "$icon  $value"; textSize = 15f; setTextColor(this@WeatherActivity.text); gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(Color.parseColor("#1A2029")); cornerRadius = 20 * dp }
    }

    private fun show(j: JSONObject, place: String?) {
        content.removeAllViews()
        val cur = j.getJSONObject("current")
        val daily = j.getJSONObject("daily")
        val hourly = j.getJSONObject("hourly")
        val code = cur.getInt("weather_code")
        val isDay = cur.optInt("is_day", 1) == 1
        updated.text = "Actualizado " + SimpleDateFormat("HH:mm", Locale.US).format(Date()) + "   "

        // ---- Ahora + datos ----
        val nowCard = card().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), px(10), px(16), px(10)) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val big = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        big.addView(ImageView(this).apply { setImageResource(Weather.icon(code, isDay)) }, LinearLayout.LayoutParams(px(64), px(64)))
        big.addView(tv("${cur.getDouble("temperature_2m").roundToInt()}°", 54f).apply { setPadding(px(10), 0, 0, 0) })
        left.addView(big)
        left.addView(tv(Weather.describe(code), 18f, bold = true))
        left.addView(tv("Sensación ${cur.getDouble("apparent_temperature").roundToInt()}°" +
            (if (place != null) "  ·  $place" else ""), 14f, dim))
        nowCard.addView(left, LinearLayout.LayoutParams(px(250), -2))

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        val wdir = dirs[((cur.getDouble("wind_direction_10m") + 22.5) / 45).toInt() % 8]
        val tmax = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt()
        val tmin = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt()
        val uv = daily.getJSONArray("uv_index_max").optDouble(0)
        val uvTxt = if (uv.isNaN()) "--" else "${uv.roundToInt()} " + when {
            uv < 3 -> "bajo"; uv < 6 -> "moderado"; uv < 8 -> "alto"; uv < 11 -> "muy alto"; else -> "extremo" }
        val sunrise = daily.getJSONArray("sunrise").getString(0).takeLast(5)
        val sunset = daily.getJSONArray("sunset").getString(0).takeLast(5)
        val rows = listOf(
            listOf("🌡" to "↑$tmax°  ↓$tmin°", "💧" to "${cur.getInt("relative_humidity_2m")} %",
                "🌬" to "${cur.getDouble("wind_speed_10m").roundToInt()} km/h $wdir"),
            listOf("☀" to "UV $uvTxt", "☁" to "Nubes ${cur.getInt("cloud_cover")} %", "🌅" to "$sunrise – $sunset"))
        for (r in rows) {
            val row = LinearLayout(this)
            for ((ic, v) in r) row.addView(chip(ic, v), LinearLayout.LayoutParams(0, px(38), 1f).apply {
                marginStart = px(4); marginEnd = px(4) })
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(6) })
        }
        nowCard.addView(grid, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(nowCard, LinearLayout.LayoutParams(-1, -2))

        // ---- Próximas horas ----
        content.addView(tv("PRÓXIMAS HORAS", 12f, dim).apply { setPadding(px(4), px(8), 0, px(4)) })
        val times = hourly.getJSONArray("time")
        val nowKey = SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US).format(Date())
        var start = 0
        for (i in 0 until times.length()) if (times.getString(i).startsWith(nowKey)) { start = i; break }
        val hours = LinearLayout(this)
        for (i in start until minOf(start + 8, times.length())) {
            val c = card().apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, px(6), 0, px(6)) }
            c.addView(tv(times.getString(i).takeLast(5), 12f, dim))
            c.addView(ImageView(this).apply {
                setImageResource(Weather.icon(hourly.getJSONArray("weather_code").getInt(i),
                    hourly.getJSONArray("is_day").getInt(i) == 1))
            }, LinearLayout.LayoutParams(px(30), px(30)))
            c.addView(tv("${hourly.getJSONArray("temperature_2m").getDouble(i).roundToInt()}°", 16f, bold = true))
            val pp = hourly.getJSONArray("precipitation_probability").optInt(i, 0)
            c.addView(tv(if (pp > 0) "💧$pp%" else " ", 11f, Color.parseColor("#6EC6FF")))
            hours.addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(3); marginEnd = px(3) })
        }
        content.addView(hours, LinearLayout.LayoutParams(-1, -2))

        // ---- Próximos días ----
        content.addView(tv("PRÓXIMOS DÍAS", 12f, dim).apply { setPadding(px(4), px(8), 0, px(2)) })
        val dayNames = arrayOf("Dom", "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb")
        val dTimes = daily.getJSONArray("time")
        val mins = daily.getJSONArray("temperature_2m_min"); val maxs = daily.getJSONArray("temperature_2m_max")
        var lo = 99.0; var hi = -99.0
        for (i in 1 until dTimes.length()) { lo = minOf(lo, mins.getDouble(i)); hi = maxOf(hi, maxs.getDouble(i)) }
        val days = card().apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), px(4), px(12), px(4)) }
        for (i in 1 until dTimes.length()) {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dTimes.getString(i))!!
            val cal = java.util.Calendar.getInstance().apply { time = d }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(tv(dayNames[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1], 16f, bold = true), LinearLayout.LayoutParams(px(56), -2))
            row.addView(ImageView(this).apply { setImageResource(Weather.icon(daily.getJSONArray("weather_code").getInt(i), true)) },
                LinearLayout.LayoutParams(px(26), px(26)))
            row.addView(tv("${daily.getJSONArray("precipitation_probability_max").optInt(i, 0)} %", 13f,
                Color.parseColor("#6EC6FF")).apply { setPadding(px(6), 0, 0, 0) }, LinearLayout.LayoutParams(px(56), -2))
            row.addView(tv("${mins.getDouble(i).roundToInt()}°", 16f, dim).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(px(44), -2))
            row.addView(TempBar(this, lo, hi, mins.getDouble(i), maxs.getDouble(i)),
                LinearLayout.LayoutParams(0, px(10), 1f).apply { marginStart = px(12); marginEnd = px(12) })
            row.addView(tv("${maxs.getDouble(i).roundToInt()}°", 16f, bold = true), LinearLayout.LayoutParams(px(44), -2))
            days.addView(row, LinearLayout.LayoutParams(-1, px(32)))
        }
        content.addView(days, LinearLayout.LayoutParams(-1, -2))
    }

    /** Barra de mínima a máxima del día, en la escala de toda la semana. */
    private class TempBar(ctx: Context, val lo: Double, val hi: Double, val min: Double, val max: Double) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.shader = null; p.color = Color.parseColor("#1E2630")
            r.set(0f, h * 0.25f, w, h * 0.75f); c.drawRoundRect(r, h, h, p)
            val span = (hi - lo).coerceAtLeast(1.0)
            val x1 = (w * (min - lo) / span).toFloat(); val x2 = (w * (max - lo) / span).toFloat()
            p.shader = LinearGradient(x1, 0f, x2, 0f, Color.parseColor("#6EC6FF"), Color.parseColor("#F5C04A"), Shader.TileMode.CLAMP)
            r.set(x1, h * 0.25f, x2.coerceAtLeast(x1 + h), h * 0.75f); c.drawRoundRect(r, h, h, p)
        }
    }
}
