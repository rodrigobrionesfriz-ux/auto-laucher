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
    private val answerBtn: ImageButton
    private var shownNumber: String? = null
    private val col: LinearLayout

    // Forma de 6 lados, igual al panel izquierdo del marco (mismas proporciones que CockpitFrame)
    private val hex = android.graphics.Path()
    private val region = android.graphics.Region()
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * resources.displayMetrics.density
        color = Color.parseColor("#2BD96B")
    }
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 8f * resources.displayMetrics.density
        color = Color.argb(50, 43, 217, 107)
    }

    /** Diseño Moderno: zona de la columna izquierda (coordenadas de este panel). */
    var cardBounds: android.graphics.RectF? = null
        set(v) { field = v; if (width > 0) layoutShape(width, height); invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutShape(w, h)
    }

    private fun layoutShape(w: Int, h: Int) {
        val lp = col.layoutParams as LayoutParams
        hex.reset()
        val card = cardBounds
        if (card != null) {
            val r = 20 * dp
            hex.addRoundRect(card, r, r, android.graphics.Path.Direction.CW)
            pFill.shader = LinearGradient(0f, card.top, 0f, card.bottom,
                Color.parseColor("#FF1B2027"), Color.parseColor("#FF0D1014"), Shader.TileMode.CLAMP)
            lp.width = (card.width() - 24 * dp).toInt()
            lp.height = (card.height() - 20 * dp).toInt()
            lp.leftMargin = (card.left + 12 * dp).toInt()
            lp.topMargin = (card.top + 14 * dp).toInt()
        } else {
            val pw = w * 0.29f
            hex.moveTo(18f, h * 0.13f); hex.lineTo(pw * 0.78f, h * 0.10f)
            hex.lineTo(pw, h * 0.20f); hex.lineTo(pw, h * 0.80f)
            hex.lineTo(pw * 0.78f, h * 0.88f); hex.lineTo(18f, h * 0.86f); hex.close()
            pFill.shader = LinearGradient(0f, h * 0.1f, 0f, h * 0.88f,
                Color.parseColor("#FF14171C"), Color.parseColor("#FF0A0B0D"), Shader.TileMode.CLAMP)
            lp.width = (pw - 18f - 16 * dp).toInt()
            lp.height = (h * 0.70f).toInt()
            lp.leftMargin = (18f + 8 * dp).toInt()
            lp.topMargin = (h * 0.14f).toInt()
        }
        region.setPath(hex, android.graphics.Region(0, 0, w, h))
        col.layoutParams = lp
    }

    private val pShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0) }

    override fun dispatchDraw(canvas: Canvas) {
        if (cardBounds != null) {
            canvas.save(); canvas.translate(0f, 6 * dp); canvas.drawPath(hex, pShadow); canvas.restore()
        }
        canvas.drawPath(hex, pGlow)
        canvas.drawPath(hex, pFill)
        canvas.drawPath(hex, pEdge)
        super.dispatchDraw(canvas)
    }

    /** Solo recibe toques dentro del hexágono; el resto pasa a la pantalla de abajo. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (visibility != View.VISIBLE || !region.contains(ev.x.toInt(), ev.y.toInt())) return false
        super.dispatchTouchEvent(ev)
        return true
    }

    init {
        setWillNotDraw(false)
        visibility = View.GONE

        col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // Estado: pastilla con punto que late
        status = TextView(ctx).apply {
            textSize = 13f; setTextColor(Color.parseColor("#2BD96B")); letterSpacing = 0.08f
            setTypeface(typeface, android.graphics.Typeface.BOLD); text = "LLAMANDO…"
            setPadding(px(12), px(4), px(12), px(4))
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp; setColor(Color.argb(40, 43, 217, 107)); setStroke(px(1), Color.argb(110, 43, 217, 107))
            }
        }
        col.addView(status, LinearLayout.LayoutParams(-2, -2))
        avatar = Avatar(ctx)
        col.addView(avatar, LinearLayout.LayoutParams(px(112), px(112)).apply { topMargin = px(12) })
        nameTv = TextView(ctx).apply {
            textSize = 24f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        }
        col.addView(nameTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        numberTv = TextView(ctx).apply {
            textSize = 16f; setTextColor(Color.parseColor("#AEB8C4")); gravity = Gravity.CENTER; maxLines = 1
        }
        col.addView(numberTv, LinearLayout.LayoutParams(-1, -2))
        timer = TextView(ctx).apply {
            textSize = 32f; setTextColor(Color.WHITE); typeface = Fonts.bold(ctx); gravity = Gravity.CENTER; text = "00:00"
        }
        col.addView(timer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })

        val buttons = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
        fun btn(icon: Int, color: String, label: String, onClick: () -> Unit): ImageButton {
            val cell = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val b = ImageButton(ctx).apply {
                setImageResource(icon); setColorFilter(Color.WHITE); contentDescription = label
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(color)) }
                scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(px(14), px(14), px(14), px(14))
                elevation = 4 * dp
                setOnClickListener { onClick() }
            }
            cell.addView(b, LinearLayout.LayoutParams(px(54), px(54)))
            cell.addView(TextView(ctx).apply {
                text = label; textSize = 11f; setTextColor(Color.parseColor("#AEB8C4")); gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(3) })
            buttons.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            b.tag = cell
            return b
        }
        answerBtn = btn(R.drawable.ic_phone, "#2BD96B", "Contestar") { hook(ctx) }
        btn(R.drawable.ic_phone_end, "#FF3B30", "Colgar") {
            hook(ctx)
            // Si la radio no avisa que se colgó, el panel se cierra igual
            postDelayed({ if (Calls.active) Calls.forceEnd() }, 3000)
        }
        muteBtn = btn(R.drawable.ic_mic_off, "#3A3F47", "Silenciar") { toggleMute(ctx) }
        btn(R.drawable.ic_dialpad, "#3A3F47", "Teclado") {
            Apps.slotFor("phone")?.let { Apps.open(ctx, it) {} }
        }
        col.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
        addView(col, LayoutParams(px(200), px(300)))
        // Mantener presionado el panel lo cierra (por si queda pegado)
        isLongClickable = true
        setOnLongClickListener {
            Calls.forceEnd()
            Toast.makeText(ctx, "Panel de llamada cerrado", Toast.LENGTH_SHORT).show()
            true
        }
        col.setOnLongClickListener { performLongClick() }
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
        val conn = Calls.connected
        val secs = (SystemClock.elapsedRealtime() - (if (conn) Calls.connectedAt else Calls.startAt)) / 1000
        timer.text = String.format("%02d:%02d", secs / 60, secs % 60)
        timer.alpha = if (conn) 1f else 0.55f
        // Mientras suena o marca: punto que parpadea y botón Contestar; ya conectada, solo colgar
        val blink = (SystemClock.elapsedRealtime() / 600) % 2 == 0L
        status.text = if (conn) "● EN LLAMADA" else (if (blink) "● " else "○ ") + "LLAMANDO…"
        (answerBtn.tag as View).visibility = if (conn) View.GONE else View.VISIBLE
        avatar.ring = !conn
    }

    /** Foto redonda o iniciales sobre degradado. */
    private class Avatar(ctx: Context) : View(ctx) {
        var ring = false
        private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
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
            val cx = width / 2f; val cy = height / 2f
            val full = minOf(width, height) / 2f
            val r = full * 0.86f
            // Anillo verde: late mientras suena, fijo cuando está conectada
            val ph = if (ring) ((SystemClock.elapsedRealtime() % 1200) / 1200f) else 0f
            pRing.strokeWidth = full * 0.06f
            pRing.color = Color.argb(if (ring) (200 * (1 - ph)).toInt() else 160, 43, 217, 107)
            c.drawCircle(cx, cy, r + (full - r) * (if (ring) ph else 0.5f), pRing)
            if (ring) postInvalidateDelayed(40)
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
