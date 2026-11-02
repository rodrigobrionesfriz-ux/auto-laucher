package cl.rodrigo.autolauncher

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** Marco cuadrado: usa el lado menor del espacio disponible (para la carátula). */
open class SquareFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = if (h == 0) w else minOf(w, h)
        val spec = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
    }
}

/** Marco circular para la carátula del diseño cockpit. */
class CircleFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    SquareFrame(context, attrs) {
    init {
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        clipToOutline = true
    }
}
