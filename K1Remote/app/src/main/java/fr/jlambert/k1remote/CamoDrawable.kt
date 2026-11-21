package fr.jlambert.k1remote

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import java.util.Random
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Fond camouflage : taches organiques (courbes lissées) ou pixels, tirées au hasard
 * avec une graine fixe pour que le motif soit toujours le même. Dessiné une fois dans
 * une image en cache, à la taille de l'écran ; [density] = dp → px.
 */
class CamoDrawable(private val camo: Camo, private val density: Float) : Drawable() {
    private var cache: Bitmap? = null
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        cache?.recycle()
        cache = null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val bmp = cache ?: render(b.width(), b.height()).also { cache = it }
        canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), bmpPaint)
    }

    private fun render(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        paint(Canvas(bmp), w / density, h / density, density, camo)
        return bmp
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        /** Dessine le motif sur [c], dans une zone de [wDp] × [hDp] dp, à l'échelle [scale] (px par dp). */
        fun paint(c: Canvas, wDp: Float, hDp: Float, scale: Float, camo: Camo) {
            c.drawColor(camo.base)
            val rnd = Random(camo.seed)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            c.save()
            c.scale(scale, scale)
            if (camo.pixel > 0) {
                paint.isAntiAlias = false
                for (l in camo.layers) pixels(c, rnd, l, camo.pixel.toFloat(), wDp, hDp, paint)
            } else {
                if (camo.blur) paint.maskFilter = BlurMaskFilter(5f * scale, BlurMaskFilter.Blur.NORMAL)
                for (l in camo.layers) blobs(c, rnd, l, wDp, hDp, paint)
                paint.maskFilter = null
                for (l in camo.extra) blobs(c, rnd, l, wDp, hDp, paint)
            }
            c.restore()
        }

        private fun blobs(c: Canvas, rnd: Random, l: CamoLayer, w: Float, h: Float, paint: Paint) {
            paint.color = l.color
            // Le motif est conçu pour un écran de 390 × 844 dp : on garde la même densité de taches
            val n = max(1, (l.count * (w * h) / (390f * 844f)).toInt())
            val path = Path()
            repeat(n) {
                val cx = -30f + rnd.nextFloat() * (w + 60f)
                val cy = -30f + rnd.nextFloat() * (h + 60f)
                val r = l.rMin + rnd.nextFloat() * (l.rMax - l.rMin)
                val rot = (rnd.nextFloat() * 2f - 1f) * l.rot
                blob(path, rnd, cx, cy, r, l.pts, l.irr, l.sx, l.sy, rot)
            }
            c.drawPath(path, paint)
        }

        private fun blob(path: Path, rnd: Random, cx: Float, cy: Float, r: Float, pts: Int, irr: Float,
                         sx: Float, sy: Float, rot: Float) {
            val xs = FloatArray(pts); val ys = FloatArray(pts)
            for (i in 0 until pts) {
                val a = 2.0 * Math.PI * i / pts + (rnd.nextFloat() * 0.5 - 0.25)
                val rr = r * (1f - irr + rnd.nextFloat() * 2f * irr)
                val x = (cos(a) * rr * sx).toFloat(); val y = (sin(a) * rr * sy).toFloat()
                xs[i] = cx + x * cos(rot) - y * sin(rot)
                ys[i] = cy + x * sin(rot) + y * cos(rot)
            }
            // Courbe fermée lissée (Catmull-Rom convertie en Bézier)
            path.moveTo(xs[0], ys[0])
            for (i in 0 until pts) {
                val i0 = (i - 1 + pts) % pts; val i2 = (i + 1) % pts; val i3 = (i + 2) % pts
                path.cubicTo(
                    xs[i] + (xs[i2] - xs[i0]) / 6f, ys[i] + (ys[i2] - ys[i0]) / 6f,
                    xs[i2] - (xs[i3] - xs[i]) / 6f, ys[i2] - (ys[i3] - ys[i]) / 6f,
                    xs[i2], ys[i2])
            }
            path.close()
        }

        private fun pixels(c: Canvas, rnd: Random, l: CamoLayer, cell: Float, w: Float, h: Float, paint: Paint) {
            paint.color = l.color
            val cols = (w / cell).toInt() + 1
            val rows = (h / cell).toInt() + 1
            val n = max(1, (l.count * (w * h) / (390f * 844f)).toInt())
            val path = Path()
            repeat(n) {
                val cx = rnd.nextInt(cols); val cy = rnd.nextInt(rows)
                val r = l.rMin + rnd.nextFloat() * (l.rMax - l.rMin)
                val rx = r * (1f + rnd.nextFloat() * 0.8f); val ry = r * (0.6f + rnd.nextFloat() * 0.4f)
                for (dx in -rx.toInt() - 1..rx.toInt() + 1) for (dy in -ry.toInt() - 1..ry.toInt() + 1) {
                    val d = (dx / rx) * (dx / rx) + (dy / ry) * (dy / ry)
                    if (d <= 0.6f + rnd.nextFloat() * 0.6f) {
                        val x = (cx + dx) * cell; val y = (cy + dy) * cell
                        path.addRect(x, y, x + cell, y + cell, Path.Direction.CW)
                    }
                }
            }
            c.drawPath(path, paint)
        }
    }
}
