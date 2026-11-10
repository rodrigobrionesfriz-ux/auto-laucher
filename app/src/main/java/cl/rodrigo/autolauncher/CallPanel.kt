package cl.rodrigo.autolauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Panel de llamada que se superpone al panel izquierdo (clima y accesos). */
class CallPanel(ctx: Context) : FrameLayout(ctx) {

    private val dp = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private val status: TextView
    private val avatar: Avatar
    private val nameTv: TextView
    private val numberTv: TextView
    private val timer: TextView
    private val muteBtn: ImageButton
    private var shownNumber: String? = null

    init {
        background = GradientDrawable().apply {
            colors = intArrayOf(Color.parseColor("#F2121418"), Color.parseColor("#F20A0B0D"))
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            cornerRadius = 18 * dp
            setStroke(px(2), Color.parseColor("#2BD96B"))
        }
        isClickable = true   // que no pase el toque a los íconos de abajo
        visibility = View.GONE

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(12), px(12), px(12), px(12))
        }
        status = TextView(ctx).apply {
            textSize = 13f; setTextColor(Color.parseColor("#2BD96B")); letterSpacing = 0.15f
            typeface = Fonts.medium(ctx); text = "LLAMADA"
        }
        col.addView(status)
        avatar = Avatar(ctx)
        col.addView(avatar, LinearLayout.LayoutParams(px(86), px(86)).apply { topMargin = px(8) })
        nameTv = TextView(ctx).apply {
            textSize = 22f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        }
        col.addView(nameTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
        numberTv = TextView(ctx).apply {
            textSize = 16f; setTextColor(Color.parseColor("#AEB8C4")); gravity = Gravity.CENTER; maxLines = 1
        }
        col.addView(numberTv, LinearLayout.LayoutParams(-1, -2))
        timer = TextView(ctx).apply {
            textSize = 30f; setTextColor(Color.WHITE); typeface = Fonts.bold(ctx); gravity = Gravity.CENTER; text = "00:00"
        }
        col.addView(timer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })

        val buttons = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
        fun btn(icon: Int, color: String, desc: String, onClick: () -> Unit): ImageButton =
            ImageButton(ctx).apply {
                setImageResource(icon); setColorFilter(Color.WHITE); contentDescription = desc
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(color)) }
                scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(px(15), px(15), px(15), px(15))
                setOnClickListener { onClick() }
                buttons.addView(this, LinearLayout.LayoutParams(px(58), px(58)).apply { marginStart = px(4); marginEnd = px(4) })
            }
        btn(R.drawable.ic_phone, "#2BD96B", "Contestar") { hook(ctx) }
        btn(R.drawable.ic_phone_end, "#FF3B30", "Colgar") { hook(ctx) }
        muteBtn = btn(R.drawable.ic_mic_off, "#3A3F47", "Silenciar") { toggleMute(ctx) }
        btn(R.drawable.ic_dialpad, "#3A3F47", "Teléfono") {
            Apps.slotFor("phone")?.let { Apps.open(ctx, it) {} }
        }
        col.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        addView(col, LayoutParams(-1, -1))
    }

    /** Botón de llamada del volante: contesta si suena, cuelga si hay llamada. */
    private fun hook(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = SystemClock.uptimeMillis()
        for (code in intArrayOf(KeyEvent.KEYCODE_HEADSETHOOK)) {
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        }
    }

    private fun toggleMute(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.isMicrophoneMute = !am.isMicrophoneMute
        muteBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (am.isMicrophoneMute) "#FFB547" else "#3A3F47"))
        }
        Toast.makeText(ctx, if (am.isMicrophoneMute) "Micrófono silenciado" else "Micrófono activo", Toast.LENGTH_SHORT).show()
    }

    /** Actualiza con el estado actual. Llamar al cambiar la llamada y cada segundo. */
    fun refresh() {
        if (!Calls.active) {
            if (visibility == View.VISIBLE) {
                (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMicrophoneMute = false
            }
            visibility = View.GONE; shownNumber = null; return
        }
        visibility = View.VISIBLE
        val num = Calls.number
        if (num != shownNumber) {
            shownNumber = num
            var nm = Calls.name
            var photo: Bitmap? = null
            if (num != null) {
                val (cName, cPhoto) = Calls.lookupContact(context, num)
                if (nm.isNullOrBlank()) nm = cName
                photo = cPhoto
            }
            nameTv.text = nm ?: (num ?: "Llamada")
            numberTv.text = if (nm != null && num != null) num else ""
            avatar.set(nm ?: num ?: "?", photo)
        }
        val secs = (SystemClock.elapsedRealtime() - Calls.startAt) / 1000
        timer.text = String.format("%02d:%02d", secs / 60, secs % 60)
        status.text = "LLAMADA BLUETOOTH"
    }

    /** Foto redonda o iniciales sobre degradado. */
    private class Avatar(ctx: Context) : View(ctx) {
        private var initials = "?"
        private var photo: Bitmap? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Fonts.bold(ctx)
        }
        fun set(label: String, bmp: Bitmap?) {
            photo = bmp
            val words = label.trim().split(" ").filter { it.isNotBlank() && it[0].isLetter() }
            initials = when {
                words.isEmpty() -> "☎"
                words.size == 1 -> words[0].take(1).uppercase()
                else -> (words[0].take(1) + words[1].take(1)).uppercase()
            }
            invalidate()
        }
        override fun onDraw(c: Canvas) {
            val r = minOf(width, height) / 2f
            val cx = width / 2f; val cy = height / 2f
            val bmp = photo
            if (bmp != null) {
                val path = android.graphics.Path().apply { addCircle(cx, cy, r, android.graphics.Path.Direction.CW) }
                c.save(); c.clipPath(path)
                c.drawBitmap(bmp, null, android.graphics.RectF(cx - r, cy - r, cx + r, cy + r), p)
                c.restore()
            } else {
                p.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                    Color.parseColor("#1F8A5B"), Color.parseColor("#155E75"), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r, p)
                t.textSize = r * 0.8f
                c.drawText(initials, cx, cy - (t.descent() + t.ascent()) / 2, t)
            }
        }
    }
}
