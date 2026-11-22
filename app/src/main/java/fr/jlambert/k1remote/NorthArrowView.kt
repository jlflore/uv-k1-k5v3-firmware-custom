package fr.jlambert.k1remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View

/**
 * Bouton « nord » de la carte, à la manière des cartes topographiques : une flèche dont une
 * moitié est pleine et l'autre claire, surmontée d'un N, dans une pastille ronde. La vue entière
 * tourne (rotation = -cap) pour que la flèche montre toujours le nord.
 */
class NorthArrowView(ctx: Context) : View(ctx) {

    /** Carte orientée vers le regard : pastille colorée. */
    var active = false
        set(v) { field = v; invalidate() }

    var bg = 0; var border = 0; var ink = 0; var light = 0
    var activeBg = 0; var activeBorder = 0; var activeInk = 0; var activeLight = 0

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val left = Path()
    private val right = Path()

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
        val lightC = if (active) activeLight else light

        // Pastille
        fill.color = if (active) activeBg else bg
        c.drawCircle(cx, cy, r, fill)
        line.color = if (active) activeBorder else border
        line.strokeWidth = 1.2f * density
        c.drawCircle(cx, cy, r, line)

        // N au-dessus de la flèche
        letter.color = inkC
        letter.textSize = r * 0.46f
        c.drawText("N", cx, cy - r * 0.30f, letter)

        // Flèche : pointe en haut, encoche en bas ; moitié gauche pleine, moitié droite claire
        val tipY = cy - r * 0.20f
        val baseY = cy + r * 0.66f
        val notchY = cy + r * 0.42f
        val half = r * 0.33f
        left.reset()
        left.moveTo(cx, tipY); left.lineTo(cx - half, baseY); left.lineTo(cx, notchY); left.close()
        right.reset()
        right.moveTo(cx, tipY); right.lineTo(cx + half, baseY); right.lineTo(cx, notchY); right.close()
        fill.color = lightC
        c.drawPath(right, fill)
        fill.color = inkC
        c.drawPath(left, fill)
        line.color = inkC
        line.strokeWidth = 1.4f * density
        c.drawPath(right, line)
        c.drawPath(left, line)
    }
}
