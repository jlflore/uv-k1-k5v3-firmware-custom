package fr.jlambert.k1remote

import android.app.Activity
import java.io.File

/**
 * Pont entre « Mes canaux » et l'écran principal, qui détient la liaison avec la radio.
 * L'écran principal s'y inscrit tant qu'il existe.
 */
object MemoryBridge {
    /** (écran appelant, image lue, brouillon, fin(réussi, nouvelle sauvegarde)) */
    @Volatile var writer: ((Activity, RadioMemory, RadioMemory, (Boolean, File?) -> Unit) -> Unit)? = null

    /** Réglages de menu (n° de la liste « All », valeur) envoyés à la radio ; renvoie null si envoyé, sinon la raison. */
    @Volatile var menuSender: ((List<Pair<Int, Int>>) -> String?)? = null

    /** Demande d'ouverture du Journal au retour sur l'écran principal (bouton des Préférences). */
    @Volatile var openJournal = false
}

/** Touches latérales de la radio : menus 23 à 27 de F4HWN et leurs fonctions (ordre ACTION_OPT du firmware). */
object SideKeys {
    /** (n° de menu, libellé, octet de la zone réglages 0xA0A8, valeur par défaut du firmware) */
    val KEYS = listOf(
        Triple(23, "F1 · appui court", 1) to 3,
        Triple(24, "F1 · appui long", 2) to 0,
        Triple(25, "F2 · appui court", 3) to 4,
        Triple(26, "F2 · appui long", 4) to 0,
        Triple(27, "MENU · appui long", 0) to 0)

    val FUNCTIONS = listOf(
        "Rien", "Lampe torche", "Puissance suivante", "Écoute forcée (monitor)", "Scan", "VOX",
        "Radio FM", "1750 Hz (ouverture de relais)", "Verrou du clavier", "Ligne A ⇄ B",
        "Fréquence ⇄ canal", "Modulation FM / AM / USB", "Mode de réception (double veille…)",
        "Ligne principale seule", "PTT (alternat)", "Bande large ⇄ étroite", "Couper le son",
        "Réception sur A (RxA)", "Journal RF", "Beam (envoi du canal)", "Puissance HIGH",
        "Ignorer le décalage", "Chasse au renard", "Balise", "Appel par tonalité")

    /** Valeur lue dans une sauvegarde (zone 0xA0A8) : MENU long est dans les bits 1-7 de l'octet 0. */
    fun fromMemory(m: RadioMemory, byte: Int): Int? {
        val v = m.image[0xA0A8 + byte].toInt() and 0xFF
        val x = if (byte == 0) v shr 1 else v
        return x.takeIf { it < FUNCTIONS.size }
    }
}
