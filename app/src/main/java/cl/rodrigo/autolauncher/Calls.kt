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
    /** true cuando la llamada ya se conectó (el estado cambió después de sonar/marcar). */
    @Volatile var connected = false; private set
    @Volatile var connectedAt = 0L; private set
    private var firstState: Int? = null

    private var numberCode = -1
    private var nameCode = -1
    private var stateCode = -1
    private var idleValue = 0
    /** Otros valores de "reposo" que la radio usa después de colgar (se aprenden solos). */
    private val idleValues = HashSet<Int>()
    /** Valores que sabemos que son de llamada real (sonando/marcando/conectada): nunca se toman como reposo. */
    private val callValues = HashSet<Int>()
    private var app: Context? = null
    private var loaded = false
    // Antipegado
    private var sawNumber = false
    private var numberGoneAt = 0L
    private var mutedNumber: String? = null
    private var mutedAt = 0L
    private var lastEndAt = 0L
    private var afterCall = false
    private val strAt = HashMap<Int, Long>()
    private class Rec(val t: Long, val code: Int, val int: Int?, val str: String?)
    private val recent = ArrayDeque<Rec>()
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
        idleValues.clear(); callValues.clear()
        p.getString("idles", "")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.let { idleValues.addAll(it) }
        callValues.clear()
        p.getString("callvals", "")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.let { callValues.addAll(it) }
        app = ctx.applicationContext
        loaded = true
    }

    fun mappingText(): String =
        "número: ${if (numberCode >= 0) "c$numberCode" else "automático"} · " +
            "nombre: ${if (nameCode >= 0) "c$nameCode" else "?"} · " +
            "estado: ${if (stateCode >= 0) "c$stateCode (reposo=${(idleValues + idleValue).sorted()})" else "?"}"

    /** Últimos datos del módulo Bluetooth, para el diagnóstico. */
    @Synchronized
    fun recentText(): String {
        if (recent.isEmpty()) return "(sin datos)"
        val t0 = recent.first().t
        val sb = StringBuilder()
        for (e in recent) {
            sb.append("+${(e.t - t0) / 1000}s c${e.code} ")
            if (e.int != null) sb.append("int=${e.int} ")
            if (e.str != null) sb.append("str=${e.str}")
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Llamado desde Syu con cada dato del módulo Bluetooth. */
    @Synchronized
    fun onSyu(module: Int, code: Int, iv: IntArray?, sv: Array<String?>?) {
        if (module != BT) return
        val s = sv?.firstOrNull()
        val i = iv?.takeIf { it.size == 1 }?.get(0)
        val t = SystemClock.elapsedRealtime()
        if (s != null) {
            if (strings[code] != s) strAt[code] = t
            strings[code] = s
            if ((s != mutedNumber && isPhone(s)) || t - mutedAt > 120_000) mutedNumber = null
        }
        if (i != null) ints[code] = i
        recent.addLast(Rec(t, code, i, s))
        while (recent.size > 80) recent.removeFirst()
        if (learning) {
            capture.add(Ev(SystemClock.elapsedRealtime(), code, i, s))
            if (i != null && !startInts.containsKey(code)) startInts[code] = i
        }
        if (code in known) return
        evaluate()
    }

    private fun evaluate() {
        // Número: casilla aprendida o cualquier texto con forma de teléfono
        val num = (if (numberCode >= 0) strings[numberCode]?.takeIf { isPhone(it) }
            else strings.entries.firstOrNull { it.key !in known && isPhone(it.value) }?.value)
            ?.takeIf { it != mutedNumber }
        val sv = ints[stateCode] ?: idleValue
        val stateOn = if (stateCode >= 0) (sv != idleValue && sv !in idleValues) else null
        val now = when (stateOn) {
            null -> num != null
            // Con estado aprendido: también vale un número nuevo (por si la radio usa otro valor)
            else -> stateOn || (num != null && !active && (strAt[numberCode] ?: 0L) > SystemClock.elapsedRealtime() - 3000)
        }
        val nm = if (nameCode >= 0) strings[nameCode]?.takeIf { it.isNotBlank() && !isPhone(it) } else null
        val changed = now != active || num != number || nm != name
        if (now && !active) {
            startAt = SystemClock.elapsedRealtime()
            firstState = if (stateCode >= 0) ints[stateCode] else null
            // Sin estado aprendido no se distingue: se toma como conectada desde el inicio
            connected = stateCode < 0
            connectedAt = startAt
            sawNumber = false
            numberGoneAt = 0L
            // ¿Se "activó" justo después de colgar? Puede ser el estado de "llamada terminada"
            afterCall = lastEndAt > 0 && startAt - lastEndAt < 15_000
            if (afterCall) main.postDelayed({ checkStuck() }, 5000)
            scheduleWatchdog()
        }
        if (now && num != null) {
            if (!sawNumber) firstState?.let { protect(it) }
            sawNumber = true; numberGoneAt = 0L
        }
        if (now && num == null && sawNumber && numberGoneAt == 0L) {
            // El número desapareció: casi seguro colgaron. Se confirma en 3 s.
            numberGoneAt = SystemClock.elapsedRealtime()
            main.postDelayed({ checkStuck() }, 3200)
        }
        // Con estado aprendido: si cambia a otro valor activo, la llamada se contestó
        if (now && active && !connected && stateCode >= 0) {
            val v = ints[stateCode]
            if (v != null && v != idleValue && v != firstState) {
                connected = true
                connectedAt = SystemClock.elapsedRealtime()
                protect(v)
                main.post { listener?.invoke() }
            }
        }
        if (!now) connected = false
        if (active && !now) lastEndAt = SystemClock.elapsedRealtime()
        active = now
        number = if (now) num else null
        name = if (now) nm else null
        if (!now && numberCode < 0) {
            // sin casilla aprendida: limpiar números viejos para no "revivir" la llamada
            strings.entries.removeAll { it.key !in known && isPhone(it.value) }
        }
        if (changed) main.post { listener?.invoke() }
    }

    private fun protect(v: Int) {
        idleValues.remove(v)
        if (callValues.add(v)) app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("callvals", callValues.joinToString(","))
            ?.putString("idles", idleValues.joinToString(","))?.apply()
    }

    private fun scheduleWatchdog() {
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 15_000)
    }

    private val watchdog = Runnable { checkStuck() }

    @Synchronized
    private fun checkStuck() {
        if (!active) return
        val now = SystemClock.elapsedRealtime()
        val numberGone = sawNumber && number == null && numberGoneAt > 0 && now - numberGoneAt >= 3000
        // Nunca conectó, nunca hubo número y ya pasó un minuto: panel pegado
        val ghost = !connected && !sawNumber && now - startAt > 60_000
        // Se activó sola justo después de colgar y sin número: es el estado de "terminada"
        val postCall = afterCall && !sawNumber && !connected && now - startAt >= 4500
        if (numberGone || postCall) forceEnd(learn = true)
        else if (ghost) forceEnd()
        else if (active) main.postDelayed(watchdog, 15_000)
    }

    /**
     * Cierra el panel aunque la radio no avise. El valor de estado en que quedó pegada
     * se aprende como otro "reposo", así la próxima vez se cierra sola.
     */
    @Synchronized
    fun forceEnd(learn: Boolean = false) {
        if (!active) return
        if (learn && stateCode >= 0) {
            val v = ints[stateCode]
            if (v != null && v != idleValue && v !in callValues && idleValues.add(v)) {
                app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
                    ?.putString("idles", idleValues.joinToString(","))?.apply()
            }
        }
        mutedNumber = number
        mutedAt = SystemClock.elapsedRealtime()
        strings.entries.removeAll { it.key !in known && isPhone(it.value) }
        active = false; connected = false; number = null; name = null
        lastEndAt = SystemClock.elapsedRealtime()
        sawNumber = false; numberGoneAt = 0L
        main.removeCallbacks(watchdog)
        main.post { listener?.invoke() }
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
        idleValues.clear(); callValues.clear()
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
