package fr.jlambert.k1remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * Aperçu miniature de l'écran principal dans un thème donné (sélecteur de thème,
 * Préférences). Dessiné dans un repère de 390 × 844, mis à l'échelle de la vue.
 */
class ThemePreview(ctx: Context, palette: Palette) : View(ctx) {
    var palette: Palette = palette
        set(v) { field = v; camoCache?.recycle(); camoCache = null; invalidate() }

    private var camoCache: Bitmap? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()

    init { contentDescription = "Aperçu du thème" }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        camoCache?.recycle(); camoCache = null
    }

    override fun onDraw(c: Canvas) {
        val p = palette
        val s = minOf(width / 390f, height / 844f)
        if (s <= 0f) return
        val ox = (width - 390f * s) / 2f
        val oy = (height - 844f * s) / 2f
        c.save()
        c.translate(ox, oy)
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, 390f * s, 844f * s), 34f * s, 34f * s, Path.Direction.CW) }
        c.clipPath(clip)

        // Fond
        val camo = p.camo
        if (camo != null) {
            val bw = (390f * s).toInt().coerceAtLeast(1); val bh = (844f * s).toInt().coerceAtLeast(1)
            val bmp = camoCache ?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
                CamoDrawable.paint(Canvas(it), 390f, 844f, s, camo); camoCache = it
            }
            c.drawBitmap(bmp, 0f, 0f, null)
        } else c.drawColor(p.bg)
        c.scale(s, s)

        // En-tête
        val hb = p.headerBg
        if (hb != null) {
            box(14f, 18f, 376f, 78f, 14f, hb, p.headerBorder)
            if (camo != null) for (i in 0..2) { fill.color = camo.flag[i]; c.drawRect(28f + i * 9f, 34f, 35f + i * 9f, 62f, fill) }
        }
        txt.textAlign = Paint.Align.LEFT
        if (camo != null) { txt.typeface = Fonts.stencil(context); label("K1 REMOTE", 64f, 54f, 22f, p.text, false); txt.typeface = Typeface.DEFAULT }
        else label("K1 Remote", 22f, 54f, 22f, p.text, true)
        label(camo?.unit ?: "UV-K1 · F4HWN Fusion", if (camo != null) 64f else 22f, 70f, 10f, p.muted, false)
        for (i in 0..2) {
            fill.color = p.surface; c.drawCircle(254f + i * 44f, 48f, 19f, fill)
            stroke.color = p.border; stroke.strokeWidth = 1.5f; c.drawCircle(254f + i * 44f, 48f, 19f, stroke)
        }
        txt.textAlign = Paint.Align.CENTER
        label("☾", 254f, 55f, 18f, p.text, true); label("⚙", 298f, 55f, 18f, p.text, true); label("?", 342f, 55f, 18f, p.text, true)

        // État
        box(16f, 92f, 280f, 124f, 16f, p.surface, p.border)
        fill.color = p.dot; c.drawCircle(34f, 108f, 5f, fill)
        txt.textAlign = Paint.Align.LEFT; label("Connecté", 48f, 113f, 13f, p.text, true)
        txt.textAlign = Paint.Align.CENTER
        box(290f, 92f, 330f, 124f, 16f, p.rxOnBg, p.rxOnBorder); label("RX", 310f, 113f, 11f, p.rxOnText, true)
        box(336f, 92f, 374f, 124f, 16f, p.surface, p.border); label("TX", 355f, 113f, 11f, p.muted, true)

        // Écran de la radio
        box(16f, 134f, 374f, 300f, 18f, p.lcdFrame, p.lcdFrameBorder)
        box(27f, 145f, 363f, 289f, 9f, p.lcdOff, null)
        txt.typeface = Typeface.MONOSPACE; txt.textAlign = Paint.Align.LEFT
        label("▶ SCORPIO", 40f, 196f, 26f, p.lcdOn, true)
        label("0012  446.0062  LOW3", 40f, 218f, 13f, p.lcdOn, false)
        fill.color = p.lcdOn; c.drawRect(40f, 230f, 350f, 231f, fill)
        label("  446.1812", 40f, 262f, 26f, p.lcdOn, true)
        label("F2    FM   MID", 40f, 280f, 13f, p.lcdOn, false)
        txt.typeface = Typeface.DEFAULT; txt.textAlign = Paint.Align.CENTER

        // Raccourcis
        box(16f, 312f, 374f, 442f, 16f, p.surface, p.border)
        txt.textAlign = Paint.Align.LEFT
        if (camo != null) { txt.typeface = Fonts.stencil(context); label("RACCOURCIS 6/9", 28f, 334f, 12f, p.text, false); txt.typeface = Typeface.DEFAULT }
        else label("RACCOURCIS 6/9", 28f, 334f, 11f, p.muted, true)
        txt.textAlign = Paint.Align.CENTER
        val chips = listOf("◆ Scorpio", "Puiss. A", "A ⇄ B", "Furtif", "Scan", "+ Ajouter")
        for (i in chips.indices) {
            val x0 = 26f + (i % 3) * 114f; val y0 = 344f + (i / 3) * 48f
            if (i == 5) box(x0, y0, x0 + 108f, y0 + 40f, 10f, Color.TRANSPARENT, p.muted)
            else box(x0, y0, x0 + 108f, y0 + 40f, 10f, p.chipBg, p.chipBorder)
            label(chips[i], x0 + 54f, y0 + 25f, 12f, if (i == 5) p.muted else p.chipText, true)
        }

        // Touches du haut
        val top = listOf("F1", "F2", "MENU", "◀", "▶", "EXIT")
        for (i in top.indices) {
            val x0 = 16f + i * 60f
            val side = i < 2
            keyFace(x0, 454f, x0 + 54f, 494f, if (side) p.sideBg else p.keyBg, if (side) p.sideBorder else p.keyBorder, p.keyEdge)
            label(top[i], x0 + 27f, 479f, 12f, if (side) p.sideText else p.keyText, true)
        }

        // Pavé
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "✱", "0", "F")
        val kh = 76f
        for (i in keys.indices) {
            val x0 = 16f + (i % 3) * 121f; val y0 = 504f + (i / 3) * (kh + 6f)
            val accent = keys[i] == "F"
            keyFace(x0, y0, x0 + 115f, y0 + kh, if (accent) p.accentBg else p.keyBg,
                if (accent) p.accentBorder else p.keyBorder, if (accent) null else p.keyEdge)
            label(keys[i], x0 + 57f, y0 + kh / 2f + 8f, 22f, if (accent) p.accentText else p.keyText, true)
        }
        c.restore()
    }

    private lateinit var cv: Canvas
    override fun draw(canvas: Canvas) { cv = canvas; super.draw(canvas) }

    private fun box(l: Float, t: Float, rr: Float, b: Float, rad: Float, color: Int, border: Int?) {
        r.set(l, t, rr, b)
        if (Color.alpha(color) > 0) { fill.color = color; cv.drawRoundRect(r, rad, rad, fill) }
        if (border != null) { stroke.color = border; stroke.strokeWidth = 1.5f; cv.drawRoundRect(r, rad, rad, stroke) }
    }

    private fun keyFace(l: Float, t: Float, rr: Float, b: Float, color: Int, border: Int, edge: Int?) {
        if (edge != null) { r.set(l, t + 3f, rr, b + 3f); fill.color = edge; cv.drawRoundRect(r, 11f, 11f, fill) }
        box(l, t, rr, b, 11f, color, border)
    }

    private fun label(s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean) {
        txt.textSize = size
        txt.color = color
        txt.isFakeBoldText = bold
        cv.drawText(s, x, y, txt)
    }
}
