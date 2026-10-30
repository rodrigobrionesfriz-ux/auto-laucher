package cl.rodrigo.autolauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build

/** Íconos cuadrados con esquinas redondeadas para la lista de accesos. */
object Icons {
    private val slotColors = mapOf(
        "maps" to "#34A853", "phone" to "#1E88E5", "settings" to "#607D8B",
        "carlink" to "#7E57C2", "radio" to "#F4511E", "music" to "#E91E63",
    )

    private fun canvasFor(sizePx: Int): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val path = Path().apply {
            addRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), sizePx * 0.22f, sizePx * 0.22f, Path.Direction.CW)
        }
        c.clipPath(path)
        return bmp to c
    }

    /** Ícono de una app instalada, recortado en cuadrado. */
    fun app(ctx: Context, d: Drawable, sizePx: Int): Drawable {
        val (bmp, c) = canvasFor(sizePx)
        if (Build.VERSION.SDK_INT >= 26 && d is AdaptiveIconDrawable) {
            val ext = sizePx / 4
            d.background?.let { it.setBounds(-ext, -ext, sizePx + ext, sizePx + ext); it.draw(c) }
            d.foreground?.let { it.setBounds(-ext, -ext, sizePx + ext, sizePx + ext); it.draw(c) }
        } else {
            c.drawColor(Color.parseColor("#E9EDF2"))
            val inset = (sizePx * 0.12f).toInt()
            d.setBounds(inset, inset, sizePx - inset, sizePx - inset)
            d.draw(c)
        }
        return BitmapDrawable(ctx.resources, bmp)
    }

    /** Ícono propio (vector) sobre un cuadrado de color. */
    fun glyph(ctx: Context, key: String, res: Int, sizePx: Int): Drawable {
        val (bmp, c) = canvasFor(sizePx)
        c.drawColor(Color.parseColor(slotColors[key] ?: "#3EE0B4"))
        ctx.getDrawable(res)?.mutate()?.let {
            it.setTint(Color.WHITE)
            val inset = (sizePx * 0.24f).toInt()
            it.setBounds(inset, inset, sizePx - inset, sizePx - inset)
            it.draw(c)
        }
        return BitmapDrawable(ctx.resources, bmp)
    }
}
