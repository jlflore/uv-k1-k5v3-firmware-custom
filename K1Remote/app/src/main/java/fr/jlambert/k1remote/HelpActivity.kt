package fr.jlambert.k1remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Aide complète du firmware F4HWN Fusion, embarquée dans l'appli (assets/aide.html).
 * Aucune connexion Internet n'est nécessaire.
 * Extras : KEY = touche à afficher (ex. "2"), NIGHT = mode nuit.
 */
class HelpActivity : Activity() {

    companion object {
        const val EXTRA_KEY = "key"
        const val EXTRA_NIGHT = "night"
        const val EXTRA_MANUAL = "manual"

        fun intent(ctx: Context, key: String?, night: Boolean): Intent =
            Intent(ctx, HelpActivity::class.java).putExtra(EXTRA_KEY, key).putExtra(EXTRA_NIGHT, night)

        /** Mode d'emploi de l'appli (assets/mode-emploi.html). */
        fun manual(ctx: Context, night: Boolean): Intent =
            Intent(ctx, HelpActivity::class.java).putExtra(EXTRA_MANUAL, true).putExtra(EXTRA_NIGHT, night)
    }

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = intent.getBooleanExtra(EXTRA_NIGHT, false)
        val key = intent.getStringExtra(EXTRA_KEY)
        val manual = intent.getBooleanExtra(EXTRA_MANUAL, false)
        val p = if (night) Themes.NUIT else Themes.CIEL
        val dark = night
        // Le mode d'emploi est clair (pastels) de jour ; l'aide du firmware reste foncée
        val light = manual && !dark
        window.statusBarColor = when {
            dark -> p.bg
            light -> android.graphics.Color.parseColor("#F7F8FB")
            else -> android.graphics.Color.parseColor("#0B0F12")
        }
        window.navigationBarColor = window.statusBarColor

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(window.statusBarColor)
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }

        val textColor = when {
            dark -> p.text
            light -> android.graphics.Color.parseColor("#1F2A37")
            else -> android.graphics.Color.parseColor("#E7EDF0")
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dpi(this@HelpActivity, 8), Ui.dpi(this@HelpActivity, 8), Ui.dpi(this@HelpActivity, 16), Ui.dpi(this@HelpActivity, 4))
        }
        bar.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            contentDescription = "Retour"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(Ui.dpi(this, 48), Ui.dpi(this, 48)))
        bar.addView(TextView(this).apply {
            text = if (manual) "Mode d'emploi" else "Aide du firmware"
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(TextView(this).apply {
            text = "hors ligne"
            textSize = 11f
            setTextColor(if (dark) p.text else android.graphics.Color.parseColor(if (light) "#2E7D57" else "#3DD68C"))
            setPadding(Ui.dpi(this@HelpActivity, 10), Ui.dpi(this@HelpActivity, 4), Ui.dpi(this@HelpActivity, 10), Ui.dpi(this@HelpActivity, 4))
        })
        root.addView(bar)

        web = WebView(this).apply {
            setBackgroundColor(window.statusBarColor)
            settings.javaScriptEnabled = true          // sommaire, recherche, ouverture sur une touche
            settings.allowFileAccess = false
            settings.blockNetworkLoads = true          // tout est local
            webViewClient = WebViewClient()
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (light && android.os.Build.VERSION.SDK_INT >= 30) runCatching {
            val f = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.decorView.windowInsetsController?.setSystemBarsAppearance(f, f)
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            val hash = StringBuilder()
            if (key != null) hash.append("touche=").append(android.net.Uri.encode(key))
            if (night) { if (hash.isNotEmpty()) hash.append('&'); hash.append("nuit=1") }
            web.loadUrl("file:///android_asset/" + (if (manual) "mode-emploi.html" else "aide.html") +
                if (hash.isNotEmpty()) "#$hash" else "")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }
}
