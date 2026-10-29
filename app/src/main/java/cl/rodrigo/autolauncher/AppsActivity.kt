package cl.rodrigo.autolauncher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.GridView
import android.widget.TextView

class AppsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        val grid = findViewById<GridView>(R.id.grid)
        Thread {
            val apps = Apps.load(applicationContext)
            runOnUiThread {
                grid.adapter = AppAdapter(this, apps, R.layout.item_app)
                grid.setOnItemClickListener { _, _, pos, _ ->
                    Apps.launch(this, apps[pos])
                    finish()
                }
                grid.setOnItemLongClickListener { _, _, pos, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + apps[pos].pkg)))
                    } catch (e: Exception) {
                    }
                    true
                }
            }
        }.start()
    }
}
