package cl.rodrigo.autolauncher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var gauge: SpeedGauge
    private lateinit var weatherTemp: TextView
    private lateinit var weatherDesc: TextView
    private lateinit var weatherPlace: TextView
    private lateinit var weatherIcon: ImageView
    private lateinit var weatherRange: TextView
    private lateinit var musicSource: TextView
    private lateinit var musicTitle: TextView
    private lateinit var musicArtist: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var musicArt: ImageView
    private lateinit var musicProgress: android.widget.ProgressBar
    private lateinit var musicElapsed: TextView
    private lateinit var musicTotal: TextView
    private var artShown: android.graphics.Bitmap? = null

    private var lm: LocationManager? = null
    private var gpsRunning = false
    private var lastFixAt = 0L
    private var lastLocation: Location? = null
    private var satsUsed = 0
    private var satsSeen = 0
    private var gnssCallback: Any? = null

    private var weatherAt = 0L
    private var weatherTryAt = 0L
    private var weatherLoading = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = onLocation(loc)
        @Deprecated("API antigua")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        gauge = findViewById(R.id.gauge)
        gauge.limit = Prefs.speedLimit(this)
        gauge.setOnLongClickListener { lightsMenu(); true }
        weatherTemp = findViewById(R.id.weatherTemp)
        weatherDesc = findViewById(R.id.weatherDesc)
        weatherPlace = findViewById(R.id.weatherPlace)
        weatherIcon = findViewById(R.id.weatherIcon)
        weatherRange = findViewById(R.id.weatherRange)
        musicSource = findViewById(R.id.musicSource)
        musicTitle = findViewById(R.id.musicTitle)
        musicArtist = findViewById(R.id.musicArtist)
        btnPlay = findViewById(R.id.btnPlay)
        musicArt = findViewById(R.id.musicArt)
        musicProgress = findViewById(R.id.musicProgress)
        musicElapsed = findViewById(R.id.musicElapsed)
        musicTotal = findViewById(R.id.musicTotal)
        Music.register(this)
        Syu.connect(this)
        if (Discovery.needsScan(this)) {
            Thread {
                Discovery.scan(applicationContext)
                runOnUiThread { Music.reRegister(this) }
            }.start()
        }
        findViewById<TextView>(R.id.clock).setOnLongClickListener { settingsMenu(); true }

        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        buildDock()

        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener {
            Music.key(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS); refreshMusicSoon()
        }
        btnPlay.setOnClickListener {
            Music.key(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); refreshMusicSoon()
        }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener {
            Music.key(this, KeyEvent.KEYCODE_MEDIA_NEXT); refreshMusicSoon()
        }

        val musicInfo = findViewById<View>(R.id.musicInfo)
        musicInfo.setOnClickListener {
            when {
                !Music.hasAccess(this) -> {
                    if (!Music.openAccessSettings(this)) {
                        Toast.makeText(this, "Esta radio no permite ver la canción actual", Toast.LENGTH_LONG).show()
                    }
                }
                // Con música Bluetooth sonando no abrimos nada: se controla con los botones
                !Music.syuTitle.isNullOrBlank() ->
                    Toast.makeText(this, "Mantén presionado para abrir la app de música", Toast.LENGTH_SHORT).show()
                else -> Apps.open(this, Apps.musicSlot) {}
            }
        }
        findViewById<View>(R.id.musicText).setOnClickListener { musicInfo.performClick() }
        findViewById<View>(R.id.musicText).setOnLongClickListener { musicInfo.performLongClick() }
        musicTitle.isSelected = true
        musicInfo.setOnLongClickListener {
            android.app.AlertDialog.Builder(this)
                .setItems(arrayOf("Abrir app de música", "Cambiar app de música")) { _, which ->
                    if (which == 0) Apps.open(this, Apps.musicSlot) {} else Apps.pick(this, Apps.musicSlot) {}
                }
                .show()
            true
        }

        findViewById<LinearLayout>(R.id.weatherBox).setOnClickListener {
            weatherAt = 0; weatherTryAt = 0
            weatherDesc.text = "Actualizando…"
        }

        askLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        startGps()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        Updater.checkInBackground(this)
    }

    override fun onDestroy() {
        Music.unregister(this)
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        stopGps()
    }

    // Botón atrás no hace nada en la pantalla de inicio.
    @Deprecated("Comportamiento de launcher")
    override fun onBackPressed() {}

    // ---------- Barra de accesos ----------

    private fun buildDock() {
        val dock = findViewById<LinearLayout>(R.id.dock)
        dock.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val keys = Apps.dockKeys(this)

        fun addRow(label: String, setIcon: (ImageView) -> Unit): View {
            val row = inflater.inflate(R.layout.item_row, dock, false)
            setIcon(row.findViewById(R.id.tileIcon))
            row.findViewById<TextView>(R.id.tileLabel).text = label
            dock.addView(row)
            return row.findViewById(R.id.rowBody)
        }

        val iconPx = (58 * resources.displayMetrics.density).toInt()
        fun appIcon(pkg: String?, iv: ImageView): Boolean {
            if (pkg == null || pkg == Apps.SETTINGS) return false
            return try {
                iv.setImageDrawable(Icons.app(this, packageManager.getApplicationIcon(pkg), iconPx))
                true
            } catch (e: Exception) { false }
        }

        for (key in keys) {
            val slot = Apps.slotFor(key)
            val body: View
            if (slot != null) {
                body = addRow(slot.label) { iv ->
                    if (!appIcon(Apps.assigned(this, slot), iv)) iv.setImageDrawable(Icons.glyph(this, slot.key, slot.icon, iconPx))
                }
                body.setOnClickListener { Apps.open(this, slot) { buildDock() } }
            } else if (key.startsWith("pkg:")) {
                val pkg = key.removePrefix("pkg:")
                val label = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) { pkg }
                body = addRow(label) { appIcon(pkg, it) }
                body.setOnClickListener {
                    if (!Apps.launch(this, pkg)) Toast.makeText(this, "No se pudo abrir $label", Toast.LENGTH_SHORT).show()
                }
            } else continue
            body.setOnLongClickListener { tileMenu(key, slot); true }
        }

        val apps = findViewById<View>(R.id.btnApps)
        apps.setOnClickListener { startActivity(Intent(this, AppsActivity::class.java)) }
        apps.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setItems(arrayOf("Agregar acceso", "Restaurar accesos originales")) { _, which ->
                    if (which == 0) addToDock()
                    else { Apps.resetDock(this); buildDock() }
                }.show()
            true
        }
    }

    private fun tileMenu(key: String, slot: Slot?) {
        val keys = Apps.dockKeys(this)
        val idx = keys.indexOf(key)
        val options = ArrayList<Pair<String, () -> Unit>>()
        if (slot != null) options.add("Cambiar app" to {
            Apps.pick(this, slot) {
                Toast.makeText(this, "${slot.label} actualizado", Toast.LENGTH_SHORT).show(); buildDock()
            }
        })
        if (idx > 0) options.add("Subir" to {
            keys.add(idx - 1, keys.removeAt(idx)); Apps.saveDock(this, keys); buildDock()
        })
        if (idx in 0 until keys.size - 1) options.add("Bajar" to {
            keys.add(idx + 1, keys.removeAt(idx)); Apps.saveDock(this, keys); buildDock()
        })
        options.add("Quitar" to {
            keys.removeAt(idx); Apps.saveDock(this, keys); buildDock()
        })
        AlertDialog.Builder(this)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    private fun addToDock() {
        val keys = Apps.dockKeys(this)
        if (keys.size >= Apps.MAX_DOCK) {
            Toast.makeText(this, "La lista ya está llena; quita un acceso primero", Toast.LENGTH_LONG).show()
            return
        }
        val list = Apps.load(this)
        AlertDialog.Builder(this)
            .setTitle("Agregar acceso")
            .setAdapter(AppAdapter(this, list, R.layout.item_pick)) { _, which ->
                val k = "pkg:" + list[which].pkg
                if (k !in keys) { keys.add(k); Apps.saveDock(this, keys); buildDock() }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Menú de ajustes (mantener presionado el reloj) ----------

    private fun settingsMenu() {
        val modeName = when (Prefs.nightMode(this)) {
            Prefs.NIGHT_DAY -> "Siempre día"
            Prefs.NIGHT_ON -> "Siempre noche"
            else -> "Automático"
        }
        val items = arrayOf(
            "Modo noche: $modeName",
            "Aviso de velocidad: ${Prefs.speedLimit(this)} km/h",
            "Luces del vehículo",
            "Buscar actualización (tienes ${Updater.currentName(this)})",
            "Diagnóstico",
        )
        AlertDialog.Builder(this).setItems(items) { _, which ->
            when (which) {
                0 -> AlertDialog.Builder(this)
                    .setItems(arrayOf("Automático (según el sol)", "Siempre día", "Siempre noche")) { _, w ->
                        Prefs.setNightMode(this, listOf(Prefs.NIGHT_AUTO, Prefs.NIGHT_DAY, Prefs.NIGHT_ON)[w])
                        nightCheckedAt = 0
                        applyNight(force = true)
                    }.show()
                1 -> {
                    val limits = listOf(50, 60, 80, 100, 110, 120)
                    AlertDialog.Builder(this)
                        .setItems(limits.map { "$it km/h" }.toTypedArray()) { _, w ->
                            Prefs.setSpeedLimit(this, limits[w])
                            gauge.limit = limits[w]
                        }.show()
                }
                2 -> lightsMenu()
                3 -> Updater.checkNow(this)
                4 -> startActivity(Intent(this, DiagActivity::class.java))
            }
        }.show()
    }

    // ---------- Luces del vehículo ----------

    private fun lightsMenu() {
        val key = Syu.lightsKey()
        val msg = if (key == null) "Todavía no sé qué señal indica las luces del auto. Para enseñarle " +
            "a la radio, toca Detectar y luego enciende y apaga las luces del vehículo dos veces."
        else "Luces vinculadas a la señal $key.\nEstado actual: ${if (Syu.lightsOn) "encendidas" else "apagadas"}."
        val b = AlertDialog.Builder(this)
            .setTitle("Luces del vehículo")
            .setMessage(msg)
            .setPositiveButton("Detectar") { _, _ -> detectLights() }
            .setNegativeButton("Cerrar", null)
        if (key != null) b.setNeutralButton("Olvidar") { _, _ -> Syu.clearLights() }
        b.show()
    }

    private fun detectLights() {
        Syu.startDetect()
        val dlg = AlertDialog.Builder(this)
            .setTitle("Detectando luces…")
            .setMessage("Ahora enciende y apaga las luces del auto dos veces.\nTienes 20 segundos.")
            .setCancelable(false)
            .show()
        handler.postDelayed({
            dlg.dismiss()
            val found = Syu.finishDetect()
            AlertDialog.Builder(this)
                .setTitle("Luces del vehículo")
                .setMessage(if (found != null) "Listo, encontré la señal ($found). El auto en pantalla " +
                    "encenderá sus luces junto con las tuyas."
                    else "No detecté ningún cambio. Puede que el cable de iluminación (ILL) de la radio " +
                    "no esté conectado. Intenta de nuevo o revisa la instalación.")
                .setPositiveButton("OK", null)
                .show()
        }, 20_000)
    }

    // ---------- Modo noche ----------

    private var nightOverlay: View? = null
    private var isNightNow: Boolean? = null
    private var nightCheckedAt = 0L

    private fun applyNight(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - nightCheckedAt < 60_000L) return
        nightCheckedAt = now
        val night = when (Prefs.nightMode(this)) {
            Prefs.NIGHT_DAY -> false
            Prefs.NIGHT_ON -> true
            else -> Sun.isNight(lastLocation?.latitude, lastLocation?.longitude)
        }
        if (!force && night == isNightNow) return
        isNightNow = night
        if (nightOverlay == null) {
            val content = findViewById<android.widget.FrameLayout>(android.R.id.content)
            nightOverlay = View(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                isClickable = false
                isFocusable = false
            }
            content.addView(nightOverlay, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        }
        nightOverlay?.alpha = if (night) 0.45f else 0f
        val lp = window.attributes
        lp.screenBrightness = if (night) 0.12f
            else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    // ---------- GPS ----------

    private fun hasLocationPermission(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun askLocationPermission() {
        if (Build.VERSION.SDK_INT >= 23 && !hasLocationPermission()) {
            requestPermissions(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (hasLocationPermission()) startGps()
    }

    private fun startGps() {
        if (gpsRunning || !hasLocationPermission()) return
        val manager = lm ?: return
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
            gpsRunning = true
            if (lastLocation == null) {
                lastLocation = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
            if (Build.VERSION.SDK_INT >= 24) {
                val cb = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        var used = 0
                        for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
                        satsUsed = used
                        satsSeen = status.satelliteCount
                    }
                }
                manager.registerGnssStatusCallback(cb)
                gnssCallback = cb
            }
        } catch (e: SecurityException) {
            gpsRunning = false
        } catch (e: Exception) {
            gpsRunning = false
        }
    }

    private fun stopGps() {
        val manager = lm ?: return
        try {
            manager.removeUpdates(locationListener)
            if (Build.VERSION.SDK_INT >= 24) {
                (gnssCallback as? GnssStatus.Callback)?.let { manager.unregisterGnssStatusCallback(it) }
            }
        } catch (e: Exception) {
        }
        gnssCallback = null
        gpsRunning = false
    }

    private fun onLocation(loc: Location) {
        lastFixAt = System.currentTimeMillis()
        lastLocation = loc
        val kmh = if (loc.hasSpeed()) (loc.speed * 3.6f).roundToInt() else 0
        gauge.setSpeed(if (kmh < 2) 0 else kmh)
        if (loc.hasBearing() && kmh >= 3) gauge.heading = cardinal(loc.bearing)
    }

    private fun cardinal(deg: Float): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        return names[(((deg % 360) + 360 + 22.5f) / 45f).toInt() % 8]
    }

    // ---------- Ciclo de cada segundo ----------

    private fun tick() {
        val now = System.currentTimeMillis()
        val fixAge = now - lastFixAt

        gauge.status = when {
            !hasLocationPermission() -> "Sin permiso de ubicación"
            lastFixAt == 0L || fixAge > 5000 -> {
                gauge.setSpeed(0)
                if (satsSeen > 0) "Buscando GPS · $satsSeen sat" else "Buscando GPS"
            }
            else -> "GPS · $satsUsed sat"
        }

        updateWeatherIfNeeded(now)
        applyNight()
        gauge.setLights(Syu.lightsOn)
        refreshMusic()
    }

    // ---------- Clima ----------

    private fun updateWeatherIfNeeded(now: Long) {
        val loc = lastLocation ?: return
        if (weatherLoading) return
        if (now - weatherAt < 30 * 60_000L) return
        if (now - weatherTryAt < 2 * 60_000L) return
        weatherLoading = true
        weatherTryAt = now
        val lat = loc.latitude
        val lon = loc.longitude
        Thread {
            val info = Weather.fetch(applicationContext, lat, lon)
            runOnUiThread {
                weatherLoading = false
                if (info != null) {
                    weatherAt = System.currentTimeMillis()
                    weatherTemp.text = "${info.temp}°"
                    weatherDesc.text = if (info.place != null) "${info.desc} · ${info.place}" else info.desc
                    weatherPlace.text = info.place ?: ""
                    weatherIcon.setImageResource(info.icon)
                    weatherIcon.visibility = View.VISIBLE
                    if (info.max != null && info.min != null) {
                        weatherRange.text = "↑ ${info.max}°   ↓ ${info.min}°"
                        weatherRange.visibility = View.VISIBLE
                    }
                } else if (weatherAt == 0L) {
                    weatherDesc.text = "Sin conexión"
                }
            }
        }.start()
    }

    // ---------- Música ----------

    private fun refreshMusicSoon() {
        handler.postDelayed({ refreshMusic() }, 400)
    }

    private fun refreshMusic() {
        val np = Music.nowPlaying(this)
        if (np == null || np.title.isNullOrBlank()) {
            musicSource.text = np?.source ?: "Música"
            if (Music.hasAccess(this)) {
                musicTitle.text = "Sin reproducción"
                musicArtist.text = "Toca para abrir tu app de música"
            } else {
                musicTitle.text = "Controles de música"
                musicArtist.text = "Toca aquí para mostrar la canción"
            }
            btnPlay.setImageResource(R.drawable.ic_play)
            showArt(null)
            showTimes(-1, -1)
            return
        }
        musicSource.text = np.source
        musicTitle.text = np.title
        musicArtist.text = listOfNotNull(np.artist, np.album).filter { it.isNotBlank() }.joinToString(" · ")
        btnPlay.setImageResource(if (np.playing) R.drawable.ic_pause else R.drawable.ic_play)
        showTimes(np.positionMs, np.durationMs)
        showArt(np.art ?: AlbumArt.get(np.artist, np.title!!) {
            runOnUiThread { refreshMusic() }
        })
    }

    private fun showArt(bmp: android.graphics.Bitmap?) {
        if (bmp === artShown && (bmp != null || musicArt.drawable != null)) return
        artShown = bmp
        val pad = if (bmp == null) (40 * resources.displayMetrics.density).toInt() else 0
        musicArt.setPadding(pad, pad, pad, pad)
        if (bmp == null) {
            musicArt.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_dim))
            musicArt.setImageResource(R.drawable.ic_music)
        } else {
            musicArt.imageTintList = null
            musicArt.setImageBitmap(bmp)
        }
    }

    private fun showTimes(pos: Long, dur: Long) {
        val known = dur > 0
        musicProgress.visibility = if (known) android.view.View.VISIBLE else android.view.View.INVISIBLE
        musicElapsed.text = if (pos >= 0) fmt(pos) else ""
        musicTotal.text = if (known) fmt(dur) else ""
        if (known) musicProgress.progress = ((pos.coerceIn(0, dur) * 1000) / dur).toInt()
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
