package cl.rodrigo.autolauncher

import android.Manifest
import android.app.Activity
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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var speedText: TextView
    private lateinit var headingText: TextView
    private lateinit var gpsText: TextView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherDesc: TextView
    private lateinit var weatherPlace: TextView
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

        speedText = findViewById(R.id.speed)
        headingText = findViewById(R.id.heading)
        gpsText = findViewById(R.id.gpsStatus)
        weatherTemp = findViewById(R.id.weatherTemp)
        weatherDesc = findViewById(R.id.weatherDesc)
        weatherPlace = findViewById(R.id.weatherPlace)
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
        findViewById<TextView>(R.id.clock).setOnLongClickListener {
            startActivity(Intent(this, DiagActivity::class.java)); true
        }

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

        val musicInfo = findViewById<LinearLayout>(R.id.musicInfo)
        musicInfo.setOnClickListener {
            if (!Music.hasAccess(this)) {
                if (!Music.openAccessSettings(this)) {
                    Toast.makeText(this, "Esta radio no permite ver la canción actual", Toast.LENGTH_LONG).show()
                }
            } else {
                Apps.open(this, Apps.musicSlot) {}
            }
        }
        musicInfo.setOnLongClickListener { Apps.pick(this, Apps.musicSlot) {}; true }

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
        val margin = (10 * resources.displayMetrics.density).toInt()

        fun addTile(label: String, icon: Int, first: Boolean): LinearLayout {
            val tile = inflater.inflate(R.layout.item_tile, dock, false) as LinearLayout
            tile.findViewById<ImageView>(R.id.tileIcon).setImageResource(icon)
            tile.findViewById<TextView>(R.id.tileLabel).text = label
            if (!first) (tile.layoutParams as LinearLayout.LayoutParams).marginStart = margin
            dock.addView(tile)
            return tile
        }

        Apps.slots.forEachIndexed { i, slot ->
            val tile = addTile(slot.label, slot.icon, i == 0)
            tile.setOnClickListener { Apps.open(this, slot) {} }
            tile.setOnLongClickListener {
                Apps.pick(this, slot) {
                    Toast.makeText(this, "${slot.label} actualizado", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
        addTile("Apps", R.drawable.ic_apps, false).setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }
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
        speedText.text = if (kmh < 2) "0" else kmh.toString()
        if (loc.hasBearing() && kmh >= 3) headingText.text = cardinal(loc.bearing)
    }

    private fun cardinal(deg: Float): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        return names[(((deg % 360) + 360 + 22.5f) / 45f).toInt() % 8]
    }

    // ---------- Ciclo de cada segundo ----------

    private fun tick() {
        val now = System.currentTimeMillis()
        val fixAge = now - lastFixAt

        gpsText.text = when {
            !hasLocationPermission() -> "Sin permiso de ubicación"
            lastFixAt == 0L || fixAge > 5000 -> {
                speedText.text = "0"
                if (satsSeen > 0) "Buscando GPS · $satsSeen sat" else "Buscando GPS"
            }
            else -> "GPS · $satsUsed sat"
        }

        updateWeatherIfNeeded(now)
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
                    weatherDesc.text = info.desc
                    weatherPlace.text = info.place ?: ""
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
        val pad = if (bmp == null) (26 * resources.displayMetrics.density).toInt() else 0
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
