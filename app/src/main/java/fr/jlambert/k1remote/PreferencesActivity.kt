package fr.jlambert.k1remote

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Préférences : thème, mode nuit, affichage, raccourcis. */
class PreferencesActivity : Activity() {

    private lateinit var prefs: Prefs
    private var p: Palette = Themes.CIEL
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        build()
    }

    private var started = false
    override fun onResume() {
        super.onResume()
        if (started) build()   // retour du sélecteur de thème
        started = true
    }

    private fun dp(v: Int) = Ui.dpi(this, v)

    private fun text(s: CharSequence, sp: Float, color: Int, face: Typeface? = null): TextView =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
            if (face != null) typeface = face
        }

    /** Vue défilante courante : sert à garder la position de lecture quand l'écran se reconstruit. */
    private var scrollView: ScrollView? = null

    private fun build() {
        p = prefs.palette()
        val keepY = scrollView?.scrollY ?: 0

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(col)
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }

        // En-tête
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "←"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(p.text)
            contentDescription = "Retour"
            background = Ui.ripple(this@PreferencesActivity, p.surface, 999f, p.border, night = p.night)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        head.addView(text("Préférences", 22f, p.text, Typeface.DEFAULT_BOLD),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        col.addView(head)

        // Thème de jour
        col.addView(section("THÈME DE JOUR"))
        val cur = prefs.dayTheme
        val themeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(16), dp(12))
            background = Ui.ripple(this@PreferencesActivity, p.surface, 16f, p.border, night = p.night)
            contentDescription = "Thème de jour : " + cur.label + ". Toucher pour choisir parmi les " + ThemeId.DAY.size + " thèmes."
            setOnClickListener { startActivity(android.content.Intent(this@PreferencesActivity, ThemePickerActivity::class.java)) }
        }
        themeRow.addView(ThemePreview(this, Themes.of(cur)), LinearLayout.LayoutParams(dp(62), dp(134)))
        val tl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tl.addView(text(cur.label, 16f, p.text, Typeface.DEFAULT_BOLD))
        tl.addView(text(cur.blurb, 13f, p.muted), lp(top = 2))
        tl.addView(text("Choisir un autre thème  ›", 14f, p.text, Typeface.DEFAULT_BOLD), lp(top = 10))
        tl.addView(text(ThemeId.DAY.size.toString() + " thèmes, dont 6 camouflages. Faites glisser pour les voir.", 12f, p.muted), lp(top = 2))
        themeRow.addView(tl, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        col.addView(themeRow, lp(top = 8))

        // Mode nuit
        col.addView(section("MODE NUIT · ROUGE SUR NOIR"))
        val night = card()
        night.addView(switchRow("Mode nuit maintenant", "Aussi accessible par le bouton lune ☾", prefs.night) {
            prefs.night = it; build()
        })
        night.addView(divider())
        night.addView(switchRow("Bascule automatique", "Passe en nuit et revient en jour aux heures choisies", prefs.autoNight) {
            prefs.autoNight = it; prefs.lastAuto = -1; build()
        })
        if (prefs.autoNight) {
            night.addView(divider())
            night.addView(timeRow("Début de la nuit", prefs.nightStart) { prefs.nightStart = it; prefs.lastAuto = -1; build() })
            night.addView(divider())
            night.addView(timeRow("Fin de la nuit", prefs.nightEnd) { prefs.nightEnd = it; prefs.lastAuto = -1; build() })
        }
        col.addView(night, lp(top = 8))

        // Liaison avec la radio
        col.addView(section("LIAISON AVEC LA RADIO"))
        val lk = card()
        lk.addView(segmentRow("Mode de liaison", "Automatique : le câble s'il est branché, sinon le boîtier Bluetooth",
            listOf("Auto", "Câble", "Bluetooth"), prefs.linkMode) {
            prefs.linkMode = it; build()
        })
        lk.addView(divider())
        lk.addView(segmentRow("Lumière du boîtier", "Éteinte d'office en mode furtif",
            listOf("Éteinte", "Faible", "Normale"), prefs.relayLed) {
            prefs.relayLed = it; build()
        })
        lk.addView(divider())
        val sleeps = listOf(0, 5, 10, 30)
        lk.addView(segmentRow("Veille du boîtier", "S'endort sans radio ; se réveille quand on l'allume (boîtier 1.2)",
            listOf("Jamais", "5 min", "10 min", "30 min"), sleeps.indexOf(prefs.relaySleep).coerceAtLeast(0)) {
            prefs.relaySleep = sleeps[it]; build()
        })
        lk.addView(divider())
        val relay = prefs.relayAddress
        lk.addView(labels("Boîtier K1 Relais",
            if (relay == null) "Aucun mémorisé : il sera recherché à la prochaine connexion"
            else "Mémorisé : $relay").apply { setPadding(dp(16), dp(12), dp(16), dp(12)) })
        col.addView(lk, lp(top = 8))
        if (relay != null) {
            col.addView(button("Oublier ce boîtier") {
                BleLink.forget(this, relay)
                prefs.relayAddress = null; build()
                android.widget.Toast.makeText(this, "Boîtier oublié", android.widget.Toast.LENGTH_SHORT).show()
            }, lp(top = 8))
        }

        // Mode furtif
        col.addView(section("INTERFACE"))
        val ui = card()
        ui.addView(segmentRow("Écran principal", "« En opération » : bande d'état, gros boutons, grand PTT, clavier replié",
            listOf("Complète", "En opération"), prefs.uiMode) { prefs.uiMode = it; build() })
        ui.addView(divider())
        ui.addView(switchRow("Vibrer aux appuis longs", "Une vibration confirme qu'un appui long sur une touche ou un raccourci est reconnu (jamais de son)",
            prefs.vibrateLong) { prefs.vibrateLong = it })
        col.addView(ui, lp(top = 8))
        col.addView(text("On passe aussi de l'une à l'autre depuis l'écran principal : bouton « En opération » au-dessus des raccourcis, lien « Interface complète » en haut de l'écran simplifié.",
            12f, p.muted).apply { setLineSpacing(0f, 1.2f) }, lp(top = 8))

        col.addView(section("MODE FURTIF · ÉCRAN DE LA RADIO"))
        val st = card()
        st.addView(segmentRow("Écran de la radio", "Il ne s'allume plus aux appels",
            listOf("Très sombre", "Éteint", "Profond"), prefs.stealthLight) {
            prefs.stealthLight = it; build()
        })
        st.addView(divider())
        st.addView(switchRow("Inverser l'écran", "Fond noir, chiffres clairs (réglage 60 SetInv)", prefs.stealthInvert) {
            prefs.stealthInvert = it; build()
        })
        col.addView(st, lp(top = 8))
        col.addView(text(if (prefs.stealthLight == 2)
                "« Profond » (firmware r18 ou plus) : l'écran de la radio devient entièrement noir, même en plein jour, et ses voyants restent éteints. Vous lisez l'écran sur le téléphone. Sécurité : si le téléphone ne donne plus de nouvelles pendant 6 secondes, ou si la radio redémarre, son écran se rallume tout seul. Sans téléphone : maintenez la touche EXIT de la radio 2 secondes (firmware r21)."
            else if (prefs.stealthLight == 1)
                "« Éteint » : la radio ne s'éclaire plus du tout, même en touchant ses touches. Vous lisez son écran sur le téléphone."
            else "« Très sombre » : la radio s'éclaire au minimum quand on touche une touche, mais plus aux appels. Elle reste utilisable seule.",
            12f, p.muted).apply { setLineSpacing(0f, 1.2f) }, lp(top = 8))

        col.addView(section("RÉGLAGES NORMAUX DE LA RADIO"))
        col.addView(text("Remis par l'appli à la sortie du furtif. Indiquez ceux de votre radio (menus 36, 38, 39, 60).",
            12f, p.muted).apply { setLineSpacing(0f, 1.2f) }, lp(top = 6))
        val day = card()
        day.addView(pickRow("39 · BLTxRx", "Éclairage aux appels", Prefs.BLTXRX_LABELS, prefs.dayBlTxRx) {
            prefs.dayBlTxRx = it; build()
        })
        day.addView(divider())
        day.addView(pickRow("36 · BLTime", "Durée de l'éclairage", (0..61).map { Prefs.blTimeLabel(it) }, prefs.dayBlTime) {
            prefs.dayBlTime = it; build()
        })
        day.addView(divider())
        day.addView(pickRow("38 · BLMax", "Luminosité de l'éclairage", (1..10).map { it.toString() }, prefs.dayBlMax - 1) {
            prefs.dayBlMax = it + 1; build()
        })
        day.addView(divider())
        day.addView(pickRow("40 · Beep", "Bip des touches", listOf("OFF", "ON"), if (prefs.dayBeep) 1 else 0) {
            prefs.dayBeep = it == 1; build()
        })
        day.addView(divider())
        day.addView(pickRow("60 · SetInv", "Écran inversé", listOf("OFF", "ON"), if (prefs.dayInv) 1 else 0) {
            prefs.dayInv = it == 1; build()
        })
        day.addView(divider())
        day.addView(switchRow("Réglages automatiques silencieux", "Coupe le bip pendant la configuration d'équipe et les longs réglages", prefs.silentSequences) {
            prefs.silentSequences = it; build()
        })
        col.addView(day, lp(top = 8))
        if (prefs.stealth) {
            col.addView(button("La radio est notée « en furtif » · effacer") {
                prefs.stealth = false; build()
            }, lp(top = 8))
        }

        // Affichage
        // Journal : tout ce que l'appli et la radio ont fait (appelants, réglages, liaison)
        col.addView(section("JOURNAL"))
        col.addView(text("Appelants reçus, réglages envoyés, connexions… À partager pour un diagnostic. " +
            "Aussi accessible par un appui long sur le titre « K1 Remote » de l'écran principal.", 12f, p.muted).apply { setLineSpacing(0f, 1.2f) }, lp(top = 6))
        col.addView(button("Ouvrir le Journal") { MemoryBridge.openJournal = true; finish() }, lp(top = 8))

        // Touches latérales de la radio (menus 23 à 27), écrites en clair
        col.addView(section("TOUCHES LATÉRALES DE LA RADIO"))
        val lastRead = RadioMemory.list(this).firstOrNull()?.let { f -> runCatching { RadioMemory.load(f) }.getOrNull() }
        val known = prefs.sideKeys.split(',').mapNotNull { it.trim().toIntOrNull() }
        val current = SideKeys.KEYS.mapIndexed { i, (k, def) ->
            known.getOrNull(i) ?: lastRead?.let { SideKeys.fromMemory(it, k.third) } ?: def
        }
        col.addView(text("Choisissez ce que fait chaque touche ; le réglage part tout de suite vers la radio (firmware Fusion PTT, radio connectée). " +
            (if (known.size == 5) "Valeurs : vos derniers choix dans l'appli."
             else if (lastRead != null) "Valeurs lues lors de la dernière lecture des canaux."
             else "Valeurs d'usine affichées : lisez les canaux de la radio (▤) pour voir les vôtres."),
            12f, p.muted).apply { setLineSpacing(0f, 1.2f) }, lp(top = 6))
        val sk = card()
        SideKeys.KEYS.forEachIndexed { i, (k, _) ->
            if (i > 0) sk.addView(divider())
            sk.addView(pickRow(k.second, "Menu ${k.first}", SideKeys.FUNCTIONS, current[i]) { v ->
                val err = MemoryBridge.menuSender?.invoke(listOf(k.first to v))
                    ?: if (MemoryBridge.menuSender == null) "Ouvrez d'abord l'écran principal, radio connectée" else null
                if (err != null) {
                    android.widget.Toast.makeText(this, "Non envoyé : $err", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    prefs.sideKeys = current.toMutableList().also { it[i] = v }.joinToString(",")
                    android.widget.Toast.makeText(this, "${k.second} : ${SideKeys.FUNCTIONS[v]}", android.widget.Toast.LENGTH_SHORT).show()
                    build()
                }
            })
        }
        col.addView(sk, lp(top = 8))
        col.addView(text("Évitez « 1750 Hz » : un appui pendant l'émission envoie un sifflement continu. « PTT » transforme la touche en alternat.",
            12f, p.muted).apply { setLineSpacing(0f, 1.2f) }, lp(top = 8))

        // Noms des appelants (codes DTMF)
        col.addView(section("NOMS DES APPELANTS (CODES DTMF)"))
        col.addView(text("Si vos coéquipiers envoient un code à l'alternat (menus 45 UPCode et 47 PTT ID) et que votre radio " +
            "l'affiche (menu 50 D Live sur ON), l'appli montre le prénom. Une ligne par coéquipier, par exemple « A1 = Paul ».",
            12f, p.muted).apply { setLineSpacing(0f, 1.2f) }, lp(top = 6))
        col.addView(android.widget.EditText(this).apply {
            setText(prefs.dtmfNames)
            hint = "A1 = Jérôme\nA2 = Paul"
            textSize = 15f
            setTextColor(p.text)
            setHintTextColor(p.muted)
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Ui.shape(this@PreferencesActivity, p.surface, 12f, p.border)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) { prefs.dtmfNames = e?.toString() ?: "" }
            })
        }, lp(top = 8))
        col.addView(text("Mon code d'appel", 13f, p.text, medium), lp(top = 12))
        col.addView(text("Envoyé au début de chacune de vos émissions (court bip-bip), pour que vos coéquipiers voient votre nom. " +
            "Il est réglé sur la radio par la configuration d'équipe (firmware Fusion PTT), avec 47 PTT ID et 50 D Live. Ex. : A1.",
            12f, p.muted).apply { setLineSpacing(0f, 1.2f) }, lp(top = 4))
        col.addView(android.widget.EditText(this).apply {
            setText(prefs.myCallCode)
            hint = "A1"
            textSize = 15f
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.AllCaps(), android.text.InputFilter.LengthFilter(8))
            setTextColor(p.text)
            setHintTextColor(p.muted)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Ui.shape(this@PreferencesActivity, p.surface, 12f, p.border)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) { prefs.myCallCode = e?.toString() ?: "" }
            })
        }, lp(top = 6))

        // Carte d'équipe
        col.addView(section("CARTE D'ÉQUIPE"))
        col.addView(text("Partage des positions par Internet (4G/5G), chiffré avec la clé de l'équipe. " +
            "Ouvrez la carte avec le bouton ⌖ de l'écran principal.", 12f, p.muted).apply { setLineSpacing(0f, 1.2f) }, lp(top = 6))
        col.addView(text("Mon nom sur la carte", 13f, p.text, medium), lp(top = 10))
        col.addView(android.widget.EditText(this).apply {
            setText(if (prefs.playerName == "Joueur") "" else prefs.playerName)
            hint = "Votre prénom ou indicatif"
            textSize = 15f
            setSingleLine()
            setTextColor(p.text)
            setHintTextColor(p.muted)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Ui.shape(this@PreferencesActivity, p.surface, 12f, p.border)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) { prefs.playerName = e?.toString()?.trim() ?: "" }
            })
        }, lp(top = 6))
        val mc = card()
        val intervals = listOf(5, 15, 30, 60)
        mc.addView(segmentRow("Envoi de ma position", "Plus souvent = plus de batterie consommée",
            intervals.map { "$it s" }, intervals.indexOf(prefs.shareInterval).coerceAtLeast(0)) {
            prefs.shareInterval = intervals[it]; build()
        })
        mc.addView(divider())
        val key = TeamMap.keyFromText(prefs.mapKey)
        mc.addView(labels("Équipe", if (key == null) "Aucune : créez-en une, ou rejoignez celle d'un coéquipier"
            else "« ${prefs.mapTeam} » · identifiant ${TeamMap.teamId(key).take(6)}").apply { setPadding(dp(16), dp(12), dp(16), dp(12)) })
        col.addView(mc, lp(top = 8))
        if (key != null) {
            col.addView(button("Partager l'équipe (QR code)") { showTeamQr(key) }, lp(top = 8))
        }
        col.addView(button(if (key == null) "Créer une équipe" else "Créer une nouvelle équipe") { createMapTeam() }, lp(top = 8))
        col.addView(button("Rejoindre une équipe (coller le lien reçu)") { joinFromClipboard() }, lp(top = 8))

        col.addView(section("AFFICHAGE"))
        val disp = card()
        disp.addView(switchRow("Afficher les raccourcis", "Bandeau de boutons sous l'écran de la radio", prefs.showShortcuts) {
            prefs.showShortcuts = it; build()
        })
        disp.addView(divider())
        disp.addView(switchRow("Bouton PTT à distance", "Nécessite le firmware « Fusion PTT » sur la radio", prefs.remotePtt) {
            prefs.remotePtt = it; build()
        })
        disp.addView(divider())
        disp.addView(segmentRow("Étiquettes des touches", "5 · Spectre ou 5 · NOAA",
            listOf("Fonctions", "Radio"), if (prefs.functionLabels) 0 else 1) {
            prefs.functionLabels = it == 0; build()
        })
        disp.addView(divider())
        disp.addView(segmentRow("Flèches de navigation", "Selon le réglage SetNav de la radio",
            listOf("◀ ▶ K1", "▲ ▼ K5"), if (prefs.k1Arrows) 0 else 1) {
            prefs.k1Arrows = it == 0; build()
        })
        col.addView(disp, lp(top = 8))

        // Raccourcis
        col.addView(section("RACCOURCIS"))
        col.addView(button("Rétablir les raccourcis par défaut") {
            AlertDialog.Builder(this)
                .setTitle("Rétablir les raccourcis ?")
                .setMessage("Vos raccourcis seront remplacés par : A ⇄ B, Puissance, Appel, Scan, Verrou, Furtif.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Rétablir") { _, _ ->
                    Shortcut.save(prefs, Shortcut.defaults())
                    build()
                }
                .show()
        }, lp(top = 8))

        // À propos
        col.addView(section("À PROPOS"))
        col.addView(button("Mode d'emploi de l'appli") {
            startActivity(HelpActivity.manual(this, p.night))
        }, lp(top = 8))
        col.addView(button("Aide du firmware") {
            startActivity(HelpActivity.intent(this, null, p.night))
        }, lp(top = 8))
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        val about = card()
        about.setPadding(dp(16), dp(14), dp(16), dp(14))
        about.addView(text("K1 Remote" + if (version.isNotEmpty()) "  v$version" else "", 17f, p.text, Typeface.DEFAULT_BOLD))
        about.addView(text("Télécommande pour Quansheng UV-K1 / UV-K5 V3 sous firmware F4HWN Fusion, par câble USB ou avec le boîtier K1 Relais.",
            13f, p.muted).apply { setLineSpacing(0f, 1.2f) }, lp(top = 4))
        about.addView(text("CRÉDITS", 12f, p.muted, Typeface.DEFAULT_BOLD).apply { letterSpacing = 0.08f }, lp(top = 14))
        for ((who, what) in listOf(
            "D'jé" to "Idée, conception, essais sur le terrain",
            "Claude (Anthropic)" to "Développement de l'appli, du firmware Fusion PTT et du boîtier K1 Relais",
            "Armel F4HWN" to "Firmware F4HWN, sur lequel repose Fusion PTT",
            "ChatGPT (OpenAI)" to "Revues de code et propositions de design")) {
            about.addView(text(who, 15f, p.text, Typeface.DEFAULT_BOLD), lp(top = 8))
            about.addView(text(what, 13f, p.muted).apply { setLineSpacing(0f, 1.15f) })
        }
        about.addView(text("Internet n'est utilisé que par la carte d'équipe (positions chiffrées) et pour télécharger les fonds de carte. " +
            "Sources publiques : github.com/jlflore/uv-k1-k5v3-firmware-custom (branches k1remote et k1remote-app).",
            12f, p.muted).apply { setLineSpacing(0f, 1.2f) }, lp(top = 14))
        col.addView(about, lp(top = 10))

        setContentView(scroll)
        scrollView = scroll
        // Après un réglage, on reste au même endroit au lieu de remonter en haut
        if (keepY > 0) scroll.post { scroll.scrollTo(0, keepY) }
        applySystemBars()   // après setContentView : la fenêtre doit exister
    }

    private fun applySystemBars() {
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
            }
        }
    }

    private fun createMapTeam() {
        val input = android.widget.EditText(this).apply {
            hint = "Nom de l'équipe, ex. Scorpio"
            setSingleLine()
            setText(prefs.mapTeam.takeIf { it != "Équipe" } ?: "")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Nouvelle équipe carte")
            .setMessage("Une clé secrète est créée. Partagez-la ensuite par QR code avec vos coéquipiers.")
            .setView(android.widget.FrameLayout(this).apply {
                setPadding(dp(20), dp(4), dp(20), 0); addView(input)
            })
            .setPositiveButton("Créer") { _, _ ->
                if (TeamMapState.running) PositionService.stop(this)
                prefs.mapKey = TeamMap.keyToText(TeamMap.newKey())
                prefs.mapTeam = input.text.toString().trim().ifEmpty { "Équipe" }
                build()
                showTeamQr(TeamMap.keyFromText(prefs.mapKey)!!)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showTeamQr(key: ByteArray) {
        val link = TeamMap.link(key, prefs.mapTeam)
        val qr = QrCode.encode(link)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), 0)
            if (qr != null) addView(QrView(this@PreferencesActivity, qr), LinearLayout.LayoutParams(dp(240), dp(240)))
            addView(text("Vos coéquipiers scannent ce code avec l'appareil photo, ou ouvrent le lien reçu : " +
                "K1 Remote leur propose alors de rejoindre « ${prefs.mapTeam} ».", 13f, android.graphics.Color.DKGRAY), lp(top = 8))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Équipe « ${prefs.mapTeam} »")
            .setView(box)
            .setPositiveButton("Envoyer le lien") { _, _ ->
                startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, "Rejoins l'équipe « ${prefs.mapTeam} » sur la carte K1 Remote : $link"),
                    "Envoyer le lien d'équipe"))
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun joinFromClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val txt = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        val link = Regex("k1remote://carte\\S+").find(txt)?.value
        val got = TeamMap.fromLink(link)
        if (got == null) {
            android.widget.Toast.makeText(this, "Aucun lien d'équipe carte dans le presse-papier", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (TeamMapState.running) PositionService.stop(this)
        prefs.mapKey = TeamMap.keyToText(got.first)
        prefs.mapTeam = got.second
        build()
        android.widget.Toast.makeText(this, "Équipe « ${got.second} » rejointe", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun section(title: String): TextView =
        text(title, 12f, p.muted, Typeface.DEFAULT_BOLD).apply {
            letterSpacing = 0.06f
            setPadding(dp(4), dp(22), 0, 0)
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.shape(this@PreferencesActivity, p.surface, 16f, p.border)
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(p.border)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun labels(title: String, subtitle: String?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 15f, p.text, medium))
        if (subtitle != null) addView(text(subtitle, 12f, p.muted))
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switchRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
        }
        row.addView(labels(title, subtitle), LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this).apply {
            isChecked = value
            contentDescription = title
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }

    private fun timeRow(title: String, minutes: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = Ui.ripple(this@PreferencesActivity, Color.TRANSPARENT, 0f, null, night = p.night)
        }
        row.addView(labels(title, null), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text(Prefs.hhmm(minutes), 16f, p.text, Typeface.DEFAULT_BOLD))
        row.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> onPick(h * 60 + m) }, minutes / 60, minutes % 60, true).show()
        }
        return row
    }

    /** Ligne avec la valeur actuelle à droite ; un toucher ouvre la liste des valeurs. */
    private fun pickRow(title: String, subtitle: String, options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = Ui.ripple(this@PreferencesActivity, Color.TRANSPARENT, 0f, null, night = p.night)
        }
        row.addView(labels(title, subtitle), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text((options.getOrNull(selected) ?: "?") + "  ›", 15f, p.text, Typeface.DEFAULT_BOLD))
        row.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selected) { d, which -> d.dismiss(); onPick(which) }
                .setNegativeButton("Annuler", null)
                .show()
        }
        return row
    }

    private fun segmentRow(title: String, subtitle: String, options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
        }
        row.addView(labels(title, subtitle), LinearLayout.LayoutParams(0, -2, 1f))
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = Ui.shape(this@PreferencesActivity, p.bg, 10f, p.border)
        }
        for ((i, o) in options.withIndex()) {
            val on = i == selected
            seg.addView(TextView(this).apply {
                text = o
                textSize = 13f
                typeface = medium
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), 0)
                setTextColor(if (on) p.primaryText else p.muted)
                background = if (on) Ui.shape(this@PreferencesActivity, p.primaryBg, 8f, p.primaryBorder) else null
                setOnClickListener { if (!on) onPick(i) }
            }, LinearLayout.LayoutParams(-2, dp(38)).apply { if (i > 0) marginStart = dp(3) })
        }
        row.addView(seg)
        return row
    }

    private fun button(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        typeface = medium
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(16), 0)
        setTextColor(p.text)
        background = Ui.ripple(this@PreferencesActivity, p.surface, 16f, p.border, night = p.night)
        minHeight = dp(52)
        setOnClickListener { onClick() }
    }

    /** Vignette de thème : aperçu miniature de l'écran principal. */
}
