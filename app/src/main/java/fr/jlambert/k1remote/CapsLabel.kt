package fr.jlambert.k1remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View

/**
 * Texte sur une ligne, dont la hauteur est celle des pixels réellement dessinés.
 *
 * Certaines polices réservent bien plus de place que leurs lettres : Black Ops One (pochoir)
 * a une ligne de 1,25 em pour des capitales de 0,65 em, ce qui gonflait l'en-tête. Ici, la
 * hauteur vient des limites du texte affiché (accents et jambages compris), à sa taille réelle
 * en sp (donc agrandie si l'utilisateur agrandit les caractères du téléphone).
 *
 * getBaseline() est fourni : dans un LinearLayout horizontal, plusieurs CapsLabel s'alignent sur
 * leur ligne de base. Trop étroit, le texte se termine par « … ».
 */
class CapsLabel(ctx: Context) : View(ctx) {

    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()

    var text: String = ""
        set(v) { field = v; contentDescription = v; requestLayout(); invalidate() }

    var color: Int
        get() = paint.color
        set(v) { paint.color = v; invalidate() }

    /** Texte calé à droite de la place disponible (sinon à gauche). */
    var textAlignEnd = false
        set(v) { field = v; invalidate() }

    var letterSpacing: Float
        get() = paint.letterSpacing
        set(v) { paint.letterSpacing = v; requestLayout(); invalidate() }

    fun setFont(face: Typeface?, sp: Float) {
        paint.typeface = face ?: Typeface.DEFAULT
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        requestLayout(); invalidate()
    }

    private fun measureBounds() {
        // Limites verticales : le texte lui-même, plus « H » pour qu'un texte sans capitale
        // (« v0.56 ») garde la même ligne de base que ses voisins
        paint.getTextBounds(text.ifEmpty { "H" }, 0, maxOf(1, text.length), bounds)
        val h = Rect(); paint.getTextBounds("H", 0, 1, h)
        if (h.top < bounds.top) bounds.top = h.top
        if (bounds.bottom < 0) bounds.bottom = 0
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureBounds()
        val wanted = Math.ceil(paint.measureText(text).toDouble()).toInt() + paddingLeft + paddingRight
        val w = resolveSize(wanted, widthMeasureSpec)
        val h = resolveSize(bounds.height() + paddingTop + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun getBaseline(): Int = paddingTop - bounds.top

    override fun onDraw(canvas: Canvas) {
        val avail = (width - paddingLeft - paddingRight).toFloat()
        val shown = TextUtils.ellipsize(text, paint, avail, TextUtils.TruncateAt.END).toString()
        val x = if (textAlignEnd) width - paddingRight - paint.measureText(shown) else paddingLeft.toFloat()
        canvas.drawText(shown, x, (paddingTop - bounds.top).toFloat(), paint)
    }
}
