package cl.rodrigo.autolauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * Conexión al servicio interno de las radios FYT/SYU (paquete com.syu.ms).
 * Ahí viaja la información de la música Bluetooth (ID3) que no publican por Android.
 * Esta primera versión escucha todo lo que el servicio manda y lo deja en el diagnóstico.
 */
object Syu {
    private const val TOOLKIT = "com.syu.ipc.IRemoteToolkit"
    private const val MODULE = "com.syu.ipc.IRemoteModule"
    private const val CALLBACK = "com.syu.ipc.IModuleCallback"
    private const val BT_MODULE = 2
    // Códigos del módulo Bluetooth (descubiertos en esta radio)
    private const val C_TITLE = 0
    private const val C_ARTIST = 1
    private const val C_DEVICE = 7
    private const val C_ALBUM = 26

    var status = "Sin conectar"
        private set
    private var bound = false
    private val callbacks = ArrayList<Cb>()
    private val lastSeen = HashMap<String, String>()

    /** Lo último que llegó con texto, por módulo/código. */
    val texts = LinkedHashMap<String, String>()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) return
            Thread { subscribe(service) }.start()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            status = "Desconectado"
        }
    }

    // ---------- Luces del vehículo ----------
    private var appCtx: Context? = null
    private val lastInt = HashMap<String, Int>()
    @Volatile var lightsOn = false
        private set
    private var detecting = false
    private val changes = HashMap<String, Int>()

    fun lightsKey(): String? = appCtx?.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        ?.getString("lights_key", null)

    fun clearLights() {
        appCtx?.getSharedPreferences("launcher", Context.MODE_PRIVATE)?.edit()?.remove("lights_key")?.apply()
        lightsOn = false
    }

    /** Empieza a observar qué valor cambia mientras el usuario enciende y apaga las luces. */
    fun startDetect() = synchronized(this) {
        changes.clear()
        detecting = true
    }

    /** Termina la detección y guarda el código que cambió (encendido y apagado). */
    fun finishDetect(): String? = synchronized(this) {
        detecting = false
        val best = changes.filter { it.value >= 2 }.maxByOrNull { it.value }?.key
            ?: changes.maxByOrNull { it.value }?.key
        if (best != null) {
            appCtx?.getSharedPreferences("launcher", Context.MODE_PRIVATE)?.edit()
                ?.putString("lights_key", best)?.apply()
            lightsOn = (lastInt[best] ?: 0) != 0
        }
        best
    }

    fun connect(ctx: Context) {
        appCtx = ctx.applicationContext
        if (bound) return
        val candidates = listOf(
            Intent("com.syu.ms.toolkit").setPackage("com.syu.ms"),
            Intent("com.syu.ms.toolkit"),
        )
        for (intent in candidates) {
            try {
                if (ctx.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
                    bound = true
                    status = "Conectando…"
                    return
                }
            } catch (e: Exception) {
                status = "Error al conectar: ${e.message}"
            }
        }
        if (!bound) status = "No se encontró el servicio com.syu.ms"
    }

    private fun subscribe(toolkit: IBinder) {
        var modules = 0
        var regs = 0
        for (m in 0..12) {
            val module = getModule(toolkit, m) ?: continue
            modules++
            val cb = Cb(m)
            callbacks.add(cb)
            for (code in 0..150) {
                if (register(module, cb, code)) regs++
            }
        }
        status = "Conectado: $modules módulos, $regs suscripciones"
        Diag.add("SYU $status")
    }

    private fun getModule(toolkit: IBinder, code: Int): IBinder? {
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(TOOLKIT)
            d.writeInt(code)
            toolkit.transact(IBinder.FIRST_CALL_TRANSACTION, d, r, 0)
            r.readException()
            r.readStrongBinder()
        } catch (e: Exception) {
            null
        } finally {
            d.recycle(); r.recycle()
        }
    }

    private fun register(module: IBinder, cb: Binder, code: Int): Boolean {
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(MODULE)
            d.writeStrongBinder(cb)
            d.writeInt(code)
            d.writeInt(1) // pedir el valor actual de inmediato
            module.transact(IBinder.FIRST_CALL_TRANSACTION + 2, d, r, 0)
            r.readException()
            true
        } catch (e: Exception) {
            false
        } finally {
            d.recycle(); r.recycle()
        }
    }

    private class Cb(val module: Int) : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(CALLBACK); return true
            }
            if (code == FIRST_CALL_TRANSACTION) {
                try {
                    data.enforceInterface(CALLBACK)
                    val updateCode = data.readInt()
                    val ints = data.createIntArray()
                    val flts = data.createFloatArray()
                    val strs = data.createStringArray()
                    onUpdate(module, updateCode, ints, flts, strs)
                } catch (e: Exception) {
                }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun onUpdate(module: Int, code: Int, ints: IntArray?, flts: FloatArray?, strs: Array<String?>?) {
        if (module == BT_MODULE) {
            val first = strs?.firstOrNull()
            when (code) {
                C_TITLE -> Music.syuTitle = first
                C_ARTIST -> Music.syuArtist = first
                C_ALBUM -> Music.syuAlbum = first
                C_DEVICE -> Music.syuDevice = first
            }
        }
        val key = "m$module c$code"
        val firstInt = ints?.takeIf { it.size == 1 }?.get(0)
        if (firstInt != null) synchronized(this) {
            val prev = lastInt.put(key, firstInt)
            // Solo valores tipo interruptor (0/1 o 0/n) cuentan para detectar luces
            if (detecting && prev != null && prev != firstInt && (prev == 0 || firstInt == 0)) {
                changes[key] = (changes[key] ?: 0) + 1
            }
            if (key == lightsKey()) lightsOn = firstInt != 0
        }
        val strText = strs?.filterNotNull()?.filter { it.isNotBlank() }.orEmpty()
        val line = "ints=${ints?.joinToString(",", "[", "]")} strs=${strText.joinToString(" | ", "[", "]")}"
        synchronized(this) {
            if (lastSeen[key] == line) return
            lastSeen[key] = line
            if (strText.isNotEmpty()) texts[key] = strText.joinToString(" | ")
        }
        // Solo registramos lo que trae texto o viene del módulo Bluetooth, para no llenar el log
        if (strText.isNotEmpty() || module == 2) Diag.add("SYU $key $line")
    }

    fun report(): String = synchronized(this) {
        buildString {
            append(status)
            if (texts.isNotEmpty()) {
                append("\nTextos recibidos:")
                texts.forEach { (k, v) -> append("\n  $k → $v") }
            }
        }
    }
}
