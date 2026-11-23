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
