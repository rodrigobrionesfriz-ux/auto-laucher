package cl.rodrigo.autolauncher

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

/** Registro en memoria de lo que llega (broadcasts y notificaciones). */
object Diag {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(s: String) = synchronized(this) {
        lines.addFirst("${fmt.format(Date())}  ${s.take(600)}")
        while (lines.size > 60) lines.removeLast()
    }

    fun all(): String = synchronized(this) {
        if (lines.isEmpty()) "(nada recibido todavía)" else lines.joinToString("\n\n")
    }
}

/**
 * Busca dentro de las apps de Bluetooth/música de la radio los nombres de mensajes
 * internos (acciones de broadcast) que podrían llevar la canción, para escucharlos.
 */
object Discovery {
    private const val PREFS = "discovery"
    private val keywords = listOf("id3", "music", "track", "song", "meta", "playinfo", "play_info",
        "avrcp", "a2dp", "title", "playstate", "play_state", "media_info", "mediainfo", "btmusic")
    private val actionRegex = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+){2,}$")

    fun cached(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet("actions", emptySet())!!.toList()

    fun scannedPackages(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pkgs", "") ?: ""

    fun needsScan(ctx: Context): Boolean =
        !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("actions")

    private fun interesting(pkg: String): Boolean {
        val p = pkg.lowercase()
        return listOf("bt", "bluetooth", "music", "media", "a2dp", "avrcp").any { p.contains(it) } &&
            !p.startsWith("com.google.") && !p.startsWith("cl.rodrigo.")
    }

    /** Lento (lee los .dex). Llamar fuera del hilo principal. */
    fun scan(ctx: Context) {
        val pm = ctx.packageManager
        val found = LinkedHashSet<String>()
        val pkgs = ArrayList<String>()
        for (app in pm.getInstalledApplications(0)) {
            if (!interesting(app.packageName)) continue
            // Solo apps del sistema o preinstaladas (las de la radio)
            if (app.flags and ApplicationInfo.FLAG_SYSTEM == 0 && !app.packageName.contains("bt", true)) continue
            pkgs.add(app.packageName)
            try {
                ZipFile(app.sourceDir).use { zip ->
                    for (entry in zip.entries().toList()) {
                        if (!entry.name.startsWith("classes") || !entry.name.endsWith(".dex")) continue
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        extract(bytes, found)
                        if (found.size > 400) break
                    }
                }
            } catch (e: Exception) {
            }
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("actions", found.take(400).toSet())
            .putString("pkgs", pkgs.joinToString(", "))
            .apply()
    }

    private fun extract(bytes: ByteArray, out: MutableSet<String>) {
        val sb = StringBuilder()
        fun flush() {
            if (sb.length in 10..120) {
                val s = sb.toString()
                val low = s.lowercase()
                if (actionRegex.matches(s) && keywords.any { low.contains(it) } &&
                    !low.startsWith("android.support") && !low.startsWith("androidx")) {
                    out.add(s)
                }
            }
            sb.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x21..0x7E) sb.append(c.toChar()) else flush()
        }
        flush()
    }
}

/** Pantalla de diagnóstico: mantener presionado el reloj para abrirla. */
class DiagActivity : Activity() {
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 18f
            setTextColor(getColor(R.color.text))
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_button)
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
            setOnClickListener { action() }
            bar.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                (56 * dp).toInt()).apply { marginEnd = (10 * dp).toInt() })
        }
        button("‹ Volver") { finish() }
        button("Actualizar") { refresh() }
        button("Buscar de nuevo") {
            text.text = "Buscando en las apps de la radio… (puede tardar un minuto)"
            Thread {
                Discovery.scan(applicationContext)
                Music.reRegister(applicationContext)
                runOnUiThread { refresh() }
            }.start()
        }
        root.addView(bar)

        text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(getColor(R.color.text))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(text) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            .apply { topMargin = (8 * dp).toInt() })
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        val actions = Discovery.cached(this)
        text.text = buildString {
            append("== SESIONES MULTIMEDIA ==\n")
            append(Music.sessionsReport(this@DiagActivity))
            append("\n\n== RECIBIDO (más reciente arriba) ==\n")
            append(Diag.all())
            append("\n\n== APPS REVISADAS ==\n")
            append(Discovery.scannedPackages(this@DiagActivity).ifBlank { "(ninguna)" })
            append("\n\n== MENSAJES ESCUCHADOS (${actions.size}) ==\n")
            append(actions.sorted().joinToString("\n"))
        }
    }
}
