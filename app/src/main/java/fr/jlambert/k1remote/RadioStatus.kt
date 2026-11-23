package fr.jlambert.k1remote

/**
 * Commande 0x09 « état de la radio », format v1 (contrat figé le 23 novembre 2026,
 * document « contrat-etat-radio-0x09-v1 » du projet Radios).
 *
 * Ce fichier ne fait que décoder et valider : il ne parle pas à la radio et ne touche pas à
 * l'affichage. Toutes les valeurs sont celles du protocole, jamais les valeurs internes du firmware.
 */
object RadioStatus {

    const val OP = 0x09
    const val SIZE_V1 = 32
    const val SIZE_V2 = 48
    const val UNKNOWN8 = 0xFF
    const val UNKNOWN16 = 0xFFFF
    const val UNKNOWN32 = 0xFFFFFFFFL
    const val STATUS_UNKNOWN_COMMAND = 9

    /** Requête : AA 55 08 01 09 chk. */
    val REQUEST: ByteArray get() = K5ViewerProtocol.command(OP)

    enum class Function { IDLE, TRANSMIT, RECEIVE, MONITOR, POWER_SAVE, SCAN_OR_SPECTRUM, UNKNOWN }
    enum class LineKind { MEMORY, FREQUENCY, WEATHER, UNKNOWN }
    enum class Modulation { FM, AM, USB, OTHER, UNKNOWN }
    enum class Width { WIDE, NARROW, NARROWER, UNKNOWN }
    enum class RxSource { A, B, OTHER_WATCHED, NONE }

    /**
     * Sous-canal (format 2) : type 0 aucun, 1 CTCSS (valeur en dixièmes de Hz), 2 DCS normal,
     * 3 DCS inversé (valeur = code octal pris comme entier : D023 → 0x13). [value] null = inconnue.
     */
    data class Tone(val type: Int, val value: Int?)

    data class Line(
        val kind: LineKind,
        /** Canal mémoire 1..1024, bande 1..7, météo 1..10 ; null = inconnu. */
        val number: Int?,
        /** Fréquence de réception en Hz ; null = inconnue. */
        val rxHz: Long?,
        /** 0 USER, 1..5 LOW 1..5, 6 MID, 7 HIGH ; null = inconnue. */
        val power: Int?,
        val modulation: Modulation,
        val width: Width,
        val rxOnly: Boolean,
        val txLock: Boolean,
        val hasShift: Boolean,
        val reverse: Boolean,
        /** 0 aucune, 1..24, 25 toutes ; null = inconnue. */
        val scanList: Int?,
        /** Format 2 : sous-canaux en réception et en émission ; null = inconnu ou format 1. */
        val rxTone: Tone? = null,
        val txTone: Tone? = null
    )

    data class Snapshot(
        val format: Int,
        /** Ligne sélectionnée (▶, celle qui émettra) : 0 = A, 1 = B. */
        val selected: Int,
        val keyLocked: Boolean,
        val dualWatch: Boolean,
        val crossBand: Boolean,
        val narrowerSetting: Boolean,
        val stealth: Boolean,
        val screenAsleep: Boolean,
        val function: Function,
        /** Ligne A ou B ; null si son type est inconnu (bloc à ignorer). */
        val a: Line?,
        val b: Line?,
        val rxSource: RxSource,
        /** Format 2 : appli spectre active (octet 32 bit 1) ; null = format 1. */
        val spectrumActive: Boolean? = null
    ) {
        fun line(i: Int): Line? = if (i == 0) a else b
        val selectedLine: Line? get() = line(selected)
    }

    sealed class Result {
        data class Ok(val snapshot: Snapshot) : Result()
        /** Réponse d'erreur du firmware (statut ≠ 0) : 9 = commande inconnue (firmware sans 0x09). */
        data class Refused(val status: Int) : Result()
        /** Trame invalide : rejetée, le dernier état valable est conservé. */
        data class Invalid(val reason: String) : Result()
    }

    private fun u8(r: ByteArray, i: Int) = r[i].toInt() and 0xFF
    private fun u16(r: ByteArray, i: Int) = u8(r, i) or (u8(r, i + 1) shl 8)
    private fun u32(r: ByteArray, i: Int): Long = u16(r, i).toLong() or (u16(r, i + 2).toLong() shl 16)

