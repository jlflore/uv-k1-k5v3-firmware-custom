package fr.jlambert.k1remote

/**
 * Fonctions des touches dans la vue spectre de F4HWN Fusion (0.80), relevées dans
 * App/app/spectrum.c (OnKeyDownCommon et OnKeyDown). Seulement l'affichage : l'appli envoie
 * les mêmes touches qu'à l'ordinaire. Après PTT (vue fixe) ou pendant la saisie d'une
 * fréquence, certaines touches font autre chose (le bandeau dit « vue spectre »).
 */
object SpectrumKeys {
    val LABELS = mapOf(
        "1" to "Pas +", "7" to "Pas −",            // pas de balayage
        "2" to "Saut +", "8" to "Saut −",          // pas des flèches
        "3" to "Échelle +", "9" to "Échelle −",    // dB max (manuel) ou sensibilité auto
        "*" to "Seuil +", "F" to "Seuil −",        // seuil de déclenchement
        "4" to "Largeur",                          // nombre de points balayés
        "5" to "Fréq.",                            // saisie d'une fréquence
        "6" to "Filtre",                           // bande passante d'écoute
        "0" to "Modul.",                           // modulation
    )
}
