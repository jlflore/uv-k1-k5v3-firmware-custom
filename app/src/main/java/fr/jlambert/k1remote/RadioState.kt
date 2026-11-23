package fr.jlambert.k1remote

/**
 * État de la radio vu par l'appli (contrat 0x09 v1 figé, § 6). Code pur, sans Android :
 * contrôlé par RadioStateTest.
 *
 *  - Détection : la commande 0x09 est essayée à la connexion. Statut 9 (inconnue) ou 3 délais
 *    dépassés avant toute réussite → repli sur la lecture de l'écran pour cette connexion.
 *  - Après une première réponse valide, plus jamais de repli : un délai dépassé rend l'état
 *    « périmé », jamais faux.
 *  - Statut 4 (spectre en cours) : radio occupée, dernier état gardé, lectures ralenties.
 *  - Une trame invalide est ignorée sans effacer le dernier état valable.
 *  - Commandes : demandée → confirmée (état conforme), refusée (accusé non nul), ou
 *    indéterminée (pas de confirmation en 2 s), jamais « échec » sans preuve.
 */
class RadioState {

    enum class Support { UNKNOWN, FIRMWARE, SCREEN }

    var support = Support.UNKNOWN; private set
    var snapshot: RadioStatus.Snapshot? = null; private set
    var receivedAt = 0L; private set
    /** Dernier statut 4 (spectre en cours) reçu. */
    var busyAt = 0L; private set
    private var failures = 0
    /** Délais dépassés consécutifs depuis la dernière réponse : sert à espacer les essais. */
    var misses = 0; private set

    companion object {
        const val FRESH_MS = 3000L
        const val CONFIRM_MS = 2000L
        const val POLL_MS = 1500L
        const val POLL_BUSY_MS = 3000L
        const val POLL_MAX_MS = 6000L
        const val DETECT_TRIES = 3
    }

    /** Déconnexion : tout est remis à zéro, la détection recommencera. */
    fun reset() {
        support = Support.UNKNOWN; snapshot = null; receivedAt = 0L; busyAt = 0L
        failures = 0; misses = 0; pending = null
    }

    /** Ce qui a changé après une réponse, pour l'affichage. */
    enum class Change { NONE, STATE, SUPPORT_FIRMWARE, SUPPORT_SCREEN }

    fun onResult(r: RadioStatus.Result, now: Long): Change {
        when (r) {
            is RadioStatus.Result.Ok -> {
                val first = support != Support.FIRMWARE
                support = Support.FIRMWARE
                snapshot = r.snapshot; receivedAt = now; misses = 0; failures = 0
                busyAt = 0L                                                    // réponse normale : spectre fermé
                checkPending(now)
                return if (first) Change.SUPPORT_FIRMWARE else Change.STATE
            }
            is RadioStatus.Result.Refused -> {
                misses = 0
                return when (r.status) {
                    4 -> { busyAt = now; Change.NONE }                         // spectre en cours
                    RadioStatus.STATUS_UNKNOWN_COMMAND ->
                        if (support == Support.UNKNOWN) { support = Support.SCREEN; Change.SUPPORT_SCREEN } else Change.NONE
                    else -> Change.NONE
                }
            }
            is RadioStatus.Result.Invalid -> return Change.NONE                // dernier état gardé
        }
    }

    /** Pas de réponse dans le délai. */
    fun onTimeout(): Change {
        misses++
        if (support == Support.UNKNOWN && ++failures >= DETECT_TRIES) { support = Support.SCREEN; return Change.SUPPORT_SCREEN }
        return Change.NONE
    }

    val usesFirmware: Boolean get() = support == Support.FIRMWARE

    /**
     * Données récentes ? Pendant une émission les lectures sont suspendues exprès : l'état n'est
     * pas déclaré périmé pour autant (la liaison est surveillée à part).
     */
    fun isFresh(now: Long, transmitting: Boolean): Boolean =
        snapshot != null && (transmitting || now - receivedAt < FRESH_MS)

    fun isBusy(now: Long) = busyAt != 0L && now - busyAt < POLL_BUSY_MS * 2

    /** Délai avant la prochaine lecture (hors émission, que l'appelant gère). */
    fun nextPollDelay(now: Long): Long {
        val base = if (isBusy(now) || snapshot?.function == RadioStatus.Function.SCAN_OR_SPECTRUM) POLL_BUSY_MS else POLL_MS
        return if (misses <= 1) base else minOf(POLL_MAX_MS, base shl minOf(misses - 1, 2))
    }

    // ------------------------------------------------------------ lecture équivalente à l'écran

    /**
     * Même forme que la lecture des pixels (ligne active, puissances, mode canal/fréquence,
     * verrou), pour que le reste de l'appli n'ait qu'une seule source à consulter.
     */
    fun reading(): ScreenReader.Reading? {
        val s = snapshot ?: return null
        fun power(l: RadioStatus.Line?): String? = l?.power?.let { if (it in 1..7) ScreenReaderPowers[it - 1] else null }
        fun mode(l: RadioStatus.Line?): Char? = when (l?.kind) {
            RadioStatus.LineKind.MEMORY -> 'M'
            RadioStatus.LineKind.FREQUENCY -> 'F'
            else -> null
        }
        return ScreenReader.Reading(
            mainVfo = if (s.selected == 0) 'A' else 'B',
            powerA = power(s.a), powerB = power(s.b),
            modeA = mode(s.a), modeB = mode(s.b),
            dtmf = null, locked = s.keyLocked)
    }

    private val ScreenReaderPowers = listOf("LOW1", "LOW2", "LOW3", "LOW4", "LOW5", "MID", "HIGH")

    // ------------------------------------------------------------ commandes en cours

    enum class Outcome { CONFIRMED, REFUSED, UNDETERMINED }

    /** Ce qu'une commande doit produire : [check] vrai quand l'état le montre. */
    class Pending(val label: String, val sentAt: Long, val check: (RadioStatus.Snapshot) -> Boolean,
                  val onOutcome: (Outcome) -> Unit)

    var pending: Pending? = null; private set

    fun expect(p: Pending) { pending = p }

    /** Accusé de la commande : un statut non nul est un refus prouvé. */
    fun onAck(status: Int) {
        val p = pending ?: return
        if (status != 0) { pending = null; p.onOutcome(Outcome.REFUSED) }
    }

    private fun checkPending(now: Long) {
        val p = pending ?: return
        val s = snapshot ?: return
        if (receivedAt >= p.sentAt && p.check(s)) { pending = null; p.onOutcome(Outcome.CONFIRMED) }
    }

    /** À appeler régulièrement : au-delà de 2 s sans confirmation, résultat indéterminé. */
    fun checkTimeout(now: Long) {
        val p = pending ?: return
        if (now - p.sentAt >= CONFIRM_MS) { pending = null; p.onOutcome(Outcome.UNDETERMINED) }
    }
}