    /** Décode la charge utile d'une réponse (sans l'enveloppe AA 55 09 … 0A). */
    fun decode(r: ByteArray): Result {
        if (r.size < 2) return Result.Invalid("trame trop courte (${r.size} octets)")
        if (u8(r, 0) != OP) return Result.Invalid("opcode ${u8(r, 0)}")
        val status = u8(r, 1)
        if (status != 0) return Result.Refused(status)           // statut lu avant la longueur
        if (r.size < SIZE_V1) return Result.Invalid("trame tronquée (${r.size} octets)")
        val format = u8(r, 2)
        if (format < 1) return Result.Invalid("format $format")
        if (format == 1 && r.size != SIZE_V1) return Result.Invalid("format 1 de ${r.size} octets")

        val selected = u8(r, 3)
        if (selected > 1) return Result.Invalid("ligne sélectionnée $selected")
        val flags = u8(r, 4)
        if (flags and 0xC0 != 0) return Result.Invalid("indicateurs réservés non nuls")
        val function = when (u8(r, 5)) {
            0 -> Function.IDLE; 1 -> Function.TRANSMIT; 2 -> Function.RECEIVE; 3 -> Function.MONITOR
            4 -> Function.POWER_SAVE; 5 -> Function.SCAN_OR_SPECTRUM; UNKNOWN8 -> Function.UNKNOWN
            else -> return Result.Invalid("fonction ${u8(r, 5)}")
        }
        val a = line(r, 6).let { if (it is LineOrError.Error) return Result.Invalid("ligne A : " + it.reason) else (it as LineOrError.Ok).line }
        val b = line(r, 18).let { if (it is LineOrError.Error) return Result.Invalid("ligne B : " + it.reason) else (it as LineOrError.Ok).line }
        val rx = when (u8(r, 30)) {
            0 -> RxSource.A; 1 -> RxSource.B; 2 -> RxSource.OTHER_WATCHED; UNKNOWN8 -> RxSource.NONE
            else -> return Result.Invalid("ligne en réception ${u8(r, 30)}")
        }
        if (u8(r, 31) != 0) return Result.Invalid("octet réservé 31 non nul")

        val base = Snapshot(
            format = format, selected = selected,
            keyLocked = flags and 0x01 != 0, dualWatch = flags and 0x02 != 0, crossBand = flags and 0x04 != 0,
            narrowerSetting = flags and 0x08 != 0, stealth = flags and 0x10 != 0, screenAsleep = flags and 0x20 != 0,
            function = function, a = a, b = b, rxSource = rx)
        // Format 2 : partie étendue lue seulement si elle est complète et valide ; sinon on garde
        // l'état v1, sans sous-canaux (jamais de témoin d'équipe sur une donnée douteuse).
        return Result.Ok(if (format >= 2) extended(r, base) ?: base else base)
    }

    private fun tone(r: ByteArray, o: Int): Tone? {
        val type = u8(r, o); val v = u16(r, o + 1)
        return when (type) {
            0 -> if (v == 0) Tone(0, 0) else null
            1 -> Tone(1, if (v == UNKNOWN16) null else if (v in 600..3000) v else return null)
            2, 3 -> Tone(type, if (v == UNKNOWN16) null else if (v in 0..0x1FF) v else return null)
            UNKNOWN8 -> null
            else -> null
        }
    }

    private fun extended(r: ByteArray, s: Snapshot): Snapshot? {
        if (r.size < SIZE_V2) return null
        val ext = u8(r, 32)
        if (ext and 0xFD != 0) return null                         // bits réservés (dont 0 : FM) non nuls
        if (u8(r, 45) != 0 || u8(r, 46) != 0 || u8(r, 47) != 0) return null
        fun withTones(l: Line?, o: Int) = l?.copy(rxTone = tone(r, o), txTone = tone(r, o + 3))
        return s.copy(a = withTones(s.a, 33), b = withTones(s.b, 39), spectrumActive = ext and 0x02 != 0)
    }

    private sealed class LineOrError {
        data class Ok(val line: Line?) : LineOrError()
        data class Error(val reason: String) : LineOrError()
    }

