package cl.rodrigo.autolauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(val tag: String, val code: Int, val apkUrl: String, val size: Long)

/** Busca versiones nuevas en GitHub, descarga el APK y abre el instalador. */
object Updater {
    private const val API = "https://api.github.com/repos/rodrigobrionesfriz-ux/auto-laucher/releases/latest"
    private const val CHECK_EVERY = 6 * 60 * 60_000L

    fun currentCode(ctx: Context): Int = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
    } catch (e: Exception) { 0 }

    fun currentName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    /** Llamar fuera del hilo principal. */
    fun latest(): Release? = try {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val j = JSONObject(body)
        val tag = j.getString("tag_name")
        val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
        val assets = j.getJSONArray("assets")
        var url: String? = null
        var size = 0L
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) {
                url = a.getString("browser_download_url"); size = a.optLong("size"); break
            }
        }
        if (url == null) null else Release(tag, code, url, size)
    } catch (e: Exception) {
        null
    }

    /** Revisión silenciosa al abrir el launcher (cada 6 horas como máximo). */
    fun checkInBackground(act: Activity) {
        val prefs = act.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("update_checked", 0) < CHECK_EVERY) return
        prefs.edit().putLong("update_checked", now).apply()
        Thread {
            val r = latest() ?: return@Thread
            if (r.code <= currentCode(act)) return@Thread
            if (prefs.getString("update_offered", null) == r.tag) return@Thread
            prefs.edit().putString("update_offered", r.tag).apply()
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                AlertDialog.Builder(act)
                    .setTitle("Versión nueva disponible")
                    .setMessage("Hay una actualización del launcher (${r.tag}). ¿Instalarla ahora?")
                    .setPositiveButton("Actualizar") { _, _ -> download(act, r) }
                    .setNegativeButton("Más tarde", null)
                    .show()
            }
        }.start()
    }

    /** Revisión pedida desde el menú. */
    fun checkNow(act: Activity) {
        Toast.makeText(act, "Buscando actualización…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = latest()
            act.runOnUiThread {
                when {
                    r == null -> AlertDialog.Builder(act)
                        .setTitle("Sin conexión")
                        .setMessage("No pude revisar GitHub. Revisa que la radio tenga internet (WiFi o hotspot).")
                        .setPositiveButton("OK", null).show()
                    r.code <= currentCode(act) -> AlertDialog.Builder(act)
                        .setTitle("Estás al día")
                        .setMessage("Ya tienes la última versión (${currentName(act)}).")
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Reinstalar") { _, _ -> download(act, r) }
                        .show()
                    else -> AlertDialog.Builder(act)
                        .setTitle("Versión nueva: ${r.tag}")
                        .setMessage("Tienes la ${currentName(act)}. ¿Descargar e instalar la nueva?")
                        .setPositiveButton("Actualizar") { _, _ -> download(act, r) }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
        }.start()
    }

    private fun canInstall(act: Activity): Boolean {
        if (Build.VERSION.SDK_INT < 26 || act.packageManager.canRequestPackageInstalls()) return true
        AlertDialog.Builder(act)
            .setTitle("Falta un permiso")
            .setMessage("Para actualizarse solo, el launcher necesita permiso para instalar apps. " +
                "Actívalo en la pantalla que se abrirá, vuelve y toca Actualizar otra vez.")
            .setPositiveButton("Abrir ajustes") { _, _ ->
                try {
                    act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.packageName)))
                } catch (e: Exception) {
                    act.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
        return false
    }

    @Suppress("DEPRECATION")
    fun download(act: Activity, r: Release) {
        if (!canInstall(act)) return
        val dlg = ProgressDialog(act).apply {
            setTitle("Descargando ${r.tag}")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }
        Thread {
            val file = File(act.cacheDir, "updates/auto-launcher.apk")
            val ok = try {
                file.parentFile?.mkdirs()
                var conn = URL(r.apkUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                // Seguir redirecciones entre dominios a mano por si acaso
                var hops = 0
                while (conn.responseCode in 300..399 && hops < 5) {
                    val next = conn.getHeaderField("Location")
                    conn.disconnect()
                    conn = URL(next).openConnection() as HttpURLConnection
                    hops++
                }
                val total = if (conn.contentLength > 0) conn.contentLength.toLong() else r.size
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                act.runOnUiThread { dlg.progress = pct }
                            }
                        }
                    }
                }
                conn.disconnect()
                file.length() > 100_000
            } catch (e: Exception) {
                false
            }
            act.runOnUiThread {
                dlg.dismiss()
                if (ok) install(act) else Toast.makeText(act,
                    "No se pudo descargar. Revisa el internet e intenta de nuevo.", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun install(act: Activity) {
        val uri = Uri.parse("content://${act.packageName}.updates/auto-launcher.apk")
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            act.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(act, "No se pudo abrir el instalador: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

/** Entrega el APK descargado al instalador del sistema. */
class UpdateProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = File(context!!.cacheDir, "updates/auto-launcher.apk")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "application/vnd.android.package-archive"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val file = File(context!!.cacheDir, "updates/auto-launcher.apk")
        val cursor = android.database.MatrixCursor(arrayOf(
            android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE))
        cursor.addRow(arrayOf<Any>("auto-launcher.apk", file.length()))
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
