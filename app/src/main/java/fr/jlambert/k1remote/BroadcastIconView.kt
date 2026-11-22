package fr.jlambert.k1remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Bouton rond « partager ma position » (carte en paysage) : un point et deux ondes de chaque
 * côté, dans une pastille. Pastille colorée quand le partage est en cours.
 */
class BroadcastIconView(ctx: Context) : View(ctx) {

    var active = false
        set(v) { field = v; invalidate() }

    var bg = 0; var border = 0; var ink = 0
    var activeBg = 0; var activeBorder = 0; var activeInk = 0

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()

    init {
        isClickable = true
        isFocusable = true
        elevation = 3 * density
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 1.5f * density
        val inkC = if (active) activeInk else ink

        fill.color = if (active) activeBg else bg
        c.drawCircle(cx, cy, r, fill)
        line.color = if (active) activeBorder else border
        line.strokeWidth = 1.2f * density
        c.drawCircle(cx, cy, r, line)

        // Point central et deux ondes de chaque côté
        fill.color = inkC
        c.drawCircle(cx, cy, r * 0.13f, fill)
        line.color = inkC
        line.strokeWidth = 2.2f * density
        for (k in listOf(0.34f, 0.56f)) {
            oval.set(cx - r * k, cy - r * k, cx + r * k, cy + r * k)
            c.drawArc(oval, -40f, 80f, false, line)      // à droite
            c.drawArc(oval, 140f, 80f, false, line)      // à gauche
        }
    }
}
