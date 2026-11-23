package fr.jlambert.k1remote

import android.net.Uri
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Configuration d'équipe : fréquence, sous-canal (CTCSS / DCS), puissance, largeur,
 * squelch, enregistrée dans un canal mémoire de la radio.
 *
 * Tout est tapé au clavier comme le ferait l'utilisateur, d'après le firmware
 * F4HWN Fusion 6.2 (App/app/menu.c, App/app/main.c) :
 *  - réglages 01 Step, 02 Power, 03 RxDCS, 04 RxCTCS, 05 TxDCS, 06 TxCTCS,
 *    07 TxODir, 09 W/N, 15 ChSave, 54 Sql (liste « All ») ;
 *  - CTCSS : valeur 0 = OFF, 1 à 50 = tonalité (2 chiffres) ;
 *  - DCS : 0 = OFF, 1 à 104 = code normal, 105 à 208 = inversé (3 chiffres) ;
 *  - ChSave : n° de canal sur 4 chiffres, puis MENU deux fois (confirmation) ;
 *  - fréquence : 6 chiffres en kHz en mode VFO, arrondie au pas.
 */
data class TeamProfile(
    var freq: String = "446.00625",
    var tone: String = "none",      // réception : none | ctcss | dcs
    var toneIdx: Int = 0,           // ctcss 1..50, dcs 1..208
    var power: Int = 1,             // 1..7 = LOW1..LOW5, MID, HIGH
    var narrow: Boolean = true,
    var squelch: Int = 3,           // 0..9
    var channel: Int = 1,           // 1..999
    var txTone: String = "none",    // émission
    var txIdx: Int = 0,
    var beep: Boolean = false,      // menu 40 Beep (OFF par défaut : jamais utile en airsoft)
    var endChannel: Boolean = false, // false = la radio reste en mode fréquence après le réglage
    var step: Int = -1              // menu 01 Step : -1 = automatique, sinon 0..23 (liste triée du firmware)
) {
    fun copy2() = copy()

    /**
     * Témoin « réglages d'équipe identiques » (0.67, état 0x09 format 2) : vrai seulement si TOUT
     * est connu et identique — canal (ou mode fréquence), fréquence, puissance, largeur, sous-canaux
     * en réception et en émission, sans décalage relais ni inversion. Une seule donnée inconnue,
     * ou un profil incomplet : faux (jamais de faux positif).
     */
    fun matches(l: RadioStatus.Line?): Boolean {
        l ?: return false
        val hz = freqHz() ?: return false
        val kindOk = when (l.kind) {
            RadioStatus.LineKind.MEMORY -> l.number == channel
            RadioStatus.LineKind.FREQUENCY -> true             // profil laissé en mode fréquence
            else -> false
        }
        val rx = l.rxTone ?: return false
        val tx = l.txTone ?: return false
        val wantRx = expectedTone(tone, toneIdx) ?: return false
        val wantTx = expectedTone(txTone, txIdx) ?: return false
        return kindOk && l.rxHz != null && (l.rxHz == hz || l.rxHz == tunedHz()) &&
            l.power == power &&
            l.width == (if (narrow) RadioStatus.Width.NARROW else RadioStatus.Width.WIDE) &&
            !l.hasShift && !l.reverse &&
            rx.value != null && tx.value != null && rx == wantRx && tx == wantTx
    }

    /** Même sous-canal dans les deux sens. */
    val sameTone: Boolean get() = tone == txTone && (tone == "none" || toneIdx == txIdx)

    /** Fréquence en Hz, ou null si invalide. */
    fun freqHz(): Long? = runCatching {
        val hz = BigDecimal(freq.trim().replace(',', '.')).movePointRight(6)
        if (hz.stripTrailingZeros().scale() > 0) return null   // plus fin que le hertz
        hz.toLong()
    }.getOrNull()

    /** Message d'erreur, ou null si le profil est utilisable. */
    /** Pas envoyé à la radio (menu 01) : celui choisi, sinon le plus grand compatible avec la fréquence. */
    fun stepFor(hz: Long): Int = if (step in STEP_HZ.indices) step else tuning(hz).first

    fun problem(): String? {
        val hz = freqHz() ?: return "Fréquence invalide (ex. 446.00625)"
        if (step in STEP_HZ.indices && !onStepGrid(hz, step))
            return "La fréquence n'est pas sur la grille du pas de ${STEP_LABELS[step]} : choisissez un autre pas, ou « Automatique »"
        if (hz < 18_000_000L || hz >= 1_000_000_000L) return "Fréquence hors des limites de la radio (18 à 999 MHz)"
        if (tone == "ctcss" && toneIdx !in 1..50) return "Choisissez une tonalité CTCSS en réception"
        if (tone == "dcs" && toneIdx !in 1..208) return "Choisissez un code DCS en réception"
        if (txTone == "ctcss" && txIdx !in 1..50) return "Choisissez une tonalité CTCSS en émission"
        if (txTone == "dcs" && txIdx !in 1..208) return "Choisissez un code DCS en émission"
        if (power !in 1..7) return "Choisissez une puissance"
        if (squelch !in 0..9) return "Squelch de 0 à 9"
        if (channel !in 1..999) return "Canal de 1 à 999"
        return null
    }

    fun signature(): String = "t:" + (freqHz() ?: freq) + "/" + tone + toneIdx + "/" + txTone + txIdx + "/" + power +
        (if (narrow) "N" else "W") + squelch + "@" + channel + (if (beep) "B" else "") + (if (endChannel) "C" else "F") +
        (if (step in STEP_HZ.indices) "S$step" else "")

    fun toneLabel(): String = if (sameTone) toneText(tone, toneIdx)
        else "réception " + toneText(tone, toneIdx) + ", émission " + toneText(txTone, txIdx)

    fun summary(): String = prettyHz(tunedHz() ?: 0) + " MHz · sous-canal " + toneLabel() + " · " + POWER_SHORT[power.coerceIn(1, 7) - 1] +
        " · " + (if (narrow) "étroit" else "large") + " · squelch " + squelch + " · bip " + (if (beep) "ON" else "OFF") +
        " · pas " + (if (step in STEP_HZ.indices) STEP_LABELS[step] else "auto (" + (freqHz()?.let { STEP_LABELS[tuning(it).first] } ?: "?") + ")") +
        " · canal " + channel + (if (endChannel) " (radio sur le canal)" else " (radio en mode fréquence)")

    fun prettyFreq(): String = freqHz()?.let { prettyHz(it) } ?: freq

    /**
     * Fréquence réellement obtenue sur la radio : on tape 6 chiffres (au kHz près), puis la
     * radio arrondit au pas choisi. On cherche le pas qui retombe pile sur la fréquence voulue ;
     * sinon, pas de 1 kHz et la fréquence est arrondie au kHz le plus proche.
     */
    fun tunedHz(): Long? = freqHz()?.let { tuning(it).second }

    /** Vrai si la fréquence voulue ne peut être obtenue qu'arrondie au kHz. */
    fun isRounded(): Boolean = freqHz()?.let { tuning(it).second != it } ?: false

    /**
     * Touches à envoyer. [fromChannelMode] : la ligne active est en mode canal (il faut
     * d'abord passer en mode fréquence avec F puis 3).
     */
    fun steps(fromChannelMode: Boolean): List<KeyStep> {
        val hz = freqHz() ?: return emptyList()
        val out = mutableListOf<KeyStep>()
        fun k(id: String) { out += KeyStep(id, false) }
        fun digits(s: String) { for (c in s) k(c.toString()) }
        fun item(menu: Int, value: String) {
            digits(menu.toString().padStart(2, '0')); k("MENU"); digits(value); k("MENU")
        }
        fun wait() { out += KeyStep(KeyStep.WAIT, false) }

        k("EXIT"); k("EXIT")
        if (fromChannelMode) { k("F"); k("3"); wait() }
        val step = stepFor(hz)
        // 1) Pas de fréquence, avant de taper la fréquence (l'arrondi en dépend)
        k("MENU"); item(1, step.toString().padStart(2, '0')); k("EXIT"); k("EXIT"); wait()
        // 2) Fréquence (6 chiffres, en kHz, arrondie au kHz le plus proche)
        digits(((hz + 500) / 1000).toString().padStart(6, '0')); wait(); wait()
        // 3) Réglages du canal
        k("MENU")
        item(2, power.toString())
        item(9, if (narrow) "1" else "0")
        item(7, "0")                                     // pas de décalage relais
        // Sous-canaux : réception (menus 04 CTCSS / 03 DCS), émission (06 CTCSS / 05 DCS).
        // « Aucun » = CTCSS puis DCS à OFF (chacun n'efface que son propre type).
        fun tones(t: String, idx: Int, ctcssMenu: Int, dcsMenu: Int) = when (t) {
            "ctcss" -> item(ctcssMenu, idx.toString().padStart(2, '0'))
            "dcs" -> item(dcsMenu, idx.toString().padStart(3, '0'))
            else -> { item(ctcssMenu, "00"); item(dcsMenu, "000") }
        }
        tones(tone, toneIdx, 4, 3)
        tones(txTone, txIdx, 6, 5)
        item(54, squelch.toString())
        item(40, if (beep) "1" else "0")
        // 4) Enregistrement dans le canal : n° sur 4 chiffres, MENU, MENU (confirmation)
        digits("15"); k("MENU"); digits(channel.toString().padStart(4, '0')); k("MENU"); k("MENU"); wait()
        k("EXIT"); k("EXIT"); wait()
        // 5) Retour en mode canal, sur le canal enregistré
        if (endChannel) { k("F"); k("3") }   // sinon : on reste en mode fréquence
        return out
    }

    /**
     * Même réglage par commandes directes (firmware « Fusion PTT ») : aucune touche
     * de menu, aucun bip, fréquence exacte. [upCode] : code d'appel de ce joueur
     * (null = ne pas toucher à l'identification).
     */
    fun directSteps(fromChannelMode: Boolean, upCode: String?, memSaveDirect: Boolean = false): List<KeyStep> {
        val hz = freqHz() ?: return emptyList()
        val out = mutableListOf<KeyStep>()
        fun k(id: String) { out += KeyStep(id, false) }
        fun c(bytes: ByteArray) { out += KeyStep("CMD", false, bytes) }
        fun m(menu: Int, v: Int) = c(K5ViewerProtocol.setMenu(menu, v))
        k("EXIT"); k("EXIT")
        if (fromChannelMode) { k("F"); k("3"); out += KeyStep(KeyStep.WAIT, false) }
        m(1, stepFor(hz))
        c(K5ViewerProtocol.setFreq(hz))
        m(2, power); m(9, if (narrow) 1 else 0); m(7, 0)
        fun tones(t: String, idx: Int, ctcssMenu: Int, dcsMenu: Int) = when (t) {
            "ctcss" -> m(ctcssMenu, idx)
            "dcs" -> m(dcsMenu, idx)
            else -> { m(ctcssMenu, 0); m(dcsMenu, 0) }
        }
        tones(tone, toneIdx, 4, 3)
        tones(txTone, txIdx, 6, 5)
        m(54, squelch)
        m(40, if (beep) 1 else 0)
        // D Live : la radio affiche le code des autres, même si ce joueur n'a pas de code à lui
        m(50, 1)
        // SetOff (67) sur OFF : sans cela, après quelques minutes sans activité la radio se met en
        // veille profonde et n'écoute plus qu'environ toutes les 8 s : les appels se perdaient
        m(67, 0)
        // SetNFM (68) sur NARROW, la bande étroite standard du PMR446. Sur NARROWER (« N+ » à l'écran),
        // la radio resserre aussi son émission : voix plus faible ou étouffée chez les autres. Réglage
        // général de la radio, identique pour toute l'équipe (0.61 ; n° vérifié sur la radio, Fusion v7).
        m(68, 0)
        if (upCode != null) {
            c(K5ViewerProtocol.setUpCode(upCode))
            m(47, 1)      // PTT ID : code « UP » au début de chaque émission
            m(48, 0)      // D ST OFF : ne pas entendre son propre code dans le haut-parleur
            m(49, 60)     // D Prel : 600 ms avant le code. Le poste qui écoute peut « dormir » jusqu'à
                          // 400 ms entre deux écoutes (économie de batterie, BatSav 1:4), puis ouvrir son
                          // squelch et son CTCSS : avec moins, le premier caractère se perdait (« un seul bip »)
        }
        // Enregistrement dans le canal (menu 15 ChSave) :
        //  - firmware r27 ou plus : commande directe, discrète et silencieuse ;
        //  - r19 à r26 : ces firmwares refusent ChSave en commande directe, donc au clavier
        //    (MENU 1 5, n° sur 4 chiffres, MENU, MENU).
        if (memSaveDirect) {
            m(15, channel - 1)    // numérotation interne à partir de 0
            out += KeyStep(KeyStep.WAIT, false)
        } else {
            out += KeyStep(KeyStep.WAIT, false)
            k("MENU"); k("1"); k("5"); k("MENU")
            for (d in channel.toString().padStart(4, '0')) k(d.toString())
            k("MENU"); k("MENU")
            out += KeyStep(KeyStep.WAIT, false)
            k("EXIT"); k("EXIT")
            out += KeyStep(KeyStep.WAIT, false)
        }
        if (endChannel) { k("F"); k("3") }   // sinon : on reste en mode fréquence
        return out
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("f", freq); put("t", tone); put("i", toneIdx); put("tt", txTone); put("ti", txIdx); put("p", power)
        put("w", if (narrow) "n" else "w"); put("s", squelch); put("c", channel)
        put("b", beep); put("m", if (endChannel) "c" else "f")
        if (step in STEP_HZ.indices) put("st", step)
    }

    /** Lien de partage : k1remote://equipe?n=…&f=…&t=c5&p=1&w=n&s=3&c=1 */
    fun toLink(name: String): String = Uri.Builder().scheme("k1remote").authority("equipe")
        .appendQueryParameter("n", name)
        .appendQueryParameter("f", prettyFreq())
        .appendQueryParameter("t", toneCode(tone, toneIdx))
        .appendQueryParameter("x", toneCode(txTone, txIdx))
        .appendQueryParameter("p", power.toString())
        .appendQueryParameter("w", if (narrow) "n" else "w")
        .appendQueryParameter("s", squelch.toString())
        .appendQueryParameter("c", channel.toString())
        .appendQueryParameter("b", if (beep) "1" else "0")
        .appendQueryParameter("m", if (endChannel) "c" else "f")
        .apply { if (step in STEP_HZ.indices) appendQueryParameter("st", step.toString()) }
        .build().toString()

    companion object {
        val POWER_SHORT = listOf("LOW 1", "LOW 2", "LOW 3", "LOW 4", "LOW 5", "MID", "HIGH")
        /** Puissances approximatives annoncées par F4HWN pour l'UV-K1 / UV-K5 V3. */
        val POWER_LABELS = listOf("LOW 1 · moins de 20 mW", "LOW 2 · environ 125 mW", "LOW 3 · environ 250 mW",
            "LOW 4 · environ 500 mW", "LOW 5 · environ 1 W", "MID · environ 2 W", "HIGH · environ 5 W")

        /** Sous-canal attendu au sens du protocole 0x09 format 2 (type, valeur), ou null si le profil est incomplet. */
        fun expectedTone(t: String, idx: Int): RadioStatus.Tone? = when (t) {
            "none" -> RadioStatus.Tone(0, 0)
            "ctcss" -> CTCSS.getOrNull(idx - 1)?.let { RadioStatus.Tone(1, it) }
            "dcs" -> if (idx !in 1..208) null else {
                val inv = idx > 104
                val i = if (inv) idx - 105 else idx - 1
                RadioStatus.Tone(if (inv) 3 else 2, DCS_LOW[i] or (if (i >= 64) 0x100 else 0))
            }
            else -> null
        }

        fun toneText(t: String, idx: Int): String = when (t) {
            "ctcss" -> ctcssLabel(idx)
            "dcs" -> dcsLabel(idx)
            else -> "aucun"
        }

        private fun toneCode(t: String, idx: Int) = when (t) { "ctcss" -> "c$idx"; "dcs" -> "d$idx"; else -> "0" }

        private fun parseTone(t: String?): Pair<String, Int> = when {
            t == null -> "none" to 0
            t.startsWith("c") -> "ctcss" to (t.drop(1).toIntOrNull() ?: 0)
            t.startsWith("d") -> "dcs" to (t.drop(1).toIntOrNull() ?: 0)
            else -> "none" to 0
        }

        fun prettyHz(hz: Long): String = BigDecimal(hz).movePointLeft(6).stripTrailingZeros().toPlainString().let { s ->
            if (s.contains('.')) s.padEnd(s.indexOf('.') + 4, '0') else "$s.000"
        }

        /**
         * (indice du menu 01 Step, fréquence obtenue). La radio arrondit au pas, et au demi-pas
         * pour les pas de 10 kHz et plus (FREQUENCY_RoundToStep).
         */
        fun tuning(hz: Long): Pair<Int, Long> {
            val typed = (hz + 500) / 1000 * 1000
            val steps = listOf(25_000L to 16, 12_500L to 13, 10_000L to 12, 6_250L to 9, 5_000L to 8,
                2_500L to 7, 1_250L to 6, 1_000L to 5)
            for ((s, idx) in steps) {
                val grid = if (s >= 10_000L) s / 2 else s
                val got = Math.round(typed.toDouble() / grid) * grid
                if (got == hz) return idx to got
            }
            return 5 to typed
        }

        /**
         * Pas du menu 01 Step, dans l'ordre de la liste triée du firmware (StepSortedIndexes,
         * App/frequencies.c) : la valeur envoyée est l'indice dans cette liste.
         */
        val STEP_HZ = longArrayOf(10, 50, 100, 250, 500, 1_000, 1_250, 2_500, 5_000, 6_250, 8_330, 9_000, 10_000,
            12_500, 15_000, 20_000, 25_000, 30_000, 50_000, 100_000, 125_000, 200_000, 250_000, 500_000)
        val STEP_LABELS = listOf("0,01 kHz", "0,05 kHz", "0,1 kHz", "0,25 kHz", "0,5 kHz", "1 kHz", "1,25 kHz", "2,5 kHz",
            "5 kHz", "6,25 kHz", "8,33 kHz", "9 kHz", "10 kHz", "12,5 kHz", "15 kHz", "20 kHz", "25 kHz", "30 kHz",
            "50 kHz", "100 kHz", "125 kHz", "200 kHz", "250 kHz", "500 kHz")

        /** Fréquence atteignable avec ce pas ? Même règle que tuning() : demi-pas à partir de 10 kHz ; 8,33 kHz non vérifié. */
        fun onStepGrid(hz: Long, idx: Int): Boolean {
            val st = STEP_HZ.getOrNull(idx) ?: return false
            if (idx == 10) return true
            val grid = if (st >= 10_000L) st / 2 else st
            return hz % grid == 0L
        }

        /** CTCSS_Options du firmware, en dixièmes de Hz. */
        private val CTCSS = intArrayOf(
            670, 693, 719, 744, 770, 797, 825, 854, 885, 915,
            948, 974, 1000, 1035, 1072, 1109, 1148, 1188, 1230, 1273,
            1318, 1365, 1413, 1462, 1514, 1567, 1598, 1622, 1655, 1679,
            1713, 1738, 1773, 1799, 1835, 1862, 1899, 1928, 1966, 1995,
            2035, 2065, 2107, 2181, 2257, 2291, 2336, 2418, 2503, 2541)

        /** DCS_OptionsLow du firmware ; le bit 8 est ajouté à partir de l'indice 64. */
        private val DCS_LOW = intArrayOf(
            0x13, 0x15, 0x16, 0x19, 0x1A, 0x1E, 0x23, 0x27, 0x29, 0x2B, 0x2C, 0x35, 0x39, 0x3A, 0x3B, 0x3C,
            0x4C, 0x4D, 0x4E, 0x52, 0x55, 0x59, 0x5A, 0x5C, 0x63, 0x65, 0x6A, 0x6D, 0x6E, 0x72, 0x75, 0x7A,
            0x7C, 0x85, 0x8A, 0x93, 0x95, 0x96, 0xA3, 0xA4, 0xA5, 0xA6, 0xA9, 0xAA, 0xAD, 0xB1, 0xB3, 0xB5,
            0xB6, 0xB9, 0xBC, 0xC6, 0xC9, 0xCD, 0xD5, 0xD9, 0xDA, 0xE3, 0xE6, 0xE9, 0xEE, 0xF4, 0xF5, 0xF9,
            0x09, 0x0A, 0x0B, 0x13, 0x19, 0x1A, 0x25, 0x26, 0x2A, 0x2C, 0x2D, 0x32, 0x34, 0x35, 0x36, 0x43,
            0x46, 0x4E, 0x53, 0x56, 0x5A, 0x66, 0x75, 0x86, 0x8A, 0x94, 0x97, 0x99, 0x9A, 0xAC, 0xB2, 0xB4,
            0xC3, 0xCA, 0xD3, 0xD9, 0xDA, 0xDC, 0xE3, 0xEC)

        /** Valeur du menu (1..50) → « 88,5 Hz ». */
        fun ctcssLabel(i: Int): String {
            val v = CTCSS.getOrNull(i - 1) ?: return "?"
            return "${v / 10},${v % 10} Hz"
        }

        /** Valeur du menu (1..208) → « D023N » / « D023I ». */
        fun dcsLabel(i: Int): String {
            if (i !in 1..208) return "?"
            val inv = i > 104
            val idx = (if (inv) i - 105 else i - 1)
            val code = DCS_LOW[idx] or (if (idx >= 64) 0x100 else 0)
            return "D" + Integer.toOctalString(code).padStart(3, '0') + if (inv) "I" else "N"
        }

        val CTCSS_LABELS: List<String> get() = (1..50).map { ctcssLabel(it) }
        val DCS_LABELS: List<String> get() = (1..208).map { dcsLabel(it) }

        fun fromJson(o: JSONObject?): TeamProfile? {
            o ?: return null
            val rx = o.optString("t", "none"); val ri = o.optInt("i", 0)
            return TeamProfile(
                o.optString("f", "446.00625"), rx, ri, o.optInt("p", 1),
                o.optString("w", "n") != "w", o.optInt("s", 3), o.optInt("c", 1),
                o.optString("tt", rx), o.optInt("ti", ri),
                o.optBoolean("b", false), o.optString("m", "f") == "c",   // anciens profils : même sous-canal dans les deux sens
                o.optInt("st", -1).let { if (it in STEP_HZ.indices) it else -1 })
        }

        /** Lit un lien k1remote://equipe?… ; renvoie (nom, profil) ou null. */
        fun fromLink(text: String?): Pair<String, TeamProfile>? {
            val raw = text?.trim()?.let { t -> Regex("k1remote://equipe\\?\\S+").find(t)?.value } ?: return null
            val u = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
            val (tone, idx) = parseTone(u.getQueryParameter("t"))
            val (txTone, txIdx) = u.getQueryParameter("x")?.let { parseTone(it) } ?: (tone to idx)
            val p = TeamProfile(
                u.getQueryParameter("f") ?: return null, tone, idx,
                u.getQueryParameter("p")?.toIntOrNull() ?: 1,
                u.getQueryParameter("w") != "w",
                u.getQueryParameter("s")?.toIntOrNull() ?: 3,
                u.getQueryParameter("c")?.toIntOrNull() ?: 1, txTone, txIdx,
                u.getQueryParameter("b") == "1", u.getQueryParameter("m") == "c",
                u.getQueryParameter("st")?.toIntOrNull()?.let { if (it in STEP_HZ.indices) it else -1 } ?: -1)
            if (p.problem() != null) return null
            val name = (u.getQueryParameter("n") ?: "Équipe").take(14)
            return name to p
        }
    }
}
