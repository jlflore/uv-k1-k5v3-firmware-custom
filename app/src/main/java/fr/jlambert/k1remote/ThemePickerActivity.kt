package fr.jlambert.k1remote

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Sélecteur de thème : l'aperçu plein écran défile d'un geste vers la gauche ou la droite,
 * le bouton du bas choisit le thème affiché.
 */
class ThemePickerActivity : Activity() {

    private lateinit var prefs: Prefs
    private val themes = ThemeId.DAY
    private var index = 0

    private lateinit var preview: ThemePreview
    private lateinit var name: TextView
    private lateinit var blurb: TextView
    private lateinit var dots: LinearLayout
    private lateinit var choose: TextView

    private val bg = Color.parseColor("#14171B")
    private val fg = Color.parseColor("#F2F4F7")
    private val muted = Color.parseColor("#A9B1BC")
    private val line = Color.parseColor("#3A414B")

    private fun dp(v: Int) = Ui.dpi(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        index = themes.indexOf(prefs.dayTheme).coerceAtLeast(0)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(18), dp(12), dp(18), dp(18))
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft + dp(18), insets.systemWindowInsetTop + dp(12),
                    insets.systemWindowInsetRight + dp(18), insets.systemWindowInsetBottom + dp(18))
                insets
            }
        }

        // En-tête
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(round("←", "Retour") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        head.addView(TextView(this).apply {
            text = "Choisir le thème"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(fg)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        col.addView(head)

        name = TextView(this).apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(fg); gravity = Gravity.CENTER }
        blurb = TextView(this).apply { textSize = 13f; setTextColor(muted); gravity = Gravity.CENTER }
        col.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        col.addView(blurb, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        // Aperçu + flèches ; glisser vers la gauche ou la droite change de thème
        val stage = FrameLayout(this)
        preview = ThemePreview(this, Themes.of(themes[index]))
        stage.addView(preview, FrameLayout.LayoutParams(-1, -1))
        stage.addView(round("‹", "Thème précédent") { go(-1) },
            FrameLayout.LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })
        stage.addView(round("›", "Thème suivant") { go(+1) },
            FrameLayout.LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL })
        var downX = 0f
        var dragging = false
        stage.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    if (abs(dx) > dp(8)) dragging = true
                    if (dragging) { preview.translationX = dx; preview.alpha = 1f - minOf(0.6f, abs(dx) / stage.width) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = ev.x - downX
                    when {
                        dx < -dp(60) -> go(+1)
                        dx > dp(60) -> go(-1)
                        else -> preview.animate().translationX(0f).alpha(1f).setDuration(150).start()
                    }
                    true
                }
                else -> false
            }
        }
        col.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })

        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        col.addView(dots, LinearLayout.LayoutParams(-1, dp(24)).apply { topMargin = dp(10) })

        choose = TextView(this).apply {
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                prefs.dayTheme = themes[index]
                finish()
            }
        }
        col.addView(choose, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(8) })
        if (prefs.night) {
            col.addView(TextView(this).apply {
                text = "Le mode nuit est actif : le thème choisi s'affichera quand vous le quitterez."
                textSize = 12f; setTextColor(muted); gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        setContentView(col)
        runCatching {
            @Suppress("DEPRECATION")
            window.statusBarColor = bg
            @Suppress("DEPRECATION")
            window.navigationBarColor = bg
            if (Build.VERSION.SDK_INT >= 30) {
                val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window.decorView.windowInsetsController?.setSystemBarsAppearance(0, light)
            }
        }
        refresh()
    }

    private fun go(step: Int) {
        val w = (preview.parent as View).width.toFloat().coerceAtLeast(1f)
        preview.animate().translationX(-step * w).alpha(0f).setDuration(140).withEndAction {
            index = (index + step + themes.size) % themes.size
            preview.palette = Themes.of(themes[index])
            refresh()
            preview.translationX = step * w
            preview.animate().translationX(0f).alpha(1f).setDuration(180).start()
        }.start()
    }

    private fun refresh() {
        val t = themes[index]
        val tp = Themes.of(t)
        name.text = t.label
        blurb.text = t.blurb + "  ·  " + (index + 1) + "/" + themes.size
        dots.removeAllViews()
        for (i in themes.indices) {
            dots.addView(View(this).apply {
                background = Ui.oval(if (i == index) fg else line)
            }, LinearLayout.LayoutParams(dp(if (i == index) 10 else 7), dp(if (i == index) 10 else 7)).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
        }
        val current = t == prefs.dayTheme
        choose.text = if (current) "✓  Thème actuel" else "Choisir « " + t.label + " »"
        choose.setTextColor(if (current) fg else tp.primaryText)
        choose.background = if (current) Ui.shape(this, Color.TRANSPARENT, 14f, line, 1.5f)
            else Ui.ripple(this, tp.primaryBg, 14f, tp.primaryBorder)
        choose.contentDescription = choose.text
    }

    private fun round(label: String, desc: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(fg); contentDescription = desc
        background = Ui.ripple(this@ThemePickerActivity, Color.argb(200, 20, 23, 27), 999f, line)
        setOnClickListener { onClick() }
    }
}
