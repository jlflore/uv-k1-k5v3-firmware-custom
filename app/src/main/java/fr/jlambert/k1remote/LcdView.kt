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

    /**
     * 0.72 : lecture de l'état de la radio à la connexion. Non nul : voile sur la vitre, anneau
     * qui tourne et ce texte ; l'appli bloque ses touches et son PTT pendant ce temps.
     */
    var busyText: String? = null
        set(v) { field = v; busyStart = android.os.SystemClock.uptimeMillis(); invalidate() }
    private var busyStart = 0L
    private val busyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val busyArc = RectF()

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

    /** Paysage : hauteur maximale imposée et vitre ajustée au contenu (pas de bandes vides). */
    var maxHeightPx = 0
    var fitContent = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        var h = MeasureSpec.getSize(heightMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        if (!fitContent) {
            val natural = w / 2
            when (hMode) {
                MeasureSpec.EXACTLY -> setMeasuredDimension(w, h)
                MeasureSpec.AT_MOST -> setMeasuredDimension(w, minOf(natural, h))
                else -> setMeasuredDimension(w, natural)
            }
            return
        }
        if (hMode == MeasureSpec.UNSPECIFIED) h = w / 2
        if (maxHeightPx > 0) h = minOf(h, maxHeightPx)
        val inset = 6 * resources.displayMetrics.density
        val scale = maxOf(1f, kotlin.math.floor(minOf((w - 2 * inset) / 128f, (h - 2 * inset) / 64f)))
        setMeasuredDimension(minOf(w, (128 * scale + 2 * inset).toInt()), minOf(h, (64 * scale + 2 * inset).toInt()))
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

        busyText?.let { txt ->
            // Voile, puis anneau (un arc de 90° qui tourne en 1 s) et le texte dessous
            busyPaint.style = Paint.Style.FILL
            busyPaint.color = if (night) Color.argb(215, 0, 0, 0) else Color.argb(200, 214, 226, 205)
            canvas.drawRoundRect(glass, 9 * d, 9 * d, busyPaint)
            val fg = if (night) Color.parseColor("#B8392E") else Color.parseColor("#2F4A2A")
            val r = 13 * d
            val cy = glass.centerY() - 10 * d
            busyArc.set(glass.centerX() - r, cy - r, glass.centerX() + r, cy + r)
            busyPaint.style = Paint.Style.STROKE
            busyPaint.strokeWidth = 3.5f * d
            busyPaint.strokeCap = Paint.Cap.ROUND
            busyPaint.color = Color.argb(60, Color.red(fg), Color.green(fg), Color.blue(fg))
            canvas.drawOval(busyArc, busyPaint)
            busyPaint.color = fg
            val turn = ((android.os.SystemClock.uptimeMillis() - busyStart) % 1000L) * 360f / 1000f
            canvas.drawArc(busyArc, turn - 90f, 90f, false, busyPaint)
            hintPaint.textSize = 14 * d
            hintPaint.color = fg
            canvas.drawText(txt, glass.centerX(), cy + r + 22 * d, hintPaint)
            postInvalidateOnAnimation()
            return
        }

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
