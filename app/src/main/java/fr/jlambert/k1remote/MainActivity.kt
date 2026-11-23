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
import java.io.File
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
    private var statusPill: LinearLayout? = null
    private var statusText: TextView? = null
    private var rxChip: TextView? = null
    private var txChip: TextView? = null
    private var chipsHost: LinearLayout? = null
    /** Interface « En opération » : bande d'état et clavier replié. */
    private var opBand: TextView? = null
    private var opKeypad = false
    private var opGrid: LinearLayout? = null
    private var opGridL = false
    private var toastView: TextView? = null

    // Références de l'éditeur
    private var edSave: TextView? = null
    private var edDup: TextView? = null
    private var edPreview: TextView? = null
    private var edHint: TextView? = null

    /** Réponses de la radio, rangées par commande : chaque demande attend la sienne. */
    private val waiting = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.LinkedBlockingQueue<ByteArray>>()

    /** Envoie une commande directe et attend SA réponse (null si rien). */
    private val cmdLock = Any()

    /** État de la radio par la commande 0x09 (contrat v1), prioritaire sur la lecture des pixels. */
    private val rstate = RadioState()
    @Volatile private var statusKick = false
    private var lastStatusAt = 0L

    private fun request(c: RadioLink?, cmd: ByteArray, timeoutMs: Long): ByteArray? {
        c ?: return null
        val op = cmd[4].toInt() and 0xFF
        // Une seule commande en vol à la fois, quel que soit son code (0.63) : le firmware n'a
        // qu'une place pour la commande reçue (KEYBOARD_TakeCommand) ; deux commandes envoyées
        // coup sur coup depuis deux fils (surveillance 05, état 09, réglage 01…) pouvaient
        // s'écraser. Les réponses restent triées par leur code.
        synchronized(cmdLock) {
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
    private val parser = K5ViewerParser(onReply = { r ->
        if (r.size >= 3 && (r[0].toInt() and 0xFF) == 0x08) {
            // r26 : code DTMF reçu, envoyé spontanément par la radio (sans lecture d'écran)
            val n = minOf(r[2].toInt() and 0xFF, r.size - 3)
            val code = String(r, 3, n, Charsets.US_ASCII)
            ui.post { announceCaller(code, fromRadio = true) }
        } else if (r.isNotEmpty()) waiting[r[0].toInt() and 0xFF]?.offer(r)
    }) { px, flags -> ui.post { onFrame(px, flags) } }

    /** Dernier appelant annoncé (pour la bande « En opération » et pour ne pas l'annoncer deux fois). */
    private var lastCaller: String? = null
    private var lastCallerCode: String? = null
    private var lastCallerAt = 0L

    /**
     * Appelant identifié par son code (« A1 ») : nom pris dans Préférences › Noms des appelants.
     * Le code arrive soit de la radio (r26, fiable), soit de la lecture de son écran (D Live).
     */
    private fun announceCaller(raw: String, fromRadio: Boolean) {
        val code = raw.trim().uppercase()
        if (code.isEmpty()) return
        val now = System.currentTimeMillis()
        if (code == lastCallerCode && now - lastCallerAt < 4000) return
        // Le décodeur de la radio double parfois un caractère (« AA1 ») ou colle deux émissions
        // rapprochées (« A1AA1 », « A22A2 ») : on retient le code connu qui apparaît le plus tard
        // dans la chaîne (à égalité, le plus long).
        val known = prefs.dtmfNames.lines().mapNotNull { l ->
            val parts = l.split('=', ':', limit = 2)
            val k = parts.getOrNull(0)?.trim()?.uppercase()
            val n = parts.getOrNull(1)?.trim()
            if (parts.size == 2 && !k.isNullOrEmpty() && !n.isNullOrEmpty()) k to n else null
        }
        val best = known.filter { code.contains(it.first) }
            .maxWithOrNull(compareBy<Pair<String, String>>({ code.lastIndexOf(it.first) + it.first.length }, { it.first.length }))
        val name = best?.second
        val shown = best?.first ?: code
        lastCallerCode = code
        lastCallerAt = now
        lastCaller = name ?: code
        CallLog.caller(name, if (name != null) shown else code, now)
        Journal.add("Appelant : " + (name?.let { "$it ($shown, reçu $code)" } ?: "code $code inconnu") + if (fromRadio) " · radio" else " · écran")
        callerActive = true
        showCallerPill(if (name != null) "Appel de $name" else "Code reçu : $code (inconnu)")
        // Le nom reste tant que la réception dure ; si elle est déjà finie (émission très courte), 3 s
        ui.removeCallbacks(endCaller)
        if (lastFlags and 0x04 == 0) ui.postDelayed(endCaller, 3000)
        refreshStatus()
        refreshOpBand()
        refreshPttBar()
    }

    /** Étiquette « Appel de … » posée en haut de l'écran de la radio (interface complète), 8 s. */
    private var callerPill: TextView? = null
    /** L'appelant annoncé est en train de parler (effacé 1,5 s après la fin de sa réception). */
    private var callerActive = false
    private var prevRx = false
    private val endCaller = Runnable {
        callerActive = false
        callerPill?.visibility = View.GONE
        refreshStatus()
        refreshOpBand()
        refreshPttBar()
    }

    private fun showCallerPill(text: String) {
        val v = callerPill ?: return
        v.text = text
        v.visibility = View.VISIBLE
    }

    /** Demande à la radio si elle comprend les commandes directes. */
    private fun probeDirect(then: (() -> Unit)? = null) {
        val c = cdc ?: run { then?.let { ui.post(it) }; return }
        sender.execute {
            try { probeLoop(c) } finally { then?.let { ui.post(it) } }
        }
    }

    private fun probeLoop(c: RadioLink) {
        run {
            repeat(3) {
                if (cdc !== c) return@run
                val r = request(c, K5ViewerProtocol.PING, 700)
                if (r != null && r.size >= 5 && r[0].toInt() == 4 && r[2] == 'K'.code.toByte()) {
                    direct = true
                    Journal.add("Radio reconnue : Fusion PTT " + (if (r.size > 7) String(r, 7, r.size - 7) else "") +
                        (if (r.size >= 7) (if (r[6].toInt() == 1) " · UV-K5 V3" else " · UV-K1") else ""))
                    startWatcher(c)
                    // Protocole 2 : modèle (réglage SetNav) et version du firmware
                    val model = if (r.size >= 7) (if (r[6].toInt() == 1) "UV-K5 V3" else "UV-K1") else null
                    val ver = if (r.size > 7) String(r, 7, r.size - 7) else ""
                    fwRevision = Regex("""r(\d+)""").find(ver)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    // Firmwares r10 à r23 : réponse limitée à 16 octets, « r23 » arrivait tronqué en « r2 ».
                    // Réponse pleine et un seul chiffre : c'est au moins la dizaine (r2 → r20 et plus).
                    if (r.size == 16 && fwRevision in 1..9) fwRevision *= 10
                    ui.post {
                        radioInfo = listOfNotNull(model, "Fusion PTT" + (if (ver.isNotEmpty()) " $ver" else "")).joinToString(" · ")
                        // Les flèches de l'appli suivent le réglage SetNav de la radio
                        if (model != null) prefs.k1Arrows = model == "UV-K1"
                        toast("$radioInfo reconnu : réglages directs et silencieux")
                        if (editor == null) buildUi()
                    }
                    return@run
                }
            }
        }
    }

    /**
     * Réglages faits au clavier même avec Fusion PTT : ceux qui attendent un n° de canal (15, 20,
     * 21, 44) et ceux que le firmware réserve au clavier par sécurité depuis r19 (13 TXLock,
     * 16 ChDele, 17 ChName, 73 SetCfg, 75 F Lock, 76 350 En, 77 BatCal, 80 Reset) : en commande
     * directe, la radio les refuserait.
     */
    private val KEYPAD_ONLY_MENUS = setOf(13, 15, 16, 17, 20, 21, 44, 73, 75, 76, 77, 80)

    /** Dernière vérification du firmware déclenchée par un raccourci (évite de boucler). */
    private var shortcutProbeAt = 0L

    private val keepAliveBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    // ---- Lecture de la mémoire de la radio (protocole de programmation d'origine) ----
    /** Session de lecture en cours : écran, diagnostic, keepalive et PTT sont suspendus. */
    @Volatile private var progSession = false
    @Volatile private var progParser: ProgParser? = null

    private fun feedIncoming(buf: ByteArray, n: Int) {
        val pp = progParser
        if (pp != null) pp.feed(buf, n) else parser.feed(buf, n)
    }

    /**
     * Lit les canaux, noms, attributs, VFO et réglages (lecture seule : 0x0514 puis 0x051B).
     * Rien n'est écrit dans la radio. Appelé hors du fil principal.
     */
    private fun readRadioMemory(c: RadioLink, progress: (Int, Int) -> Unit): RadioMemory {
        val replies = java.util.concurrent.LinkedBlockingQueue<Pair<Int, ByteArray>>()
        progSession = true
        try {
            Thread.sleep(400)                       // laisse passer la fin des trames d'écran
            progParser = ProgParser { id, body -> replies.offer(id to body) }
            val ts = java.util.Random().nextInt()
            var version = ""
            for (attempt in 0 until 3) {
                replies.clear()
                if (!c.write(ProgProtocol.hello(ts))) continue
                val r = pollFor(replies, ProgProtocol.ID_VERSION, 1500) ?: continue
                version = String(r, 4, minOf(16, r.size - 4)).trim { it <= ' ' || it == '\u0000' }
                break
            }
            if (version.isEmpty()) error("La radio ne répond pas à la demande de lecture")
            Journal.add("Lecture mémoire : radio « $version »")
            val img = ByteArray(RadioMemory.SIZE) { 0xFF.toByte() }
            val total = RadioMemory.RANGES.sumOf { (it.last - it.first + ProgProtocol.MAX_READ) / ProgProtocol.MAX_READ }
            var done = 0
            for (range in RadioMemory.RANGES) {
                var off = range.first
                while (off <= range.last) {
                    if (cdc !== c) error("Liaison avec la radio perdue")
                    val size = minOf(ProgProtocol.MAX_READ, range.last + 1 - off)
                    var got: ByteArray? = null
                    for (attempt in 0 until 4) {
                        replies.clear()
                        if (!c.write(ProgProtocol.read(off, size, ts))) { Thread.sleep(100); continue }
                        val r = pollFor(replies, ProgProtocol.ID_READ_REPLY, 900) ?: continue
                        // 1C 05 | taille | adresse (2) | longueur | bourrage | données
                        val rOff = (r[4].toInt() and 0xFF) or ((r[5].toInt() and 0xFF) shl 8)
                        val rLen = r[6].toInt() and 0xFF
                        if (rOff != off || rLen != size || r.size < 8 + size) continue
                        got = r.copyOfRange(8, 8 + size)
                        break
                    }
                    val data = got ?: error(String.format("Lecture impossible à l'adresse %04X", off))
                    System.arraycopy(data, 0, img, off, size)
                    off += size
                    progress(++done, total)
                }
            }
            return RadioMemory(img, version, System.currentTimeMillis(), "")
        } finally {
            progParser = null
            progSession = false
            parser.reset()
            Thread { request(c, K5ViewerProtocol.PING, 700) }.start()   // l'écran revient en entier
        }
    }

    private fun pollFor(q: java.util.concurrent.BlockingQueue<Pair<Int, ByteArray>>, id: Int, ms: Long): ByteArray? {
        val end = System.currentTimeMillis() + ms
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) return null
            val m = q.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS) ?: return null
            if (m.first == id) return m.second
        }
    }

    // ---- Écriture des canaux (0x051D), chaque bloc relu et comparé ----

    private class WriteCancelled : Exception("Envoi annulé : rien n'a été écrit dans la radio")

    /** Ouvre une session de programmation ; renvoie la version annoncée par la radio. */
    private fun progHello(c: RadioLink, q: java.util.concurrent.BlockingQueue<Pair<Int, ByteArray>>, ts: Int): String {
        repeat(3) {
            q.clear()
            if (c.write(ProgProtocol.hello(ts))) {
                val r = pollFor(q, ProgProtocol.ID_VERSION, 1500)
                if (r != null) return String(r, 4, minOf(16, r.size - 4)).trim { it <= ' ' || it == '\u0000' }
            }
        }
        error("La radio ne répond pas à la demande de programmation")
    }

    private fun progRead(c: RadioLink, q: java.util.concurrent.BlockingQueue<Pair<Int, ByteArray>>, ts: Int, off: Int, size: Int): ByteArray {
        repeat(4) {
            q.clear()
            if (!c.write(ProgProtocol.read(off, size, ts))) { Thread.sleep(100); return@repeat }
            val r = pollFor(q, ProgProtocol.ID_READ_REPLY, 900) ?: return@repeat
            val rOff = (r[4].toInt() and 0xFF) or ((r[5].toInt() and 0xFF) shl 8)
            if (rOff == off && (r[6].toInt() and 0xFF) == size && r.size >= 8 + size) return r.copyOfRange(8, 8 + size)
        }
        error(String.format("Lecture impossible à l'adresse %04X", off))
    }

    /**
     * Écrit les différences entre [draft] et [base] :
     *  1. (boîtier) attend l'autorisation donnée par un appui court sur BOOT ;
     *  2. relit toute la radio et l'enregistre (« Avant écriture ») ;
     *  3. s'arrête si les zones à écrire ont changé dans la radio depuis la lecture ;
     *  4. écrit chaque bloc, le relit et le compare (3 essais) ;
     *  5. enregistre l'état final (« Après écriture ») et renvoie ce fichier.
     * Hors du fil principal.
     */
    private fun writeRadioMemory(c: RadioLink, base: RadioMemory, draft: RadioMemory,
                                 cancelled: () -> Boolean, step: (String, Int) -> Unit): File {
        val blocks = draft.diff(base)
        if (blocks.isEmpty()) error("Aucune modification à envoyer")
        if (c is BleLink) {
            c.requestProgramming(true)
            val end = System.currentTimeMillis() + 30_000
            var polls = 0
            while (!c.progOpen) {
                if (++polls % 8 == 0) c.refreshState()      // toutes les 2 s, si les notifications manquent
                if (cancelled()) throw WriteCancelled()
                if (cdc !== c) error("Liaison avec le boîtier perdue")
                val left = ((end - System.currentTimeMillis()) / 1000).toInt()
                if (left <= 0) error("Pas d'appui sur BOOT dans les 30 secondes : rien n'a été écrit")
                step("Appuyez brièvement sur le bouton BOOT du boîtier\n(lumière orange clignotante) — $left s", -1)
                Thread.sleep(250)
            }
            Journal.add("Écriture des canaux autorisée sur le boîtier")
        }
        val q = java.util.concurrent.LinkedBlockingQueue<Pair<Int, ByteArray>>()
        progSession = true
        var written = 0
        var before: File? = null
        try {
            Thread.sleep(400)
            progParser = ProgParser { id, body -> q.offer(id to body) }
            val ts = java.util.Random().nextInt()
            val version = progHello(c, q, ts)
            // 2. Sauvegarde complète juste avant d'écrire
            val img = ByteArray(RadioMemory.SIZE) { 0xFF.toByte() }
            val total = RadioMemory.RANGES.sumOf { (it.last - it.first + ProgProtocol.MAX_READ) / ProgProtocol.MAX_READ }
            var done = 0
            for (range in RadioMemory.RANGES) {
                var off = range.first
                while (off <= range.last) {
                    if (cdc !== c) error("Liaison avec la radio perdue")
                    if (cancelled()) throw WriteCancelled()
                    val size = minOf(ProgProtocol.MAX_READ, range.last + 1 - off)
                    System.arraycopy(progRead(c, q, ts, off, size), 0, img, off, size)
                    off += size
                    step("Sauvegarde de la radio avant écriture…", ++done * 50 / total)
                }
            }
            val now = RadioMemory(img, version, System.currentTimeMillis(), "Avant écriture")
            // 3. Même radio, même firmware, même emplacement Multiboot : tous les canaux, noms et
            //    attributs doivent être identiques à ceux de la lecture (sinon : autre banque CFG,
            //    autre firmware ou canaux changés entre-temps)
            if (base.firmware.isNotEmpty() && version != base.firmware)
                error("La radio annonce « $version » au lieu de « ${base.firmware} » lors de la lecture " +
                    "(autre firmware ou autre emplacement Multiboot). Rien n'a été écrit. Relisez la radio.")
            for (zone in RadioMemory.WRITE_ZONES) for (a in zone) if (now.image[a] != base.image[a])
                error("Les canaux de la radio ont changé depuis votre lecture (modifiés sur la radio, autre " +
                    "emplacement Multiboot ou autre appli). Rien n'a été écrit. Relisez la radio : vos modifications " +
                    "restent dans le brouillon de l'ancienne sauvegarde.")
            before = now.save(this)
            if (cancelled()) throw WriteCancelled()
            // 4. Écriture vérifiée, bloc par bloc
            for ((off, data) in blocks) {
                var ok = false
                for (attempt in 0 until 3) {
                    if (cdc !== c) error("Liaison avec la radio perdue")
                    if (c is BleLink && !c.progOpen)
                        error("Autorisation d'écriture refermée sur le boîtier (10 minutes écoulées ou BOOT pressé)")
                    q.clear()
                    if (!c.write(ProgProtocol.write(off, data, ts))) { Thread.sleep(150); continue }
                    val r = pollFor(q, ProgProtocol.ID_WRITE_REPLY, 1500) ?: continue
                    val rOff = (r[4].toInt() and 0xFF) or ((r[5].toInt() and 0xFF) shl 8)
                    if (r.size < 6 || rOff != off) continue
                    if (progRead(c, q, ts, off, data.size).contentEquals(data)) { ok = true; break }
                }
                if (!ok) error(String.format("Écriture non confirmée à l'adresse %04X", off))
                System.arraycopy(data, 0, now.image, off, data.size)
                written++
                step("Écriture des canaux… $written / ${blocks.size}", 50 + written * 50 / blocks.size)
            }
            // 5. État final = radio relue + blocs vérifiés
            val after = RadioMemory(now.image, version, System.currentTimeMillis(), "Après écriture")
            Journal.add("Écriture des canaux : $written bloc(s) écrits et vérifiés")
            return after.save(this)
        } catch (e: Exception) {
            if (written > 0) throw IllegalStateException((e.message ?: "Erreur") +
                "\n\n$written bloc(s) sur ${blocks.size} ont été écrits et vérifiés avant l'arrêt. " +
                "La sauvegarde « ${before?.name} » contient l'état de la radio juste avant l'envoi.", e)
            throw e
        } finally {
            progParser = null
            progSession = false
            parser.reset()
            if (c is BleLink) c.requestProgramming(false)
            Thread { request(c, K5ViewerProtocol.PING, 700) }.start()
        }
    }

    /** Appelé par l'écran « Mes canaux » (via [MemoryBridge]) ; les dialogues s'affichent sur [act]. */
    private fun startMemoryWrite(act: Activity, base: RadioMemory, draft: RadioMemory, done: (Boolean, File?) -> Unit) {
        fun info(title: String, msg: String, then: () -> Unit = {}) {
            if (act.isFinishing) { then(); return }
            android.app.AlertDialog.Builder(act).setTitle(title).setMessage(msg)
                .setPositiveButton("OK") { _, _ -> then() }.setOnCancelListener { then() }.show()
        }
        val c = cdc
        if (c == null || !direct || (c is BleLink && !relayRadio)) {
            info("Radio non connectée", "Connectez la radio (firmware Fusion PTT) à l'appli, puis renvoyez les modifications. " +
                "Elles restent dans l'écran « Mes canaux » tant que vous ne le quittez pas.")
            return
        }
        if (pttOn) { info("PTT", "Relâchez le PTT d'abord."); return }
        if (c is BleLink && !c.canProgram) {
            info("Boîtier à mettre à jour", "L'écriture des canaux par Bluetooth demande K1 Relais 1.5 ou plus " +
                "(boîtier actuel : ${c.relayVersion ?: "ancienne version"}). Flashez k1-relais-v1.5.bin, " +
                "ou branchez la radio directement au téléphone en USB.")
            return
        }
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val density = act.resources.displayMetrics.density
        val label = android.widget.TextView(act).apply { textSize = 14f; setTextColor(android.graphics.Color.GRAY); text = "Préparation…" }
        val bar = android.widget.ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val p = (22 * density).toInt(); setPadding(p, (16 * density).toInt(), p, (8 * density).toInt())
            addView(label)
            addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
        }
        val dlg = android.app.AlertDialog.Builder(act).setTitle("Envoi à la radio").setView(box).setCancelable(false)
            .setNegativeButton("Annuler", null).create()
        dlg.show()
        dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { cancel.set(true); label.text = "Annulation…" }
        Thread {
            try {
                val f = writeRadioMemory(c, base, draft, { cancel.get() }) { msg, pct ->
                    ui.post {
                        label.text = msg
                        bar.isIndeterminate = pct < 0
                        if (pct >= 0) bar.progress = pct
                        // Plus d'annulation une fois l'écriture commencée : chaque bloc est vérifié
                        if (pct > 50) dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.visibility = View.GONE
                    }
                }
                ui.post {
                    dlg.dismiss()
                    SuccessCard.show(act, "Canaux envoyés",
                        "Toutes les modifications ont été écrites puis relues dans la radio.\n\n" +
                            "Éteignez la radio puis rallumez-la : elle ne prend les nouveaux canaux qu'au démarrage.") { done(true, f) }
                }
            } catch (e: Exception) {
                Journal.add("Écriture des canaux interrompue : ${e.message}")
                ui.post { dlg.dismiss(); info(if (e is WriteCancelled) "Envoi annulé" else "Envoi interrompu", e.message ?: "Erreur") { done(false, null) } }
            }
        }.start()
    }

    /** Bouton « Canaux » : lit la radio (si elle est là) puis ouvre la liste. */
    private fun openChannels() {
        val c = cdc
        val last = RadioMemory.list(this).firstOrNull()
        if (c == null || !direct) {
            if (last != null) startActivity(ChannelsActivity.intent(this, last))
            else toast("Connectez la radio pour lire ses canaux")
            return
        }
        val b = android.app.AlertDialog.Builder(this)
            .setTitle("Canaux de la radio")
            .setMessage("Lire les 1 024 canaux de la radio et les enregistrer dans une sauvegarde ?\n\n" +
                "Lecture seule : rien n'est modifié dans la radio. Comptez une vingtaine de secondes ; " +
                "l'écran et le PTT de l'appli sont suspendus pendant la lecture.")
            .setPositiveButton("Lire la radio") { _, _ -> startMemoryRead(c) }
            .setNegativeButton("Annuler", null)
        if (last != null) b.setNeutralButton("Dernière sauvegarde") { _, _ -> startActivity(ChannelsActivity.intent(this, last)) }
        b.show()
    }

    private fun startMemoryRead(c: RadioLink) {
        if (pttOn) { toast("Relâchez le PTT d'abord"); return }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val label = text("Préparation…", 14f, android.graphics.Color.GRAY)   // lisible sur le fond du dialogue système
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(8))
            addView(label); addView(bar, lp(-1, -2, top = 12))
        }
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle("Lecture des canaux")
            .setView(box)
            .setCancelable(false)
            .create()
        dlg.show()
        Thread {
            try {
                val mem = readRadioMemory(c) { d, t -> ui.post { bar.progress = d * 100 / t; label.text = "Lecture… ${d * 100 / t} %" } }
                val f = mem.save(this)
                val n = mem.channels().size
                Journal.add("Lecture mémoire terminée : $n canaux, sauvegarde ${f.name}")
                ui.post { dlg.dismiss(); startActivity(ChannelsActivity.intent(this, f)) }
            } catch (e: Exception) {
                Journal.add("Lecture mémoire interrompue : ${e.message}")
                ui.post {
                    dlg.dismiss()
                    android.app.AlertDialog.Builder(this).setTitle("Lecture interrompue")
                        .setMessage((e.message ?: "Erreur") + "\n\nRien n'a été modifié dans la radio. Rapprochez le téléphone et réessayez.")
                        .setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }
    private val keepAlive = object : Runnable {
        override fun run() {
            val c = cdc ?: return
            if (progSession) { ui.postDelayed(this, KEEPALIVE_MS); return }   // lecture mémoire : silence
            // Fil séparé : le keepalive ne doit pas attendre la fin d'une suite de touches.
            // Un seul en vol : sous congestion, le suivant est sauté au lieu de s'empiler.
            if (keepAliveBusy.compareAndSet(false, true)) Thread {
                try { c.write(K5ViewerProtocol.KEEPALIVE) } finally { keepAliveBusy.set(false) }
            }.start()
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
        CallLog.init(this)
        MemoryBridge.writer = { act, base, draft, done -> startMemoryWrite(act, base, draft, done) }
        MemoryBridge.channelSelector = { n -> selectChannel(n) }
        MemoryBridge.menuSender = { items ->
            val c = cdc
            when {
                c == null || (c is BleLink && !relayRadio) -> "Radio non connectée"
                !direct -> "Réglage direct impossible : il faut le firmware Fusion PTT sur la radio"
                progSession -> "Lecture ou écriture des canaux en cours"
                else -> if (sendSteps(settingSteps(items.map { (m, v) -> m to v.toString() }))) null else "Envoi refusé par l'appli (voir le message)"
            }
        }
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
        if (MemoryBridge.openJournal) { MemoryBridge.openJournal = false; ui.postDelayed({ showCallLog() }, 300) }
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
        MemoryBridge.writer = null
        MemoryBridge.menuSender = null
        MemoryBridge.channelSelector = null
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
            onData = { buf, n -> feedIncoming(buf, n) },
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
            radioInfo = null; fwRevision = 0
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
            onData = { buf, n -> feedIncoming(buf, n) },
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
                if (!longSequenceRunning && !progSession) pollStatus(c)
                if (!longSequenceRunning && !progSession) {
                    val d = request(c, K5ViewerProtocol.command(0x05), 700)?.let { decodeDiag(it) }
                    if (d != null) {
                        val tx = lastFlags and 0x02 != 0
                        val now = System.currentTimeMillis()
                        if (d.first != lastKey) { Journal.add(d.second); lastKey = d.first; lastTxLog = now }
                        else if (tx && now - lastTxLog > 5000) { Journal.add("(toujours en émission)\n" + d.second.substringAfterLast("\n")); lastTxLog = now }
                    }
                }
                // Pendant un PTT de l'appli, le diagnostic s'efface (relevé au début et à la fin
                // seulement, via watchKick) : la liaison reste au PTT. Émission par la radio : 1 s.
                var waited = 0
                val period = when {
                    pttOn -> 3000
                    lastFlags and 0x02 != 0 -> 1000
                    else -> 1500
                }
                while (waited < period && !watchKick && !statusKick) {
                    Thread.sleep(50); waited += 50
                    synchronized(rstate) { rstate.checkTimeout(System.currentTimeMillis()) }
                }
                watchKick = false
            }
            if (watcherFor === c) watcherFor = null
        }, "k1-watch").start()
    }

    /** Journal (appui long sur le titre) : tout ce que l'appli et la radio ont fait, en direct. */
    /** Journal des appels (lisible) ; le journal technique reste à un bouton. */
    private fun showCallLog() {
        CallLogDialog.show(this, onTechnical = { showDiagnostic() }, onShare = { calls -> shareJournal(calls) })
    }

    private fun shareJournal(callsOnly: Boolean) {
        val head = "K1 Remote " + appVersion + " · " + (radioInfo ?: "radio ?")
        // Android refuse (et ferme l'appli) au-delà d'environ 500 Ko passés à une autre appli :
        // on ne partage que le plus récent, bien en dessous de cette limite (0.60).
        fun cap(t: String, max: Int) = if (t.length <= max) t
            else t.substring(0, t.lastIndexOf('\n', max).takeIf { it > 0 } ?: max) + "\n… (plus ancien non joint)"
        val body = if (callsOnly) head + "\n\nJournal des appels\n\n" + cap(CallLog.text(), 40_000)
            else head + "\n\n=== Journal des appels ===\n" + cap(CallLog.text(), 20_000) +
                "\n\n=== Journal technique (le plus récent en haut) ===\n" + cap(Journal.text(), 60_000)
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, if (callsOnly) "Journal des appels K1 Remote" else "Rapport K1 Remote")
                putExtra(Intent.EXTRA_TEXT, body)
            }, if (callsOnly) "Envoyer le journal des appels" else "Envoyer le rapport"))
        }.onFailure { toast("Partage impossible : " + (it.message ?: it.javaClass.simpleName)) }
    }

    // =====================================================================
    // État de la radio par la commande 0x09 (firmware r28, contrat v1 figé)
    // =====================================================================

    /** Appelée par la boucle de surveillance (fil k1-watch), jamais pendant un réglage long. */
    private fun pollStatus(c: RadioLink) {
        val now = System.currentTimeMillis()
        val (support, delay) = synchronized(rstate) { rstate.support to rstate.nextPollDelay(now) }
        if (support == RadioState.Support.SCREEN) return
        if (pttOn) return                                    // PTT prioritaire, reprise après
        if (!statusKick && now - lastStatusAt < delay) return
        if (!resumed && !statusKick) return                 // appli en arrière-plan : aucune lecture
        statusKick = false
        lastStatusAt = now
        val raw = request(c, RadioStatus.REQUEST, 800)
        if (cdc !== c) return
        val t = System.currentTimeMillis()
        val change = synchronized(rstate) {
            if (raw == null) rstate.onTimeout() else rstate.onResult(RadioStatus.decode(raw), t)
        }
        if (raw != null) (RadioStatus.decode(raw) as? RadioStatus.Result.Invalid)?.let { Journal.add("État 0x09 ignoré : ${it.reason}") }
        when (change) {
            RadioState.Change.SUPPORT_FIRMWARE -> {
                Journal.add("État de la radio lu directement dans le firmware (commande 0x09)")
                ui.post { applyFirmwareState() }
            }
            RadioState.Change.SUPPORT_SCREEN -> Journal.add("Firmware sans commande 0x09 : état lu sur l'écran de la radio")
            RadioState.Change.STATE -> ui.post { applyFirmwareState() }
            RadioState.Change.NONE -> if (raw == null && synchronized(rstate) { rstate.usesFirmware }) ui.post { refreshChips() }
        }
        ui.post { refreshIfActiveChanged() }
    }

    /** Recopie l'état 0x09 dans la lecture consultée par tout l'affichage. */
    private fun applyFirmwareState() {
        val fw = synchronized(rstate) { rstate.reading() } ?: return
        val old = reading
        reading = fw
        live = fw.copy(dtmf = live.dtmf)
        if (old.mainVfo != fw.mainVfo || old.mainPower != fw.mainPower || old.powerA != fw.powerA ||
            old.powerB != fw.powerB || stateWasStale) refreshChips()
        stateWasStale = false
    }

    private var stateWasStale = false

    /** État du firmware utilisé mais trop ancien (hors émission) : affiché grisé, jamais comme actuel. */
    private fun stateStale(): Boolean {
        val stale = synchronized(rstate) {
            rstate.usesFirmware && !rstate.isFresh(System.currentTimeMillis(), pttOn || lastFlags and 0x02 != 0)
        }
        if (stale) stateWasStale = true
        return stale
    }

    /** Réglage envoyé : sa confirmation est attendue dans l'état 0x09 (contrat § 6.5). */
    private fun expectState(label: String, check: (RadioStatus.Snapshot) -> Boolean) {
        synchronized(rstate) {
            if (!rstate.usesFirmware) return
            rstate.expect(RadioState.Pending(label, System.currentTimeMillis(), check) { outcome ->
                when (outcome) {
                    RadioState.Outcome.CONFIRMED -> Journal.add("Confirmé par la radio : $label")
                    RadioState.Outcome.REFUSED -> Journal.add("Refusé par la radio : $label")
                    RadioState.Outcome.UNDETERMINED -> {
                        Journal.add("Non confirmé par la radio : $label (résultat inconnu)")
                        statusKick = true; watchKick = true
                        ui.post { toast("$label : non confirmé par la radio") }
                    }
                }
            })
        }
        statusKick = true; watchKick = true
    }

    /**
     * Fonction d'un raccourci encore active sur la radio (0.64). Avec le firmware r28 (0x09) :
     * verrou, furtif, scan et spectre viennent de la radio ; sinon, ce que l'appli sait déjà.
     */
    private fun shortcutActive(action: String): Boolean {
        val now = System.currentTimeMillis()
        val (snap, busy) = synchronized(rstate) {
            (if (rstate.usesFirmware) rstate.snapshot else null) to rstate.isBusy(now)
        }
        return when (action) {
            "screendark" -> prefs.screenDark
            "lock" -> snap?.keyLocked ?: live.locked
            // Furtif de l'appli (réglages de menus) ou furtif profond du firmware (commande 07, indicateur 0x09)
            "stealth" -> prefs.stealth || snap?.stealth == true
            "scan" -> snap != null && !busy && snap.function == RadioStatus.Function.SCAN_OR_SPECTRUM
            "spectrum" -> busy
            else -> false
        }
    }

    /** Signature des témoins : les raccourcis sont redessinés seulement quand elle change. */
    private var activeSig = ""
    private fun refreshIfActiveChanged() {
        val sig = listOf("screendark", "lock", "stealth", "scan", "spectrum").joinToString("") { if (shortcutActive(it)) "1" else "0" }
        if (sig != activeSig) {
            activeSig = sig; refreshChips()
            val k = fKeyDef
            if (k != null) fKeyView?.text = if (shortcutActive("lock")) lockedLabel(k) else keyLabel(k)
        }
    }

    /** « F 🔒 » : plus court que l'étiquette normale, donc tient toujours dans la touche. */
    private fun lockedLabel(k: KeyDef): CharSequence = SpannableStringBuilder().apply {
        append(k.main, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append("  🔒")
    }

    /** « Mes canaux » › Passer la radio sur ce canal : mêmes touches que le raccourci « Aller à un canal ». */
    private fun selectChannel(n: Int): String? {
        if (cdc == null) return "Radio non connectée"
        if (live.locked) return "Clavier verrouillé : appui long sur F pour le déverrouiller"
        val mode = live.mainMode ?: return "Revenez à l'écran principal de la radio (ligne active non reconnue)"
        if (!sendSteps(Shortcut.channelSteps(n.toString(), fromFreqMode = mode == 'F'))) return "Envoi impossible pour l'instant"
        expectChannel(n)
        return null
    }

    private fun expectChannel(n: Int) = expectState("Canal $n") { st ->
        st.selectedLine?.let { it.kind == RadioStatus.LineKind.MEMORY && it.number == n } == true
    }

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
            .setTitle("Journal technique")
            .setView(box)
            .setPositiveButton("Partager") { _, _ -> shareJournal(false) }
            .setNeutralButton("Effacer") { _, _ ->
                android.app.AlertDialog.Builder(this).setTitle("Effacer le journal technique ?")
                    .setMessage("Le journal des appels n'est pas touché.")
                    .setPositiveButton("Effacer") { _, _ -> Journal.clear(); toast("Journal technique effacé") }
                    .setNegativeButton("Annuler", null).show()
            }
            .setNegativeButton("Fermer", null)
            .setOnDismissListener { Journal.listener = null }
            .show()
        cdc?.let { if (direct) { startWatcher(it); watchKick = true } }
    }

    // =====================================================================
    // PTT à distance (firmware « Fusion PTT »)
    // =====================================================================

    private val pttExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var pttOn = false
    private val pttHoldQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private var pttSince = 0L
    private var pttView: TextView? = null

    private val pttRepeat = object : Runnable {
        override fun run() {
            if (!pttOn) return
            // Sécurité côté téléphone aussi : 30 s au maximum
            if (System.currentTimeMillis() - pttSince > 30_000) { pttStop(); toast("Émission coupée après 30 s"); return }
            val c = cdc
            if (c == null) { pttStop(); return }
            // Maintien coalescé : au plus un en attente, et abandonné si le bouton a été relâché
            if (pttHoldQueued.compareAndSet(false, true)) pttExec.execute {
                pttHoldQueued.set(false)
                if (pttOn) c.write(K5ViewerProtocol.ptt(true))
            }
            ui.postDelayed(this, 200)
        }
    }

    private fun pttStart() {
        if (pttOn) return
        if (longSequenceRunning) { toast("Émission bloquée : réglage de la radio en cours"); return }
        if (progSession) { toast("Émission bloquée : lecture des canaux en cours"); return }
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
        // Hors du fil PTT : une requête de 800 ms ne doit jamais retarder le relâchement
        Thread {
            val r = request(c, K5ViewerProtocol.command(0x05), 800) ?: return@Thread
            if (r.size < 16 || r[0].toInt() != 5) return@Thread
            fun u(i: Int) = r[i].toInt() and 0xFF
            val why = when {
                r.size >= 22 && u(21) == 1 -> "la radio est en PTT « une pression » (indicateur OP en haut de l'écran). " +
                    "Une touche latérale réglée sur « PTT » l'a basculée : appuyez de nouveau dessus, ou éteignez puis rallumez la radio"
                u(6) > 0 -> "la radio se croit en cours de programmation par le PC : fermez UV Studio ou débranchez le câble"
                u(7) == 1 -> "la radio émet la tonalité 1750 Hz"
                u(2) == 3 || u(3) == 3 -> "émission interdite sur cette fréquence (F Lock / TXLock du canal)"
                u(9) == 0 -> "batterie trop faible"
                else -> null
            } ?: return@Thread
            Journal.add("Appli : pas d'émission — $why")
            ui.post { toast("Pas d'émission : $why") }
        }.start()
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
        // En réception d'un appelant identifié, le bouton dit qui parle
        val talking = !pttOn && callerActive && lastCaller != null && (lastFlags and 0x04 != 0)
        v.text = when {
            pttOn -> "● ÉMISSION…  relâchez pour écouter"
            talking -> "◉  $lastCaller parle"
            else -> "Maintenir pour émettre (PTT)"
        }
        v.setTextColor(if (pttOn) p.txOnText else if (talking) p.rxOnText else p.text)
        v.background = Ui.shape(this, if (pttOn) p.txOnBg else if (talking) p.rxOnBg else p.surface, 16f,
            if (pttOn) p.txOnBorder else if (talking) p.rxOnBorder else p.border, 2f)
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
        CallLog.rxEnd(); CallLog.txEnd()
        ui.removeCallbacks(keepAlive)
        cdc?.close()
        cdc = null
        pendingBle?.close()
        pendingBle = null
        relayRadio = false
        direct = false
        synchronized(rstate) { rstate.reset() }
        lastStatusAt = 0L
        radioInfo = null; fwRevision = 0
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
            if (ch and 0x02 != 0) { if (flags and 0x02 != 0) CallLog.txStart() else CallLog.txEnd() }
            if (ch and 0x04 != 0) { if (flags and 0x04 != 0) CallLog.rxStart() else CallLog.rxEnd() }
            if (ch and 0x01 != 0) Journal.add("Radio : " + (if (flags and 0x01 != 0) "mise en veille de l'écran" else "réveil de l'écran"))
            lastFlags = flags; refreshLeds()
            watchKick = true
        }
        val r = ScreenReader.read(px)
        val fw = synchronized(rstate) { if (rstate.usesFirmware) rstate.reading() else null }
        // État du firmware (0x09) prioritaire : les pixels ne servent plus qu'au code DTMF
        // (firmwares d'avant r26) et, en secours, aux firmwares sans 0x09
        live = fw?.copy(dtmf = r.dtmf) ?: r
        val code = r.dtmf
        if (code != null && code != lastDtmf) announceCaller(code, fromRadio = false)
        lastDtmf = code
        // Changement de ligne (A ⇄ B) : le bouton Puissance doit suivre, même si les deux lignes
        // ont la même puissance (avant la 0.51, la ligne était mise à jour avant d'être comparée,
        // et le bouton gardait l'ancienne lettre).
        refreshIfActiveChanged()
        if (fw != null) {
            val via0 = (if (cdc is BleLink) " · Bluetooth" else "") + (if (direct) " · PTT" else "")
            if (sleeping) setLink(Link.SLEEP, "Radio en veille$via0") else setLink(Link.ON, "Connecté$via0")
            return
        }
        val lineChanged = r.mainVfo != null && r.mainVfo != reading.mainVfo
        if (lineChanged) {
            // La ligne active se lit même si la puissance n'est pas reconnue
            reading = ScreenReader.Reading(r.mainVfo, reading.powerA, reading.powerB)
        }
        val powerChanged = r.mainPower != null && r.mainPower != reading.mainPower
        if (r.mainPower != null) reading = r
        if (lineChanged || powerChanged) refreshChips()
        val via = (if (cdc is BleLink) " · Bluetooth" else "") + (if (direct) " · PTT" else "")
        if (sleeping) setLink(Link.SLEEP, "Radio en veille$via") else setLink(Link.ON, "Connecté$via")
    }

    @Volatile private var longSequenceRunning = false

    /** [onDone] : appelé (fil principal) seulement si toute la séquence est passée sans erreur. */
    private fun sendSteps(input: List<KeyStep>, restoreBeep: Boolean = true, onDone: (() -> Unit)? = null): Boolean {
        val c = cdc ?: run { toast("Radio non connectée"); return false }
        Journal.add("Appli : envoi " + input.filter { it.keyId != KeyStep.WAIT }.joinToString(" ") {
            if (it.cmd != null) "[cmd " + it.cmd.joinToString("") { b -> "%02X".format(b) }.drop(8).dropLast(2) + "]"
            else it.keyId + (if (it.long) "(long)" else "") }.take(400))
        if (longSequenceRunning) { toast("Patientez : réglage de la radio en cours"); return false }
        // Réglage long et bip actif : on coupe le bip d'abord (quelques bips au lieu de dizaines),
        // puis on le rétablit à la fin. Inutile en furtif : le bip y est déjà coupé.
        // Clavier de la radio verrouillé (cadenas) : le verrou protège aussi l'appli des touchers
        // involontaires (choix de D'jé, 0.64). Touches et raccourcis sont refusés, sauf l'appui long
        // sur F (raccourci Verrou compris), qui déverrouille. Avant la 0.64, un raccourci de plusieurs
        // touches déverrouillait, agissait puis reverrouillait tout seul.
        val lockedNow = live.locked
        if (lockedNow && !(input.size == 1 && input[0].keyId == "F" && input[0].long)) {
            toast("Clavier verrouillé : appui long sur F pour le déverrouiller")
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
            var completed = false
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
                                val op = st.cmd.getOrNull(4)?.toInt()
                                val why = when (r?.getOrNull(1)?.toInt()) {
                                    null -> "pas de réponse de la radio"
                                    3 -> if (op == 0x01) "réglage réservé au clavier de la radio (sécurité du firmware)"
                                         else "la ligne active n'est pas en mode fréquence"
                                    4 -> "fréquence hors des bandes de la radio"
                                    else -> "réglage refusé (code ${r[1]})"
                                }
                                // Refus prouvé par l'accusé (statut non nul) : la commande attendue est refusée
                                r?.getOrNull(1)?.toInt()?.let { st -> if (st != 0) synchronized(rstate) { rstate.onAck(st) } }
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
                    if (!disturbed) { completed = true; break }
                    if (++attempt > 3) break
                    ui.post { toast("Réception pendant le réglage : reprise dès la fin de l'appel") }
                    waitQuiet()
                }
            } finally {
                if (long) longSequenceRunning = false
                // Réglage fini : l'état de la radio est relu tout de suite (confirmation, § 6.5)
                statusKick = true; watchKick = true
                // Réussite annoncée seulement si tout est passé (avant la 0.58, « terminé »
                // s'affichait même après une interruption)
                if (completed) ui.post { if (onDone != null) onDone() else if (long) toast("Réglage de la radio terminé") }
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
            // Marges extérieures réduites (0.56) : l'écran du téléphone est utilisé au maximum
            if (L) setPadding(dp(10), dp(6), dp(10), dp(6)) else setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        opBand = null
        opGrid = null
        chipsHost = null
        callerPill = null
        statusPill = null; statusText = null; statusDot = null
        rxChip = null; txChip = null
        val operation = prefs.uiMode == 1 && !editMode && !helpMode
        if (operation) buildOperation(content, L) else {
        val header = buildHeader(L)
        // Ligne 2 : pastille d'état + boutons ; en mode aide, le bandeau d'aide vient dessous
        val controls = buildControlRow(L, null)
        val helpBanner: View? = if (helpMode) buildHelpBanner() else null
        val lcdFrame = FrameLayout(this).apply {
            background = Ui.shape(this@MainActivity, p.lcdFrame, 18f, p.lcdFrameBorder)
            val side = dp(if (L) 8 else 10)
            setPadding(side, side, side, side)
            clipToPadding = false     // la pastille déborde sur le cadre
            // Hauteur naturelle (moitié de la largeur), réduite si la place manque : en paysage,
            // l'écran de la radio passe avant les raccourcis.
            lcd.fitContent = L
            // Paysage : l'écran ne prend qu'environ la moitié de la hauteur, le reste va aux raccourcis
            lcd.maxHeightPx = 0   // paysage : l'écran prend la hauteur laissée par les raccourcis
            addView(lcd, FrameLayout.LayoutParams(if (L) -2 else -1, -2))
            // Appelant identifié : étiquette verte posée sur le haut de l'écran de la radio
            addView(TextView(this@MainActivity).apply {
                textSize = if (L) 14f else 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(p.rxOnText)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = Ui.shape(this@MainActivity, p.rxOnBg, 999f, p.rxOnBorder)
                elevation = dp(4).toFloat()
                visibility = if (callerActive && lastCaller != null) View.VISIBLE else View.GONE
                text = lastCaller?.let { "Appel de $it" } ?: ""
                callerPill = this
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(4) })
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
            content.addView(controls, lp(-1, -2, top = 8))
            if (helpBanner != null) content.addView(helpBanner, lp(-1, -2, top = 8))
            content.addView(lcdFrame, lp(-1, -2, top = 10))
            if (panel != null) content.addView(panel, lp(-1, -2, top = 10))
            content.addView(topKeys, lp(-1, dp(42), top = 10))
            content.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
            if (prefs.remotePtt) content.addView(buildPttBar(), lp(-1, dp(64), top = 8))
        } else {
            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(header)
                addView(controls, lp(-1, -2, top = 6))
                if (helpBanner != null) addView(helpBanner, lp(-1, -2, top = 6))
                // Les raccourcis s'affichent en entier en bas ; l'écran de la radio prend la place
                // restante (cadre ajusté à son contenu, centré), quitte à être un peu plus petit.
                addView(FrameLayout(this@MainActivity).apply {
                    addView(lcdFrame, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
                if (panel != null) addView(panel, lp(-1, -2, top = 6))
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

    /**
     * Ligne 1 de l'en-tête : l'identité, sur une seule ligne et en pleine largeur.
     * Thème camouflage : écusson cousu, drapeau, « K1 REMOTE » au pochoir, version, unité à droite.
     * Autres thèmes : « K1 Remote », version, radio reconnue à droite.
     * Les textes sont des CapsLabel (hauteur des lettres réelles) alignés sur leur ligne de base.
     * Appui long sur le titre : Journal.
     */
    private fun buildHeader(L: Boolean): LinearLayout {
        val camo = p.camo
        val headerBg = p.headerBg
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (camo != null && headerBg != null) {
            row.background = Ui.shape(this, headerBg, 12f, p.headerBorder, 1.5f, dashed = true)
            row.setPadding(dp(10), dp(if (L) 5 else 7), dp(10), dp(if (L) 5 else 7))
            val flag = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, col) in camo.flag.withIndex()) {
                flag.addView(View(this).apply { setBackgroundColor(col) },
                    LinearLayout.LayoutParams(dp(4), dp(if (L) 12 else 14)).apply { if (i > 0) marginStart = dp(2) })
            }
            row.addView(flag, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
        }
        val title = CapsLabel(this).apply {
            text = if (camo != null) "K1 REMOTE" else "K1 Remote"
            setFont(if (camo != null) Fonts.stencil(this@MainActivity) else Typeface.DEFAULT_BOLD, if (L) 17f else 20f)
            if (camo != null) letterSpacing = 0.02f
            color = p.text
            isLongClickable = true
            setOnLongClickListener { showDiagnostic(); true }
        }
        texts.addView(title, LinearLayout.LayoutParams(-2, -2))
        if (appVersion.isNotEmpty()) texts.addView(CapsLabel(this).apply {
            text = appVersion
            setFont(medium, 11f)
            // Version lisible sur tous les fonds : couleur du texte à 75 %
            color = (p.text and 0x00FFFFFF) or (0xBF shl 24)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        // À droite : l'unité (camouflage) ou la radio reconnue ; tronquée par « … » si la place manque
        val right = CapsLabel(this).apply {
            text = when {
                camo != null -> camo.unit
                p.night -> "Mode nuit"
                else -> radioSubtitle()
            }
            setFont(if (camo != null) Typeface.DEFAULT_BOLD else Typeface.DEFAULT, if (camo != null) 9f else 12f)
            if (camo != null) letterSpacing = 0.1f
            color = p.muted
        }
        texts.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        texts.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        right.textAlignEnd = true
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    /**
     * Ligne 2 de l'en-tête : à gauche la pastille d'état (ou, en interface « En opération », le
     * lien vers l'interface complète), à droite les cinq boutons ronds.
     */
    private fun buildControlRow(L: Boolean, leading: View?): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(leading ?: buildStatusPill(), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        val size = dp(if (L) 34 else 38)
        val gap = dp(5)
        fun add(v: View, first: Boolean = false) =
            row.addView(v, LinearLayout.LayoutParams(size, size).apply { if (!first) marginStart = gap })
        add(roundButton("☾", if (p.night) "Quitter le mode furtif" else "Mode furtif : appli en nuit et écran de la radio éteint", p.night) {
            // Lune = furtif complet. Appli en nuit alors que la radio ne l'est pas (radio absente au
            // moment de l'appui) : on rétablit d'abord l'appli seule.
            if (p.night && !prefs.stealth) { prefs.night = false; buildUi() }
            else toggleStealth(!prefs.stealth)
        }, first = true)
        add(roundButton("▤", "Canaux de la radio : lire, sauvegarder, consulter", false) { openChannels() })
        add(roundButton("⌖", "Carte d'équipe", TeamMapState.running) {
            startActivity(Intent(this, MapActivity::class.java))
        })
        add(roundButton("⚙︎", "Préférences", false) {
            startActivity(Intent(this, PreferencesActivity::class.java))
        })
        add(roundButton("?", if (helpMode) "Quitter le mode aide" else "Mode aide : touchez ensuite une touche", helpMode) {
            helpMode = !helpMode
            editMode = false
            buildUi()
        })
        return row
    }

    /** Radio reconnue (firmware Fusion PTT) : « UV-K5 V3 · Fusion PTT v7.0.0 ». */
    private var radioInfo: String? = null
    /** Révision Fusion PTT (« r17 » → 17) ; 0 si inconnue. Le furtif complet demande la r17. */
    @Volatile private var fwRevision = 0

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
            statusPill = this
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
        refreshStatus()
        // Fin de réception : le nom de l'appelant s'efface 1,5 s après
        if (prevRx && !rx && callerActive) { ui.removeCallbacks(endCaller); ui.postDelayed(endCaller, 1500) }
        if (rx) ui.removeCallbacks(endCaller)
        prevRx = rx
        refreshOpBand()
        refreshPttBar()
    }

    // ---------------- Interface « En opération » ----------------

    /**
     * Écran simplifié pour la partie : ce que fait la radio (grande bande), son écran, les
     * raccourcis en gros boutons et un grand PTT. Le clavier complet se déplie à la demande.
     */
    private fun buildOperation(content: LinearLayout, L: Boolean) {
        val header = buildHeader(L)
        val band = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = if (L) 17f else 20f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(12), 0)
        }
        opBand = band
        refreshOpBand()
        val lcdFrame = FrameLayout(this).apply {
            background = Ui.shape(this@MainActivity, p.lcdFrame, 18f, p.lcdFrameBorder)
            val side = dp(8)
            setPadding(side, side, side, side)
            lcd.fitContent = L
            lcd.maxHeightPx = 0
            addView(lcd, FrameLayout.LayoutParams(if (L) -2 else -1, -2))
        }
        // Raccourcis : deux colonnes de gros boutons (la modification se fait dans l'interface complète)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        opGrid = grid
        opGridL = L
        fillOpGrid()
        val keysToggle = TextView(this).apply {
            text = if (opKeypad) "Masquer le clavier  ▴" else "⌨  Clavier de la radio  ▾"
            textSize = 14f
            typeface = medium
            gravity = Gravity.CENTER
            setTextColor(p.text)
            background = Ui.ripple(this@MainActivity, p.surface, 12f, p.border, night = p.night)
            setOnClickListener { opKeypad = !opKeypad; buildUi() }
        }
        val modeLink = TextView(this).apply {
            text = "‹  Complète"
            textSize = 13f
            typeface = medium
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextColor(p.text)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            // Pastille comme les autres commandes : lisible sur tous les fonds (camouflages compris)
            background = Ui.ripple(this@MainActivity, p.surface, 999f, p.border, night = p.night)
            contentDescription = "Revenir à l'interface complète"
            setOnClickListener { prefs.uiMode = 0; buildUi() }
        }
        fun ptt() = buildPttBar().apply { textSize = 22f }
        if (!L) {
            content.addView(header)
            content.addView(buildControlRow(false, modeLink), lp(-1, -2, top = 8))
            content.addView(band, lp(-1, dp(58), top = 8))
            content.addView(lcdFrame, lp(-1, -2, top = 10))
            if (opKeypad) {
                content.addView(keysToggle, lp(-1, dp(40), top = 10))
                content.addView(buildTopKeys(), lp(-1, dp(42), top = 8))
                content.addView(buildPad(false), LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
            } else {
                // Partie centrale défilante : le PTT reste toujours entier en bas, quel que soit
                // le nombre de raccourcis ou la taille du téléphone
                val mid = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(grid, lp(-1, -2))
                    addView(keysToggle, lp(-1, dp(44), top = 12))
                }
                content.addView(android.widget.ScrollView(this).apply {
                    isVerticalScrollBarEnabled = false
                    isFillViewport = false
                    addView(mid)
                }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
            }
            if (prefs.remotePtt) content.addView(ptt(), lp(-1, dp(if (opKeypad) 64 else 84), top = 10))
        } else {
            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(header)
                addView(buildControlRow(true, modeLink), lp(-1, -2, top = 6))
                addView(band, lp(-1, dp(44), top = 6))
                addView(FrameLayout(this@MainActivity).apply {
                    addView(lcdFrame, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
            }
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                if (opKeypad) {
                    addView(buildTopKeys(), lp(-1, dp(38)))
                    addView(buildPad(true), LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })
                } else {
                    addView(android.widget.ScrollView(this@MainActivity).apply {
                        isVerticalScrollBarEnabled = false
                        addView(grid)
                    }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })
                }
                addView(keysToggle, lp(-1, dp(36), top = 6))
                if (prefs.remotePtt) addView(ptt(), lp(-1, dp(64), top = 6))
            }
            content.addView(left, LinearLayout.LayoutParams(0, -1, 1.2f).apply { marginEnd = dp(14) })
            content.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    /** Bande d'état de l'interface « En opération » : émission, réception, veille ou liaison. */
    private fun refreshOpBand() {
        val b = opBand ?: return
        val tx = lastFlags and 0x02 != 0
        val rx = lastFlags and 0x04 != 0
        val line = reading.mainVfo?.let { "Ligne $it" }
        val pw = reading.mainPower?.let { val n = ScreenReader.pretty(it); val w = RadioMemory.powerWatts(n); if (w.isEmpty()) n else "$n ($w)" }
        val (label, bg, fg, border) = when {
            link != Link.ON && link != Link.SLEEP -> listOf(linkText.ifEmpty { "Radio non connectée" }, p.surface, p.muted, p.border)
            tx -> listOf("●  ÉMISSION", p.txOnBg, p.txOnText, p.txOnBorder)
            rx -> listOf("●  RÉCEPTION" + (lastCaller?.takeIf { callerActive }?.let { "  ·  $it" } ?: ""),
                p.rxOnBg, p.rxOnText, p.rxOnBorder)
            else -> listOf(listOfNotNull("Veille", line, pw).joinToString("  ·  "), p.surface, p.text, p.border)
        }
        b.text = label as String
        b.setTextColor(fg as Int)
        b.background = Ui.shape(this, bg as Int, 16f, border as Int)
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

    /**
     * Pastille d'état (ligne 2 de l'en-tête) :
     *  - en émission : fond rouge, « ÉMISSION » ;
     *  - en réception : fond vert, nom de l'appelant s'il est identifié, sinon « RÉCEPTION » ;
     *  - sinon : point coloré et état court de la liaison (« Connecté », « Radio en veille »…).
     */
    private fun refreshStatus() {
        refreshOpBand()
        val tx = link == Link.ON && lastFlags and 0x02 != 0
        val rx = link == Link.ON && lastFlags and 0x04 != 0
        val label = statusText ?: return
        val pill = statusPill
        val dot = statusDot
        when {
            tx -> {
                label.text = "ÉMISSION"
                label.setTextColor(p.txOnText)
                pill?.background = Ui.ripple(this, p.txOnBg, 999f, p.txOnBorder, night = p.night)
                dot?.visibility = View.GONE
            }
            rx -> {
                label.text = if (callerActive && lastCaller != null) lastCaller else "RÉCEPTION"
                label.setTextColor(p.rxOnText)
                pill?.background = Ui.ripple(this, p.rxOnBg, 999f, p.rxOnBorder, night = p.night)
                dot?.visibility = View.GONE
            }
            else -> {
                label.text = when (link) {
                    Link.ON -> "Connecté"
                    Link.SLEEP -> "Radio en veille"
                    else -> linkText
                }
                label.setTextColor(p.text)
                pill?.background = Ui.ripple(this, p.surface, 999f, p.border, night = p.night)
                dot?.visibility = View.VISIBLE
                val c = when (link) {
                    Link.OFF -> p.muted
                    Link.WAITING -> if (p.night) p.text else Color.parseColor("#E07B00")
                    Link.ON -> p.dot
                    Link.SLEEP -> if (p.night) p.muted else Color.parseColor("#3C7DD9")
                }
                (dot?.background as? GradientDrawable)?.setColor(c)
            }
        }
    }

    // ---------------- Raccourcis ----------------

    private fun buildShortcutPanel(L: Boolean): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (L) setPadding(dp(6), dp(4), dp(6), dp(6)) else setPadding(dp(8), dp(6), dp(8), dp(8))
            background = Ui.shape(this@MainActivity, p.surface, 16f, p.border)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, dp(if (L) 4 else 6))
        }
        head.addView(text("RACCOURCIS " + shortcuts.size + "/" + Shortcut.MAX, if (p.camo != null) 13f else 12f,
            if (p.camo != null) p.text else p.muted,
            if (p.camo != null) Fonts.stencil(this) else Typeface.DEFAULT_BOLD).apply {
            letterSpacing = 0.06f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (!editMode && !helpMode) head.addView(TextView(this).apply {
            text = "En opération"
            textSize = 12f
            typeface = medium
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(p.text)
            contentDescription = "Passer à l'interface simplifiée « En opération »"
            background = Ui.ripple(this@MainActivity, Color.TRANSPARENT, 8f, p.border, night = p.night)
            setOnClickListener { prefs.uiMode = 1; opKeypad = false; buildUi() }
        }, LinearLayout.LayoutParams(-2, dp(if (L) 26 else 32)).apply { marginEnd = dp(6) })
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
        }, LinearLayout.LayoutParams(-2, dp(if (L) 26 else 32)))
        panel.addView(head)
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chipsHost = host
        panel.addView(host)
        fillChips(L)
        return panel
    }

    private fun refreshChips() {
        refreshOpBand()
        if (chipsHost != null) fillChips(landscape)
        // Interface « En opération » : sa grille de raccourcis suit aussi la ligne active et la
        // puissance (avant la 0.57, elle n'était dessinée qu'une fois : le bouton Puissance
        // gardait l'ancienne ligne A ou B)
        if (opGrid != null) fillOpGrid()
    }

    /** Grille de l'interface « En opération » : raccourcis en gros boutons sur deux colonnes. */
    private fun fillOpGrid() {
        val grid = opGrid ?: return
        val L = opGridL
        grid.removeAllViews()
        val rows = (shortcuts.size + 1) / 2
        val rowH = dp(if (L) 38 else if (rows > 3) 42 else 46)
        for ((r, pair) in shortcuts.chunked(2).withIndex()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, s) in pair.withIndex()) {
                val v = chipView(s, false)
                (v as? ViewGroup)?.let { g -> for (k in 0 until g.childCount) (g.getChildAt(k) as? TextView)?.textSize = 16f }
                row.addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = dp(8) })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(8) })
            grid.addView(row, lp(-1, rowH, top = if (r == 0) 0 else 8))
        }
    }

    private fun fillChips(L: Boolean) {
        val host = chipsHost ?: return
        host.removeAllViews()
        val cols = if (L) 5 else 3
        val h = dp(if (L) 30 else 36)
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
            if (stateStale()) badge.alpha = 0.45f
            box.addView(badge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5) })
            box.contentDescription = "Puissance actuelle de la ligne $vfo : ${ScreenReader.pretty(power)}. Toucher pour passer à la suivante."
        } else {
            if (s.type == "team" && !editMode) {
                // Pastille « équipe » pour distinguer ce bouton
                box.addView(text("◆", if (L) 9f else 10f, p.primaryBorder, Typeface.DEFAULT_BOLD),
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
            }
            // Fonction restée active (écran éteint, verrou, furtif, scan, spectre) : le bouton le montre
            val darkOn = s.type == "action" && !editMode && shortcutActive(s.action)
            if (darkOn) {
                // Éclairage coupé : le bouton le montre (pastille pleine), un appui le rallume
                box.background = Ui.ripple(this@MainActivity, p.accentBg, 10f, p.accentBorder, night = p.night)
                box.addView(text("●", if (L) 9f else 10f, p.accentText, Typeface.DEFAULT_BOLD),
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
            }
            box.addView(text((if (editMode) "✎ " else "") + s.name, if (L) 11f else 12f,
                if (darkOn) p.accentText else p.chipText, Typeface.DEFAULT_BOLD).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            box.contentDescription = if (darkOn) s.name + " : actif, toucher pour rallumer" else s.name
        }
        box.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            when {
                helpMode -> openSheet("sc:" + s.id)
                editMode -> openEditor(s)
                else -> runShortcut(s)
            }
        }
        if (s.isPower && !helpMode && !editMode) attachPowerPicker(box)
        else slowLongPress(box) {
            longPressFeedback(it)
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
    /**
     * Appui long reconnu. Option « Vibrer aux appuis longs » : une vibration nette de 70 ms,
     * même si le retour tactile du téléphone est coupé ; jamais de son. Sinon : retour tactile
     * habituel du système.
     */
    private fun longPressFeedback(v: View) {
        if (!prefs.vibrateLong) { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); return }
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= 31)
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator)
            if (Build.VERSION.SDK_INT >= 26)
                vib.vibrate(android.os.VibrationEffect.createOneShot(70, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vib.vibrate(70)
        }
    }

    // ---- Puissance « glisser » : appui long sur le raccourci Puissance ----
    // Une petite colonne de puissances s'ouvre sous le doigt ; on glisse sans lâcher jusqu'à la
    // puissance voulue, qui clignote quand on lève le doigt ; la colonne se ferme et la radio change.

    private val powerLevels = listOf("LOW1", "LOW2", "LOW3", "LOW4", "LOW5", "MID", "HIGH")
    private var picker: LinearLayout? = null
    private var pickerItems: List<TextView> = emptyList()
    private var pickerSel = -1
    private var pickerTop = 0f
    private var pickerItemH = 1

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachPowerPicker(v: View) {
        var fired = false
        var picking = false
        var downX = 0f; var downY = 0f; var rawX = 0f; var rawY = 0f
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val open = Runnable {
            fired = true
            v.isPressed = false
            longPressFeedback(v)
            picking = showPowerPicker(rawX, rawY)
            if (picking) v.parent?.requestDisallowInterceptTouchEvent(true)
        }
        v.setOnTouchListener { _, ev ->
            rawX = ev.rawX; rawY = ev.rawY
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { fired = false; picking = false; downX = ev.x; downY = ev.y; v.postDelayed(open, LONG_PRESS_MS) }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (picking) { movePowerPicker(ev.rawY); return@setOnTouchListener true }
                    if (Math.abs(ev.x - downX) > slop || Math.abs(ev.y - downY) > slop) v.removeCallbacks(open)
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(open)
                    if (picking) { picking = false; releasePowerPicker(true); return@setOnTouchListener true }
                    if (fired) { v.isPressed = false; return@setOnTouchListener true }
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(open)
                    if (picking) { picking = false; releasePowerPicker(false); return@setOnTouchListener true }
                }
            }
            false
        }
    }

    private fun stylePickerItem(t: TextView, level: String, on: Boolean) {
        if (on) { styleBadge(t, level); t.textSize = 17f }
        else {
            t.background = null
            t.setTextColor(p.text)
            t.textSize = 15f
        }
    }

    private fun showPowerPicker(rawX: Float, rawY: Float): Boolean {
        if (picker != null) return false
        val cur = powerLevels.indexOf(reading.mainPower ?: "").let { if (it < 0) 2 else it }
        val itemH = dp(46)
        val pad = dp(5)
        val w = dp(196)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = Ui.shape(this@MainActivity, p.surface, 18f, p.border, 1.5f)
            elevation = dp(14).toFloat()
        }
        // HIGH en haut, LOW 1 en bas : on monte pour plus de puissance
        val order = powerLevels.indices.reversed().toList()
        val items = order.map { i ->
            val name = ScreenReader.pretty(powerLevels[i])
            TextView(this).apply {
                text = name + "  ·  " + RadioMemory.powerWatts(name)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(p.text)
            }
        }
        items.forEach { box.addView(it, LinearLayout.LayoutParams(-1, itemH)) }
        val totalH = itemH * items.size + 2 * pad
        val loc = IntArray(2)
        overlay.getLocationOnScreen(loc)
        val row = order.indexOf(cur)
        val top = (rawY - loc[1] - pad - row * itemH - itemH / 2f).toInt().coerceIn(0, maxOf(0, overlay.height - totalH))
        val left = (rawX - loc[0] - w / 2f).toInt().coerceIn(0, maxOf(0, overlay.width - w))
        overlay.addView(box, FrameLayout.LayoutParams(w, totalH).apply { leftMargin = left; topMargin = top })
        picker = box
        pickerItems = items
        pickerTop = (loc[1] + top + pad).toFloat()
        pickerItemH = itemH
        pickerSel = row
        items.forEachIndexed { k, t -> stylePickerItem(t, powerLevels[order[k]], k == row) }
        return true
    }

    private fun movePowerPicker(rawY: Float) {
        if (picker == null) return
        val row = Math.floor(((rawY - pickerTop) / pickerItemH).toDouble()).toInt().coerceIn(0, pickerItems.size - 1)
        if (row == pickerSel) return
        val order = powerLevels.indices.reversed().toList()
        stylePickerItem(pickerItems[pickerSel], powerLevels[order[pickerSel]], false)
        pickerSel = row
        stylePickerItem(pickerItems[row], powerLevels[order[row]], true)
        picker?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun releasePowerPicker(apply: Boolean) {
        val box = picker ?: return
        if (!apply) { overlay.removeView(box); picker = null; return }
        val order = powerLevels.indices.reversed().toList()
        val target = order[pickerSel]
        val item = pickerItems[pickerSel]
        var n = 0
        // La puissance choisie clignote trois fois, puis la colonne se ferme et la radio change
        ui.post(object : Runnable {
            override fun run() {
                n++
                item.alpha = if (n % 2 == 1) 0.15f else 1f
                if (n < 6) ui.postDelayed(this, 90)
                else { overlay.removeView(box); picker = null; applyPower(target) }
            }
        })
    }

    private fun applyPower(target: Int) {
        val cur = powerLevels.indexOf(reading.mainPower ?: "")
        if (target == cur) return
        val label = ScreenReader.pretty(powerLevels[target])
        if (direct) {
            // Menu 2 Power : 0 USER, 1 LOW 1 … 7 HIGH
            val sel = reading.mainVfo
            if (sendSteps(listOf(KeyStep("CMD", false, K5ViewerProtocol.setMenu(2, target + 1))))) {
                toast("Puissance : $label")
                expectState("Puissance $label", { st -> st.selectedLine?.power == target + 1 &&
                    (sel == null || st.selected == (if (sel == 'B') 1 else 0)) })
            }
        } else {
            if (cur < 0) { toast("Puissance actuelle non lue : revenez à l'écran principal de la radio"); return }
            val steps = (target - cur + powerLevels.size) % powerLevels.size
            if (sendSteps(List(steps) { KeyStep("6", true) })) toast("Puissance : $label")
        }
    }

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

    /**
     * Furtif = bascule : entrer (écran de la radio éteint/sombre + appli en nuit) / sortir (réglages
     * normaux). Utilisé par le raccourci Furtif et par la lune. Radio absente : seule l'appli change.
     */
    private fun toggleStealth(entering: Boolean) {
        // Radio réellement là ? Boîtier connecté sans radio : on n'envoie rien (sinon la
        // séquence attendait des réponses qui ne viennent pas et bloquait le retour au normal).
        val radioHere = cdc != null && (cdc !is BleLink || relayRadio)
        if (radioHere) {
            if (!sendSteps(stealthSteps(entering))) return
            prefs.stealth = entering
            toast(if (entering && prefs.stealthLight == 2 && fwRevision >= 18) "Furtif profond : écran de la radio noir, voyants éteints (quelques secondes)…"
                  else if (entering) "Mode furtif : écran de la radio éteint (quelques secondes)…"
                  else "Sortie du furtif : réglages normaux rétablis (quelques secondes)…")
        } else if (!entering && prefs.stealth) {
            toast("La radio est restée en furtif : rebranchez-la puis touchez la lune pour la rétablir")
        }
        prefs.night = entering
        applyRelayLed()
        buildUi()
    }

    /**
     * « Écran éteint » : plus aucun éclairage de la radio, même en réception ou en émission
     * (39 BLTxRx OFF, 36 BLTime OFF). L'afficheur reste lisible à la lumière du jour ; l'appli
     * ne change pas de thème. Réappuyer rétablit l'éclairage normal des Préférences.
     */
    private fun toggleScreenDark(enter: Boolean) {
        val radioHere = cdc != null && (cdc !is BleLink || relayRadio)
        if (!radioHere) { toast("Radio non connectée"); return }
        val items = if (enter) listOf(Shortcut.MENU_BLTXRX to "0", Shortcut.MENU_BLTIME to "00")
            else listOf(Shortcut.MENU_BLTXRX to prefs.dayBlTxRx.toString(),
                        Shortcut.MENU_BLTIME to Shortcut.pad2(prefs.dayBlTime.toString()))
        if (!sendSteps(settingSteps(items))) return
        prefs.screenDark = enter
        toast(if (enter) "Éclairage de la radio coupé (même pendant les appels)" else "Éclairage de la radio rétabli")
        buildUi()
    }

    /** Réglages envoyés à la radio pour entrer dans le furtif ou en sortir. */
    private fun stealthSteps(enter: Boolean): List<KeyStep> {
        val items = mutableListOf<Pair<Int, String>>()
        if (enter) {
            items += Shortcut.MENU_BLTXRX to "0"                                   // pas d'éclairage aux appels
            if (prefs.stealthLight >= 1) items += Shortcut.MENU_BLTIME to "00"     // jamais d'éclairage
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
        val steps = settingSteps(items).toMutableList()
        // r17 : afficheur LCD et voyants coupés (entrée, en dernier) / rétablis (sortie, en premier)
        // Furtif profond (r18+) : écran de la radio entièrement noir et voyants éteints. En sortie,
        // on l'envoie toujours (même si la préférence a changé entre-temps) : sans effet s'il n'était pas actif.
        if (direct && fwRevision >= 18 && (!enter || prefs.stealthLight == 2)) {
            val cmd = KeyStep("CMD", false, K5ViewerProtocol.command(0x07, if (enter) 1 else 0))
            if (enter) steps += cmd else steps.add(0, cmd)
        }
        return steps
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
                Actions.byId("ab")?.let { if (sendSteps(it.steps)) expectState("Ligne $target", { st -> st.selected == (if (top) 0 else 1) }) }
            }
        }
    }

    private fun runShortcut(s: Shortcut) {
        // Liaison qui vient de se rétablir : l'appli ne sait pas encore que la radio a le firmware
        // Fusion PTT. Pour un réglage, on le vérifie d'abord (≤ 2 s) au lieu de retomber sur les
        // appuis de touches.
        if (!direct && cdc != null && (s.type == "team" || s.type == "setting") &&
            System.currentTimeMillis() - shortcutProbeAt > 4000) {
            shortcutProbeAt = System.currentTimeMillis()
            probeDirect { runShortcut(s) }
            return
        }
        val a = if (s.type == "action") Actions.byId(s.action) else null
        if (a?.nightToggle == true) { toggleStealth(!prefs.stealth); return }
        if (a?.screenToggle == true) { toggleScreenDark(!prefs.screenDark); return }
        if (s.type == "team" || s.type == "channel") {
            // Il faut savoir si la ligne active est en mode canal ou fréquence
            val mode = live.mainMode
            if (cdc != null && mode == null) {
                toast("Revenez à l'écran principal de la radio (ligne active non reconnue)")
                return
            }
            if (s.type == "channel") {
                if (sendSteps(Shortcut.channelSteps(s.channel, fromFreqMode = mode == 'F'))) {
                    toast(s.name + " envoyé")
                    s.channel.toIntOrNull()?.let { n -> expectChannel(n) }
                }
                return
            }
            val t = s.team ?: return
            t.problem()?.let { toast(it); return }
            if (sendSteps(if (direct) t.directSteps(fromChannelMode = mode == 'M', upCode = prefs.myCallCode.ifEmpty { null },
                              memSaveDirect = fwRevision >= 27)
                          else t.steps(fromChannelMode = mode == 'M'), restoreBeep = false, onDone = {
                    val code = prefs.myCallCode.ifEmpty { null }
                    SuccessCard.show(this, "Équipe configurée",
                        "« ${s.name} » est enregistrée dans le canal ${t.channel}." +
                            (if (direct && code != null) "\nVotre code d'appel $code part au début de chaque émission." else ""))
                })) {
                toast(if (direct) "« " + s.name + " » : réglage direct de la radio (quelques secondes)…"
                      else "« " + s.name + " » : réglage de la radio, ne touchez à rien (≈ 25 s)…")
                if (!direct && prefs.myCallCode.isNotEmpty())
                    toast("Code d'appel non envoyé : il faut le firmware Fusion PTT (sinon, réglez-le avec UV Studio)")
            }
            return
        }
        // Réglage simple : en direct si possible (sauf réglages qui attendent un n° de canal)
        val menuNo = s.menu.toIntOrNull()
        val steps = if (direct && s.type == "setting" && menuNo != null && menuNo !in KEYPAD_ONLY_MENUS &&
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

    /** Touche F du clavier de l'appli : porte un cadenas quand le clavier de la radio est verrouillé (0.64). */
    private var fKeyView: TextView? = null
    private var fKeyDef: KeyDef? = null

    private fun keyView(k: KeyDef, side: Boolean = false): TextView = TextView(this).apply {
        text = keyLabel(k)
        if (k.id == "F") { fKeyView = this; fKeyDef = k; if (shortcutActive("lock")) text = lockedLabel(k) }
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
            longPressFeedback(it)
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

    /**
     * Pavé 3 × 4 en lignes et colonnes de poids égaux. (Un GridLayout pondéré se mesurait mal
     * quand l'écran était reconstruit au retour de veille : seules les touches 1-2-3 restaient,
     * en géant.)
     */
    private fun buildPad(L: Boolean): LinearLayout {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val gap = dp(3)
        for (row in 0 until 4) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until 3) {
                val k = Keys.PAD.getOrNull(row * 3 + col) ?: continue
                line.addView(keyView(k), LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(gap, gap, gap, gap) })
            }
            grid.addView(line, LinearLayout.LayoutParams(-1, 0, 1f))
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
            "spec" -> fillSpectrumHelp(card, arg.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
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
            // Trace pour le journal : ce que la radio dit de l'écran affiché
            Journal.add("Aide : écran signalé par la radio = " +
                (r?.joinToString(" ") { String.format("%02X", it) } ?: "aucune réponse"))
            ui.post {
                val d = r
                when {
                    d == null || d.size < 8 || d[1].toInt() != 0 -> {
                        toast(if (fwRevision in 1..21) "Aide du spectre et des menus : il faut le firmware Fusion PTT r22"
                              else "Aide de chaque réglage : il faut le firmware Fusion PTT r7")
                        openSheet("key:LCD")
                    }
                    (d[2].toInt() and 0xFF) == 0xF0 && d.size >= 24 ->
                        openSheet("spec:" + d.joinToString("") { String.format("%02X", it) })
                    d[2].toInt() == 1 && d[3].toInt() == 1 && d.size >= 15 -> {
                        val name = String(d, 8, 7, Charsets.ISO_8859_1).substringBefore('\u0000').trim()
                        openSheet("menu:" + (d[5].toInt() and 0xFF) + "|" + name)
                    }
                    // Écran principal : directement l'aide complète de tout ce qu'on y voit (0.60)
                    (d[2].toInt() and 0xFF) == 0 -> openSheet("key:LCD")
                    else -> openSheet("screen:" + (d[2].toInt() and 0xFF) + "," + (d[3].toInt() and 0xFF))
                }
            }
        }, "k1-ctxhelp").start()
    }

    /**
     * Aide du spectre, avec ce que la radio est en train d'afficher (firmware r22 :
     * op 06 répond depuis l'appli Spectre). Touches d'après app/spectrum.c (OnKeyDown*).
     */
    private fun fillSpectrumHelp(card: LinearLayout, d: ByteArray) {
        fun u8(i: Int) = d[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun u32(i: Int) = (u16(i).toLong()) or (u16(i + 2).toLong() shl 16)
        val state = u8(3)
        val manual = u8(4) != 0
        val mod = listOf("FM", "AM", "USB", "BYP", "RAW").getOrElse(u8(6)) { "?" }
        val bw = listOf("25 kHz", "12,5 kHz", "6,25 kHz").getOrElse(u8(7)) { "?" }
        val f = u32(8) * 10
        val stepHz = u16(12) * 10L
        val points = u16(14)
        val peak = u32(16) * 10
        val dbMin = d[20].toInt(); val dbMax = d[21].toInt()
        val width = stepHz * points
        val centered = stepHz < 2500
        val from = if (centered) f - width / 2 else f
        val to = from + width
        fun mhz(hz: Long) = String.format(java.util.Locale.FRANCE, "%.4f", hz / 1e6)
        fun khz(hz: Long) = if (hz >= 1000) String.format(java.util.Locale.FRANCE, "%.2f kHz", hz / 1000.0).replace(",00", "")
                            else "$hz Hz"
        when (state) {
            1 -> {
                sheetTitle(card, "i", "Spectre · saisie de fréquence", "Écran de la radio")
                sheetRow(card, "CET ÉCRAN", "Tapez la fréquence en MHz avec les chiffres (* pour la virgule), puis MENU pour valider. EXIT efface ou annule.")
            }
            2 -> {
                sheetTitle(card, "i", "Spectre · écoute de ${mhz(f)} MHz", "Écran de la radio")
                sheetRow(card, "CE QUE VOUS VOYEZ", "La radio écoute une seule fréquence (${mod}, bande $bw) et affiche la force du signal.")
                sheetRow(card, "À L'ÉCRAN", "En haut : fréquence écoutée (et nom du canal mémoire s'il existe), modulation ($mod) et largeur de bande ($bw) à droite. " +
                    "Au milieu : « S: » = force du signal sur l'échelle S (S1 faible à S9 fort) et sa valeur en dBm (−120 très faible, −50 très fort). " +
                    "Avec MENU : LNAs, LNA, PGA = gains des étages d'entrée du récepteur, en dB.")
                sheetRow(card, "TOUCHES", "▲ ▼ : ajuster la fréquence · 5 : taper une fréquence · touche latérale 1 : écoute forcée (squelch ouvert) · MENU : réglages fins du récepteur (▲ ▼ pour les changer) · EXIT : revenir au balayage.")
            }
            else -> {
                sheetTitle(card, "i", "Spectre · balayage", "Écran de la radio")
                sheetRow(card, "CE QUE VOUS VOYEZ",
                    "La radio balaie $points points espacés de ${khz(stepHz)}, de ${mhz(from)} à ${mhz(to)} MHz " +
                    "(largeur ${khz(width)}). Chaque barre montre la force du signal ; plus elle est haute, plus l'émission est forte. " +
                    (if (peak > 0) "Signal le plus fort : ${mhz(peak)} MHz. " else "") +
                    "Écoute en ${mod}, bande $bw.")
                sheetRow(card, "À L'ÉCRAN",
                    "Tout en haut à gauche : " + (if (manual) "« M -xx/-yy » = mode manuel : force du signal actuel / seuil d'écoute, en dBm (« -- » = pas de seuil)."
                        else "« A:NORM > » = sensibilité automatique (WEAK faible, NORM normale, STRG forte) ; > ou < = sens du balayage.") +
                    " En haut au centre : fréquence du curseur (et nom du canal mémoire correspondant). En haut à droite : modulation ($mod) et, dessous, largeur de bande d'écoute ($bw). " +
                    "À gauche sous la barre : « ${points}x » = nombre de points, et « ${khz(stepHz).replace(" kHz", "k")} » = écart entre deux points. " +
                    (if (centered) "En bas : fréquence centrale puis, après le petit symbole, de combien ▲ ▼ décalent la plage."
                     else "En bas : à gauche la première fréquence (${mhz(from)}), au milieu, après le petit symbole, de combien ▲ ▼ décalent la plage, à droite la dernière (${mhz(to)}).") +
                    " Les barres verticales montrent la force reçue sur chaque point ; la ligne horizontale est le seuil au-dessus duquel la radio se met à écouter.")
                sheetRow(card, "SENSIBILITÉ", if (manual) "Manuelle : échelle de $dbMin à $dbMax dBm (3 / 9 pour la régler)." else "Automatique (3 / 9 pour l'ajuster) ; MENU passe en manuel.")
                sheetRow(card, "RÉGLER LA PLAGE", "1 / 7 : pas plus grand / plus petit · 4 : nombre de points (128, 64, 32, 16) · 2 / 8 : de combien se décale la plage · ▲ ▼ : décaler la plage · 5 : taper la fréquence de départ.")
                sheetRow(card, "ÉCOUTER", "PTT : se caler sur le signal le plus fort et l'écouter · * / F : seuil de déclenchement de l'écoute · 0 : modulation (FM, AM, USB) · 6 : largeur de bande d'écoute · touche latérale 1 : ignorer la fréquence écoutée (fausse alerte).")
                sheetRow(card, "AUTRES", "Touche latérale 2 : éclairage · EXIT : quitter le spectre (les réglages sont gardés).")
            }
        }
        sheetButtons(card,
            Triple("Aide complète", 0, { openFullHelp(null) }),
            Triple("Compris", 1, { closeSheet() }))
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
                        // Nom encore automatique (nom d'une action, « Équipe »…) : il suit le type choisi
                        val autoNames = Actions.LIST.map { it.name } + listOf("Équipe", "Réglage", "Canal", "Touches")
                        if (e.name.isBlank() || e.name.trim() in autoNames) e.name = when (id) {
                            "team" -> "Équipe"
                            "setting" -> "Réglage"
                            "channel" -> "Canal"
                            "keys" -> "Touches"
                            else -> ""
                        }
                        e.type = id
                        if (id == "action" && (e.action.isEmpty() || e.action in usedActions(e.editingId))) {
                            Actions.LIST.firstOrNull { it.id !in usedActions(e.editingId) }?.let { e.action = it.id }
                        }
                        if (id == "action" && e.name.isBlank()) e.name = Actions.byId(e.action)?.name ?: ""
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
