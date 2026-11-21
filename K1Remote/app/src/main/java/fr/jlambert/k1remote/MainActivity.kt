package fr.jlambert.k1remote

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val ACTION_PERMISSION = "fr.jlambert.k1remote.USB_PERMISSION"
        private const val VID = 0x36B7
        private const val REQ_BLE = 41
        private const val KEEPALIVE_MS = 250L
        private const val STEP_MS = 280L        // pause après un appui court
        private const val LONG_STEP_MS = 700L   // pause après un appui long
        private const val WAIT_MS = 900L        // pause « WAIT » dans une suite de touches
        private const val LONG_PRESS_MS = 800L  // appui long sur un raccourci (Android : ~400 ms)
    }

    private enum class Link { OFF, WAITING, ON, SLEEP }

    /** Brouillon de l'éditeur de raccourcis (conservé à la rotation). */
    private class EditorState(
        val editingId: Long?,
        var name: String,
        var type: String,
        var action: String,
        var menu: String,
        var value: String,
        var channel: String,
        val seq: MutableList<KeyStep>,
        var longNext: Boolean = false,
        var team: TeamProfile = TeamProfile(),
        var teamSameTone: Boolean = true
    )

    private lateinit var usbManager: UsbManager
    private lateinit var prefs: Prefs
    private var p: Palette = Themes.CIEL
    private lateinit var lcd: LcdView

    private val ui = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()
    private var cdc: RadioLink? = null
    /** Boîtier Bluetooth en cours de connexion (pas encore prêt). */
    private var pendingBle: BleLink? = null
    /** Déconnexion demandée par l'utilisateur : pas de reconnexion Bluetooth automatique. */
    private var manualOff = false
    private var resumed = false
    private var relayRadio = false
    private var lastFrameAt = 0L
    @Volatile private var lastFlags = 0
    private var reading = ScreenReader.Reading(null, null, null)
    /** Lecture de la toute dernière image (mode canal / fréquence), sans mémoire. */
    private var live = ScreenReader.Reading(null, null, null)
    private var lastDtmf: String? = null
    private var link = Link.OFF
    private var linkText = "Déconnecté"

    private var shortcuts = mutableListOf<Shortcut>()
    private var helpMode = false
    private var editMode = false
    private var editor: EditorState? = null
    private var sheet: String? = null        // "key:<id>", "sc:<id>", "act:<id>" : panneau ouvert

    // Vues reconstruites à chaque changement de thème ou d'orientation
    private lateinit var rootFrame: FrameLayout
    private lateinit var overlay: FrameLayout
    private var statusDot: View? = null
    private var statusText: TextView? = null
    private var rxChip: TextView? = null
    private var txChip: TextView? = null
    private var chipsHost: LinearLayout? = null
    private var toastView: TextView? = null

    // Références de l'éditeur
    private var edSave: TextView? = null
    private var edDup: TextView? = null
    private var edPreview: TextView? = null
    private var edHint: TextView? = null

    /** Réponses de la radio, rangées par commande : chaque demande attend la sienne. */
    private val waiting = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.LinkedBlockingQueue<ByteArray>>()

    /** Envoie une commande directe et attend SA réponse (null si rien). */
    private val opLocks = java.util.concurrent.ConcurrentHashMap<Int, Any>()

    private fun request(c: RadioLink?, cmd: ByteArray, timeoutMs: Long): ByteArray? {
        c ?: return null
        val op = cmd[4].toInt() and 0xFF
        // Une seule demande à la fois par commande : deux demandes 05 simultanées
        // (surveillance + diagnostic du PTT) ne se volent plus leur réponse.
        synchronized(opLocks.getOrPut(op) { Any() }) {
            val q = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
            synchronized(waiting) { waiting[op] = q }
            try {
                if (!c.write(cmd)) return null
                return q.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } finally { synchronized(waiting) { if (waiting[op] === q) waiting.remove(op) } }
        }
    }
    /** Firmware « Fusion PTT » détecté : réglages par commandes directes. */
    @Volatile private var direct = false
    private val parser = K5ViewerParser(onReply = { r -> if (r.isNotEmpty()) waiting[r[0].toInt() and 0xFF]?.offer(r) }) { px, flags -> ui.post { onFrame(px, flags) } }

    /** Demande à la radio si elle comprend les commandes directes. */
    private fun probeDirect() {
        val c = cdc ?: return
        sender.execute {
            repeat(3) {
                if (cdc !== c) return@execute
                val r = request(c, K5ViewerProtocol.PING, 700)
                if (r != null && r.size >= 5 && r[0].toInt() == 4 && r[2] == 'K'.code.toByte()) {
                    direct = true
                    Journal.add("Radio reconnue : Fusion PTT " + (if (r.size > 7) String(r, 7, r.size - 7) else "") +
                        (if (r.size >= 7) (if (r[6].toInt() == 1) " · UV-K5 V3" else " · UV-K1") else ""))
                    startWatcher(c)
                    // Protocole 2 : modèle (réglage SetNav) et version du firmware
                    val model = if (r.size >= 7) (if (r[6].toInt() == 1) "UV-K5 V3" else "UV-K1") else null
                    val ver = if (r.size > 7) String(r, 7, r.size - 7) else ""
                    ui.post {
                        radioInfo = listOfNotNull(model, "Fusion PTT" + (if (ver.isNotEmpty()) " $ver" else "")).joinToString(" · ")
                        // Les flèches de l'appli suivent le réglage SetNav de la radio
                        if (model != null) prefs.k1Arrows = model == "UV-K1"
                        toast("$radioInfo reconnu : réglages directs et silencieux")
                        if (editor == null) buildUi()
                    }
                    return@execute
                }
            }
        }
    }

    private val keepAlive = object : Runnable {
        override fun run() {
            val c = cdc ?: return
            // Fil séparé : le keepalive ne doit pas attendre la fin d'une suite de touches
            Thread { c.write(K5ViewerProtocol.KEEPALIVE) }.start()
            if (lastFrameAt != 0L && System.currentTimeMillis() - lastFrameAt > 3000) {
                setLink(Link.WAITING, "En attente de l'écran")
            }
            ui.postDelayed(this, KEEPALIVE_MS)
        }
    }

    private val minuteTick = object : Runnable {
        override fun run() {
            applyAutoNight()
            ui.postDelayed(this, 60_000L)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val dev = intent.usbDevice()
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) openDevice(dev)
                    else setLink(Link.OFF, "Accès USB refusé")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (intent.usbDevice()?.deviceId == (cdc as? UsbCdc)?.device?.deviceId) {
                        disconnect("Radio débranchée")
                        if (prefs.stealth) warnStealthUnplugged()
                    }
                }
            }
        }
    }

    // =====================================================================
    // Cycle de vie
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashReporter()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        prefs = Prefs(this)
        Journal.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lcd = LcdView(this)
        // Toucher l'écran de la radio : haut = ligne A, bas = ligne B
        var lcdTapY = 0f
        lcd.setOnTouchListener { _, ev ->
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) lcdTapY = ev.y
            false
        }
        lcd.setOnClickListener { v -> onLcdTap(lcdTapY < v.height / 2f) }
        lcd.contentDescription = "Écran de la radio. Toucher en haut pour la ligne A, en bas pour la ligne B."
        shortcuts = Shortcut.load(prefs)
        if (!showPendingCrash()) buildUi()

        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(usbReceiver, filter)

        handleIntent(intent)
    }

    /** En cas de plantage, garde le rapport pour l'afficher au lancement suivant. */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                getSharedPreferences("k1remote", Context.MODE_PRIVATE).edit()
                    .putString("lastCrash", "K1 Remote 0.14 · Android " + Build.VERSION.RELEASE + " · " + Build.MODEL + "\n\n" +
                        android.util.Log.getStackTraceString(e))
                    .commit()
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Affiche le rapport du dernier plantage, s'il y en a un. Renvoie true si un rapport est affiché. */
    private fun showPendingCrash(): Boolean {
        val sp = getSharedPreferences("k1remote", Context.MODE_PRIVATE)
        val trace = sp.getString("lastCrash", null) ?: return false
        sp.edit().remove("lastCrash").apply()
        setContentView(TextView(this).apply { setBackgroundColor(Color.parseColor("#DCE8F2")) })
        android.app.AlertDialog.Builder(this)
            .setTitle("L'appli s'est arrêtée la dernière fois")
            .setMessage("Touchez « Copier » puis collez ce rapport dans la conversation avec Claude.\n\n" + trace.take(4000))
            .setPositiveButton("Copier") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Rapport K1 Remote", trace))
                toastSafe("Rapport copié")
            }
            .setNegativeButton("Fermer", null)
            .setOnDismissListener { buildUi() }
            .show()
        return true
    }

    private fun toastSafe(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        applyRelayLed()   // réglage éventuellement changé dans les Préférences
        if (cdc == null && pendingBle == null && !manualOff && prefs.linkMode != Prefs.LINK_USB && prefs.relayAddress != null) ui.post { connect() }
        // Les préférences ont pu changer (thème, raccourcis, étiquettes…)
        shortcuts = Shortcut.load(prefs)
        if (editor == null && ::rootFrame.isInitialized) buildUi()
        ui.removeCallbacks(minuteTick)
        ui.post(minuteTick)
    }

    override fun onPause() {
        super.onPause()
        pttStop()
        resumed = false
        ui.removeCallbacks(minuteTick)
    }

    // Rotation : on recompose l'écran sans recréer l'activité, la liaison USB reste ouverte
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            editor != null -> { editor = null; overlay.removeAllViews() }
            sheet != null -> closeSheet()
            helpMode -> { helpMode = false; buildUi() }
            editMode -> { editMode = false; buildUi() }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        disconnect(null)
        runCatching { unregisterReceiver(usbReceiver) }
        sender.shutdownNow()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        // Profil d'équipe reçu par lien (QR code scanné, message…)
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == "k1remote" && intent.data?.host == "carte") {
            val got = TeamMap.fromLink(intent.dataString)
            ui.post {
                if (got == null) toast("Lien d'équipe carte illisible")
                else android.app.AlertDialog.Builder(this)
                    .setTitle("Rejoindre l'équipe « ${got.second} » ?")
                    .setMessage("Votre position sera partagée avec cette équipe quand vous l'activerez sur la carte.")
                    .setPositiveButton("Rejoindre") { _, _ ->
                        if (TeamMapState.running) PositionService.stop(this)
                        prefs.mapKey = TeamMap.keyToText(got.first)
                        prefs.mapTeam = got.second
                        startActivity(Intent(this, MapActivity::class.java))
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
            if (cdc == null) connect()
            return
        }
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == "k1remote") {
            val got = TeamProfile.fromLink(intent.dataString)
            ui.post {
                if (got == null) toast("Lien de profil d'équipe illisible")
                else if (::rootFrame.isInitialized) openEditorWithTeam(got.first, got.second)
            }
            if (cdc == null) connect()
            return
        }
        val dev = intent?.usbDevice()
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && dev != null) openDevice(dev)
        else if (cdc == null) connect()
    }

    private fun applyAutoNight() {
        if (!prefs.autoNight) return
        val want = if (prefs.isNightTime()) 1 else 0
        if (prefs.lastAuto != want) {
            prefs.lastAuto = want
            if (prefs.night != (want == 1)) {
                prefs.night = want == 1
                if (editor == null) buildUi()
            }
        }
    }

    // =====================================================================
    // Connexion USB
    // =====================================================================

    private fun connect() {
        manualOff = false
        val devices = usbManager.deviceList.values
        val dev = if (prefs.linkMode == Prefs.LINK_BLE) null
            else devices.firstOrNull { it.vendorId == VID }
                ?: devices.firstOrNull { d -> (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == 0x0A } }
        if (dev != null) {
            if (usbManager.hasPermission(dev)) openDevice(dev) else requestPermission(dev)
            return
        }
        if (prefs.linkMode != Prefs.LINK_USB) { connectBle(); return }
        setLink(Link.OFF, "Aucune radio détectée")
        lcd.placeholder = "Branchez la radio en USB-C"
    }

    // =====================================================================
    // Connexion Bluetooth (boîtier « K1 Relais »)
    // =====================================================================

    private fun blePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasBlePermissions(): Boolean = Build.VERSION.SDK_INT < 23 ||
        blePermissions().all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    private fun connectBle() {
        if (cdc != null || pendingBle != null) return
        val adapter = BleLink.adapter(this)
        if (adapter == null) {
            setLink(Link.OFF, "Aucune radio détectée")
            lcd.placeholder = "Branchez la radio en USB-C"
            return
        }
        if (!hasBlePermissions()) {
            setLink(Link.WAITING, "Autorisez le Bluetooth")
            if (Build.VERSION.SDK_INT >= 23) requestPermissions(blePermissions(), REQ_BLE)
            return
        }
        if (!adapter.isEnabled) {
            setLink(Link.OFF, "Bluetooth désactivé")
            lcd.placeholder = "Branchez la radio, ou activez le Bluetooth"
            return
        }
        val addr = prefs.relayAddress
        if (addr == null) {
            setLink(Link.WAITING, "Recherche du boîtier…")
            lcd.placeholder = "Recherche du boîtier K1 Relais…"
            BleLink.scan(this, 8000) { found ->
                if (found == null) {
                    setLink(Link.OFF, "Aucune radio ni boîtier")
                    lcd.placeholder = "Branchez la radio, ou allumez le boîtier"
                } else {
                    prefs.relayAddress = found
                    toast("Boîtier K1 Relais trouvé")
                    if (cdc == null) openBle(found)
                }
            }
        } else openBle(addr)
    }

    private fun openBle(addr: String) {
        if (cdc != null || pendingBle != null) return
        setLink(Link.WAITING, "Connexion au boîtier…")
        lcd.placeholder = "Connexion au boîtier…"
        relayRadio = false
        lateinit var link: BleLink
        link = BleLink(this, addr,
            onData = { buf, n -> parser.feed(buf, n) },
            onRadio = { on -> if (cdc === link) onRelayRadio(on) },
            onReady = {
                if (pendingBle === link) {
                    pendingBle = null
                    cdc = link
                    lastFrameAt = 0L
                    setLink(Link.WAITING, "Boîtier connecté")
                    applyRelayLed()
                    ui.post(keepAlive)
                    ui.postDelayed({ probeDirect() }, 1500)
                }
            },
            onLost = { lost ->
                val msg = if (!link.secured && lost.startsWith("Connexion")) "Boîtier refusé ou hors de portée" else lost
                if (pendingBle === link) pendingBle = null
                if (cdc === link) disconnect(msg) else setLink(Link.OFF, msg)
                lcd.placeholder = if (!link.secured) "Boîtier injoignable.\nNouveau téléphone ? Appuyez sur BOOT du boîtier, puis acceptez l'association"
                    else "Boîtier hors de portée ou éteint"
                // Nouvel essai tant que l'appli est au premier plan
                if (!manualOff) ui.postDelayed({ if (resumed && cdc == null && pendingBle == null && !manualOff) connectBle() }, 5000)
            })
        pendingBle = link
        link.open()
    }

    private fun onRelayRadio(on: Boolean) {
        val was = relayRadio
        relayRadio = on
        if (on) {
            if (!was) { lcd.placeholder = "Connexion…"; setLink(Link.WAITING, "Radio détectée, attente de l'écran") }
        } else {
            lastFrameAt = 0L
            lcd.clear()
            parser.reset()
            // La radio a pu redémarrer sur un autre emplacement Multiboot (autre firmware) :
            // on vérifiera de nouveau son firmware à la première image.
            direct = false
            radioInfo = null
            lastProbeAt = 0L
            watcherFor = null
            lcd.placeholder = "Allumez la radio et branchez-la sur le boîtier"
            setLink(Link.WAITING, "Boîtier connecté, radio absente")
            if (was && prefs.stealth) warnStealthUnplugged()
        }
    }

    /** Applique l'intensité de la lumière du boîtier (éteinte en mode furtif). */
    private fun applyRelayLed() {
        (cdc as? BleLink)?.let { it.setLed(if (prefs.stealth) 0 else prefs.relayLed); it.setSleep(prefs.relaySleep) }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION") super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLE) {
            if (hasBlePermissions()) connectBle()
            else setLink(Link.OFF, "Bluetooth non autorisé")
        }
    }

    private fun requestPermission(dev: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERMISSION).setPackage(packageName), flags)
        setLink(Link.WAITING, "Autorisez l'accès USB")
        usbManager.requestPermission(dev, pi)
    }

    private fun openDevice(dev: UsbDevice) {
        // Le câble passe avant le boîtier Bluetooth
        if (cdc is BleLink || pendingBle != null) {
            pendingBle?.close(); pendingBle = null
            if (cdc is BleLink) disconnect(null)
        }
        if (cdc != null) return
        if (!usbManager.hasPermission(dev)) { requestPermission(dev); return }
        val link = UsbCdc(usbManager, dev)
        val ok = link.open(
            onData = { buf, n -> parser.feed(buf, n) },
            onError = { msg -> ui.post { setLink(Link.OFF, msg) } }
        )
        if (!ok) return
        cdc = link
        lastFrameAt = 0L
        lcd.placeholder = "Connexion…"
        setLink(Link.WAITING, "Connecté, attente de l'écran")
        ui.post(keepAlive)
        if (prefs.stealth) toast("Radio en mode furtif : touchez Furtif pour rétablir l'écran")
    }

    // =====================================================================
    // Diagnostic (firmware Fusion PTT r6 et plus) : ce que la radio pense de l'émission
    // =====================================================================

    private val dStates = listOf("normal", "occupé (BCL)", "batterie faible", "ÉMISSION INTERDITE", "temps dépassé", "tension trop haute")
    private val dLocks = listOf("DEF", "FCC", "CA", "CE (144-146, 430-440)", "GB", "430", "438",
        "PMR (446)", "GMRS/FRS/MURS", "ALL (émission interdite partout)", "NONE (tout autorisé)")
    private val dFuncs = listOf("veille", "ÉMISSION", "écoute forcée (monitor)", "signal reçu", "réception", "économie", "spectre")
    private val dActions = listOf("aucune", "lampe", "puissance", "monitor", "scan", "VOX", "FM", "1750 Hz", "verrou",
        "A/B", "VFO/MR", "modulation", "mode RX", "Main only", "PTT", "W/N", "muet", "RX audio",
        "journal", "beam", "puiss. max", "sans décalage", "chasse", "balise", "appel tonal")
    private val dPttIds = listOf("OFF", "UP CODE", "DOWN CODE", "UP+DOWN", "Apollo")

    /** État lu par la commande 05 → (clé de comparaison sans le chrono, texte). */
    private fun decodeDiag(r: ByteArray): Pair<String, String>? {
        if (r.size < 16 || r[0].toInt() != 5) return null
        fun u(i: Int) = r[i].toInt() and 0xFF
        val f = (u(10) or (u(11) shl 8) or (u(12) shl 16) or (u(13) shl 24)).toLong() * 10
        val sb = StringBuilder()
        sb.append("État radio : ").append(dFuncs.getOrElse(u(8)) { "?" })
        sb.append("\nligne A ").append(dStates.getOrElse(u(2)) { "?" }).append(" · ligne B ").append(dStates.getOrElse(u(3)) { "?" })
        sb.append("\nF Lock ").append(dLocks.getOrElse(u(4)) { "?" }).append(" · TXLock ").append(if (u(5) == 1) "ON" else "OFF")
        sb.append("\nTX %.5f MHz · modulation %d · TxTOut %d".format(f / 1e6, u(14), u(15)))
        sb.append("\n1750 Hz ").append(if (u(7) == 1) "ACTIF" else "non").append(" · prog. PC ").append(if (u(6) > 0) "EN COURS" else "non")
            .append(" · batterie ").append(u(9))
        if (r.size >= 28) {
            fun a(i: Int) = dActions.getOrElse(u(i)) { "n°${u(i)}" }
            sb.append("\nPTT physique ").append(if (u(24) == 1) "APPUYÉ" else "relâché").append(" · à distance ")
                .append(if (u(25) == 1) "ACTIF" else "non").append(" · vu ").append(if (u(26) == 1) "appuyé" else "relâché")
            sb.append("\nSetPTT ").append(if (u(21) == 1) "UNE PRESSION" else "classique").append(" · PTT ID ")
                .append(dPttIds.getOrElse(u(23)) { "?" }).append(" · D ST ").append(if (u(22) == 1) "ON" else "OFF")
            sb.append("\ntouches F1 ").append(a(16)).append("/").append(a(17)).append(" · F2 ").append(a(18)).append("/")
                .append(a(19)).append(" · M long ").append(a(20))
        }
        if (r.size >= 31 && u(6) > 0) {
            val id = u(28) or (u(29) shl 8)
            sb.append("\norigine prog. PC : commande %04X par ".format(id))
                .append(when (u(30)) { 0 -> "la prise jack (UART)"; 1 -> "l'USB"; else -> "?" })
        }
        if (r.size >= 43) {
            fun w(i: Int) = u(i) or (u(i + 1) shl 8)
            val r70 = w(31); val r71 = w(33)
            sb.append("\nBK4819 : tonalité 1 ").append(if (r70 and 0x8000 != 0) "ACTIVE (%d Hz)".format((r71 / 10.32444).toInt()) else "off")
                .append(" · tonalité 2 ").append(if (r70 and 0x0080 != 0) "ACTIVE" else "off")
            sb.append("\nREG 70=%04X 71=%04X 47=%04X 30=%04X 50=%04X 31=%04X".format(r70, r71, w(35), w(37), w(39), w(41)))
        }
        val key = sb.toString()
        if (r.size >= 28) sb.append("\nchrono TX ").append(u(27) / 2).append(" s")
        return key to sb.toString()
    }

    @Volatile private var watchKick = false
    @Volatile private var watcherFor: RadioLink? = null

    /**
     * Surveillance continue (firmware Fusion PTT r6+) : lit l'état de la radio et note dans
     * le journal chaque changement. Plus souvent pendant une émission ou un appui sur PTT.
     */
    private fun startWatcher(c: RadioLink) {
        if (watcherFor === c) return
        watcherFor = c
        Thread({
            var lastKey = ""
            var lastTxLog = 0L
            while (cdc === c && direct && watcherFor === c) {
                if (!longSequenceRunning) {
                    val d = request(c, K5ViewerProtocol.command(0x05), 700)?.let { decodeDiag(it) }
                    if (d != null) {
                        val tx = lastFlags and 0x02 != 0
                        val now = System.currentTimeMillis()
                        if (d.first != lastKey) { Journal.add(d.second); lastKey = d.first; lastTxLog = now }
                        else if (tx && now - lastTxLog > 5000) { Journal.add("(toujours en émission)\n" + d.second.substringAfterLast("\n")); lastTxLog = now }
                    }
                }
                val busy = pttOn || lastFlags and 0x02 != 0
                var waited = 0
                val period = if (busy) 400 else 1500
                while (waited < period && !watchKick) { Thread.sleep(50); waited += 50 }
                watchKick = false
            }
            if (watcherFor === c) watcherFor = null
        }, "k1-watch").start()
    }

    /** Journal (appui long sur le titre) : tout ce que l'appli et la radio ont fait, en direct. */
    private fun showDiagnostic() {
        val tv = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(10), dp(16), dp(4))
            setTextIsSelectable(true)
            text = Journal.text().ifEmpty { "Journal vide." }
        }
        val head = TextView(this).apply {
            textSize = 12f
            setPadding(dp(16), dp(4), dp(16), dp(4))
            text = if (cdc == null) "Radio non connectée : journal enregistré."
                else if (!direct) "Firmware sans Fusion PTT : seuls les événements de l'appli et les LED sont notés."
                else "Surveillance active : chaque changement d'état de la radio est noté (le plus récent en haut)."
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(head)
            addView(android.widget.ScrollView(this@MainActivity).apply { addView(tv) },
                LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.6).toInt()))
        }
        Journal.listener = { ui.post { tv.text = Journal.text() } }
        android.app.AlertDialog.Builder(this)
            .setTitle("Journal de la radio")
            .setView(box)
            .setPositiveButton("Partager") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Journal K1 Remote")
                    putExtra(Intent.EXTRA_TEXT, "K1 Remote " + appVersion + " · " + (radioInfo ?: "radio ?") + "\n\n" + Journal.text())
                }, "Envoyer le journal"))
            }
            .setNeutralButton("Effacer") { _, _ -> Journal.clear(); toast("Journal effacé") }
            .setNegativeButton("Fermer", null)
            .setOnDismissListener { Journal.listener = null }
            .show()
        cdc?.let { if (direct) { startWatcher(it); watchKick = true } }
    }

    // =====================================================================
    // PTT à distance (firmware « Fusion PTT »)
    // =====================================================================

    private val pttExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var pttOn = false
    private var pttSince = 0L
    private var pttView: TextView? = null

    private val pttRepeat = object : Runnable {
        override fun run() {
            if (!pttOn) return
            // Sécurité côté téléphone aussi : 30 s au maximum
            if (System.currentTimeMillis() - pttSince > 30_000) { pttStop(); toast("Émission coupée après 30 s"); return }
            val c = cdc
            if (c == null) { pttStop(); return }
            pttExec.execute { c.write(K5ViewerProtocol.ptt(true)) }
            ui.postDelayed(this, 200)
        }
    }

    private fun pttStart() {
        if (pttOn) return
        if (longSequenceRunning) { toast("Émission bloquée : réglage de la radio en cours"); return }
        if (cdc == null) { toast("Radio non connectée"); return }
        pttOn = true
        pttSince = System.currentTimeMillis()
        Journal.add("Appli : bouton PTT appuyé")
        watchKick = true
        pttView?.isPressed = true
        refreshPttBar()
        window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        ui.post(pttRepeat)
        // Si la radio n'émet pas au bout de 0,8 s, on lui demande pourquoi (firmware r6 ou plus)
        val since = pttSince
        ui.postDelayed({ if (pttOn && pttSince == since && lastFlags and 0x02 == 0 && direct) explainNoTx() }, 800)
    }

    /** La radio refuse le PTT à distance : lire son état (commande 05) et le dire clairement. */
    private fun explainNoTx() {
        val c = cdc ?: return
        pttExec.execute {
            val r = request(c, K5ViewerProtocol.command(0x05), 800) ?: return@execute
            if (r.size < 16 || r[0].toInt() != 5) return@execute
            fun u(i: Int) = r[i].toInt() and 0xFF
            val why = when {
                r.size >= 22 && u(21) == 1 -> "la radio est en PTT « une pression » (indicateur OP en haut de l'écran). " +
                    "Une touche latérale réglée sur « PTT » l'a basculée : appuyez de nouveau dessus, ou éteignez puis rallumez la radio"
                u(6) > 0 -> "la radio se croit en cours de programmation par le PC : fermez UV Studio ou débranchez le câble"
                u(7) == 1 -> "la radio émet la tonalité 1750 Hz"
                u(2) == 3 || u(3) == 3 -> "émission interdite sur cette fréquence (F Lock / TXLock du canal)"
                u(9) == 0 -> "batterie trop faible"
                else -> null
            } ?: return@execute
            Journal.add("Appli : pas d'émission — $why")
            ui.post { toast("Pas d'émission : $why") }
        }
    }

    private fun pttStop() {
        if (!pttOn) return
        Journal.add("Appli : bouton PTT relâché (" + (System.currentTimeMillis() - pttSince) + " ms)")
        watchKick = true
        pttOn = false
        ui.removeCallbacks(pttRepeat)
        val c = cdc
        if (c != null) pttExec.execute { repeat(2) { c.write(K5ViewerProtocol.ptt(false)) } }
        refreshPttBar()
    }

    private fun refreshPttBar() {
        val v = pttView ?: return
        v.text = if (pttOn) "● ÉMISSION…  relâchez pour écouter" else "Maintenir pour émettre (PTT)"
        v.setTextColor(if (pttOn) p.txOnText else p.text)
        v.background = Ui.shape(this, if (pttOn) p.txOnBg else p.surface, 16f, if (pttOn) p.txOnBorder else p.border, 2f)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun buildPttBar(): TextView = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        contentDescription = "PTT à distance : maintenir pour émettre"
        pttView = this
        refreshPttBar()
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> pttStart()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> pttStop()
            }
            true
        }
    }

    private fun disconnect(msg: String?) {
        pttStop()
        ui.removeCallbacks(keepAlive)
        cdc?.close()
        cdc = null
        pendingBle?.close()
        pendingBle = null
        relayRadio = false
        direct = false
        radioInfo = null
        lastProbeAt = 0L
        parser.reset()
        lastFlags = 0
        reading = ScreenReader.Reading(null, null, null)
        lcd.dimmed = false
        lcd.placeholder = "Branchez la radio"
        lcd.clear()
        if (::rootFrame.isInitialized) { refreshLeds(); refreshChips() }
        if (msg != null) setLink(Link.OFF, msg)
    }

    private var lastProbeAt = 0L

    private fun onFrame(px: ByteArray, flags: Int) {
        lastFrameAt = System.currentTimeMillis()
        // La radio répond : on vérifie (au plus toutes les 10 s) si elle a le firmware Fusion PTT
        if (!direct && lastFrameAt - lastProbeAt > 10_000) { lastProbeAt = lastFrameAt; probeDirect() }
        val sleeping = flags and 0x01 != 0
        lcd.dimmed = sleeping
        lcd.show(px)
        if (flags != lastFlags) {
            val ch = flags xor lastFlags
            if (ch and 0x02 != 0) Journal.add("Radio : émission " + (if (flags and 0x02 != 0) "DÉBUT (LED rouge)" else "fin"))
            if (ch and 0x04 != 0) Journal.add("Radio : réception " + (if (flags and 0x04 != 0) "début (LED verte)" else "fin"))
            if (ch and 0x01 != 0) Journal.add("Radio : " + (if (flags and 0x01 != 0) "mise en veille de l'écran" else "réveil de l'écran"))
            lastFlags = flags; refreshLeds()
            watchKick = true
        }
        val r = ScreenReader.read(px)
        live = r
        val code = r.dtmf
        if (code != null && code != lastDtmf) {
            // Nom de l'appelant d'après son code DTMF (liste dans Préférences)
            val name = prefs.dtmfName(code)
            toast(if (name != null) "Appel de $name ($code)" else "Code d'appel reçu : $code")
        }
        lastDtmf = code
        if (r.mainVfo != null && r.mainVfo != reading.mainVfo) {
            // La ligne active se lit même si la puissance n'est pas reconnue
            reading = ScreenReader.Reading(r.mainVfo, reading.powerA, reading.powerB)
        }
        if (r.mainPower != null && (r.mainPower != reading.mainPower || r.mainVfo != reading.mainVfo)) {
            reading = r
            refreshChips()
        } else if (r.mainPower != null) {
            reading = r
        }
        val via = (if (cdc is BleLink) " · Bluetooth" else "") + (if (direct) " · PTT" else "")
        if (sleeping) setLink(Link.SLEEP, "Radio en veille$via") else setLink(Link.ON, "Connecté$via")
    }

    @Volatile private var longSequenceRunning = false

    private fun sendSteps(input: List<KeyStep>, restoreBeep: Boolean = true): Boolean {
        val c = cdc ?: run { toast("Radio non connectée"); return false }
        Journal.add("Appli : envoi " + input.filter { it.keyId != KeyStep.WAIT }.joinToString(" ") {
            if (it.cmd != null) "[cmd " + it.cmd.joinToString("") { b -> "%02X".format(b) }.drop(8).dropLast(2) + "]"
            else it.keyId + (if (it.long) "(long)" else "") }.take(400))
        if (longSequenceRunning) { toast("Patientez : réglage de la radio en cours"); return false }
        // Réglage long et bip actif : on coupe le bip d'abord (quelques bips au lieu de dizaines),
        // puis on le rétablit à la fin. Inutile en furtif : le bip y est déjà coupé.
        // Clavier de la radio verrouillé (cadenas) : les touches envoyées seraient ignorées.
        // Pour un réglage, on déverrouille (appui long sur F), puis on reverrouille à la fin.
        val lockedNow = live.locked
        if (lockedNow && input.size <= 1 && !(input.firstOrNull()?.let { it.keyId == "F" && it.long } ?: false)) {
            toast("Clavier de la radio verrouillé : appui long sur F pour le déverrouiller")
            return false
        }
        val unlocked = if (lockedNow && input.size > 1)
            listOf(KeyStep("F", true), KeyStep(KeyStep.WAIT, false)) + input + listOf(KeyStep("F", true))
        else input
        val steps0 = unlocked
        val steps = if (input.size > 12 && prefs.silentSequences && !prefs.stealth && !direct)
            (if (lockedNow) listOf(KeyStep("F", true), KeyStep(KeyStep.WAIT, false)) else emptyList()) +
                Shortcut.multiSettingSteps(listOf(Shortcut.MENU_BEEP to "0")) + input +
                (if (prefs.dayBeep && restoreBeep) Shortcut.multiSettingSteps(listOf(Shortcut.MENU_BEEP to "1")) else emptyList()) +
                (if (lockedNow) listOf(KeyStep("F", true)) else emptyList())
        else steps0
        val long = steps.size > 12
        if (long) longSequenceRunning = true
        sender.execute {
            val rxNow = { lastFlags and 0x04 != 0 }
            /** Attend la fin d'une réception (et 1,5 s de calme), 2 minutes au plus. */
            fun waitQuiet() {
                val until = System.currentTimeMillis() + 120_000
                var calmSince = 0L
                while (System.currentTimeMillis() < until) {
                    if (rxNow()) calmSince = 0L
                    else if (calmSince == 0L) calmSince = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - calmSince > 1500) return
                    Thread.sleep(100)
                }
            }
            try {
                // Une réception pendant un réglage peut faire sortir la radio de son menu :
                // on attend qu'elle finisse, puis on recommence le réglage depuis le début
                // (il commence toujours par EXIT, donc il peut être rejoué sans risque).
                var attempt = 0
                run@ while (true) {
                    if (long && rxNow()) waitQuiet()
                    var disturbed = false
                    // Reprise : si le clavier a déjà été déverrouillé, on ne refait pas l'appui long sur F
                    val run = if (attempt > 0 && lockedNow && !live.locked) steps.drop(2) else steps
                    for (st in run) {
                        if (long && rxNow()) { disturbed = true; break }
                        if (st.keyId == KeyStep.WAIT) { Thread.sleep(WAIT_MS); continue }
                        if (st.cmd != null) {
                            val r = request(c, st.cmd, 1200)
                            if (r == null || r.size < 2 || r[1].toInt() != 0) {
                                val why = when (r?.getOrNull(1)?.toInt()) {
                                    null -> "pas de réponse de la radio"
                                    3 -> "la ligne active n'est pas en mode fréquence"
                                    4 -> "fréquence hors des bandes de la radio"
                                    else -> "réglage refusé (code ${r[1]})"
                                }
                                Journal.add("Appli : réglage interrompu — $why")
                                ui.post { toast("Réglage interrompu : $why") }
                                break@run
                            }
                            Thread.sleep(40)
                            continue
                        }
                        val k = st.key ?: continue
                        if (!c.write(K5ViewerProtocol.keyPress(k.code, st.long))) break@run
                        Thread.sleep(if (st.long) LONG_STEP_MS else STEP_MS)
                    }
                    if (!disturbed || ++attempt > 3) break
                    ui.post { toast("Réception pendant le réglage : reprise dès la fin de l'appel") }
                    waitQuiet()
                }
            } finally {
                if (long) {
                    longSequenceRunning = false
                    ui.post { toast("Réglage de la radio terminé") }
                }
            }
        }
        return true
    }

    // =====================================================================
    // Construction de l'écran
    // =====================================================================

    private fun dp(v: Int) = Ui.dpi(this, v)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val landscape: Boolean get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun buildUi() {
        p = prefs.palette()
        lcd.setColors(p.lcdOn, p.lcdOff, p.lcdOffDim, p.night)
        (lcd.parent as? ViewGroup)?.removeView(lcd)

        rootFrame = FrameLayout(this).apply {
            val camo = p.camo
            if (camo != null) background = CamoDrawable(camo, resources.displayMetrics.density) else setBackgroundColor(p.bg)
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        val L = landscape
        val content = LinearLayout(this).apply {
            orientation = if (L) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (L) setPadding(dp(16), dp(10), dp(16), dp(10)) else setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        val header = buildHeader(L)
        // En paysage, l'état de la liaison est dans l'en-tête ; le bandeau d'aide le remplace
        val status: View? = when {
            helpMode -> buildHelpBanner()
            !L -> buildStatusRow()
            else -> null
        }
        val lcdFrame = FrameLayout(this).apply {
            background = Ui.shape(this@MainActivity, p.lcdFrame, 18f, p.lcdFrameBorder)
            val side = dp(if (L) 8 else 10)
            setPadding(side, side, side, side)
            clipToPadding = false     // la pastille déborde sur le cadre
            // Hauteur naturelle (moitié de la largeur), réduite si la place manque : en paysage,
            // l'écran de la radio passe avant les raccourcis.
            addView(lcd, FrameLayout.LayoutParams(-1, -2))
            // Pastille « i » orange posée sur le coin bas droit du cadre : son arrondi extérieur
            // épouse celui du cadre, les autres reprennent celui de l'écran de la radio.
            val gap = dp(3)
            val bh = dp(if (L) 20 else 22)
            addView(TextView(this@MainActivity).apply {
                text = "i  aide"
                textSize = if (L) 11f else 12f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.04f
                gravity = Gravity.CENTER
                includeFontPadding = false
                contentDescription = "Aide de l'écran affiché par la radio"
                val orange = if (p.night) p.accentBorder else Color.parseColor("#F08A24")
                setTextColor(if (p.night) Color.BLACK else Color.parseColor("#1A1206"))
                val r9 = Ui.dp(this@MainActivity, 9f)
                val rc = Ui.dp(this@MainActivity, 18f) - gap   // concentrique au coin du cadre
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.argb(70, 255, 255, 255)),
                    GradientDrawable().apply {
                        setColor(orange)
                        // ordre : haut-gauche, haut-droit, bas-droit, bas-gauche
                        cornerRadii = floatArrayOf(r9, r9, r9, r9, rc, rc, r9, r9)
                    }, null)
                setOnClickListener { contextHelp() }
            }, FrameLayout.LayoutParams(dp(if (L) 64 else 72), bh, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, -(side - gap), -(side - gap))
            })
        }
        val panel = if (prefs.showShortcuts) buildShortcutPanel(L) else null
        val topKeys = buildTopKeys()
        val pad = buildPad(L)

        if (!L) {
            content.addView(header)
            if (status != null) content.addView(status, lp(-1, -2, top = 10))
            content.addView(lcdFrame, lp(-1, -2, top = 10))
            if (panel != null) content.addView(panel, lp(-1, -2, top = 10))
            content.addView(topKeys, lp(-1, dp(42), top = 10))
            content.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
            if (prefs.remotePtt) content.addView(buildPttBar(), lp(-1, dp(64), top = 8))
        } else {
            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(header)
                if (status != null) addView(status, lp(-1, -2, top = 6))
                addView(lcdFrame, lp(-1, -2, top = 6))
                // Les raccourcis prennent la place restante et défilent si besoin
                if (panel != null) addView(ScrollView(this@MainActivity).apply {
                    isVerticalScrollBarEnabled = false
                    addView(panel)
                }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
            }
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(topKeys, lp(-1, dp(40)))
                addView(pad, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
                if (prefs.remotePtt) addView(buildPttBar(), lp(-1, dp(52), top = 6))
            }
            content.addView(left, LinearLayout.LayoutParams(0, -1, 1.3f).apply { marginEnd = dp(14) })
            content.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        }

        rootFrame.addView(content, FrameLayout.LayoutParams(-1, -1))
        overlay = FrameLayout(this)
        rootFrame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(rootFrame)
        applySystemBars()   // après setContentView : la fenêtre doit exister (plantage sinon sur Android 17)

        refreshStatus()
        refreshLeds()
        // Panneaux ouverts avant une rotation
        if (editor != null) renderEditor() else sheet?.let { reopenSheet(it) }
    }

    private fun lp(w: Int, h: Int, top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun applySystemBars() {
        // Purement cosmétique : ne doit jamais faire planter l'appli
        runCatching {
            @Suppress("DEPRECATION")
            window.statusBarColor = p.bg
            @Suppress("DEPRECATION")
            window.navigationBarColor = p.bg
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window.decorView.windowInsetsController?.setSystemBarsAppearance(if (p.darkChrome) 0 else light, light)
            } else if (Build.VERSION.SDK_INT >= 26) {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (p.darkChrome) 0 else
                    (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
            }
        }
    }

    private fun text(s: CharSequence, sp: Float, color: Int, face: Typeface? = null): TextView =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
            if (face != null) typeface = face
        }

    private fun roundButton(label: String, desc: String, active: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            contentDescription = desc
            setTextColor(if (active) p.primaryText else p.text)
            background = Ui.ripple(this@MainActivity, if (active) p.primaryBg else p.surface, 999f,
                if (active) p.primaryBorder else p.border, night = p.night)
            setOnClickListener { onClick() }
        }

    private fun buildHeader(L: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val camo = p.camo
        val headerBg = p.headerBg
        if (camo != null && headerBg != null) {
            // Thème camouflage : en-tête en écusson cousu, petit drapeau, titre au pochoir
            row.background = Ui.shape(this, headerBg, 14f, p.headerBorder, 1.5f, dashed = true)
            row.setPadding(dp(10), dp(if (L) 4 else 8), dp(8), dp(if (L) 4 else 8))
            val flag = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, col) in camo.flag.withIndex()) {
                flag.addView(View(this).apply { setBackgroundColor(col) },
                    LinearLayout.LayoutParams(dp(6), dp(if (L) 20 else 24)).apply { if (i > 0) marginStart = dp(2) })
            }
            row.addView(flag, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (camo != null) {
                addView(titleWithVersion(text("K1 REMOTE", if (L) 18f else 22f, p.text, Fonts.stencil(this@MainActivity)).apply { letterSpacing = 0.02f }))
                if (!L) addView(text(camo.unit, 10f, p.muted, Typeface.DEFAULT_BOLD).apply { letterSpacing = 0.12f })
            } else {
                addView(titleWithVersion(text("K1 Remote", if (L) 18f else 22f, p.text, Typeface.DEFAULT_BOLD)))
                if (!L) addView(text(if (p.night) "Mode nuit" else radioSubtitle(), 12f, p.muted))
            }
        }
        row.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        if (L && !helpMode) {
            row.addView(buildStatusPill(), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            rxChip = ledChip("RX"); txChip = ledChip("TX")
            row.addView(rxChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
            row.addView(txChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        val size = dp(if (camo != null) 40 else 44)
        row.addView(roundButton("☾", if (p.night) "Quitter le mode nuit" else "Mode nuit", p.night) {
            prefs.night = !prefs.night
            buildUi()
        }, LinearLayout.LayoutParams(size, size).apply { marginStart = dp(6) })
        row.addView(roundButton("⌖", "Carte d'équipe", TeamMapState.running) {
            startActivity(Intent(this, MapActivity::class.java))
        }, LinearLayout.LayoutParams(size, size).apply { marginStart = dp(6) })
        row.addView(roundButton("⚙︎", "Préférences", false) {
            startActivity(Intent(this, PreferencesActivity::class.java))
        }, LinearLayout.LayoutParams(size, size).apply { marginStart = dp(6) })
        row.addView(roundButton("?", if (helpMode) "Quitter le mode aide" else "Mode aide : touchez ensuite une touche", helpMode) {
            helpMode = !helpMode
            editMode = false
            buildUi()
        }, LinearLayout.LayoutParams(size, size).apply { marginStart = dp(6) })
        return row
    }

    /** Radio reconnue (firmware Fusion PTT) : « UV-K5 V3 · Fusion PTT v7.0.0 ». */
    private var radioInfo: String? = null

    /** Sous-titre : la radio reconnue, sinon le type choisi dans les Préférences (flèches K1/K5). */
    private fun radioSubtitle(): String = radioInfo
        ?: ((if (prefs.k1Arrows) "UV-K1" else "UV-K5 V3") + " · F4HWN" + (if (cdc == null) "" else " Fusion"))

    /** Version de l'appli (ex. « v0.12.1 »), lue dans le paquet installé. */
    private val appVersion: String by lazy {
        runCatching { "v" + packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("")
    }

    /** Titre de l'appli suivi de sa version, en petit, à droite. Appui long : diagnostic. */
    private fun titleWithVersion(title: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        setOnLongClickListener { showDiagnostic(); true }
        addView(title)
        if (appVersion.isNotEmpty()) addView(text(appVersion, 11f, p.muted, medium).apply {
            setPadding(dp(6), 0, 0, dp(3))
        })
    }

    private fun buildStatusPill(): LinearLayout {
        val dot = View(this).apply { background = Ui.oval(p.muted) }
        val label = text(linkText, 13f, p.text, medium).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        statusDot = dot
        statusText = label
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(14), dp(7))
            background = Ui.ripple(this@MainActivity, p.surface, 999f, p.border, night = p.night)
            addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
            addView(label, LinearLayout.LayoutParams(-2, -2))
            contentDescription = "État de la liaison. Toucher pour connecter, appui long pour déconnecter."
            setOnClickListener { if (cdc == null) connect() }
            setOnLongClickListener {
                if (cdc != null || pendingBle != null) { manualOff = true; disconnect("Déconnecté"); toast("Radio déconnectée") }
                true
            }
        }
    }

    private fun buildStatusRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(buildStatusPill(), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        rxChip = ledChip("RX"); txChip = ledChip("TX")
        row.addView(rxChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        row.addView(txChip)
        return row
    }

    private fun buildHelpBanner(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = Ui.shape(this@MainActivity, p.primaryBg, 14f, p.primaryBorder)
        }
        row.addView(text("Aide : touchez une touche ou un raccourci", 13f, p.primaryText, medium),
            LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "Mode d'emploi"
            textSize = 12f
            typeface = medium
            gravity = Gravity.CENTER
            setTextColor(p.primaryText)
            setPadding(dp(10), 0, dp(10), 0)
            contentDescription = "Ouvrir le mode d'emploi de l'appli"
            background = Ui.ripple(this@MainActivity, Color.TRANSPARENT, 10f, p.primaryText, night = p.night)
            setOnClickListener { startActivity(HelpActivity.manual(this@MainActivity, p.night)) }
        }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) })
        row.addView(TextView(this).apply {
            text = "Annuler"
            textSize = 12f
            typeface = medium
            gravity = Gravity.CENTER
            setTextColor(p.primaryText)
            setPadding(dp(12), 0, dp(12), 0)
            background = Ui.ripple(this@MainActivity, Color.TRANSPARENT, 10f, p.primaryText, night = p.night)
            setOnClickListener { helpMode = false; buildUi() }
        }, LinearLayout.LayoutParams(-2, dp(38)))
        rxChip = null; txChip = null
        return row
    }

    private fun ledChip(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    private fun refreshLeds() {
        val tx = lastFlags and 0x02 != 0
        val rx = lastFlags and 0x04 != 0
        rxChip?.let { styleLed(it, rx, p.rxOnBg, p.rxOnText, p.rxOnBorder) }
        txChip?.let { styleLed(it, tx, p.txOnBg, p.txOnText, p.txOnBorder) }
    }

    private fun styleLed(v: TextView, on: Boolean, bg: Int, fg: Int, border: Int) {
        if (on) {
            v.setTextColor(fg)
            v.background = Ui.shape(this, bg, 999f, border)
        } else {
            v.setTextColor(p.muted)
            v.background = Ui.shape(this, p.surface, 999f, p.border)
        }
    }

    private fun setLink(state: Link, msg: String) {
        if (state != link || msg != linkText) Journal.add("Liaison : $msg")
        link = state
        linkText = msg
        refreshStatus()
    }

    private fun refreshStatus() {
        statusText?.text = linkText
        val c = when (link) {
            Link.OFF -> p.muted
            Link.WAITING -> if (p.night) p.text else Color.parseColor("#E07B00")
            Link.ON -> p.dot
            Link.SLEEP -> if (p.night) p.muted else Color.parseColor("#3C7DD9")
        }
        (statusDot?.background as? GradientDrawable)?.setColor(c)
    }

    // ---------------- Raccourcis ----------------

    private fun buildShortcutPanel(L: Boolean): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            background = Ui.shape(this@MainActivity, p.surface, 16f, p.border)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, dp(6))
        }
        head.addView(text("RACCOURCIS " + shortcuts.size + "/" + Shortcut.MAX, if (p.camo != null) 13f else 12f,
            if (p.camo != null) p.text else p.muted,
            if (p.camo != null) Fonts.stencil(this) else Typeface.DEFAULT_BOLD).apply {
            letterSpacing = 0.06f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(this).apply {
            text = if (editMode) "Terminé" else "Modifier"
            textSize = 12f
            typeface = medium
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(if (editMode) p.primaryText else p.text)
            background = Ui.ripple(this@MainActivity, if (editMode) p.primaryBg else Color.TRANSPARENT, 8f,
                if (editMode) p.primaryBorder else p.border, night = p.night)
            setOnClickListener { editMode = !editMode; helpMode = false; buildUi() }
        }, LinearLayout.LayoutParams(-2, dp(32)))
        panel.addView(head)
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chipsHost = host
        panel.addView(host)
        fillChips(L)
        return panel
    }

    private fun refreshChips() {
        if (chipsHost != null) fillChips(landscape)
    }

    private fun fillChips(L: Boolean) {
        val host = chipsHost ?: return
        host.removeAllViews()
        val cols = if (L) 5 else 3
        val h = dp(if (L) 34 else 40)
        val items = mutableListOf<View>()
        for (s in shortcuts) items += chipView(s, L)
        if (shortcuts.size < Shortcut.MAX && !helpMode) items += addChip()
        var row: LinearLayout? = null
        for ((i, v) in items.withIndex()) {
            if (i % cols == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                host.addView(row, lp(-1, h, top = if (i == 0) 0 else 6))
            }
            row!!.addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply {
                if (i % cols != 0) marginStart = dp(6)
            })
        }
        // Compléter la dernière rangée pour garder des boutons de même largeur
        val rest = if (items.isEmpty()) 0 else (cols - items.size % cols) % cols
        for (i in 0 until rest) row?.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(6) })
    }

    private fun chipView(s: Shortcut, L: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
            background = when {
                editMode -> Ui.ripple(this@MainActivity, p.chipBg, 10f, p.chipText, dashed = true, night = p.night)
                helpMode -> Ui.ripple(this@MainActivity, p.chipBg, 10f, p.sub, dashed = true, night = p.night)
                else -> Ui.ripple(this@MainActivity, p.chipBg, 10f, p.chipBorder, night = p.night)
            }
        }
        val power = reading.mainPower
        if (s.isPower && power != null) {
            val vfo = reading.mainVfo ?: 'A'
            box.addView(text((if (editMode) "✎ " else "") + (if (L) "P. " else "Puiss. ") + vfo, if (L) 10f else 11f, p.chipMuted, medium))
            val badge = text(if (L) power else ScreenReader.pretty(power), if (L) 10f else 11f, Color.BLACK, Typeface.DEFAULT_BOLD).apply {
                setPadding(dp(if (L) 4 else 6), dp(2), dp(if (L) 4 else 6), dp(2))
            }
            styleBadge(badge, power)
            box.addView(badge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5) })
            box.contentDescription = "Puissance actuelle de la ligne $vfo : ${ScreenReader.pretty(power)}. Toucher pour passer à la suivante."
        } else {
            if (s.type == "team" && !editMode) {
                // Pastille « équipe » pour distinguer ce bouton
                box.addView(text("◆", if (L) 9f else 10f, p.primaryBorder, Typeface.DEFAULT_BOLD),
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
            }
            box.addView(text((if (editMode) "✎ " else "") + s.name, if (L) 11f else 12f, p.chipText, Typeface.DEFAULT_BOLD).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            box.contentDescription = s.name
        }
        box.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            when {
                helpMode -> openSheet("sc:" + s.id)
                editMode -> openEditor(s)
                else -> runShortcut(s)
            }
        }
        slowLongPress(box) {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (helpMode) openSheet("sc:" + s.id) else openSheet("act:" + s.id)
        }
        return box
    }

    private fun styleBadge(v: TextView, power: String) {
        if (p.night) {
            v.setTextColor(Color.BLACK)
            v.background = Ui.shape(this, p.text, 6f)
            return
        }
        val (bg, fg, border) = when (power) {
            "HIGH" -> Triple("#FAD4D1", "#8E1B13", "#D9564B")
            "MID" -> Triple("#FFE7C2", "#7A4300", "#D08A1E")
            else -> Triple("#CFF1DF", "#0E5A36", "#1F9D62")
        }
        v.setTextColor(Color.parseColor(fg))
        v.background = Ui.shape(this, Color.parseColor(bg), 6f, Color.parseColor(border))
    }

    private fun addChip(): View = TextView(this).apply {
        text = "+ Ajouter"
        textSize = 12f
        typeface = medium
        gravity = Gravity.CENTER
        setTextColor(p.muted)
        background = Ui.ripple(this@MainActivity, Color.TRANSPARENT, 10f, p.muted, dashed = true, night = p.night)
        setOnClickListener { openEditor(null) }
    }

    /**
     * Appui long « lent » (LONG_PRESS_MS) pour les raccourcis : évite d'ouvrir
     * le menu Modifier/Supprimer par accident. Un appui court reste un clic normal.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun slowLongPress(v: View, action: (View) -> Unit) {
        var fired = false
        var downX = 0f; var downY = 0f
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val r = Runnable { fired = true; v.isPressed = false; action(v) }
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    fired = false; downX = ev.x; downY = ev.y
                    v.postDelayed(r, LONG_PRESS_MS)
                }
                android.view.MotionEvent.ACTION_MOVE ->
                    if (Math.abs(ev.x - downX) > slop || Math.abs(ev.y - downY) > slop) v.removeCallbacks(r)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(r)
                    if (fired) { v.isPressed = false; return@setOnTouchListener true }  // pas de clic après l'appui long
                }
            }
            false
        }
    }

    /** Réglages envoyés à la radio pour entrer dans le furtif ou en sortir. */
    private fun stealthSteps(enter: Boolean): List<KeyStep> {
        val items = mutableListOf<Pair<Int, String>>()
        if (enter) {
            items += Shortcut.MENU_BLTXRX to "0"                                   // pas d'éclairage aux appels
            if (prefs.stealthLight == 1) items += Shortcut.MENU_BLTIME to "00"     // jamais d'éclairage
            else items += Shortcut.MENU_BLMAX to "01"                              // éclairage au minimum
            if (prefs.stealthInvert) items += Shortcut.MENU_SETINV to "1"          // fond noir
            items += Shortcut.MENU_BEEP to "0"                                      // touches muettes
        } else {
            // En sortie, on rétablit tout : robuste même si les préférences ont changé entre-temps
            items += Shortcut.MENU_BLTXRX to prefs.dayBlTxRx.toString()
            items += Shortcut.MENU_BLTIME to Shortcut.pad2(prefs.dayBlTime.toString())
            items += Shortcut.MENU_BLMAX to Shortcut.pad2(prefs.dayBlMax.toString())
            items += Shortcut.MENU_SETINV to (if (prefs.dayInv) "1" else "0")
            // Le bip n'est rétabli que si l'utilisateur l'a explicitement voulu (inutile en airsoft)
            if (prefs.dayBeep) items += Shortcut.MENU_BEEP to "1"
        }
        return settingSteps(items)
    }

    /** Réglages de menu : commandes directes si le firmware les comprend, sinon touches. */
    private fun settingSteps(items: List<Pair<Int, String>>): List<KeyStep> =
        if (direct) items.map { (menu, v) -> KeyStep("CMD", false, K5ViewerProtocol.setMenu(menu, v.toIntOrNull() ?: 0)) }
        else Shortcut.multiSettingSteps(items)

    private fun warnStealthUnplugged() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Radio restée en mode furtif")
            .setMessage("Son écran reste éteint ou sombre.\n\n" +
                "• Le plus simple : rebranchez-la et touchez Furtif.\n" +
                "• Sans téléphone : de jour l'écran se lit sans éclairage. Menu 39 (BLTxRx), 38 (BLMax), " +
                "36 (BLTime) et 60 (SetInv) pour remettre vos valeurs.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun onLcdTap(top: Boolean) {
        if (helpMode) { contextHelp(); return }
        val link = cdc ?: run { toast("Radio non connectée"); return }
        if (direct) {
            // Firmware Fusion PTT r7+ : la radio dit quel écran elle affiche. Hors de
            // l'écran principal (menu, FM, journal…), un toucher ouvre l'aide de cet écran.
            Thread({
                val d = request(link, K5ViewerProtocol.command(0x06), 700)
                ui.post {
                    if (d != null && d.size >= 8 && d[1].toInt() == 0 && d[2].toInt() != 0) contextHelp()
                    else switchLineTo(top, d != null && d.size >= 8 && d[1].toInt() == 0)
                }
            }, "k1-lcdtap").start()
            return
        }
        switchLineTo(top, false)
    }

    /** [onMain] : la radio a confirmé qu'elle affiche l'écran principal. */
    private fun switchLineTo(top: Boolean, onMain: Boolean) {
        val target = if (top) 'A' else 'B'
        val cur = reading.mainVfo
        when {
            cur == null || (!onMain && reading.mainMode == null) -> toast("Ligne active non reconnue sur cet écran")
            cur == target -> toast("Ligne $target déjà active")
            else -> {
                lcd.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                Actions.byId("ab")?.let { sendSteps(it.steps) }
            }
        }
    }

    private fun runShortcut(s: Shortcut) {
        val a = if (s.type == "action") Actions.byId(s.action) else null
        if (a?.nightToggle == true) {
            // Furtif = bascule : entrer (écran radio éteint/sombre + appli en nuit) / sortir (réglages normaux)
            val entering = !prefs.stealth
            if (!sendSteps(stealthSteps(entering))) return
            prefs.stealth = entering
            prefs.night = entering
            applyRelayLed()
            toast(if (entering) "Mode furtif : réglage de la radio (quelques secondes)…"
                  else "Sortie du furtif : réglages normaux rétablis (quelques secondes)…")
            buildUi()
            return
        }
        if (s.type == "team" || s.type == "channel") {
            // Il faut savoir si la ligne active est en mode canal ou fréquence
            val mode = live.mainMode
            if (cdc != null && mode == null) {
                toast("Revenez à l'écran principal de la radio (ligne active non reconnue)")
                return
            }
            if (s.type == "channel") {
                if (sendSteps(Shortcut.channelSteps(s.channel, fromFreqMode = mode == 'F'))) toast(s.name + " envoyé")
                return
            }
            val t = s.team ?: return
            t.problem()?.let { toast(it); return }
            if (sendSteps(if (direct) t.directSteps(fromChannelMode = mode == 'M', upCode = prefs.myCallCode.ifEmpty { null })
                          else t.steps(fromChannelMode = mode == 'M'), restoreBeep = false)) {
                toast(if (direct) "« " + s.name + " » : réglage direct de la radio (quelques secondes)…"
                      else "« " + s.name + " » : réglage de la radio, ne touchez à rien (≈ 25 s)…")
                if (!direct && prefs.myCallCode.isNotEmpty())
                    toast("Code d'appel non envoyé : il faut le firmware Fusion PTT (sinon, réglez-le avec UV Studio)")
            }
            return
        }
        // Réglage simple : en direct si possible (sauf réglages qui attendent un n° de canal)
        val menuNo = s.menu.toIntOrNull()
        val steps = if (direct && s.type == "setting" && menuNo != null && menuNo !in setOf(15, 16, 17, 20, 21, 44) &&
                        s.value.toIntOrNull() != null) settingSteps(listOf(menuNo to s.value))
                    else s.steps()
        if (!sendSteps(steps)) return
        if (!s.isPower) toast(s.name + " envoyé")
    }

    // ---------------- Clavier ----------------

    private fun keyLabel(k: KeyDef): CharSequence {
        val main = when {
            k.id == "LEFT" && !prefs.k1Arrows -> "▲"
            k.id == "RIGHT" && !prefs.k1Arrows -> "▼"
            else -> k.main
        }
        val sub = if (prefs.functionLabels) k.funcSub else k.radioSub
        if (sub.isEmpty()) return main
        val accent = k.id == "F"
        return SpannableStringBuilder().apply {
            append(main, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("  ")
            val s = length
            append(sub)
            setSpan(RelativeSizeSpan(0.55f), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(if (accent) p.accentText else p.sub), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun keyView(k: KeyDef, side: Boolean = false): TextView = TextView(this).apply {
        text = keyLabel(k)
        val small = k.main.length > 2
        textSize = if (side || small) 13f else 19f
        typeface = if (small) medium else Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 1
        val accent = k.id == "F"
        setTextColor(when { accent -> p.accentText; side -> p.sideText; else -> p.keyText })
        background = when {
            helpMode -> Ui.shape(this@MainActivity, if (accent) p.accentBg else p.keyBg, 12f, p.sub, 1.5f, dashed = true)
            accent -> Ui.key(this@MainActivity, p.accentBg, p.accentBorder, null, 12f, p.night)
            side -> Ui.key(this@MainActivity, p.sideBg, p.sideBorder, null, 10f, p.night)
            else -> Ui.key(this@MainActivity, p.keyBg, p.keyBorder, p.keyEdge, 12f, p.night)
        }
        contentDescription = KeyHelp.ENTRIES[k.id]?.title ?: k.main
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (helpMode) openSheet("key:" + k.id) else sendSteps(listOf(KeyStep(k.id, false)))
        }
        setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (helpMode) openSheet("key:" + k.id) else sendSteps(listOf(KeyStep(k.id, true)))
            true
        }
    }

    private fun buildTopKeys(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, k) in Keys.TOP.withIndex()) {
            row.addView(keyView(k, side = k.id == "F1" || k.id == "F2"),
                LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        return row
    }

    private fun buildPad(L: Boolean): GridLayout {
        val grid = GridLayout(this).apply { columnCount = 3; rowCount = 4 }
        val gap = dp(if (L) 3 else 3)
        for ((i, k) in Keys.PAD.withIndex()) {
            val lpg = GridLayout.LayoutParams(GridLayout.spec(i / 3, 1f), GridLayout.spec(i % 3, 1f)).apply {
                width = 0
                height = 0
                setMargins(gap, gap, gap, gap)
            }
            grid.addView(keyView(k), lpg)
        }
        return grid
    }

    // =====================================================================
    // Panneaux : aide d'une touche, aide d'un raccourci, menu d'un raccourci
    // =====================================================================

    private fun openSheet(id: String) {
        sheet = id
        reopenSheet(id)
    }

    private fun closeSheet() {
        sheet = null
        overlay.removeAllViews()
    }

    private fun reopenSheet(id: String) {
        overlay.removeAllViews()
        val kind = id.substringBefore(':')
        val arg = id.substringAfter(':')
        val card = sheetFrame()
        when (kind) {
            "key" -> fillKeyHelp(card, arg)
            "sc" -> {
                val s = shortcuts.firstOrNull { it.id.toString() == arg } ?: run { closeSheet(); return }
                fillShortcutHelp(card, s)
            }
            "act" -> {
                val s = shortcuts.firstOrNull { it.id.toString() == arg } ?: run { closeSheet(); return }
                fillShortcutActions(card, s)
            }
            "menu" -> fillMenuHelp(card, arg)
            "screen" -> {
                val (scr, lvl) = arg.split(',').map { it.toIntOrNull() ?: 0 }.let { it[0] to it.getOrElse(1) { 0 } }
                val (title, body) = MenuHelp.screenHelp(scr, lvl)
                sheetTitle(card, "i", title, "Écran de la radio")
                sheetRow(card, "CET ÉCRAN", body)
                sheetButtons(card,
                    Triple("Aide complète", 0, { openFullHelp(if (scr == 0) "LCD" else null) }),
                    Triple("Compris", 1, { closeSheet() }))
            }
            "qr" -> {
                val s = shortcuts.firstOrNull { it.id.toString() == arg } ?: run { closeSheet(); return }
                fillShare(card, s)
            }
        }
    }

    /** Fond assombri + carte en bas d'écran. Renvoie la carte à remplir. */
    private fun sheetFrame(): LinearLayout {
        overlay.addView(View(this).apply {
            setBackgroundColor(p.scrim)
            setOnClickListener { closeSheet() }
        }, FrameLayout.LayoutParams(-1, -1))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(20))
            background = GradientDrawable().apply {
                val r = Ui.dp(this@MainActivity, 24f)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(p.sheet)
                setStroke(dp(1), p.border)
            }
            isClickable = true
        }
        card.addView(View(this).apply { background = Ui.shape(this@MainActivity, p.border, 999f) },
            LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        val scroll = ScrollView(this).apply { addView(card) }
        val maxW = minOf(resources.displayMetrics.widthPixels, dp(600))
        overlay.addView(scroll, FrameLayout.LayoutParams(maxW, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        return card
    }

    private fun sheetTitle(card: LinearLayout, badge: CharSequence?, title: String, subtitle: String?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (badge != null) {
            row.addView(TextView(this).apply {
                text = badge
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(p.text)
                setPadding(dp(8), 0, dp(8), 0)
                minWidth = dp(52)
                background = Ui.key(this@MainActivity, p.surface, p.border, p.keyEdge, 14f, p.night)
            }, LinearLayout.LayoutParams(-2, dp(52)).apply { marginEnd = dp(12) })
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 19f, p.text, Typeface.DEFAULT_BOLD))
        if (subtitle != null) col.addView(text(subtitle, 13f, p.muted))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
    }

    private fun sheetRow(card: LinearLayout, label: String, body: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(text(label, 11f, p.muted, Typeface.DEFAULT_BOLD).apply { setPadding(0, dp(3), 0, 0) },
            LinearLayout.LayoutParams(dp(100), -2).apply { marginEnd = dp(12) })
        row.addView(text(body, 14f, p.text).apply { setLineSpacing(0f, 1.15f) }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row, lp(-1, -2, top = 12))
    }

    private fun sheetButtons(card: LinearLayout, vararg buttons: Triple<String, Int, () -> Unit>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, b) in buttons.withIndex()) {
            row.addView(actionButton(b.first, b.second, b.third),
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        card.addView(row, lp(-1, -2, top = 18))
    }

    /** style : 0 secondaire, 1 principal, 2 danger */
    private fun actionButton(label: String, style: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        typeface = medium
        gravity = Gravity.CENTER
        when (style) {
            1 -> { setTextColor(p.primaryText); background = Ui.ripple(this@MainActivity, p.primaryBg, 12f, p.primaryBorder, night = p.night) }
            2 -> { setTextColor(p.danger); background = Ui.ripple(this@MainActivity, Color.TRANSPARENT, 12f, p.danger, night = p.night) }
            else -> { setTextColor(p.text); background = Ui.ripple(this@MainActivity, p.surface, 12f, p.border, night = p.night) }
        }
        setOnClickListener { onClick() }
    }

    private fun fillKeyHelp(card: LinearLayout, keyId: String) {
        val h = KeyHelp.ENTRIES[keyId] ?: return
        val k = Keys.byId(keyId)
        sheetTitle(card, if (k != null) keyLabel(k) else keyId, h.title, h.subtitle)
        for ((label, body) in h.rows) sheetRow(card, label, body)
        sheetButtons(card,
            Triple("Aide complète", 0, { openFullHelp(keyId) }),
            Triple("Compris", 1, { closeSheet() }))
    }

    private fun fillMenuHelp(card: LinearLayout, arg: String) {
        val no = arg.substringBefore('|')
        val name = arg.substringAfter('|')
        val h = MenuHelp.ENTRIES[name]
        sheetTitle(card, no.ifEmpty { "i" }, (if (h != null) h.title else name), "Menu de la radio · $name")
        if (h == null) {
            sheetRow(card, "CE RÉGLAGE", "Pas encore d'aide pour ce réglage.")
        } else {
            sheetRow(card, "À QUOI ÇA SERT", h.what)
            sheetRow(card, "VALEURS", h.values)
            h.tip?.let { sheetRow(card, "CONSEIL", it) }
        }
        sheetButtons(card,
            Triple("Aide complète", 0, { openFullHelp(null) }),
            Triple("Compris", 1, { closeSheet() }))
    }

    /**
     * Aide de l'écran affiché par la radio. Avec Fusion PTT r7 ou plus, la radio dit
     * elle-même quel écran / quel réglage de menu est affiché (commande 06).
     */
    private fun contextHelp() {
        if (helpMode) { helpMode = false; buildUi() }
        val link = cdc
        if (link == null || !direct) {
            if (link != null) toast("Aide de chaque réglage : il faut le firmware Fusion PTT r7")
            openSheet("key:LCD"); return
        }
        Thread({
            val r = request(link, K5ViewerProtocol.command(0x06), 900)
            ui.post {
                val d = r
                when {
                    d == null || d.size < 8 || d[1].toInt() != 0 -> { toast("Aide de chaque réglage : il faut le firmware Fusion PTT r7"); openSheet("key:LCD") }
                    d[2].toInt() == 1 && d[3].toInt() == 1 && d.size >= 15 -> {
                        val name = String(d, 8, 7, Charsets.ISO_8859_1).substringBefore('\u0000').trim()
                        openSheet("menu:" + (d[5].toInt() and 0xFF) + "|" + name)
                    }
                    else -> openSheet("screen:" + (d[2].toInt() and 0xFF) + "," + (d[3].toInt() and 0xFF))
                }
            }
        }, "k1-ctxhelp").start()
    }

    private fun fillShortcutHelp(card: LinearLayout, s: Shortcut) {
        sheetTitle(card, "★", "Raccourci « " + s.name + " »", "Bouton de l'appli")
        sheetRow(card, "APPUI", s.describe())
        sheetRow(card, "APPUI LONG", "Ouvre le menu Modifier / Supprimer.")
        sheetButtons(card,
            Triple("Aide complète", 0, { openFullHelp(null) }),
            Triple("Compris", 1, { closeSheet() }))
    }

    private fun fillShortcutActions(card: LinearLayout, s: Shortcut) {
        sheetTitle(card, null, "Raccourci « " + s.name + " »", null)
        card.addView(text(s.describe(), 13f, p.muted).apply { setLineSpacing(0f, 1.15f) }, lp(-1, -2, top = 6))
        if (s.type == "team" && s.team != null) {
            card.addView(actionButton("◆  Partager à l'équipe (QR code)", 1) { openSheet("qr:" + s.id) }, lp(-1, dp(48), top = 16))
        }
        card.addView(actionButton("Modifier", 0) { closeSheet(); openEditor(s) },
            lp(-1, dp(48), top = if (s.type == "team") 8 else 16))
        card.addView(actionButton("Supprimer", 2) { closeSheet(); deleteShortcut(s.id) }, lp(-1, dp(48), top = 8))
        card.addView(actionButton("Annuler", 0) { closeSheet() }.apply {
            background = null
            setTextColor(p.muted)
        }, lp(-1, dp(44), top = 4))
    }

    /** QR code + lien du profil d'équipe, à scanner par les coéquipiers. */
    private fun fillShare(card: LinearLayout, s: Shortcut) {
        val t = s.team ?: return
        val link = t.toLink(s.name)
        sheetTitle(card, "◆", "Partager « " + s.name + " »", t.summary())
        val qr = QrCode.encode(link)
        if (qr != null) {
            // Toujours noir sur blanc, même en mode nuit : les lecteurs de QR code en ont besoin
            card.addView(QrView(this, qr), LinearLayout.LayoutParams(dp(230), dp(230)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10)
            })
        }
        card.addView(text("Vos coéquipiers scannent ce code avec l'appareil photo de leur téléphone : K1 Remote s'ouvre avec le profil, " +
            "ils touchent « Enregistrer ». Sinon, envoyez-leur le lien, qu'ils copient puis collent dans l'éditeur (« Coller un profil reçu »).",
            12f, p.muted).apply { setLineSpacing(0f, 1.15f) }, lp(-1, -2, top = 10))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(actionButton("Envoyer le lien", 1) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Profil radio « " + s.name + " » pour K1 Remote (" + t.summary() + ") :\n" + link)
            }, "Envoyer le profil"))
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(actionButton("Copier", 0) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Profil K1 Remote", link))
            toast("Lien copié")
        }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(8) })
        card.addView(row, lp(-1, -2, top = 12))
        card.addView(actionButton("Fermer", 0) { closeSheet() }.apply {
            background = null
            setTextColor(p.muted)
        }, lp(-1, dp(44), top = 4))
    }

    private fun openFullHelp(keyId: String?) {
        closeSheet()
        if (helpMode) { helpMode = false; buildUi() }
        startActivity(HelpActivity.intent(this, keyId, p.night))
    }

    // =====================================================================
    // Éditeur de raccourcis
    // =====================================================================

    private fun usedActions(exceptId: Long?): Set<String> =
        shortcuts.filter { it.id != exceptId && it.type == "action" }.map { it.action }.toSet()

    private fun openEditor(s: Shortcut?) {
        if (s == null && shortcuts.size >= Shortcut.MAX) {
            toast("9 raccourcis maximum : supprimez-en un pour en ajouter un autre")
            return
        }
        editor = if (s != null) {
            EditorState(s.id, s.name, s.type, s.action, s.menu, s.value, s.channel, s.seq.toMutableList(),
                team = s.team?.copy2() ?: TeamProfile(), teamSameTone = s.team?.sameTone ?: true)
        } else {
            val free = Actions.LIST.firstOrNull { it.id !in usedActions(null) }
            if (free != null) EditorState(null, free.name, "action", free.id, "02", "3", "1", mutableListOf())
            else EditorState(null, "", "setting", "", "02", "3", "1", mutableListOf())
        }
        renderEditor()
    }

    private fun closeEditor() {
        editor = null
        overlay.removeAllViews()
        edSave = null; edDup = null; edPreview = null; edHint = null
    }

    private fun draft(e: EditorState): Shortcut =
        Shortcut(e.editingId ?: -1L, e.name.trim(), e.type, e.action, e.menu, e.value, e.channel, e.seq.toMutableList(),
            if (e.type == "team") e.team.copy2() else null)

    private fun duplicateOf(e: EditorState): Shortcut? {
        val sig = draft(e).signature()
        return shortcuts.firstOrNull { it.id != e.editingId && it.signature() == sig }
    }

    private fun canSave(e: EditorState): Boolean {
        if (e.name.isBlank() || duplicateOf(e) != null) return false
        return when (e.type) {
            "action" -> Actions.byId(e.action) != null && e.action !in usedActions(e.editingId)
            "setting" -> e.menu.isNotEmpty() && e.value.isNotEmpty()
            "channel" -> (e.channel.toIntOrNull() ?: 0) > 0
            "team" -> e.team.problem() == null
            else -> e.seq.isNotEmpty()
        }
    }

    private fun refreshEditorStatus() {
        val e = editor ?: return
        val dup = duplicateOf(e)
        edDup?.apply {
            visibility = if (dup != null) View.VISIBLE else View.GONE
            text = if (dup != null) "Ce raccourci existe déjà : « " + dup.name + " ». Modifiez-le plutôt que d'en créer un second." else ""
        }
        val ok = canSave(e)
        edSave?.apply {
            isEnabled = ok
            alpha = if (ok) 1f else 0.45f
        }
        edPreview?.text = when (e.type) {
            "setting" -> "Touches envoyées : " + Shortcut.stepsText(Shortcut.settingSteps(e.menu, e.value))
            "channel" -> "Touches envoyées : " + Shortcut.stepsText(Shortcut.channelSteps(e.channel))
            "team" -> e.team.problem()?.let { "⚠ $it" } ?: ("✓ " + e.team.summary() +
                if (e.team.isRounded()) "\nFréquence arrondie au kHz le plus proche : la radio se règle au kHz près." else "")
            else -> ""
        }
        if (e.type == "team") return
        edHint?.text = if (Shortcut.pad2(e.menu) == "02")
            "Réglage 02 = puissance. Valeurs : 0 USER, 1 à 5 = LOW 1 à LOW 5, 6 MID, 7 HIGH."
        else
            "Le numéro est celui affiché à côté du réglage dans le menu de la radio."
    }

    private fun fieldLabel(s: String): TextView = text(s, 11f, p.muted, Typeface.DEFAULT_BOLD).apply { letterSpacing = 0.06f }

    private fun input(value: String, numeric: Boolean, maxLen: Int, onChange: (String) -> Unit): EditText = EditText(this).apply {
        setText(value)
        textSize = 16f
        typeface = medium
        setTextColor(p.text)
        setHintTextColor(p.muted)
        setSingleLine(true)
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLen))
        setPadding(dp(14), 0, dp(14), 0)
        background = Ui.shape(this@MainActivity, p.surface, 12f, p.border)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onChange(s?.toString() ?: "")
                refreshEditorStatus()
            }
        })
    }

    private fun toggleTile(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        typeface = medium
        gravity = Gravity.CENTER
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(if (on) p.primaryText else p.text)
        background = Ui.ripple(this@MainActivity, if (on) p.primaryBg else p.surface, 10f, if (on) p.primaryBorder else p.border, night = p.night)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
        setOnClickListener { onClick() }
    }

    private fun renderEditor() {
        val e = editor ?: return
        // Garder la position de défilement quand on change une valeur (sinon retour en haut)
        val keepY = (overlay.getChildAt(0) as? ScrollView)?.takeIf { it.tag == "editor" }?.scrollY ?: 0
        overlay.removeAllViews()
        val L = landscape
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(if (L) 10 else 16), dp(18), dp(20))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(p.bg)
            isFillViewport = true
            tag = "editor"
            addView(panel)
        }
        overlay.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        if (keepY > 0) scroll.post { scroll.scrollTo(0, keepY) }

        // En-tête
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(roundButton("←", "Annuler", false) { closeEditor() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        head.addView(text(if (e.editingId != null) "Modifier le raccourci" else "Nouveau raccourci", 20f, p.text, Typeface.DEFAULT_BOLD),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        panel.addView(head)

        // Nom
        panel.addView(fieldLabel("NOM DU BOUTON"), lp(-1, -2, top = 16))
        panel.addView(input(e.name, false, 14) { e.name = it }.apply { hint = "ex. Canal équipe" }, lp(-1, dp(48), top = 6))

        // Type
        panel.addView(fieldLabel("CE QUE FAIT LE BOUTON"), lp(-1, -2, top = 16))
        val types = listOf("action" to "Actions disponibles", "setting" to "Réglage de menu", "channel" to "Aller à un canal",
            "keys" to "Suite de touches", "team" to "◆  Configuration d'équipe")
        for (r in 0 until 3) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until (if (r == 2) 1 else 2)) {
                val (id, label) = types[r * 2 + c]
                row.addView(toggleTile(label, e.type == id) {
                    if (e.type != id) {
                        e.type = id
                        if (id == "action" && (e.action.isEmpty() || e.action in usedActions(e.editingId))) {
                            Actions.LIST.firstOrNull { it.id !in usedActions(e.editingId) }?.let { e.action = it.id }
                        }
                        renderEditor()
                    }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (c > 0) marginStart = dp(6) })
            }
            panel.addView(row, lp(-1, -2, top = 6))
        }

        // Contenu selon le type
        edPreview = null; edHint = null
        when (e.type) {
            "action" -> {
                val used = usedActions(e.editingId)
                for (a in Actions.LIST) {
                    val isUsed = a.id in used
                    val selected = e.action == a.id
                    val item = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = when {
                            isUsed -> Ui.shape(this@MainActivity, p.surface, 12f, p.border, 1f, dashed = true)
                            selected -> Ui.ripple(this@MainActivity, p.primaryBg, 12f, p.primaryBorder, night = p.night)
                            else -> Ui.ripple(this@MainActivity, p.surface, 12f, p.border, night = p.night)
                        }
                        alpha = if (isUsed) 0.45f else 1f
                        // Action choisie : fond plein foncé + coche, bien distincte des autres
                        val titleColor = if (selected) p.primaryText else p.text
                        val descColor = if (selected) (p.primaryText and 0x00FFFFFF) or (0xCC shl 24) else p.muted
                        addView(text((if (selected) "✓  " else "") + a.name, 14f, titleColor, Typeface.DEFAULT_BOLD))
                        addView(text(if (isUsed) "Déjà dans vos raccourcis" else a.desc, 12f, descColor))
                        if (!isUsed) setOnClickListener {
                            val names = Actions.LIST.map { it.name }
                            if (e.name.isBlank() || e.name.trim() in names) e.name = a.name
                            e.action = a.id
                            renderEditor()
                        }
                    }
                    panel.addView(item, lp(-1, -2, top = if (a == Actions.LIST.first()) 14 else 6))
                }
            }
            "setting" -> {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val c1 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                c1.addView(fieldLabel("N° DU RÉGLAGE"))
                c1.addView(input(e.menu, true, 2) { e.menu = it }, lp(-1, dp(48), top = 6))
                val c2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                c2.addView(fieldLabel("VALEUR"))
                c2.addView(input(e.value, true, 3) { e.value = it }, lp(-1, dp(48), top = 6))
                row.addView(c1, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(c2, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
                panel.addView(row, lp(-1, -2, top = 14))
                edHint = text("", 13f, p.muted)
                panel.addView(edHint, lp(-1, -2, top = 8))
                edPreview = previewBox()
                panel.addView(edPreview, lp(-1, -2, top = 8))
            }
            "team" -> renderTeamBody(panel, e)
            "channel" -> {
                panel.addView(fieldLabel("NUMÉRO DE CANAL (1 À 999)"), lp(-1, -2, top = 14))
                panel.addView(input(e.channel, true, 3) { e.channel = it }, lp(-1, dp(48), top = 6))
                panel.addView(text("Si la radio est en mode fréquence, l'appli passe d'abord en mode canal.", 13f, p.muted), lp(-1, -2, top = 8))
                edPreview = previewBox()
                panel.addView(edPreview, lp(-1, -2, top = 8))
            }
            else -> {
                panel.addView(text("Touchez les touches dans l'ordre. Elles seront rejouées telles quelles.", 13f, p.muted), lp(-1, -2, top = 14))
                panel.addView(previewBox().apply {
                    text = if (e.seq.isEmpty()) "Aucune touche pour l'instant" else Shortcut.stepsText(e.seq)
                }, lp(-1, -2, top = 8))
                val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                tools.addView(toggleTile("Prochaine touche en appui long", e.longNext) {
                    e.longNext = !e.longNext; renderEditor()
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                tools.addView(toggleTile("Effacer", false, e.seq.isNotEmpty()) {
                    e.seq.clear(); e.longNext = false; renderEditor()
                }, LinearLayout.LayoutParams(-2, dp(42)).apply { marginStart = dp(6) })
                panel.addView(tools, lp(-1, -2, top = 8))
                val grid = GridLayout(this).apply { columnCount = 6 }
                for ((i, k) in Keys.ALL.withIndex()) {
                    grid.addView(TextView(this).apply {
                        text = when {
                            k.id == "LEFT" && !prefs.k1Arrows -> "▲"
                            k.id == "RIGHT" && !prefs.k1Arrows -> "▼"
                            else -> k.main
                        }
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        setTextColor(p.text)
                        background = Ui.key(this@MainActivity, p.surface, p.border, p.keyEdge, 9f, p.night)
                        contentDescription = "Ajouter " + (KeyHelp.ENTRIES[k.id]?.title ?: k.main)
                        setOnClickListener {
                            if (e.seq.size < 30) e.seq += KeyStep(k.id, e.longNext)
                            e.longNext = false
                            renderEditor()
                        }
                    }, GridLayout.LayoutParams(GridLayout.spec(i / 6), GridLayout.spec(i % 6, 1f)).apply {
                        width = 0
                        height = dp(42)
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    })
                }
                panel.addView(grid, lp(-1, -2, top = 8))
            }
        }

        // Doublon + boutons
        edDup = text("", 13f, p.danger, medium).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = Ui.shape(this@MainActivity, Color.TRANSPARENT, 12f, p.danger)
            visibility = View.GONE
        }
        panel.addView(edDup, lp(-1, -2, top = 14))
        panel.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val editingId: Long? = e.editingId
        if (editingId != null) {
            val id: Long = editingId
            buttons.addView(actionButton("Supprimer", 2) { deleteShortcut(id) },
                LinearLayout.LayoutParams(-2, dp(50)).apply { marginEnd = dp(8) })
        }
        val save = actionButton("Enregistrer", 1) { saveEditor() }.apply { setPadding(dp(18), 0, dp(18), 0) }
        edSave = save
        buttons.addView(save, LinearLayout.LayoutParams(0, dp(50), 1f))
        panel.addView(buttons, lp(-1, -2, top = 16))

        refreshEditorStatus()
    }

    // ---------------- Configuration d'équipe ----------------

    /** Bouton qui affiche la valeur choisie et ouvre la liste des valeurs. */
    private fun pickerButton(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit): TextView =
        toggleTile((options.getOrNull(selected) ?: "Choisir") + "  ▾", false) {
            android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selected) { d, which -> d.dismiss(); onPick(which) }
                .setNegativeButton("Annuler", null)
                .show()
        }

    private fun renderTeamBody(panel: LinearLayout, e: EditorState) {
        val t = e.team
        panel.addView(text("Un appui règle la radio comme vous l'avez décidé et enregistre le tout dans un canal" + (if (t.endChannel) ", puis passe dessus. " else " ; la radio reste en mode fréquence. ") +
            "Idéal pour vos coéquipiers : partagez-le ensuite par QR code (appui long sur le bouton).", 13f, p.muted)
            .apply { setLineSpacing(0f, 1.15f) }, lp(-1, -2, top = 14))

        panel.addView(toggleTile("Coller un profil reçu (lien k1remote://…)", false) { importTeamFromClipboard(e) },
            lp(-1, dp(42), top = 10))

        panel.addView(fieldLabel("FRÉQUENCE (MHz)"), lp(-1, -2, top = 14))
        panel.addView(input(t.freq, false, 11) { t.freq = it }.apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "ex. 446.00625"
        }, lp(-1, dp(48), top = 6))

        // Sous-canaux : réception, puis émission (identique par défaut)
        fun toneBlock(label: String, cur: String, idx: Int, set: (String, Int) -> Unit) {
            panel.addView(fieldLabel(label), lp(-1, -2, top = 14))
            val tones = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, pair) in listOf("none" to "Aucun", "ctcss" to "CTCSS", "dcs" to "DCS").withIndex()) {
                tones.addView(toggleTile(pair.second, cur == pair.first) {
                    if (cur != pair.first) { set(pair.first, if (pair.first == "none") 0 else 1); renderEditor() }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(6) })
            }
            panel.addView(tones, lp(-1, -2, top = 6))
            if (cur == "ctcss") {
                panel.addView(pickerButton("Tonalité CTCSS", TeamProfile.CTCSS_LABELS, idx - 1) {
                    set("ctcss", it + 1); renderEditor()
                }, lp(-1, dp(44), top = 6))
            } else if (cur == "dcs") {
                panel.addView(pickerButton("Code DCS (N normal, I inversé)", TeamProfile.DCS_LABELS, idx - 1) {
                    set("dcs", it + 1); renderEditor()
                }, lp(-1, dp(44), top = 6))
            }
        }
        val same = e.teamSameTone
        toneBlock(if (same) "SOUS-CANAL (RÉCEPTION ET ÉMISSION)" else "SOUS-CANAL EN RÉCEPTION", t.tone, t.toneIdx) { ty, i ->
            t.tone = ty; t.toneIdx = i
            if (e.teamSameTone) { t.txTone = ty; t.txIdx = i }
        }
        panel.addView(toggleTile(if (same) "Sous-canal différent en émission…" else "✓  Sous-canal différent en émission", !same) {
            e.teamSameTone = !e.teamSameTone
            if (e.teamSameTone) { t.txTone = t.tone; t.txIdx = t.toneIdx }
            renderEditor()
        }, lp(-1, dp(42), top = 8))
        if (!same) {
            toneBlock("SOUS-CANAL EN ÉMISSION", t.txTone, t.txIdx) { ty, i -> t.txTone = ty; t.txIdx = i }
        }

        panel.addView(fieldLabel("PUISSANCE D'ÉMISSION"), lp(-1, -2, top = 14))
        panel.addView(pickerButton("Puissance (valeurs approximatives)", TeamProfile.POWER_LABELS, t.power - 1) {
            t.power = it + 1; renderEditor()
        }, lp(-1, dp(44), top = 6))

        panel.addView(fieldLabel("SQUELCH (0 = OUVERT, 9 = TRÈS FERMÉ)"), lp(-1, -2, top = 14))
        panel.addView(pickerButton("Squelch", (0..9).map { it.toString() }, t.squelch) { t.squelch = it; renderEditor() },
            lp(-1, dp(44), top = 6))

        panel.addView(fieldLabel("LARGEUR DE BANDE"), lp(-1, -2, top = 14))
        val bw = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bw.addView(toggleTile("Étroite (12,5 kHz)", t.narrow) { t.narrow = true; renderEditor() },
            LinearLayout.LayoutParams(0, dp(44), 1f))
        bw.addView(toggleTile("Large (25 kHz)", !t.narrow) { t.narrow = false; renderEditor() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
        panel.addView(bw, lp(-1, -2, top = 6))

        panel.addView(fieldLabel("BIP DES TOUCHES (MENU 40)"), lp(-1, -2, top = 14))
        val bp = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bp.addView(toggleTile("Bip OFF", !t.beep) { t.beep = false; renderEditor() },
            LinearLayout.LayoutParams(0, dp(44), 1f))
        bp.addView(toggleTile("Bip ON", t.beep) { t.beep = true; renderEditor() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
        panel.addView(bp, lp(-1, -2, top = 6))

        panel.addView(fieldLabel("APRÈS LE RÉGLAGE, LA RADIO RESTE"), lp(-1, -2, top = 14))
        val md = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        md.addView(toggleTile("En mode fréquence", !t.endChannel) { t.endChannel = false; renderEditor() },
            LinearLayout.LayoutParams(0, dp(44), 1f))
        md.addView(toggleTile("Sur le canal", t.endChannel) { t.endChannel = true; renderEditor() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
        panel.addView(md, lp(-1, -2, top = 6))

        panel.addView(fieldLabel("ENREGISTRER DANS LE CANAL N°"), lp(-1, -2, top = 14))
        panel.addView(input(t.channel.toString(), true, 3) { t.channel = it.toIntOrNull() ?: 0 }, lp(-1, dp(48), top = 6))
        panel.addView(text("Attention : ce canal de la radio sera remplacé. Le squelch est un réglage général de la radio, " +
            "pas seulement de ce canal.", 12f, p.muted).apply { setLineSpacing(0f, 1.15f) }, lp(-1, -2, top = 6))

        edPreview = previewBox().apply { textSize = 13f; typeface = medium }
        panel.addView(edPreview, lp(-1, -2, top = 10))
    }

    private fun importTeamFromClipboard(e: EditorState) {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val txt = runCatching { cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        val got = TeamProfile.fromLink(txt)
        if (got == null) { toast("Aucun profil d'équipe dans le presse-papiers"); return }
        e.name = got.first
        e.team = got.second
        e.teamSameTone = got.second.sameTone
        e.type = "team"
        toast("Profil « " + got.first + " » importé")
        renderEditor()
    }

    /** Ouvre l'éditeur pré-rempli avec un profil reçu par lien (QR code scanné…). */
    private fun openEditorWithTeam(name: String, t: TeamProfile) {
        val existing = shortcuts.firstOrNull { it.type == "team" && it.team?.signature() == t.signature() }
        if (existing != null) { toast("Ce profil est déjà dans vos raccourcis : « " + existing.name + " »"); return }
        if (shortcuts.size >= Shortcut.MAX) { toast("9 raccourcis maximum : supprimez-en un pour importer ce profil"); return }
        editor = EditorState(null, name, "team", "", "02", "3", "1", mutableListOf(), team = t, teamSameTone = t.sameTone)
        renderEditor()
        toast("Profil « $name » reçu : vérifiez puis Enregistrer")
    }

    private fun previewBox(): TextView = text("", 15f, p.text, Typeface.MONOSPACE).apply {
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = Ui.shape(this@MainActivity, p.surface, 12f, p.border, 1f, dashed = true)
    }

    private fun saveEditor() {
        val e = editor ?: return
        if (!canSave(e)) return
        val s = draft(e)
        val editingId: Long? = e.editingId
        if (editingId != null) {
            val i = shortcuts.indexOfFirst { it.id == editingId }
            s.id = editingId
            if (i >= 0) shortcuts[i] = s
            toast("Raccourci « " + s.name + " » modifié")
        } else {
            if (shortcuts.size >= Shortcut.MAX) { toast("9 raccourcis maximum"); return }
            s.id = (shortcuts.maxOfOrNull { it.id } ?: 0L) + 1L
            shortcuts.add(s)
            toast("Raccourci « " + s.name + " » ajouté")
        }
        Shortcut.save(prefs, shortcuts)
        closeEditor()
        buildUi()
    }

    private fun deleteShortcut(id: Long) {
        val s = shortcuts.firstOrNull { it.id == id } ?: return
        shortcuts.remove(s)
        Shortcut.save(prefs, shortcuts)
        closeEditor()
        buildUi()
        toast("Raccourci « " + s.name + " » supprimé")
    }

    // =====================================================================
    // Message bref
    // =====================================================================

    private fun toast(msg: String) {
        toastView?.let { rootFrame.removeView(it) }
        val t = text(msg, 13f, p.primaryText, medium).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = Ui.shape(this@MainActivity, p.primaryBg, 14f, p.primaryBorder)
            elevation = Ui.dp(this@MainActivity, 6f)
        }
        toastView = t
        rootFrame.addView(t, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(28)
            leftMargin = dp(24)
            rightMargin = dp(24)
        })
        val shown = t
        ui.postDelayed({
            if (toastView === shown) {
                (shown.parent as? ViewGroup)?.removeView(shown)
                toastView = null
            }
        }, 2200L)
    }
}

@Suppress("DEPRECATION")
private fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    else getParcelableExtra(UsbManager.EXTRA_DEVICE)
