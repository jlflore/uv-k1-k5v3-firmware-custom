package fr.jlambert.k1remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

/**
 * Écran 128 x 64 de la radio, agrandi sans lissage (pixels nets).
 * Les couleurs suivent le thème : vert rétroéclairé de jour, rouge sur noir la nuit.
 */
class LcdView(context: Context) : View(context) {

    private val bmp = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(128 * 64)
    private val pixelPaint = Paint().apply { isFilterBitmap = false }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val src = Rect(0, 0, 128, 64)
    private val dst = RectF()
    private val glass = RectF()
    private val clip = Path()

    private var onColor = Color.parseColor("#17241B")
    private var offColor = Color.parseColor("#B9D3A6")
    private var offDim = Color.parseColor("#7E9472")
    private var night = false

    /** Dernière image reçue (1 = pixel allumé), conservée pour les changements de thème. */
    var lastFrame: ByteArray? = null
        private set

    var dimmed = false
        set(v) { field = v; repaint() }

    /** Texte affiché tant qu'aucune image n'est reçue. */
    var placeholder: String = "Branchez la radio"
        set(v) { field = v; invalidate() }

    init { repaint() }

    fun setColors(on: Int, off: Int, offDim: Int, night: Boolean) {
        onColor = on; offColor = off; this.offDim = offDim; this.night = night
        repaint()
    }

    fun clear() {
        lastFrame = null
        repaint()
    }

    fun show(px: ByteArray) {
        lastFrame = px
        repaint()
    }

    private fun repaint() {
        val off = if (dimmed) offDim else offColor
        val f = lastFrame
        if (f == null) pixels.fill(off)
        else for (i in f.indices) pixels[i] = if (f[i].toInt() != 0) onColor else off
        bmp.setPixels(pixels, 0, 128, 0, 0, 128, 64)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val natural = w / 2
        when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> setMeasuredDimension(w, h)
            MeasureSpec.AT_MOST -> setMeasuredDimension(w, minOf(natural, h))
            else -> setMeasuredDimension(w, natural)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        glass.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset()
        clip.addRoundRect(glass, 9 * d, 9 * d, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        glassPaint.shader = null
        glassPaint.color = if (dimmed) offDim else offColor
        canvas.drawRect(glass, glassPaint)

        // Échelle entière pour des pixels nets, centrée dans la vitre
        val inset = 6 * d
        val scale = maxOf(1f, kotlin.math.floor(minOf((width - 2 * inset) / 128f, (height - 2 * inset) / 64f)))
        val w = 128 * scale
        val h = 64 * scale
        val left = (width - w) / 2
        val top = (height - h) / 2
        dst.set(left, top, left + w, top + h)
        canvas.drawBitmap(bmp, src, dst, pixelPaint)

        if (!night) {   // léger reflet de vitre, de jour seulement
            glassPaint.shader = LinearGradient(0f, 0f, 0f, height * 0.45f,
                Color.argb(34, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(glass, glassPaint)
        }
        canvas.restore()

        if (lastFrame == null && placeholder.isNotEmpty()) {
            hintPaint.textSize = 15 * d
            hintPaint.color = if (night) Color.parseColor("#B8392E") else Color.parseColor("#4E6446")
            // Retour à la ligne automatique : le texte reste dans la vitre, centré
            val maxW = glass.width() - 24 * d
            val lines = ArrayList<String>()
            for (para in placeholder.split('\n')) {
                var cur = ""
                for (w in para.split(' ')) {
                    val t = if (cur.isEmpty()) w else "$cur $w"
                    if (hintPaint.measureText(t) <= maxW || cur.isEmpty()) cur = t
                    else { lines.add(cur); cur = w }
                }
                lines.add(cur)
            }
            while (lines.size * hintPaint.textSize * 1.3f > glass.height() - 12 * d && hintPaint.textSize > 10 * d) {
                hintPaint.textSize -= d
            }
            val lh = hintPaint.textSize * 1.3f
            var y = glass.centerY() - (lines.size - 1) * lh / 2 + 5 * d
            for (l in lines) { canvas.drawText(l, glass.centerX(), y, hintPaint); y += lh }
        }
    }
}
