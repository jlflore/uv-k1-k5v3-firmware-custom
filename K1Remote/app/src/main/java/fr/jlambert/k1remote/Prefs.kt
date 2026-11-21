package fr.jlambert.k1remote

import android.content.Context
import java.util.Calendar

/** Préférences de l'appli, enregistrées sur le téléphone. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("k1remote", Context.MODE_PRIVATE)

    /** Thème utilisé de jour (Ciel ou Clair minimal). */
    var dayTheme: ThemeId
        get() {
            val v = sp.getString("dayTheme", ThemeId.CIEL.name) ?: ThemeId.CIEL.name
            val t = runCatching { ThemeId.valueOf(v) }.getOrDefault(ThemeId.CIEL)
            return if (t == ThemeId.NUIT) ThemeId.CIEL else t
        }
        set(v) { sp.edit().putString("dayTheme", v.name).apply() }

    /** Mode nuit actuellement actif (bouton lune ou bascule automatique). */
    var night: Boolean
        get() = sp.getBoolean("night", false)
        set(v) { sp.edit().putBoolean("night", v).apply() }

    var autoNight: Boolean
        get() = sp.getBoolean("autoNight", false)
        set(v) { sp.edit().putBoolean("autoNight", v).apply() }

    /** Début et fin de la nuit automatique, en minutes depuis minuit. */
    var nightStart: Int
        get() = sp.getInt("nightStart", 20 * 60)
        set(v) { sp.edit().putInt("nightStart", v).apply() }

    var nightEnd: Int
        get() = sp.getInt("nightEnd", 7 * 60)
        set(v) { sp.edit().putInt("nightEnd", v).apply() }

    var showShortcuts: Boolean
        get() = sp.getBoolean("showShortcuts", true)
        set(v) { sp.edit().putBoolean("showShortcuts", v).apply() }

    /** true : étiquettes « Fonctions » (5 · Spectre) ; false : inscriptions de la radio (5 · NOAA). */
    var functionLabels: Boolean
        get() = sp.getBoolean("functionLabels", true)
        set(v) { sp.edit().putBoolean("functionLabels", v).apply() }

    /** true : flèches ◀ ▶ (UV-K1) ; false : ▲ ▼ (UV-K5). */
    var k1Arrows: Boolean
        get() = sp.getBoolean("k1Arrows", true)
        set(v) { sp.edit().putBoolean("k1Arrows", v).apply() }

    /** Dernier état appliqué par la nuit automatique : -1 inconnu, 0 jour, 1 nuit. */
    var lastAuto: Int
        get() = sp.getInt("lastAuto", -1)
        set(v) { sp.edit().putInt("lastAuto", v).apply() }

    var shortcutsJson: String?
        get() = sp.getString("shortcuts", null)
        set(v) { sp.edit().putString("shortcuts", v).apply() }

    // ---- Liaison avec la radio ----

    /** Câble et/ou boîtier Bluetooth : LINK_AUTO, LINK_USB ou LINK_BLE. */
    var linkMode: Int
        get() = sp.getInt("linkMode", LINK_AUTO)
        set(v) { sp.edit().putInt("linkMode", v).apply() }

    /** Adresse du boîtier « K1 Relais » mémorisé (null : à rechercher). */
    /** Mon code d'appel DTMF (ex. « A1 »), envoyé au début de chaque émission. Vide = aucun. */
    var myCallCode: String
        get() = sp.getString("myCallCode", "") ?: ""
        set(v) { sp.edit().putString("myCallCode", v.uppercase().filter { it in "0123456789ABCD*#" }.take(8)).apply() }

    /** Bouton PTT à distance (nécessite le firmware « Fusion PTT »). */
    var remotePtt: Boolean
        get() = sp.getBoolean("remotePtt", false)
        set(v) { sp.edit().putBoolean("remotePtt", v).apply() }

    // ---- Carte d'équipe ----

    /** Clé de l'équipe carte (texte), null si aucune équipe. */
    var mapKey: String?
        get() = sp.getString("mapKey", null)
        set(v) { sp.edit().putString("mapKey", v).apply() }

    var mapTeam: String
        get() = sp.getString("mapTeam", "Équipe") ?: "Équipe"
        set(v) { sp.edit().putString("mapTeam", v).apply() }

    /** Nom affiché sur la carte des autres joueurs. */
    var playerName: String
        get() = sp.getString("playerName", null)?.takeIf { it.isNotBlank() } ?: "Joueur"
        set(v) { sp.edit().putString("playerName", v.take(20)).apply() }

    /** Identifiant aléatoire et stable de ce téléphone sur la carte. */
    val playerId: String
        get() = sp.getString("playerId", null) ?: java.util.UUID.randomUUID().toString().replace("-", "").take(10)
            .also { sp.edit().putString("playerId", it).apply() }

    /** Intervalle d'envoi de la position, en secondes. */
    var shareInterval: Int
        get() = sp.getInt("shareInterval", 15)
        set(v) { sp.edit().putInt("shareInterval", v).apply() }

    /** Fond de carte : 0 photo aérienne, 1 plan. */
    var mapLayer: Int
        get() = sp.getInt("mapLayer", 0)
        set(v) { sp.edit().putInt("mapLayer", v).apply() }

    /** Veille du boîtier après N minutes sans radio (0 = jamais). */
    var relaySleep: Int
        get() = sp.getInt("relaySleep", 10)
        set(v) { sp.edit().putInt("relaySleep", v).apply() }

    /** Lumière du boîtier : 0 éteinte, 1 faible, 2 normale. */
    var relayLed: Int
        get() = sp.getInt("relayLed", 1)
        set(v) { sp.edit().putInt("relayLed", v).apply() }

    var relayAddress: String?
        get() = sp.getString("relayAddress", null)
        set(v) { sp.edit().putString("relayAddress", v).apply() }

    // ---- Mode furtif (réglages envoyés à la radio) ----

    /** La radio a été mise en furtif par l'appli et n'en est pas encore sortie. */
    var stealth: Boolean
        get() = sp.getBoolean("stealth", false)
        set(v) { sp.edit().putBoolean("stealth", v).apply() }

    /** Écran de la radio en furtif : 0 très sombre (BLMax 1), 1 éteint (BLTime OFF). */
    var stealthLight: Int
        get() = sp.getInt("stealthLight", 0)
        set(v) { sp.edit().putInt("stealthLight", v).apply() }

    /** Inverser l'écran de la radio en furtif (fond noir). */
    var stealthInvert: Boolean
        get() = sp.getBoolean("stealthInvert", true)
        set(v) { sp.edit().putBoolean("stealthInvert", v).apply() }

    /** Réglages normaux de la radio, rétablis à la sortie du furtif. */
    var dayBlTxRx: Int          // 0 OFF, 1 TX, 2 RX, 3 TX/RX
        get() = sp.getInt("dayBlTxRx", 3)
        set(v) { sp.edit().putInt("dayBlTxRx", v).apply() }

    var dayBlTime: Int          // 0 OFF, n = n × 5 s, 61 = ON
        get() = sp.getInt("dayBlTime", 4)
        set(v) { sp.edit().putInt("dayBlTime", v).apply() }

    var dayBlMax: Int           // 1 à 10
        get() = sp.getInt("dayBlMax", 10)
        set(v) { sp.edit().putInt("dayBlMax", v).apply() }

    /** Bip des touches de la radio en usage normal (menu 40 Beep). */
    var dayBeep: Boolean
        get() = sp.getBoolean("dayBeep", false)
        set(v) { sp.edit().putBoolean("dayBeep", v).apply() }

    /** Coupe le bip pendant les réglages automatiques (configuration d'équipe…). */
    /** Après une configuration d'équipe : false = rester en mode fréquence (le canal est quand même enregistré). */
    var teamEndChannel: Boolean
        get() = sp.getBoolean("teamEndChannel", false)
        set(v) { sp.edit().putBoolean("teamEndChannel", v).apply() }

    var silentSequences: Boolean
        get() = sp.getBoolean("silentSequences", true)
        set(v) { sp.edit().putBoolean("silentSequences", v).apply() }

    var dayInv: Boolean
        get() = sp.getBoolean("dayInv", false)
        set(v) { sp.edit().putBoolean("dayInv", v).apply() }

    /** Noms des appelants : une ligne « code = prénom » par coéquipier (ex. « A1 = Paul »). */
    var dtmfNames: String
        get() = sp.getString("dtmfNames", "") ?: ""
        set(v) { sp.edit().putString("dtmfNames", v).apply() }

    fun dtmfName(code: String): String? {
        for (line in dtmfNames.lines()) {
            val parts = line.split('=', ':', limit = 2)
            if (parts.size == 2 && parts[0].trim().equals(code, ignoreCase = true)) return parts[1].trim().ifEmpty { null }
        }
        return null
    }

    fun palette(): Palette = if (night) Themes.NUIT else Themes.of(dayTheme)

    /** L'heure actuelle est-elle dans la plage de nuit automatique ? */
    fun isNightTime(now: Calendar = Calendar.getInstance()): Boolean {
        val m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val s = nightStart
        val e = nightEnd
        return if (s == e) false else if (s < e) m in s until e else (m >= s || m < e)
    }

    companion object {
        const val LINK_AUTO = 0
        const val LINK_USB = 1
        const val LINK_BLE = 2
        /** Libellé d'une valeur de BLTime (0 OFF, n × 5 s, 61 ON). */
        fun blTimeLabel(i: Int): String = when {
            i <= 0 -> "OFF"
            i >= 61 -> "ON (toujours)"
            i * 5 < 60 -> "${i * 5} s"
            else -> String.format("%d min %02d s", i * 5 / 60, i * 5 % 60)
        }
        val BLTXRX_LABELS = listOf("OFF", "TX", "RX", "TX/RX")

        fun hhmm(minutes: Int): String = String.format("%02d:%02d", minutes / 60, minutes % 60)
    }
}
