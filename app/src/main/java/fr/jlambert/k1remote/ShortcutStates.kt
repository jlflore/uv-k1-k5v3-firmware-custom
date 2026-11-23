package fr.jlambert.k1remote

/**
 * Témoins des raccourcis « qui restent actifs » (0.65). Règle pure, contrôlée dans toutes ses
 * combinaisons par ShortcutStatesTest : le bouton s'allume tant que la fonction est active.
 *
 * Sources, de la plus sûre à la moins sûre :
 *  - état 0x09 du firmware r28 (verrou, furtif profond, scan, inversion relais) ;
 *  - statut « occupée » de la radio (spectre ouvert) ;
 *  - écran affiché, commande 06 (3 = radio FM), lu seulement si un raccourci Radio FM est présent ;
 *  - ce que l'appli a elle-même activé (écran éteint, furtif par réglages de menus) ;
 *  - lecture de l'écran (cadenas), pour un firmware sans 0x09.
 */
object ShortcutStates {

    /** Actions dont le bouton peut rester allumé. */
    val TOGGLES = listOf("screendark", "stealth", "lock", "scan", "spectrum", "reverse", "fm")

    data class Inputs(
        val appScreenDark: Boolean = false,
        val appStealth: Boolean = false,
        /** Cadenas lu sur l'écran (firmware sans 0x09). */
        val pixelLocked: Boolean = false,
        /** Dernier état 0x09, null si le firmware ne le fournit pas. */
        val snapshot: RadioStatus.Snapshot? = null,
        /** Radio « occupée » (statut 4) : spectre ouvert. */
        val busy: Boolean = false,
        /** Écran radio FM affiché (commande 06), null si inconnu. */
        val fmScreen: Boolean? = null
    )

    fun active(action: String, i: Inputs): Boolean {
        val s = i.snapshot
        return when (action) {
            "screendark" -> i.appScreenDark
            "stealth" -> i.appStealth || s?.stealth == true
            "lock" -> s?.keyLocked ?: i.pixelLocked
            "scan" -> s != null && !i.busy && s.function == RadioStatus.Function.SCAN_OR_SPECTRUM
            "spectrum" -> i.busy
            "reverse" -> !i.busy && s?.selectedLine?.reverse == true
            "fm" -> !i.busy && i.fmScreen == true
            else -> false
        }
    }
}

/**
 * Éclairage de la radio voulu selon la combinaison furtif × écran éteint (0.66).
 * Une seule règle, appliquée à l'entrée comme à la sortie de chacun des deux modes :
 * sortir de l'un ne défait plus l'autre (bug 0.65 : sortir du furtif rallumait l'écran
 * alors que « Écran éteint » restait actif). Contrôlée dans toutes ses combinaisons par LightPlanTest.
 */
object LightPlan {
    /** Réglages normaux de la radio (Préférences › Réglages normaux). */
    data class Day(val blTxRx: Int, val blTime: Int, val blMax: Int, val inv: Boolean)

    /** Valeurs des menus 39 BLTxRx, 36 BLTime, 38 BLMax, 60 SetInv. */
    fun target(stealth: Boolean, screenDark: Boolean, stealthLight: Int, stealthInvert: Boolean, day: Day): List<Pair<Int, String>> = listOf(
        Shortcut.MENU_BLTXRX to (if (stealth || screenDark) "0" else day.blTxRx.toString()),
        Shortcut.MENU_BLTIME to (if (screenDark || (stealth && stealthLight >= 1)) "00" else Shortcut.pad2(day.blTime.toString())),
        Shortcut.MENU_BLMAX to (if (stealth && stealthLight == 0) "01" else Shortcut.pad2(day.blMax.toString())),
        Shortcut.MENU_SETINV to (if (stealth && stealthInvert) "1" else if (day.inv) "1" else "0"))
}
