package fr.jlambert.k1remote

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Journal des appels (0.60) : par jour, une ligne par appel (heure, appelant, durée).
 * Le journal technique complet reste accessible par un bouton, et reste partageable.
 */
object CallLogDialog {

    fun show(act: Activity, onTechnical: () -> Unit, onShare: (calls: Boolean) -> Unit) {
        if (act.isFinishing) return
        val p = Prefs(act).palette()
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun tv(s: CharSequence, sp: Float, color: Int, bold: Boolean = false) = TextView(act).apply {
            text = s; textSize = sp; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        fun button(label: String, primary: Boolean, onClick: () -> Unit) = tv(label, 15f, if (primary) p.primaryText else p.text, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(4), 0, dp(4), 0)
            background = Ui.ripple(act, if (primary) p.primaryBg else p.surface, 999f, if (primary) p.primaryBorder else p.border, night = p.night)
            setOnClickListener { onClick() }
        }

        val dialog = Dialog(act)
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            background = Ui.shape(act, p.sheet, 22f, p.border)
        }
        card.addView(tv("Journal des appels", 20f, p.text, true))
        val sub = tv("", 13f, p.muted)
        card.addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        // Hauteur ajustée au contenu, plafonnée à 55 % de l'écran (plus de grand vide sous la liste)
        val maxH = (act.resources.displayMetrics.heightPixels * 0.55).toInt()
        val scroll = object : ScrollView(act) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, android.view.View.MeasureSpec.makeMeasureSpec(maxH, android.view.View.MeasureSpec.AT_MOST))
        }.apply { addView(list) }
        card.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        fun fill() {
            list.removeAllViews()
            val all = CallLog.all().reversed()
            val calls = all.count { it.kind == CallLog.Kind.RX }
            val tx = all.size - calls
            sub.text = if (all.isEmpty()) "Les appels reçus et vos émissions s'afficheront ici."
                else "$calls réception${if (calls > 1) "s" else ""} · $tx émission${if (tx > 1) "s" else ""} · le plus récent en haut"
            if (all.isEmpty()) {
                list.addView(tv("Aucun appel pour l'instant.", 15f, p.muted).apply { setPadding(0, dp(20), 0, dp(20)); gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(-1, -2))
                return
            }
            var day = ""
            for (e in all) {
                val title = CallLog.dayTitle(e.start)
                if (title != day) {
                    day = title
                    list.addView(tv(title.uppercase(), 12f, p.muted, true).apply { letterSpacing = 0.06f },
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (list.childCount == 0) 2 else 14); bottomMargin = dp(4) })
                }
                val rx = e.kind == CallLog.Kind.RX
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    // De nuit, pas d'aplat rouge vif : un liseré suffit à distinguer les réceptions
                    background = if (rx && !p.night) Ui.shape(act, p.rxOnBg, 12f, p.rxOnBorder)
                        else Ui.shape(act, p.surface, 12f, if (rx) p.dot else p.border, if (rx) 1.5f else 1f)
                }
                val fg = if (rx && !p.night) p.rxOnText else p.text
                val fgMuted = if (rx && !p.night) p.rxOnText else p.muted
                row.addView(tv(CallLog.time(e.start).substring(0, 5), 15f, fgMuted, true).apply {
                    fontFeatureSettings = "tnum"
                }, LinearLayout.LayoutParams(dp(54), -2))
                row.addView(tv(CallLog.label(e), 15f, fg, rx && e.name != null),
                    LinearLayout.LayoutParams(0, -2, 1f))
                val dur = CallLog.duration(e.durMs)
                if (dur.isNotEmpty()) row.addView(tv(dur, 13f, fgMuted).apply { setPadding(dp(8), 0, 0, 0) })
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            }
        }
        fill()
        CallLog.listener = { act.runOnUiThread { if (dialog.isShowing) fill() } }

        val row1 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("Partager", false) {
            AlertDialog.Builder(act).setTitle("Partager")
                .setItems(arrayOf("Le journal des appels", "Le rapport complet (diagnostic)")) { _, w -> onShare(w == 0) }
                .show()
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        row1.addView(button("Effacer", false) {
            val n = CallLog.size()
            if (n == 0) return@button
            AlertDialog.Builder(act).setTitle("Effacer le journal des appels ?")
                .setMessage("$n élément${if (n > 1) "s" else ""} ser${if (n > 1) "ont" else "a"} supprimé${if (n > 1) "s" else ""}. Le journal technique n'est pas touché.")
                .setPositiveButton("Effacer") { _, _ -> CallLog.clear(); fill() }
                .setNegativeButton("Annuler", null).show()
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        row1.addView(button("Technique", false) { dialog.dismiss(); onTechnical() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        card.addView(row1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        card.addView(button("Fermer", true) { dialog.dismiss() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout((act.resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener { CallLog.listener = null }
        dialog.show()
    }
}
