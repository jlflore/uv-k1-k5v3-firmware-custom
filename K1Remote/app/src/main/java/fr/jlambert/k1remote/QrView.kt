package fr.jlambert.k1remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Affiche un QR code noir sur fond blanc, avec la marge blanche réglementaire (4 modules). */
class QrView(ctx: Context, private val qr: QrCode) : View(ctx) {
    private val dark = Paint().apply { color = Color.BLACK; isAntiAlias = false }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val n = qr.size + 8
        val cell = minOf(width, height) / n.toFloat()
        val ox = (width - cell * n) / 2f
        val oy = (height - cell * n) / 2f
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr.get(x, y)) {
                val l = ox + (x + 4) * cell
                val t = oy + (y + 4) * cell
                canvas.drawRect(l, t, l + cell + 0.5f, t + cell + 0.5f, dark)
            }
        }
    }
}
