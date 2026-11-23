package fr.jlambert.k1remote

/**
 * Commande 0x0A « lire des réglages » (firmware Fusion PTT r30, protocole 5).
 * Contrat : K1R-contrat-lecture-reglages-0x0A.md v1.3 (racine du dépôt firmware).
 *
 * Ce fichier ne fait que construire les requêtes, décoder les réponses et comparer : il ne parle
 * pas à la radio. Règle : une donnée absente, inconnue ou incohérente ne vaut jamais « conforme ».
 */
object RadioSettings {

    const val OP = 0x0A
    const val MAX_PER_REQUEST = 7
    const val PROTOCOL = 5

    // Positions de menu (Fusion v7, même adressage que l'op 01)
    const val STEP = 1
    const val BEEP = 40
    const val UPCODE = 45
    const val PTT_ID = 47
    const val D_ST = 48
    const val D_PRE = 49
    const val D_LIVE = 50
    const val SQL = 54
    const val SET_OFF = 67
    const val SET_NFM = 68
    // r31 (protocole 6) : éclairage, pour le témoin « Écran éteint »
    const val BL_TIME = 36
    const val BL_TXRX = 39
    const val PROTOCOL_LIGHTS = 6
    val LIGHTS = listOf(BL_TIME, BL_TXRX)

    /** Les 10 réglages de la liste blanche r30, dans l'ordre des deux requêtes (5 + 5). */
    val WHITELIST = listOf(STEP, BEEP, UPCODE, PTT_ID, D_ST, D_PRE, D_LIVE, SQL, SET_OFF, SET_NFM)

    val LABELS = mapOf(
        STEP to "pas", BEEP to "bip", UPCODE to "code d'appel", PTT_ID to "envoi du code (PTT ID)",
        D_ST to "écoute de son code (D ST)", D_PRE to "préambule du code (D Prel)",
        D_LIVE to "affichage des codes (D Live)", SQL to "squelch", SET_OFF to "veille (SetOff)",
        SET_NFM to "SetNFM", BL_TIME to "durée d'éclairage (BLTime)", BL_TXRX to "éclairage en émission/réception (BLTxRx)")

    /**
     * Résumé court des réglages vérifiés, pour l'affichage. N'énumère que [keys] (jamais plus) ;
     * le code d'appel et ses 3 options (PTT ID, D ST, D Prel) sont regroupés.
     */
    fun summary(keys: Set<Int>): String {
        val short = linkedMapOf(STEP to "pas", SQL to "squelch", BEEP to "bip", SET_OFF to "veille",
            SET_NFM to "SetNFM", D_LIVE to "D Live")
        val parts = short.filterKeys { it in keys }.values.toMutableList()
        val codeOpts = listOf(PTT_ID, D_ST, D_PRE)
        when {
            UPCODE in keys && keys.containsAll(codeOpts) -> parts += "code d'appel (avec ses options)"
            else -> {
                if (UPCODE in keys) parts += "code d'appel"
                codeOpts.filter { it in keys }.forEach { parts += LABELS.getValue(it) }
            }
        }
        return if (parts.size <= 1) parts.joinToString() else parts.dropLast(1).joinToString(", ") + " et " + parts.last()
    }

    fun request(positions: List<Int>): ByteArray {
        require(positions.size in 1..MAX_PER_REQUEST)
        return K5ViewerProtocol.command(OP, positions.size, *positions.toIntArray())
    }

    /** Valeur lue : numérique (u16) ou texte (code d'appel). */
    sealed class Value {
        data class Num(val v: Int) : Value()
        data class Text(val s: String) : Value()
    }

    data class Entry(val position: Int, val status: Int, val value: Value?)

    sealed class Result {
        /** [vfo] = ligne des réglages de ligne (pas, PTT ID) : 0 = A, 1 = B. */
        data class Ok(val vfo: Int, val entries: List<Entry>) : Result()
        /** Statut global ≠ 0 : 1 trame mal formée, 4 spectre ouvert, 9 commande inconnue (avant r30). */
        data class Refused(val status: Int) : Result()
        data class Invalid(val reason: String) : Result()
    }

