package fr.jlambert.k1remote

/**
 * Raccourci Scan (0.77, choix de D'jé) : règle pure, contrôlée par ScanControlTest.
 *
 *  - appui court : scan en cours → on l'arrête (EXIT) ; sinon on le lance tel que réglé
 *    (appui long sur ✱), après avoir proposé de passer en mode canal si la ligne est en fréquence ;
 *  - appui long : choix d'une liste, puis scan lancé automatiquement en mode canal.
 *
 * Pourquoi un suivi local : l'état 0x09 ne dit « scan » (fonction 5) que quand la radio est au
 * repos. Sur un signal reçu, elle annonce « réception », et juste après l'appui, l'état n'est pas
 * encore relu. Sans ce suivi, un second appui renvoyait un appui long sur ✱, ce qui, pendant un
 * scan Fusion, fait défiler les listes (bug du 23/11 : affichage figé, L1, L2…).
 */
class ScanTracker(private val graceMs: Long = 3_000) {
    @Volatile var running = false; private set
    private var changedAt = 0L

    fun onStartSent(now: Long) { running = true; changedAt = now }
    fun onStopSent(now: Long) { running = false; changedAt = now }
    fun reset() { running = false; changedAt = 0L }

    /**
     * Nouvel état 0x09. [scanOrSpectrum] : fonction 5 ; [spectrum] : spectre ouvert ; [quiet] :
     * veille ou économie (radio au repos, donc pas de scan si la fonction n'est pas 5).
     * Les états lus moins de [graceMs] après un appui de l'appli sont ignorés : ils peuvent
     * dater d'avant l'appui.
     */
    fun onState(scanOrSpectrum: Boolean, spectrum: Boolean, quiet: Boolean, now: Long) {
        if (now - changedAt < graceMs) return
        when {
            spectrum -> running = false
            scanOrSpectrum -> running = true
            quiet -> running = false
            // réception, émission, monitor : on garde ce qu'on sait (le scan reprendra après le signal)
        }
    }
}

object ScanControl {
    enum class Decision { STOP, START, ASK_CHANNEL_MODE, LINE_UNKNOWN }

    /** [mode] : 'M' canal, 'F' fréquence, null inconnu. */
    fun onShortPress(scanning: Boolean, mode: Char?): Decision = when {
        scanning -> Decision.STOP
        mode == 'F' -> Decision.ASK_CHANNEL_MODE
        mode == 'M' -> Decision.START
        else -> Decision.LINE_UNKNOWN
    }

    /** Touches pour lancer le scan : appui long sur ✱ ; depuis le mode fréquence, d'abord F puis 3 (VFO/MR),
     *  comme Shortcut.channelSteps. */
    fun startSteps(fromFrequency: Boolean): List<KeyStep> =
        (if (fromFrequency) listOf(KeyStep("EXIT", false), KeyStep("F", false), KeyStep("3", false), KeyStep(KeyStep.WAIT, false))
         else emptyList()) + listOf(KeyStep("*", true))

    val STOP_STEPS = listOf(KeyStep("EXIT", false))
}
