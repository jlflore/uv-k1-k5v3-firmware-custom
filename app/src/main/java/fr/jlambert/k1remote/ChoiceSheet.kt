package fr.jlambert.k1remote

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Fenêtre de choix aux couleurs de l'appli (0.69) : remplace les listes système d'Android
 * (« Canaux de la radio », Bibliothèque, Sauvegardes…). Chaque option est une tuile avec une
 * icône, un titre, une ligne d'explication et une flèche : on voit tout de suite qu'elle se touche.
 */
object ChoiceSheet {

    class Option(
        val icon: String,
        val title: String,
        val subtitle: String? = null,
        /** Action principale : tuile pleine, couleur du thème. */
        val primary: Boolean = false,
        /** Action destructrice (supprimer) : texte rouge. */
        val danger: Boolean = false,
        /** Option marquée (sauvegarde ouverte…) : pastille. */
        val badge: String? = null,
        val onClick: () -> Unit
    )

    fun show(act: Activity, title: String, message: String?, options: List<Option>, closeLabel: String = "Fermer"): Dialog? {
        if (act.isFinishing) return null
        val p = Prefs(act).palette()
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun tv(s: CharSequence, sp: Float, color: Int, bold: Boolean = false) = TextView(act).apply {
            text = s; textSize = sp; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        val dialog = Dialog(act)
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            background = Ui.shape(act, p.sheet, 22f, p.border)
        }
        card.addView(tv(title, 20f, p.text, true))
        if (!message.isNullOrBlank())
            card.addView(tv(message, 13.5f, p.muted).apply { setLineSpacing(0f, 1.2f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        for (o in options) {
            val fg = when { o.primary -> p.primaryText; o.danger -> p.danger; else -> p.text }
            val sub = if (o.primary) p.primaryText else p.muted
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = Ui.ripple(act, if (o.primary) p.primaryBg else p.surface, 14f,
                    if (o.primary) p.primaryBorder else p.border, night = p.night)
                isClickable = true
                contentDescription = o.title + (o.subtitle?.let { ". $it" } ?: "")
                setOnClickListener { dialog.dismiss(); o.onClick() }
            }
            // Icône dans une pastille ronde
            row.addView(tv(o.icon, 18f, if (o.primary) p.accentText else fg, true).apply {
                gravity = Gravity.CENTER
                background = Ui.shape(act, if (o.primary) p.accentBg else p.chipBg, 999f, if (o.primary) p.accentBorder else p.chipBorder)
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(tv(o.title, 16f, fg, true).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
            if (o.badge != null) head.addView(tv(o.badge, 11f, p.accentText, true).apply {
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = Ui.shape(act, p.accentBg, 999f, p.accentBorder)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            texts.addView(head, LinearLayout.LayoutParams(-1, -2))
            if (!o.subtitle.isNullOrBlank()) texts.addView(tv(o.subtitle, 13f, sub).apply { setLineSpacing(0f, 1.15f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(tv("›", 24f, sub), LinearLayout.LayoutParams(-2, -2))
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        // Liste longue (sauvegardes) : défilante, hauteur plafonnée à 60 % de l'écran
        val maxH = (act.resources.displayMetrics.heightPixels * 0.6).toInt()
        val scroll = object : ScrollView(act) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, android.view.View.MeasureSpec.makeMeasureSpec(maxH, android.view.View.MeasureSpec.AT_MOST))
        }.apply { addView(list) }
        card.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        card.addView(tv(closeLabel, 15f, p.text, true).apply {
            gravity = Gravity.CENTER
            background = Ui.ripple(act, p.surface, 999f, p.border, night = p.night)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(14) })

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout((act.resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        return dialog
    }
}