    private fun line(r: ByteArray, o: Int): LineOrError {
        val kind = when (u8(r, o)) {
            0 -> LineKind.MEMORY; 1 -> LineKind.FREQUENCY; 2 -> LineKind.WEATHER
            UNKNOWN8 -> return LineOrError.Ok(null)                 // type inconnu : bloc ignoré
            else -> return LineOrError.Error("type ${u8(r, o)}")
        }
        val num = u16(r, o + 1)
        val number = when {
            num == UNKNOWN16 -> null
            kind == LineKind.MEMORY && num in 1..1024 -> num
            kind == LineKind.FREQUENCY && num in 1..7 -> num
            kind == LineKind.WEATHER && num in 1..10 -> num
            else -> return LineOrError.Error("numéro $num")
        }
        val f = u32(r, o + 3)
        val rxHz = if (f == UNKNOWN32) null else f * 10
        val power = u8(r, o + 7).let { if (it == UNKNOWN8) null else if (it <= 7) it else return LineOrError.Error("puissance $it") }
        val mod = when (u8(r, o + 8)) {
            0 -> Modulation.FM; 1 -> Modulation.AM; 2 -> Modulation.USB; 3 -> Modulation.OTHER; UNKNOWN8 -> Modulation.UNKNOWN
            else -> return LineOrError.Error("modulation ${u8(r, o + 8)}")
        }
        val width = when (u8(r, o + 9)) {
            0 -> Width.WIDE; 1 -> Width.NARROW; 2 -> Width.NARROWER; UNKNOWN8 -> Width.UNKNOWN
            else -> return LineOrError.Error("largeur ${u8(r, o + 9)}")
        }
        val cf = u8(r, o + 10)
        if (cf and 0xF0 != 0) return LineOrError.Error("drapeaux réservés non nuls")
        val list = u8(r, o + 11).let { if (it == UNKNOWN8) null else if (it <= RadioMemory.LIST_ALL) it else return LineOrError.Error("liste $it") }
        return LineOrError.Ok(Line(kind, number, rxHz, power, mod, width,
            rxOnly = cf and 1 != 0, txLock = cf and 2 != 0, hasShift = cf and 4 != 0, reverse = cf and 8 != 0, scanList = list))
    }

    /** Fabrique une charge utile v1 (trames de référence des tests, futur simulateur de démonstration). */
    fun encode(s: Snapshot): ByteArray {
        val v2 = s.format >= 2
        val r = ByteArray(if (v2) SIZE_V2 else SIZE_V1)
        r[0] = OP.toByte(); r[1] = 0; r[2] = (if (v2) 2 else 1).toByte()
        r[3] = s.selected.toByte()
        r[4] = ((if (s.keyLocked) 1 else 0) or (if (s.dualWatch) 2 else 0) or (if (s.crossBand) 4 else 0) or
            (if (s.narrowerSetting) 8 else 0) or (if (s.stealth) 0x10 else 0) or (if (s.screenAsleep) 0x20 else 0)).toByte()
        r[5] = when (s.function) {
            Function.IDLE -> 0; Function.TRANSMIT -> 1; Function.RECEIVE -> 2; Function.MONITOR -> 3
            Function.POWER_SAVE -> 4; Function.SCAN_OR_SPECTRUM -> 5; Function.UNKNOWN -> UNKNOWN8
        }.toByte()
        encodeLine(r, 6, s.a); encodeLine(r, 18, s.b)
        r[30] = when (s.rxSource) { RxSource.A -> 0; RxSource.B -> 1; RxSource.OTHER_WATCHED -> 2; RxSource.NONE -> UNKNOWN8 }.toByte()
        r[31] = 0
        if (v2) {
            r[32] = (if (s.spectrumActive == true) 0x02 else 0).toByte()
            fun putTone(o: Int, t: Tone?) {
                r[o] = (t?.type ?: UNKNOWN8).toByte()
                val v = if (t == null) UNKNOWN16 else (t.value ?: UNKNOWN16)
                r[o + 1] = v.toByte(); r[o + 2] = (v shr 8).toByte()
            }
            for ((i, l) in listOf(s.a, s.b).withIndex()) { putTone(33 + i * 6, l?.rxTone); putTone(36 + i * 6, l?.txTone) }
        }
        return r
    }

    private fun encodeLine(r: ByteArray, o: Int, l: Line?) {
        if (l == null) { for (k in 0 until 12) r[o + k] = 0; r[o] = UNKNOWN8.toByte(); return }
        r[o] = when (l.kind) { LineKind.MEMORY -> 0; LineKind.FREQUENCY -> 1; LineKind.WEATHER -> 2; LineKind.UNKNOWN -> UNKNOWN8 }.toByte()
        val n = l.number ?: UNKNOWN16
        r[o + 1] = n.toByte(); r[o + 2] = (n shr 8).toByte()
        val f = l.rxHz?.let { it / 10 } ?: UNKNOWN32
        for (k in 0 until 4) r[o + 3 + k] = (f shr (8 * k)).toByte()
        r[o + 7] = (l.power ?: UNKNOWN8).toByte()
        r[o + 8] = when (l.modulation) { Modulation.FM -> 0; Modulation.AM -> 1; Modulation.USB -> 2; Modulation.OTHER -> 3; Modulation.UNKNOWN -> UNKNOWN8 }.toByte()
        r[o + 9] = when (l.width) { Width.WIDE -> 0; Width.NARROW -> 1; Width.NARROWER -> 2; Width.UNKNOWN -> UNKNOWN8 }.toByte()
        r[o + 10] = ((if (l.rxOnly) 1 else 0) or (if (l.txLock) 2 else 0) or (if (l.hasShift) 4 else 0) or (if (l.reverse) 8 else 0)).toByte()
        r[o + 11] = (l.scanList ?: UNKNOWN8).toByte()
    }
}
