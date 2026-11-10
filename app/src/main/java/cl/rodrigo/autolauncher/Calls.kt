package cl.rodrigo.autolauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract

/**
 * Estado de la llamada Bluetooth, leído del servicio interno de la radio (módulo 2 de SYU).
 * Las "casillas" de número, nombre y estado se aprenden con una llamada de prueba;
 * mientras tanto se usa una heurística: hay llamada cuando aparece un número telefónico.
 */
object Calls {
    private const val PREFS = "calls"
    private const val BT = 2
    // Casillas que ya sabemos que son música o datos del teléfono (no de la llamada)
    private val known = setOf(0, 1, 26, 6, 7, 14, 15, 17)

    @Volatile var active = false; private set
    @Volatile var number: String? = null; private set
    @Volatile var name: String? = null; private set
    @Volatile var startAt = 0L; private set

    private var numberCode = -1
    private var nameCode = -1
    private var stateCode = -1
    private var idleValue = 0
    private var loaded = false
    private val strings = HashMap<Int, String>()
    private val ints = HashMap<Int, Int>()

    private val main = Handler(Looper.getMainLooper())
    var listener: (() -> Unit)? = null

    // ---------- Aprendizaje ----------
    private class Ev(val t: Long, val code: Int, val int: Int?, val str: String?)
    private val capture = ArrayList<Ev>()
    @Volatile var learning = false; private set
    private var learnStart = 0L
    private val startInts = HashMap<Int, Int>()
    var lastReport = ""; private set

    private val phoneRe = Regex("^[+]?[0-9][0-9 ()\\-]{4,}[0-9]$")
    fun isPhone(s: String?): Boolean = s != null && phoneRe.matches(s.trim()) && s.count { it.isDigit() } >= 5

    fun load(ctx: Context) {
        if (loaded) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        numberCode = p.getInt("number", -1)
        nameCode = p.getInt("name", -1)
        stateCode = p.getInt("state", -1)
        idleValue = p.getInt("idle", 0)
        lastReport = p.getString("report", "") ?: ""
        loaded = true
    }

    fun mappingText(): String =
        "número: ${if (numberCode >= 0) "c$numberCode" else "automático"} · " +
            "nombre: ${if (nameCode >= 0) "c$nameCode" else "?"} · " +
            "estado: ${if (stateCode >= 0) "c$stateCode (reposo=$idleValue)" else "?"}"

    /** Llamado desde Syu con cada dato del módulo Bluetooth. */
    @Synchronized
    fun onSyu(module: Int, code: Int, iv: IntArray?, sv: Array<String?>?) {
        if (module != BT) return
        val s = sv?.firstOrNull()
        val i = iv?.takeIf { it.size == 1 }?.get(0)
        if (s != null) strings[code] = s
        if (i != null) ints[code] = i
        if (learning) {
            capture.add(Ev(SystemClock.elapsedRealtime(), code, i, s))
            if (i != null && !startInts.containsKey(code)) startInts[code] = i
        }
        if (code in known) return
        evaluate()
    }

    private fun evaluate() {
        // Número: casilla aprendida o cualquier texto con forma de teléfono
        val num = if (numberCode >= 0) strings[numberCode]?.takeIf { isPhone(it) }
            else strings.entries.firstOrNull { it.key !in known && isPhone(it.value) }?.value
        val stateOn = if (stateCode >= 0) (ints[stateCode] ?: idleValue) != idleValue else null
        val now = when (stateOn) {
            null -> num != null
            else -> stateOn
        }
        val nm = if (nameCode >= 0) strings[nameCode]?.takeIf { it.isNotBlank() && !isPhone(it) } else null
        val changed = now != active || num != number || nm != name
        if (now && !active) startAt = SystemClock.elapsedRealtime()
        active = now
        number = if (now) num else null
        name = if (now) nm else null
        if (!now && numberCode < 0) {
            // sin casilla aprendida: limpiar números viejos para no "revivir" la llamada
            strings.entries.removeAll { it.key !in known && isPhone(it.value) }
        }
        if (changed) main.post { listener?.invoke() }
    }

    fun startLearning() = synchronized(this) {
        capture.clear(); startInts.clear()
        for ((k, v) in ints) startInts[k] = v
        learnStart = SystemClock.elapsedRealtime()
        learning = true
    }

    /** Analiza lo capturado y guarda las casillas encontradas. Devuelve un resumen legible. */
    fun finishLearning(ctx: Context): String = synchronized(this) {
        learning = false
        val evs = ArrayList(capture)
        val sb = StringBuilder()
        // Número: la casilla con más valores con forma de teléfono
        val numCand = evs.filter { it.str != null && isPhone(it.str) && it.code !in known }
            .groupBy { it.code }.maxByOrNull { it.value.size }
        val firstNumAt = numCand?.value?.minOf { it.t }
        // Nombre: texto (no teléfono) que cambió cerca de cuando apareció el número
        val nameCand = evs.filter {
            it.str != null && it.str.isNotBlank() && !isPhone(it.str) && it.code !in known &&
                it.code != numCand?.key && (firstNumAt == null || kotlin.math.abs(it.t - firstNumAt) < 4000)
        }.groupBy { it.code }.maxByOrNull { it.value.size }
        // Estado: entero que cambió al menos 2 veces y terminó como empezó (reposo)
        val stateCand = evs.filter { it.int != null && it.code !in known }
            .groupBy { it.code }
            .filter { (_, v) -> v.map { it.int }.distinct().size >= 2 && v.size >= 2 }
            .minByOrNull { (_, v) -> if (firstNumAt == null) 0L else kotlin.math.abs(v.first().t - firstNumAt) }

        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (numCand != null) { numberCode = numCand.key; p.putInt("number", numberCode); sb.append("Número en c${numCand.key}\n") }
        else sb.append("No vi un número de teléfono\n")
        if (nameCand != null) { nameCode = nameCand.key; p.putInt("name", nameCode); sb.append("Nombre en c${nameCand.key}: ${nameCand.value.last().str}\n") }
        else sb.append("No vi el nombre del contacto\n")
        if (stateCand != null) {
            stateCode = stateCand.key
            idleValue = startInts[stateCand.key] ?: stateCand.value.last().int ?: 0
            p.putInt("state", stateCode).putInt("idle", idleValue)
            sb.append("Estado en c${stateCand.key}: valores ${stateCand.value.map { it.int }.distinct()} (reposo $idleValue)\n")
        } else sb.append("No vi un estado de llamada\n")

        // Registro completo para el diagnóstico
        val log = StringBuilder()
        for (e in evs.take(120)) {
            log.append("+${(e.t - learnStart) / 1000}s c${e.code} ")
            if (e.int != null) log.append("int=${e.int} ")
            if (e.str != null) log.append("str=${e.str}")
            log.append('\n')
        }
        lastReport = sb.toString() + "\n" + log.toString()
        p.putString("report", lastReport).apply()
        evaluate()
        sb.toString()
    }

    fun resetLearning(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        numberCode = -1; nameCode = -1; stateCode = -1; idleValue = 0; lastReport = ""
    }

    // ---------- Contactos de la radio (si los hay) ----------
    fun lookupContact(ctx: Context, num: String): Pair<String?, Bitmap?> {
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup._ID), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null to null
                val nm = c.getString(0)
                val id = c.getLong(1)
                val photo = ContactsContract.Contacts.openContactPhotoInputStream(ctx.contentResolver,
                    android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id), true)
                    ?.use { BitmapFactory.decodeStream(it) }
                nm to photo
            } ?: (null to null)
        } catch (e: Exception) {
            null to null
        }
    }
}
