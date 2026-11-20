package cl.rodrigo.autolauncher

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/**
 * Aleatorio y repetir.
 * 1) Apps en la radio: por la sesión multimedia (setShuffleMode / setRepeatMode).
 * 2) Música del teléfono por Bluetooth: por el perfil AVRCP ("ajustes del reproductor"),
 *    el mismo canal que usan las radios de fábrica. Funciona si el teléfono y la app
 *    (Tidal, Spotify…) lo aceptan.
 */
object PlayModes {
    const val REPEAT_OFF = 0
    const val REPEAT_ONE = 1
    const val REPEAT_ALL = 2

    // Estado mostrado (si el teléfono no informa, se recuerda lo último que pedimos)
    var shuffle = false; private set
    var repeat = REPEAT_OFF; private set

    private var avrcp: BluetoothProfile? = null
    private const val AVRCP_CONTROLLER = 12   // BluetoothProfile.AVRCP_CONTROLLER (oculto)

    fun init(ctx: Context) {
        if (avrcp != null) return
        try {
            BluetoothAdapter.getDefaultAdapter()?.getProfileProxy(ctx.applicationContext,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { avrcp = proxy }
                    override fun onServiceDisconnected(profile: Int) { avrcp = null }
                }, AVRCP_CONTROLLER)
        } catch (e: Throwable) { Diag.add("MODOS avrcp: $e") }
    }

    private fun session(ctx: Context): MediaController? = try {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val list = msm.getActiveSessions(ComponentName(ctx, MediaListener::class.java))
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
    } catch (_: Throwable) { null }

    private fun btDevice(): BluetoothDevice? = try {
        avrcp?.connectedDevices?.firstOrNull()
    } catch (_: Throwable) { null }

    // ---- AVRCP por reflexión (API oculta de Android 8.1) ----
    private val settingsCls by lazy { try { Class.forName("android.bluetooth.BluetoothAvrcpPlayerSettings") } catch (_: Throwable) { null } }
    private fun const(name: String): Int = settingsCls?.getField(name)?.getInt(null) ?: -99

    private fun avrcpGet(): Pair<Int?, Int?>? = try {
        val p = avrcp ?: return null
        val dev = btDevice() ?: return null
        val s = p.javaClass.getMethod("getPlayerSettings", BluetoothDevice::class.java).invoke(p, dev) ?: return null
        val mask = s.javaClass.getMethod("getSettings").invoke(s) as Int
        val getV = s.javaClass.getMethod("getSettingValue", Int::class.javaPrimitiveType)
        val sh = const("SETTING_SHUFFLE"); val rp = const("SETTING_REPEAT")
        Pair(if (mask and sh != 0) getV.invoke(s, sh) as Int else null,
             if (mask and rp != 0) getV.invoke(s, rp) as Int else null)
    } catch (e: Throwable) { null }

    private fun avrcpSet(settingName: String, valueName: String): Boolean = try {
        val p = avrcp ?: throw IllegalStateException("sin perfil AVRCP")
        val cls = settingsCls ?: throw IllegalStateException("sin BluetoothAvrcpPlayerSettings")
        val setting = const(settingName)
        val obj = cls.getConstructor(Int::class.javaPrimitiveType).newInstance(setting)
        cls.getMethod("addSettingValue", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(obj, setting, const(valueName))
        val ok = p.javaClass.getMethod("setPlayerApplicationSetting", cls).invoke(p, obj) as? Boolean ?: true
        Diag.add("MODOS avrcp $settingName=$valueName -> $ok")
        ok
    } catch (e: Throwable) { Diag.add("MODOS avrcp $settingName falló: $e"); false }

    /**
     * Apps de la radio: el modo aleatorio/repetir se pide con la acción estándar de la
     * librería multimedia de Android (la usan Spotify, YouTube Music, Tidal, etc.).
     */
    private fun compat(c: MediaController, what: String, value: Int): Boolean = try {
        val b = android.os.Bundle()
        b.putInt("android.support.v4.media.session.action.ARGUMENT_${what}_MODE", value)
        c.transportControls.sendCustomAction("android.support.v4.media.session.action.SET_${what}_MODE", b)
        true
    } catch (_: Throwable) { false }

    /** Lee el estado real si el reproductor lo informa. */
    fun refresh(ctx: Context) {
        avrcpGet()?.let { (sh, rp) ->
            sh?.let { shuffle = it == const("STATE_ALL_TRACK") || it == const("STATE_GROUP") }
            rp?.let {
                repeat = when (it) {
                    const("STATE_SINGLE_TRACK") -> REPEAT_ONE
                    const("STATE_ALL_TRACK"), const("STATE_GROUP") -> REPEAT_ALL
                    else -> REPEAT_OFF
                }
            }
        }
    }

    fun toggleShuffle(ctx: Context): Boolean {
        val want = !shuffle
        var ok = false
        val c = session(ctx)
        if (c != null && c.packageName != "com.android.bluetooth")
            ok = compat(c, "SHUFFLE", if (want) 1 else 0)
        if (c == null || c.packageName == "com.android.bluetooth" || !ok)
            ok = avrcpSet("SETTING_SHUFFLE", if (want) "STATE_ALL_TRACK" else "STATE_OFF") || ok
        shuffle = want
        return ok
    }

    /** Recorre: apagado → todas → una. */
    fun cycleRepeat(ctx: Context): Boolean {
        val want = when (repeat) { REPEAT_OFF -> REPEAT_ALL; REPEAT_ALL -> REPEAT_ONE; else -> REPEAT_OFF }
        var ok = false
        val c = session(ctx)
        if (c != null && c.packageName != "com.android.bluetooth")
            ok = compat(c, "REPEAT", when (want) { REPEAT_ONE -> 1; REPEAT_ALL -> 2; else -> 0 })
        if (c == null || c.packageName == "com.android.bluetooth" || !ok)
            ok = avrcpSet("SETTING_REPEAT", when (want) {
                REPEAT_ONE -> "STATE_SINGLE_TRACK"; REPEAT_ALL -> "STATE_ALL_TRACK"; else -> "STATE_OFF"
            }) || ok
        repeat = want
        return ok
    }
}
