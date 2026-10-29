package cl.rodrigo.autolauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Respaldo: algunas radios ignoran el launcher predeterminado al encender. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            ctx.startActivity(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
    }
}
