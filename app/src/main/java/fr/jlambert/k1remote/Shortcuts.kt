package fr.jlambert.k1remote

import org.json.JSONArray
import org.json.JSONObject

/** Une touche de la radio, avec ses deux étiquettes possibles. */
class KeyDef(
    val id: String,         // identifiant interne et clé de l'aide
    val code: Int,          // code envoyé à la radio
    val main: String,       // ce qui est écrit en gros
    val radioSub: String,   // inscription d'origine sur la radio
    val funcSub: String     // fonction F4HWN, en français
)

object Keys {
    val F1 = KeyDef("F1", K1Keys.SIDE1, "F1", "", "")
    val F2 = KeyDef("F2", K1Keys.SIDE2, "F2", "", "")
    val MENU = KeyDef("MENU", K1Keys.MENU, "MENU", "", "")
    val LEFT = KeyDef("LEFT", K1Keys.UP, "◀", "", "")
    val RIGHT = KeyDef("RIGHT", K1Keys.DOWN, "▶", "", "")
    val EXIT = KeyDef("EXIT", K1Keys.EXIT, "EXIT", "", "")

    val PAD = listOf(
        KeyDef("1", 1, "1", "BAND", "Bande"),
        KeyDef("2", 2, "2", "A/B", "A/B"),
        KeyDef("3", 3, "3", "VFO/MR", "Fréq/Can"),
        KeyDef("4", 4, "4", "FC", "Copie"),
        KeyDef("5", 5, "5", "NOAA", "Spectre"),
        KeyDef("6", 6, "6", "H/M/L", "Puiss."),
        KeyDef("7", 7, "7", "VOX", "VOX"),
        KeyDef("8", 8, "8", "R", "Inv. relais"),
        KeyDef("9", 9, "9", "CALL", "Appel"),
        KeyDef("*", K1Keys.STAR, "✱", "SCAN", "Scan"),
        KeyDef("0", 0, "0", "FM", "Radio FM"),
        KeyDef("F", K1Keys.F, "F", "#", "Verrou")
    )

    val TOP = listOf(F1, F2, MENU, LEFT, RIGHT, EXIT)
    val ALL = TOP + PAD

    fun byId(id: String): KeyDef? = ALL.firstOrNull { it.id == id }
}

/** Un appui à envoyer à la radio. */
data class KeyStep(val keyId: String, val long: Boolean, val cmd: ByteArray? = null) {
    val key: KeyDef? get() = Keys.byId(keyId)
    val text: String get() = if (keyId == WAIT) "pause" else (key?.main ?: keyId) + if (long) " (long)" else ""

    companion object {
        /** Pause sans touche, le temps que la radio prenne en compte une saisie. */
        const val WAIT = "WAIT"
    }
}

/** Une action « toute prête » proposée dans l'éditeur. */
class ActionDef(val id: String, val name: String, val desc: String, val steps: List<KeyStep>, val nightToggle: Boolean = false,
                val screenToggle: Boolean = false)

object Actions {
    private fun long(id: String) = KeyStep(id, true)
    private fun short(id: String) = KeyStep(id, false)

    val LIST = listOf(
        ActionDef("ab", "A ⇄ B", "Change la ligne principale, A ou B (appui long sur 2)", listOf(long("2"))),
        ActionDef("power", "Puissance", "Puissance suivante à chaque appui (appui long sur 6). Appui long sur le bouton : choisir la puissance au doigt", listOf(long("6"))),
        ActionDef("call", "Appel", "Va sur votre canal d'appel, choisi au réglage n°44 du menu (appui long sur 9)", listOf(long("9"))),
        ActionDef("scan", "Scan", "Lance ou arrête le scan (appui long sur ✱). Appui long sur le bouton : choisir la liste de scan au doigt", listOf(long("*"))),
        ActionDef("lock", "Verrou", "Verrouille ou déverrouille le clavier de la radio (appui long sur F)", listOf(long("F"))),
        ActionDef("reverse", "Inv. relais", "Sur un relais, écoute directement votre correspondant (appui long sur 8)", listOf(long("8"))),
        ActionDef("fm", "Radio FM", "Allume ou éteint la radio FM (appui long sur 0)", listOf(long("0"))),
        ActionDef("spectrum", "Spectre", "Ouvre l'analyseur de spectre (F puis 5)", listOf(short("F"), short("5"))),
        ActionDef("beepoff", "Bip off", "Coupe le bip des touches de la radio (réglage 40 Beep sur OFF)",
            Shortcut.multiSettingSteps(listOf(Shortcut.MENU_BEEP to "0"))),
        ActionDef("stealth", "Furtif",
            "Éteint ou assombrit l'écran de la radio, même pendant les appels (réglages 39 BLTxRx, 38 BLMax ou 36 BLTime, 60 SetInv), et passe l'appli en mode nuit. Réappuyer pour rétablir vos réglages normaux (Préférences › Mode furtif).",
            emptyList(), nightToggle = true),
        ActionDef("screendark", "Écran éteint",
            "Coupe l'éclairage de la radio, même pendant les appels et les émissions (réglages 39 BLTxRx et 36 BLTime), sans mode nuit dans l'appli. Réappuyer pour rétablir l'éclairage normal (Préférences › Réglages normaux de la radio).",
            emptyList(), screenToggle = true)
    )

