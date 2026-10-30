package cl.rodrigo.autolauncher

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

data class AppEntry(val label: String, val pkg: String, val activity: String) {
    var icon: Drawable? = null
}

/** Un acceso de la barra inferior (o de la tarjeta de música). */
data class Slot(val key: String, val label: String, val icon: Int, val hints: List<String>)

object Apps {
    const val SETTINGS = "__settings__"
    private const val PREFS = "launcher"

    val slots = listOf(
        Slot("maps", "Mapas", R.drawable.ic_nav,
            listOf("com.google.android.apps.maps", "com.waze", "waze", "maps", "mapas", "navi")),
        Slot("carlink", "Car Link", R.drawable.ic_link,
            listOf("car link", "carlink", "zlink", "autokit", "carplay", "android auto")),
        Slot("radio", "Radio", R.drawable.ic_radio,
            listOf("radio", "fm radio", ".fm")),
        Slot("phone", "Teléfono", R.drawable.ic_phone,
            listOf("bluetooth", "teléfono", "telefono", "phone", "dialer", "btphone")),
        Slot("settings", "Ajustes", R.drawable.ic_tune, emptyList()),
    )

    val musicSlot = Slot("music", "Música", R.drawable.ic_music,
        listOf("com.spotify.music", "spotify", "tidal", "youtube music", "music", "música", "musica"))

    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .distinctBy { it.pkg + "/" + it.activity }
            .sortedBy { it.label.lowercase() }
    }

    const val MAX_DOCK = 6
    private val defaultDock = listOf("maps", "phone", "settings", "carlink", "radio")

    fun dockKeys(ctx: Context): MutableList<String> {
        val raw = prefs(ctx).getString("dock", null) ?: return defaultDock.toMutableList()
        return raw.split(",").filter { it.isNotBlank() }.toMutableList()
    }

    fun saveDock(ctx: Context, keys: List<String>) {
        prefs(ctx).edit().putString("dock", keys.joinToString(",")).apply()
    }

    fun resetDock(ctx: Context) = prefs(ctx).edit().remove("dock").apply()

    fun slotFor(key: String): Slot? = slots.firstOrNull { it.key == key }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun assigned(ctx: Context, slot: Slot): String? {
        prefs(ctx).getString(slot.key, null)?.let { return it }
        if (slot.key == "settings") return SETTINGS
        val guess = guess(ctx, slot) ?: return null
        prefs(ctx).edit().putString(slot.key, guess).apply()
        return guess
    }

    fun assign(ctx: Context, slot: Slot, value: String) {
        prefs(ctx).edit().putString(slot.key, value).apply()
    }

    /** Busca una app instalada que calce con las pistas del acceso. */
    private fun guess(ctx: Context, slot: Slot): String? {
        val apps = load(ctx)
        for (hint in slot.hints) {
            apps.firstOrNull { it.pkg == hint }?.let { return it.pkg }
            apps.firstOrNull { it.label.lowercase() == hint }?.let { return it.pkg }
        }
        for (hint in slot.hints) {
            apps.firstOrNull { it.label.lowercase().contains(hint) || it.pkg.lowercase().contains(hint) }
                ?.let { return it.pkg }
        }
        return null
    }

    /** Abre el acceso. Si no tiene app asignada, muestra el selector. */
    fun open(ctx: Context, slot: Slot, onChanged: () -> Unit) {
        val target = assigned(ctx, slot)
        if (target == null) {
            pick(ctx, slot, onChanged); return
        }
        if (!launch(ctx, target)) {
            Toast.makeText(ctx, "No pude abrir la app, elige otra", Toast.LENGTH_SHORT).show()
            pick(ctx, slot, onChanged)
        }
    }

    fun launch(ctx: Context, target: String): Boolean {
        return try {
            val intent = if (target == SETTINGS) Intent(Settings.ACTION_SETTINGS)
            else ctx.packageManager.getLaunchIntentForPackage(target) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun launch(ctx: Context, app: AppEntry) {
        try {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(app.pkg, app.activity)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, "No se pudo abrir ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Selector para asignar una app a un acceso (mantener presionado). */
    fun pick(ctx: Context, slot: Slot, onChanged: () -> Unit) {
        val list = load(ctx).toMutableList()
        if (slot.key == "settings") {
            list.add(0, AppEntry("Ajustes del sistema", SETTINGS, ""))
        }
        val adapter = AppAdapter(ctx, list, R.layout.item_pick)
        AlertDialog.Builder(ctx)
            .setTitle("Elegir app para \"${slot.label}\"")
            .setAdapter(adapter) { _, which ->
                assign(ctx, slot, list[which].pkg)
                onChanged()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

class AppAdapter(
    private val ctx: Context,
    private val items: List<AppEntry>,
    private val layout: Int,
) : BaseAdapter() {
    private val inflater = LayoutInflater.from(ctx)

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: inflater.inflate(layout, parent, false)
        val app = items[position]
        val iconView = v.findViewById<ImageView>(R.id.appIcon)
        v.findViewById<TextView>(R.id.appLabel).text = app.label
        if (app.pkg == Apps.SETTINGS) {
            iconView.setImageResource(R.drawable.ic_tune)
        } else {
            if (app.icon == null) {
                app.icon = try {
                    ctx.packageManager.getActivityIcon(
                        android.content.ComponentName(app.pkg, app.activity))
                } catch (e: Exception) {
                    ctx.packageManager.defaultActivityIcon
                }
            }
            iconView.setImageDrawable(app.icon)
        }
        return v
    }
}
