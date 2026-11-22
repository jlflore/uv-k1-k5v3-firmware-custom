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
class MapActivity : Activity(), android.hardware.SensorEventListener {

    private lateinit var prefs: Prefs
    private var p: Palette = Themes.CIEL
    private lateinit var map: TileMapView
    private lateinit var status: TextView
    private var shareBtn: TextView? = null
    /** Paysage : bouton rond de partage, dans la colonne de droite. */
    private var shareIcon: BroadcastIconView? = null
    private lateinit var layerBtn: TextView
    private val ui = Handler(Looper.getMainLooper())
    /** Suit ma position tant que l'utilisateur n'a pas déplacé la carte. */
    private var follow = true
    private var centeredOnce = false
    private lateinit var compassBtn: NorthArrowView
    private lateinit var distBtn: TextView
    private var sensors: android.hardware.SensorManager? = null
    /** Cap lissé (degrés depuis le nord) ; null tant que la boussole n'a rien donné. */
    private var heading: Float? = null
    private var compassUnreliable = false

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
        // Paysage : pas de grande carte du bas (elle mangeait un quart de l'écran) ; le partage
        // devient un bouton rond aligné avec les autres, l'état une petite étiquette en haut.
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val stack = if (land) 14 else 150     // bas du premier bouton rond de la colonne de droite
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
            marginEnd = dp(14); bottomMargin = dp(stack + if (land) 62 else 0)
        })
        // Orientation : nord en haut, ou carte tournée dans la direction du regard. Le « N » du
        // bouton montre toujours le nord.
        compassBtn = NorthArrowView(this).apply {
            bg = p.surface; border = p.border; ink = p.text; light = p.bg
            activeBg = p.primaryBg; activeBorder = p.primaryBorder; activeInk = p.primaryText; activeLight = Color.argb(110, 255, 255, 255)
            setOnClickListener { setHeadingUp(!prefs.mapHeadingUp) }
        }
        root.addView(compassBtn, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(14); bottomMargin = dp(stack + if (land) 124 else 62)
        })
        // Distances aux coéquipiers
        distBtn = chip("↔", "Afficher la distance aux coéquipiers") {
            prefs.mapDistances = !prefs.mapDistances
            map.showDistances = prefs.mapDistances
            styleToggles()
            if (prefs.mapDistances && TeamMapState.me == null) toast("Distances affichées dès que votre position est connue")
        }
        root.addView(distBtn, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(14); bottomMargin = dp(stack + if (land) 186 else 124)
        })
        map.showDistances = prefs.mapDistances
        styleToggles()

        // Carte du bas : état et partage (portrait) ; en paysage : étiquette d'état + bouton rond
        status = TextView(this).apply { textSize = 14f; setTextColor(p.text) }
        var statusPill: View? = null
        var credit: View? = null
        if (land) {
            status.textSize = 12f
            status.maxLines = 1
            status.ellipsize = android.text.TextUtils.TruncateAt.END
            status.setPadding(dp(12), dp(6), dp(12), dp(6))
            status.background = Ui.shape(this, p.surface, 999f, p.border)
            root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(10), dp(64), dp(80), 0)
            })
            statusPill = status
            shareIcon = BroadcastIconView(this).apply {
                bg = p.surface; border = p.border; ink = p.text
                activeBg = p.primaryBg; activeBorder = p.primaryBorder; activeInk = p.primaryText
                setOnClickListener { toggleShare() }
            }
            root.addView(shareIcon, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = dp(14); bottomMargin = dp(stack)
            })
            credit = TextView(this).apply {
                text = "© IGN – Géoplateforme"
                textSize = 9f
                setTextColor(p.muted)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = Ui.shape(this@MapActivity, p.surface, 6f, null)
            }
            root.addView(credit, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(8), 0, 0, dp(8))
            })
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = Ui.shape(this@MapActivity, p.surface, 20f, p.border)
        }
        if (!land) bottom.addView(status)
        shareBtn = if (land) null else TextView(this).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(52)
            setOnClickListener { toggleShare() }
        }
        shareBtn?.let { bottom.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }) }
        bottom.addView(TextView(this).apply {
            text = "Fond de carte © IGN – Géoplateforme"
            textSize = 10f
            setTextColor(p.muted)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        if (!land) root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(10), 0, dp(10), dp(10))
        })

        // Écran bord à bord (Android 15) : on décale les commandes sous la barre d'état
        // et au-dessus de la barre de navigation pour ne pas gêner l'heure, la batterie…
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION") val t = insets.systemWindowInsetTop
            @Suppress("DEPRECATION") val b = insets.systemWindowInsetBottom
            (top.layoutParams as FrameLayout.LayoutParams).topMargin = t
            top.requestLayout()
            (bottom.layoutParams as? FrameLayout.LayoutParams)?.let { it.bottomMargin = dp(10) + b; bottom.requestLayout() }
            fun lift(v: View?, base: Int) {
                (v?.layoutParams as? FrameLayout.LayoutParams)?.let { it.bottomMargin = dp(base) + b; v.requestLayout() }
            }
            lift(shareIcon, stack)
            lift(recenter, stack + if (land) 62 else 0)
            lift(compassBtn, stack + if (land) 124 else 62)
            lift(distBtn, stack + if (land) 186 else 124)
            lift(credit, 8)
            (statusPill?.layoutParams as? FrameLayout.LayoutParams)?.let { it.topMargin = dp(64) + t; statusPill?.requestLayout() }
            insets
        }
        setContentView(root)
        refresh()
    }

    private fun layerLabel() = if (prefs.mapLayer == 1) "Plan" else "Photo"

    // ------------------------------------------------------------------ orientation

    /** Boutons actifs en couleur (comme les autres boutons « allumés » de l'appli). */
    private fun styleToggles() {
        fun style(v: TextView, on: Boolean) {
            v.setTextColor(if (on) p.primaryText else p.text)
            v.background = if (on) Ui.ripple(this, p.primaryBg, 999f, p.primaryBorder, night = p.night)
                           else Ui.ripple(this, p.surface, 999f, p.border, night = p.night)
        }
        compassBtn.active = prefs.mapHeadingUp
        style(distBtn, prefs.mapDistances)
        compassBtn.contentDescription = if (prefs.mapHeadingUp) "Carte orientée vers le regard ; toucher pour remettre le nord en haut"
            else "Nord en haut ; toucher pour orienter la carte dans la direction du regard"
    }

    private fun setHeadingUp(on: Boolean) {
        if (on && sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR) == null) {
            toast("Ce téléphone n'a pas de boussole utilisable"); return
        }
        prefs.mapHeadingUp = on
        if (on) {
            follow = true
            TeamMapState.me?.let { map.center(it.lat, it.lon) }
            startCompass()
            toast("Carte orientée dans la direction du téléphone")
        } else {
            stopCompass()
            map.mapBearing = 0f
            compassBtn.rotation = 0f
        }
        styleToggles()
    }

    private fun startCompass() {
        val sm = sensors ?: return
        val s = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR) ?: return
        heading = null
        sm.registerListener(this, s, android.hardware.SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopCompass() { sensors?.unregisterListener(this) }

    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orient = FloatArray(3)

    override fun onSensorChanged(e: android.hardware.SensorEvent) {
        android.hardware.SensorManager.getRotationMatrixFromVector(rot, e.values)
        // Cap de l'arrière du téléphone tenu devant soi, en tenant compte de la rotation de l'écran
        @Suppress("DEPRECATION")
        val screenRot = windowManager.defaultDisplay.rotation
        val (ax, ay) = when (screenRot) {
            android.view.Surface.ROTATION_90 -> android.hardware.SensorManager.AXIS_Y to android.hardware.SensorManager.AXIS_MINUS_X
            android.view.Surface.ROTATION_180 -> android.hardware.SensorManager.AXIS_MINUS_X to android.hardware.SensorManager.AXIS_MINUS_Y
            android.view.Surface.ROTATION_270 -> android.hardware.SensorManager.AXIS_MINUS_Y to android.hardware.SensorManager.AXIS_X
            else -> android.hardware.SensorManager.AXIS_X to android.hardware.SensorManager.AXIS_Y
        }
        android.hardware.SensorManager.remapCoordinateSystem(rot, ax, ay, remapped)
        android.hardware.SensorManager.getOrientation(remapped, orient)
        val az = ((Math.toDegrees(orient[0].toDouble()).toFloat()) + 360f) % 360f
        // Lissage circulaire (évite les tremblements et le saut 359° → 0°)
        val h0 = heading
        val h = if (h0 == null) az else {
            var d = az - h0
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            (h0 + d * 0.2f + 360f) % 360f
        }
        heading = h
        if (kotlin.math.abs(h - map.mapBearing).let { minOf(it, 360f - it) } >= 0.8f) {
            map.mapBearing = h
            compassBtn.rotation = -h          // le « N » pointe vers le nord
        }
    }

    override fun onAccuracyChanged(s: android.hardware.Sensor, accuracy: Int) {
        val bad = accuracy <= android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW
        if (bad && !compassUnreliable && prefs.mapHeadingUp) toast("Boussole imprécise : décrivez un grand 8 avec le téléphone pour la calibrer")
        compassUnreliable = bad
    }

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
        shareBtn?.let { b ->
            b.text = if (on) "Arrêter le partage" else "Partager ma position"
            b.setTextColor(if (on) p.text else p.primaryText)
            b.background = if (on) Ui.ripple(this, p.surface, 14f, p.danger, night = p.night)
                           else Ui.ripple(this, p.primaryBg, 14f, p.primaryBorder, night = p.night)
        }
        shareIcon?.let {
            it.active = on
            it.contentDescription = if (on) "Partage en cours : toucher pour l'arrêter" else "Partager ma position"
        }
    }

    private fun refreshStatus() {
        val now = System.currentTimeMillis()
        val live = TeamMapState.players.values.count { now - it.time < TeamMap.STALE_MS }
        val total = TeamMapState.players.size
        status.text = if (!TeamMapState.running) (if (shareIcon != null) "Partage arrêté · bouton en bas à droite pour partager"
            else "Partage arrêté. Touchez le bouton pour partager votre position et voir l'équipe.")
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
        if (sensors == null) sensors = getSystemService(SENSOR_SERVICE) as? android.hardware.SensorManager
        if (prefs.mapHeadingUp) startCompass()
        TeamMapState.listen(onChange)
        ui.post(ticker)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        stopCompass()
        TeamMapState.unlisten(onChange)
        ui.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        map.release()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