    fun decode(raw: ByteArray, asked: List<Int>): Result {
        fun u(i: Int) = raw[i].toInt() and 0xFF
        if (raw.size < 2 || u(0) != OP) return Result.Invalid("code de réponse inattendu")
        if (u(1) != 0) return if (raw.size == 2) Result.Refused(u(1)) else Result.Invalid("statut ${u(1)} avec données")
        if (raw.size < 4) return Result.Invalid("réponse trop courte")
        val vfo = u(2)
        if (vfo > 1) return Result.Invalid("VFO $vfo")
        val n = u(3)
        if (n != asked.size) return Result.Invalid("$n entrées pour ${asked.size} demandées")
        var off = 4
        val out = ArrayList<Entry>(n)
        for (k in 0 until n) {
            if (off + 3 > raw.size) return Result.Invalid("entrée ${k + 1} tronquée")
            val p = u(off); val s = u(off + 1); val len = u(off + 2)
            off += 3
            if (p != asked[k]) return Result.Invalid("position $p au lieu de ${asked[k]}")
            if (off + len > raw.size) return Result.Invalid("valeur ${k + 1} tronquée")
            val value: Value? = when {
                s != 0 -> { if (len != 0) return Result.Invalid("refus avec valeur"); null }
                p == UPCODE -> {
                    if (len > 16) return Result.Invalid("code d'appel de $len octets")
                    Value.Text(String(raw, off, len, Charsets.US_ASCII))
                }
                len == 2 -> Value.Num(u(off) or (u(off + 1) shl 8))
                else -> return Result.Invalid("longueur $len pour la position $p")
            }
            out += Entry(p, s, value)
            off += len
        }
        if (off != raw.size) return Result.Invalid("${raw.size - off} octets en trop")
        return Result.Ok(vfo, out)
    }

    /**
     * Résultat des deux lectures (5 + 5) réunies. [key] = clé stable (stableKey) de la ligne
     * validée par la séquence : la lecture ne vaut que tant que la ligne courante a la même clé.
     */
    data class Reading(val vfo: Int, val values: Map<Int, Value?>, val at: Long, val key: String? = null)

    /** Lecture encore applicable : même ligne (clé stable identique) et moins de [maxAgeMs]. */
    fun applies(r: Reading, current: RadioStatus.Snapshot?, now: Long, maxAgeMs: Long = 45_000): Boolean =
        r.key != null && current != null && stableKey(current) == r.key && now - r.at <= maxAgeMs

    fun merge(parts: List<Result>, at: Long): Reading? {
        val oks = parts.map { it as? Result.Ok ?: return null }
        if (oks.isEmpty() || oks.any { it.vfo != oks[0].vfo }) return null
        val values = HashMap<Int, Value?>()
        for (r in oks) for (e in r.entries) values[e.position] = e.value
        return Reading(oks[0].vfo, values, at)
    }

    /**
     * Réglages attendus après la configuration d'équipe. [expected] vient de
     * [TeamProfile.expectedSettings]. Renvoie les libellés des réglages différents, absents ou
     * illisibles ; liste vide = tout est conforme.
     */
    fun differences(expected: Map<Int, Value>, read: Reading): List<String> =
        expected.mapNotNull { (p, want) ->
            val got = read.values[p]
            if (got == null || got != want) LABELS[p] ?: "menu $p" else null
        }

    /**
     * Champs de configuration stables de la ligne sélectionnée (contrat 0x0A v1.3) : deux lectures
     * 0x09 qui encadrent les deux requêtes 0x0A doivent donner la même clé. L'activité radio
     * (réception, squelch, fonction, ligne écoutée) n'y entre pas : une réception ne l'invalide pas.
     */
    fun stableKey(s: RadioStatus.Snapshot): String? {
        val l = s.selectedLine ?: return null
        return listOf(s.selected, l.kind, l.number, l.rxHz, l.power, l.modulation, l.width,
            l.rxTone, l.txTone, l.hasShift, l.reverse).joinToString("|")
    }
}