    fun byId(id: String): ActionDef? = LIST.firstOrNull { it.id == id }
}

/** Un raccourci de l'appli. type = action | setting | channel | keys */
class Shortcut(
    var id: Long,
    var name: String,
    var type: String,
    var action: String = "",
    var menu: String = "",
    var value: String = "",
    var channel: String = "",
    var seq: MutableList<KeyStep> = mutableListOf(),
    var team: TeamProfile? = null
) {
    val isPower: Boolean get() = type == "action" && action == "power"
    val isScan: Boolean get() = type == "action" && action == "scan"

    fun copy(): Shortcut = Shortcut(id, name, type, action, menu, value, channel, seq.toMutableList(), team?.copy2())

    /** Deux raccourcis qui envoient la même chose ont la même signature. */
    fun signature(): String = when (type) {
        "action" -> "a:$action"
        "setting" -> "s:" + pad2(menu) + "=" + (value.toIntOrNull() ?: value)
        "channel" -> "c:" + (channel.toIntOrNull() ?: channel)
        "team" -> team?.signature() ?: "t:?"
        else -> "k:" + seq.joinToString(",") { it.keyId + if (it.long) "L" else "" }
    }

    fun steps(): List<KeyStep> = when (type) {
        "action" -> Actions.byId(action)?.steps ?: emptyList()
        "setting" -> settingSteps(menu, value)
        "channel" -> channelSteps(channel)
        "team" -> team?.steps(false) ?: emptyList()
        else -> seq.toList()
    }

    fun describe(): String = when (type) {
        "action" -> Actions.byId(action)?.desc ?: ""
        "setting" -> "Réglage n°" + pad2(menu) + " du menu = " + value + ". Touches envoyées : " + stepsText(steps())
        "channel" -> "Va au canal " + channel + " (passe d'abord en mode canal si besoin). Touches envoyées : " + stepsText(steps())
        "team" -> "Configuration d'équipe : " + (team?.summary() ?: "?") +
            ". L'appli tape tous les réglages, les enregistre dans le canal (environ 25 secondes)."
        else -> "Suite de touches : " + stepsText(seq)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("type", type)
        put("action", action); put("menu", menu); put("value", value); put("channel", channel)
        val arr = JSONArray()
        for (s in seq) arr.put(JSONObject().apply { put("k", s.keyId); put("l", s.long) })
        put("seq", arr)
        team?.let { put("team", it.toJson()) }
    }

    companion object {
        const val MAX = 9

        fun pad2(v: String): String = v.filter { it.isDigit() }.padStart(2, '0').takeLast(2)

        /** EXIT EXIT · MENU · 2 chiffres du réglage · MENU · valeur · MENU · EXIT EXIT */
        // N° des réglages utilisés par le mode furtif (F4HWN Fusion 6.2, liste « All »)
        const val MENU_BLTIME = 36
        const val MENU_BLMAX = 38
        const val MENU_BLTXRX = 39
        const val MENU_SETINV = 60
        const val MENU_BEEP = 40

        /**
         * Plusieurs réglages à la suite sans ressortir du menu :
         * EXIT EXIT M, puis pour chacun n° (2 chiffres) M valeur M, puis EXIT EXIT.
         */
        fun multiSettingSteps(items: List<Pair<Int, String>>): List<KeyStep> {
            val out = mutableListOf(KeyStep("EXIT", false), KeyStep("EXIT", false), KeyStep("MENU", false))
            for ((menu, value) in items) {
                val m = pad2(menu.toString())
                out += KeyStep(m[0].toString(), false)
                out += KeyStep(m[1].toString(), false)
                out += KeyStep("MENU", false)
                for (ch in value.filter { it.isDigit() }) out += KeyStep(ch.toString(), false)
                out += KeyStep("MENU", false)
            }
            out += KeyStep("EXIT", false)
            out += KeyStep("EXIT", false)
            return out
        }

        fun settingSteps(menu: String, value: String): List<KeyStep> {
            val m = pad2(menu)
            val out = mutableListOf(KeyStep("EXIT", false), KeyStep("EXIT", false), KeyStep("MENU", false))
            out += KeyStep(m[0].toString(), false)
            out += KeyStep(m[1].toString(), false)
            out += KeyStep("MENU", false)
            for (ch in value.filter { it.isDigit() }) out += KeyStep(ch.toString(), false)
            out += KeyStep("MENU", false)
            out += KeyStep("EXIT", false)
            out += KeyStep("EXIT", false)
            return out
        }

        /** En mode canal, taper 4 chiffres sélectionne le canal (F4HWN : saisie sur 4 chiffres). */
        fun channelSteps(channel: String, fromFreqMode: Boolean = false): List<KeyStep> {
            val c = channel.filter { it.isDigit() }.padStart(4, '0').takeLast(4)
            val out = mutableListOf(KeyStep("EXIT", false))
            if (fromFreqMode) { out += KeyStep("F", false); out += KeyStep("3", false); out += KeyStep(KeyStep.WAIT, false) }
            return out + c.map { KeyStep(it.toString(), false) }
        }

        fun stepsText(steps: List<KeyStep>): String = steps.joinToString(" · ") { it.text }

        fun fromJson(o: JSONObject): Shortcut {
            val seq = mutableListOf<KeyStep>()
            val arr = o.optJSONArray("seq")
            if (arr != null) for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                seq += KeyStep(s.optString("k"), s.optBoolean("l"))
            }
            return Shortcut(
                o.optLong("id"), o.optString("name"), o.optString("type", "action"),
                o.optString("action"), o.optString("menu"), o.optString("value"), o.optString("channel"), seq,
                TeamProfile.fromJson(o.optJSONObject("team"))
            )
        }

        fun defaults(): MutableList<Shortcut> = mutableListOf(
            Shortcut(1, "A ⇄ B", "action", "ab"),
            Shortcut(2, "Puissance", "action", "power"),
            Shortcut(3, "Appel", "action", "call"),
            Shortcut(4, "Scan", "action", "scan"),
            Shortcut(5, "Verrou", "action", "lock"),
            Shortcut(6, "Furtif", "action", "stealth"),
            Shortcut(7, "Écran éteint", "action", "screendark")
        )

        fun load(prefs: Prefs): MutableList<Shortcut> {
            val json = prefs.shortcutsJson ?: return defaults().also { prefs.screenDarkOffered = true }
            val list = runCatching {
                val arr = JSONArray(json)
                MutableList(arr.length()) { fromJson(arr.getJSONObject(it)) }
            }.getOrElse { defaults() }.take(MAX).toMutableList()
            // 0.37 : le nouveau raccourci « Écran éteint » est ajouté une fois aux listes existantes
            if (!prefs.screenDarkOffered) {
                prefs.screenDarkOffered = true
                if (list.size < MAX && list.none { it.type == "action" && it.action == "screendark" }) {
                    list += Shortcut((list.maxOfOrNull { it.id } ?: 0L) + 1, "Écran éteint", "action", "screendark")
                    save(prefs, list)
                }
            }
            return list
        }

        fun save(prefs: Prefs, list: List<Shortcut>) {
            val arr = JSONArray()
            for (s in list) arr.put(s.toJson())
            prefs.shortcutsJson = arr.toString()
        }
    }
}
