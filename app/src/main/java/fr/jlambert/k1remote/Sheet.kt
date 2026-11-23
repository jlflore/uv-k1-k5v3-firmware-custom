package fr.jlambert.k1remote

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 0.76 : fenêtres aux couleurs de l'appli, pour qu'il ne reste plus aucune fenêtre système.
 * Même carte que [ChoiceSheet] (coins de 22, fond « sheet », titre 20 sp) :
 * - [show] : titre, message, contenu libre et boutons ;
 * - [message], [confirm] : les cas courants ;
 * - [pick] : choix dans une liste (tuiles de [ChoiceSheet], la valeur actuelle cochée) ;
 * - [ProgressSheet] : progression d'une lecture ou d'un envoi.
 */
object Sheet {

    enum class Style { NORMAL, PRIMARY, DANGER }

    class Btn(val label: String, val style: Style = Style.NORMAL, val onClick: () -> Unit = {})

    internal class Card(val dialog: Dialog, val body: LinearLayout, val p: Palette, val dp: (Int) -> Int)

    /** Carte vide (titre et message), pas encore affichée. */
    internal fun card(act: Activity, title: String, message: CharSequence?): Card {
        val p = Prefs(act).palette()
        val d = act.resources.displayMetrics.density
        val dp = { v: Int -> (v * d).toInt() }
        val dialog = Dialog(act)
        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            background = Ui.shape(act, p.sheet, 22f, p.border)
        }
        body.addView(text(act, title, 20f, p.text, true))
        if (!message.isNullOrBlank())
            body.addView(text(act, message, 14f, p.muted).apply { setLineSpacing(0f, 1.2f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return Card(dialog, body, p, dp)
    }

    internal fun text(ctx: Context, s: CharSequence, sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = s; textSize = sp; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** Bouton en pilule : principal (plein), danger (texte rouge) ou normal. */
    internal fun pill(act: Activity, p: Palette, b: Btn, onTap: () -> Unit) =
        text(act, b.label, 15f, when (b.style) { Style.PRIMARY -> p.primaryText; Style.DANGER -> p.danger; else -> p.text }, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            background = Ui.ripple(act, if (b.style == Style.PRIMARY) p.primaryBg else p.surface, 999f,
                when (b.style) { Style.PRIMARY -> p.primaryBorder; Style.DANGER -> p.danger; else -> p.border }, night = p.night)
            isClickable = true
            contentDescription = b.label
            setOnClickListener { onTap() }
        }

    /** Boutons : côte à côte s'ils sont un ou deux (le principal à droite), empilés au-delà. */
    internal fun buttons(act: Activity, c: Card, list: List<Btn>, cancelable: Boolean) {
        if (list.isEmpty()) return
        val row = LinearLayout(act).apply { orientation = if (list.size <= 2) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        list.forEachIndexed { i, b ->
            val v = pill(act, c.p, b) { c.dialog.dismiss(); b.onClick() }
            row.addView(v, if (list.size <= 2) LinearLayout.LayoutParams(0, c.dp(46), 1f).apply { if (i > 0) marginStart = c.dp(8) }
                           else LinearLayout.LayoutParams(-1, c.dp(46)).apply { if (i > 0) topMargin = c.dp(8) })
        }
        c.body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(16) })
        c.dialog.setCancelable(cancelable)
    }

    internal fun open(act: Activity, c: Card): Dialog {
        c.dialog.setContentView(c.body)
        c.dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout((act.resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            // Champs de saisie (nom d'équipe…) : le clavier doit pouvoir s'ouvrir dans la carte
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        c.dialog.show()
        return c.dialog
    }

    /**
     * Fenêtre générale. [content] s'insère entre le message et les boutons. [onDismiss] est appelé
     * à chaque fermeture (bouton, retour arrière ou toucher à côté si [cancelable]).
     */
    fun show(act: Activity, title: String, message: CharSequence? = null, content: View? = null,
             buttons: List<Btn>, cancelable: Boolean = true, onDismiss: (() -> Unit)? = null): Dialog? {
        if (act.isFinishing) return null
        val c = card(act, title, message)
        if (content != null) c.body.addView(content, LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(12) })
        buttons(act, c, buttons, cancelable)
        if (onDismiss != null) c.dialog.setOnDismissListener { onDismiss() }
        return open(act, c)
    }

    /** Information, avec au besoin une action utile en plus du bouton de fermeture. */
    fun message(act: Activity, title: String, message: CharSequence, ok: String = "OK",
                action: Btn? = null, cancelable: Boolean = true, onClose: (() -> Unit)? = null): Dialog? {
        var acted = false
        val list = listOfNotNull(Btn(ok, if (action == null) Style.PRIMARY else Style.NORMAL),
            action?.let { a -> Btn(a.label, a.style) { acted = true; a.onClick() } })
        return show(act, title, message, null, list, cancelable, onDismiss = { if (!acted) onClose?.invoke() })
    }

    /** Confirmation : Annuler à gauche, l'action à droite (rouge si [danger]). */
    fun confirm(act: Activity, title: String, message: CharSequence, confirm: String, danger: Boolean = true,
                cancel: String = "Annuler", onConfirm: () -> Unit): Dialog? =
        show(act, title, message, null, listOf(Btn(cancel), Btn(confirm, if (danger) Style.DANGER else Style.PRIMARY, onConfirm)))

    /** Choix dans une liste : tuiles, la valeur actuelle pleine et cochée. */
    fun pick(act: Activity, title: String, options: List<String>, selected: Int, message: String? = null,
             onPick: (Int) -> Unit): Dialog? =
        ChoiceSheet.show(act, title, message, options.mapIndexed { i, o ->
            ChoiceSheet.Option(if (i == selected) "✓" else "", o, primary = i == selected, arrow = false) { onPick(i) }
        }, "Annuler")
}

/**
 * 0.76 : progression d'une lecture ou d'un envoi des canaux, aux couleurs de l'appli.
 * À piloter depuis le fil principal : [update], [cancelVisible], [dismiss].
 */
class ProgressSheet(private val act: Activity, title: String, message: CharSequence? = null,
                    cancelLabel: String? = null, private val onCancel: (() -> Unit)? = null) {

    private val c = Sheet.card(act, title, message)
    private val step = Sheet.text(act, "Préparation…", 14f, c.p.text)
    private val pct = Sheet.text(act, "", 14f, c.p.text, true)
    private val bar = ThemeBar(act, c.p)
    private var cancelView: View? = null

    init {
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(step, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(pct, LinearLayout.LayoutParams(-2, -2).apply { marginStart = c.dp(8) })
        c.body.addView(head, LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(16) })
        c.body.addView(bar, LinearLayout.LayoutParams(-1, c.dp(10)).apply { topMargin = c.dp(10) })
        if (cancelLabel != null) {
            val v = Sheet.pill(act, c.p, Sheet.Btn(cancelLabel)) { onCancel?.invoke() }
            c.body.addView(v, LinearLayout.LayoutParams(-1, c.dp(46)).apply { topMargin = c.dp(18) })
            cancelView = v
        }
        c.dialog.setCancelable(false)
    }

    fun show(): ProgressSheet { if (!act.isFinishing) Sheet.open(act, c); return this }

    /** [percent] < 0 : progression indéterminée (bande qui défile). */
    fun update(text: String, percent: Int = -1) {
        step.text = text
        pct.text = if (percent >= 0) "$percent %" else ""
        bar.percent = percent
    }

    /** Texte du bouton d'annulation (« Annulation… ») ; null : inchangé. */
    fun cancelText(label: String) { (cancelView as? TextView)?.text = label }

    /** Le bouton disparaît quand l'annulation n'est plus possible. */
    fun cancelVisible(visible: Boolean) { cancelView?.visibility = if (visible) View.VISIBLE else View.GONE }

    fun dismiss() { runCatching { if (c.dialog.isShowing) c.dialog.dismiss() } }

    /** Barre arrondie : piste discrète, remplissage couleur d'accent ; bande animée si indéterminée. */
    private class ThemeBar(ctx: Context, p: Palette) : View(ctx) {
        var percent = -1
            set(v) { field = v; invalidate() }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.chipBg }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accentBg }
        private val r = RectF()
        private val start = SystemClock.uptimeMillis()

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat(); val w = width.toFloat(); val rad = h / 2
            r.set(0f, 0f, w, h); canvas.drawRoundRect(r, rad, rad, track)
            if (percent >= 0) {
                r.set(0f, 0f, maxOf(h, w * percent.coerceIn(0, 100) / 100f), h)
                canvas.drawRoundRect(r, rad, rad, fill)
            } else {
                // Bande de 30 % qui traverse la piste en 1,2 s
                val t = ((SystemClock.uptimeMillis() - start) % 1200L) / 1200f
                val seg = w * 0.3f
                val x = -seg + (w + seg) * t
                canvas.save(); canvas.clipRect(0f, 0f, w, h)
                r.set(x, 0f, x + seg, h); canvas.drawRoundRect(r, rad, rad, fill)
                canvas.restore()
                postInvalidateOnAnimation()
            }
        }
    }
}
