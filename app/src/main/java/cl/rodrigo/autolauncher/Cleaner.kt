package cl.rodrigo.autolauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.maplibre.android.offline.OfflineManager
import java.io.File

/**
 * Limpieza de lo que ya no sirve: caché temporal (mapa clásico, actualizaciones ya
 * instaladas, archivos temporales) y la caché de navegación del mapa nuevo.
 * NO borra las zonas de mapa descargadas, los ajustes, el odómetro ni tus fotos.
 */
object Cleaner {
    private val main = Handler(Looper.getMainLooper())

    private fun size(f: File?): Long = when {
        f == null || !f.exists() -> 0L
        f.isFile -> f.length()
        else -> f.listFiles()?.sumOf { size(it) } ?: 0L
    }

    /** Espacio que usa la app en disco (sin contar la instalación). */
    fun usedBytes(ctx: Context): Long =
        size(ctx.cacheDir) + size(ctx.filesDir) + size(ctx.externalCacheDir) +
            size(ctx.getExternalFilesDir(null)) + size(File(ctx.applicationInfo.dataDir, "databases"))

    fun mb(bytes: Long): String = String.format(java.util.Locale("es", "CL"), "%.1f MB", bytes / 1048576.0)

    /** Borra y avisa cuánto se liberó (en el hilo principal). */
    fun clean(ctx: Context, done: (freed: Long, now: Long) -> Unit) {
        val app = ctx.applicationContext
        val before = usedBytes(app)
        Thread {
            // 1) Caché temporal completa: piezas del mapa clásico, APK de actualizaciones, temporales
            app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            app.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
            main.post {
                // 2) Mapa nuevo: borra lo que se fue viendo al pasar, conserva las zonas descargadas,
                //    y compacta la base de datos para devolver el espacio
                val finish = { main.postDelayed({ val now = usedBytes(app); done((before - now).coerceAtLeast(0), now) }, 500) }
                try {
                    if (!VectorMapPanel.supported(app)) { finish(); return@post }
                    org.maplibre.android.MapLibre.getInstance(app)
                    val om = OfflineManager.getInstance(app)
                    om.clearAmbientCache(object : OfflineManager.FileSourceCallback {
                        override fun onSuccess() {
                            om.packDatabase(object : OfflineManager.FileSourceCallback {
                                override fun onSuccess() { finish() }
                                override fun onError(message: String) { finish() }
                            })
                        }
                        override fun onError(message: String) { finish() }
                    })
                } catch (e: Throwable) { Diag.add("LIMPIEZA mapa: $e"); finish() }
            }
        }.start()
    }
}
