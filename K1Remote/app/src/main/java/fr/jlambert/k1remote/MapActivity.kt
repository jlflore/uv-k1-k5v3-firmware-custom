package fr.jlambert.k1remote

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Carte d'équipe : fond IGN, ma position et celles des coéquipiers,
 * bouton de partage (service de premier plan) et recentrage.
 */
class MapActivity : Activity() {

    private lateinit var prefs: Prefs
    private var p: Palette = Themes.CIEL
    private lateinit var map: TileMapView
    private lateinit var status: TextView
    private lateinit var shareBtn: TextView
    private lateinit var layerBtn: TextView
    private val ui = Handler(Looper.getMainLooper())
    /** Suit ma position tant que l'utilisateur n'a pas déplacé la carte. */
    private var follow = true
    private var centeredOnce = false

    private val onChange: () -> Unit = { ui.post { refresh() } }
    private val ticker = object : Runnable {
        override fun run() { map.invalidate(); refreshStatus(); ui.postDelayed(this, 5000) }
    }

    private fun dp(v: Int) = Ui.dpi(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        p = prefs.palette()
        build()
        runCatching {
            @Suppress("DEPRECATION")
            window.statusBarColor = p.bg
            @Suppress("DEPRECATION")
            window.navigationBarColor = p.bg
        }
    }

    private fun build() {
        val root = FrameLayout(this)
        map = TileMapView(this).apply {
            layer = if (prefs.mapLayer == 1) TileMapView.Layer.PLAN else TileMapView.Layer.PHOTO
            night = p.night
            onUserMove = { follow = false }
        }
        // Point de départ : dernière position connue, sinon la France entière
        val me = TeamMapState.me
        if (me != null) { map.center(me.lat, me.lon, 17.0); centeredOnce = true }
        else map.center(46.6, 2.4, 5.5)
        root.addView(map, FrameLayout.LayoutParams(-1, -1))

        // Bandeau du haut
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        top.addView(chip("‹", "Retour") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(TextView(this).apply {
            text = "Carte · " + prefs.mapTeam
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.text)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = Ui.shape(this@MapActivity, p.surface, 999f, p.border)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
        layerBtn = chip(layerLabel(), "Changer de fond de carte") {
            prefs.mapLayer = 1 - prefs.mapLayer
            map.layer = if (prefs.mapLayer == 1) TileMapView.Layer.PLAN else TileMapView.Layer.PHOTO
            layerBtn.text = layerLabel()
        }
        top.addView(layerBtn, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Bouton de recentrage
        val recenter = chip("◎", "Me recentrer") {
            follow = true
            TeamMapState.me?.let { map.center(it.lat, it.lon, maxOf(map.zoom, 16.0)) }
                ?: toast("Position pas encore connue")
        }
        root.addView(recenter, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(14); bottomMargin = dp(150)
        })

        // Carte du bas : état et partage
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = Ui.shape(this@MapActivity, p.surface, 20f, p.border)
        }
        status = TextView(this).apply { textSize = 14f; setTextColor(p.text) }
        bottom.addView(status)
        shareBtn = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(52)
            setOnClickListener { toggleShare() }
        }
        bottom.addView(shareBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        bottom.addView(TextView(this).apply {
            text = "Fond de carte © IGN – Géoplateforme"
            textSize = 10f
            setTextColor(p.muted)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(10), 0, dp(10), dp(10))
        })

        // Écran bord à bord (Android 15) : on décale les commandes sous la barre d'état
        // et au-dessus de la barre de navigation pour ne pas gêner l'heure, la batterie…
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION") val t = insets.systemWindowInsetTop
            @Suppress("DEPRECATION") val b = insets.systemWindowInsetBottom
            (top.layoutParams as FrameLayout.LayoutParams).topMargin = t
            top.requestLayout()
            (bottom.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(10) + b
            bottom.requestLayout()
            (recenter.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(150) + b
            recenter.requestLayout()
            insets
        }
        setContentView(root)
        refresh()
    }

    private fun layerLabel() = if (prefs.mapLayer == 1) "Plan" else "Photo"

    private fun chip(label: String, desc: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        contentDescription = desc
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        setTextColor(p.text)
        background = Ui.ripple(this@MapActivity, p.surface, 999f, p.border, night = p.night)
        elevation = dp(3).toFloat()
        setOnClickListener { onClick() }
    }

    private fun refresh() {
        val me = TeamMapState.me
        if (me != null && (follow || !centeredOnce)) {
            map.center(me.lat, me.lon, if (!centeredOnce) 17.0 else null)
            centeredOnce = true
        }
        map.invalidate()
        refreshStatus()
        val on = TeamMapState.running
        shareBtn.text = if (on) "Arrêter le partage" else "Partager ma position"
        shareBtn.setTextColor(if (on) p.text else p.primaryText)
        shareBtn.background = if (on) Ui.ripple(this, p.surface, 14f, p.danger, night = p.night)
                              else Ui.ripple(this, p.primaryBg, 14f, p.primaryBorder, night = p.night)
    }

    private fun refreshStatus() {
        val now = System.currentTimeMillis()
        val live = TeamMapState.players.values.count { now - it.time < TeamMap.STALE_MS }
        val total = TeamMapState.players.size
        status.text = if (!TeamMapState.running) "Partage arrêté. Touchez le bouton pour partager votre position et voir l'équipe."
        else {
            val who = when (total) {
                0 -> "aucun coéquipier visible pour l'instant"
                else -> "$total coéquipier${if (total > 1) "s" else ""}" + (if (live < total) " ($live à jour)" else "")
            }
            val gps = TeamMapState.me?.let { " · GPS ±${it.accuracy.toInt()} m" } ?: " · recherche GPS…"
            "${TeamMapState.status} · $who$gps"
        }
    }

    // ------------------------------------------------------------------ partage

    private fun toggleShare() {
        if (TeamMapState.running) { PositionService.stop(this); return }
        if (prefs.mapKey == null) {
            toast("Créez ou rejoignez d'abord une équipe dans les Préférences, rubrique « Carte d'équipe »")
            return
        }
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty() && Build.VERSION.SDK_INT >= 23) { requestPermissions(need.toTypedArray(), 51); return }
        startShare()
    }

    private fun startShare() {
        follow = true
        PositionService.start(this)
        ui.postDelayed({ refresh() }, 400)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION") super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 51) return
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startShare()
        else toast("Sans accès à la position, impossible de la partager")
    }

    override fun onResume() {
        super.onResume()
        TeamMapState.listen(onChange)
        ui.post(ticker)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        TeamMapState.unlisten(onChange)
        ui.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        map.release()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
