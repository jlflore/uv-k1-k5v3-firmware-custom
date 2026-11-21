package fr.jlambert.k1remote

/**
 * Lit quelques informations sur l'image de l'écran envoyée par la radio (128 x 64 points).
 *
 * On ne fait pas de reconnaissance de caractères « devinée » : on redessine chaque valeur
 * possible avec la police exacte du firmware, à la position exacte où le firmware l'écrit
 * (App/ui/main.c), et on garde celle qui correspond pixel pour pixel.
 *
 * Repères (coordonnées de l'image reçue ; la ligne 0 de l'image est la barre d'état) :
 *  - ligne A du firmware = gFrameBuffer[0], ligne B = gFrameBuffer[4] ;
 *  - marqueur de la ligne principale : 7 colonnes à x = 0 de la page (ligne + 1) ;
 *  - puissance, interface TINY : petite police, page (ligne + 3), à partir de x = 42 ;
 *  - puissance, interface CLASSIC : police 3x5, x = 24, y = 25 (A) ou 57 (B).
 */
object ScreenReader {

    /**
     * [modeA]/[modeB] : 'M' = mode canal (étiquette « 0001 »), 'F' = mode fréquence
     * (étiquette « F1 »…), null = non reconnu (menu, spectre…).
     */
    data class Reading(val mainVfo: Char?, val powerA: String?, val powerB: String?,
                       val modeA: Char? = null, val modeB: Char? = null, val dtmf: String? = null,
                       /** Cadenas affiché dans la barre d'état : clavier de la radio verrouillé. */
                       val locked: Boolean = false) {
        val mainMode: Char?
            get() = when (mainVfo) {
                'A' -> modeA
                'B' -> modeB
                else -> null
            }

        /** Puissance de la ligne principale, au format long (LOW1… HIGH). */
        val mainPower: String?
            get() = when (mainVfo) {
                'B' -> powerB
                else -> powerA
            }
    }

    private val LONG = listOf("LOW1", "LOW2", "LOW3", "LOW4", "LOW5", "MID", "HIGH")
    private val SHORT = listOf("L1", "L2", "L3", "L4", "L5", "M", "H")

    fun read(raw: ByteArray): Reading {
        if (raw.size != 128 * 64) return Reading(null, null, null)
        // Réglage SetInv : le firmware envoie l'image en négatif
        var lit = 0
        for (b in raw) if (b.toInt() != 0) lit++
        val inverted = lit > raw.size * 55 / 100
        val px = { x: Int, y: Int ->
            if (x < 0 || x >= 128 || y < 0 || y >= 64) false
            else (raw[y * 128 + x].toInt() != 0) != inverted
        }
        return Reading(mainVfo(px), power(px, 0), power(px, 4), mode(px, 0), mode(px, 4), dtmf(px), locked(px))
    }

    /** Icône cadenas du firmware (gFontKeyLock, 9 colonnes), dessinée dans la barre d'état à x ≥ 69. */
    private val LOCK = intArrayOf(0x7c, 0x46, 0x45, 0x45, 0x45, 0x45, 0x45, 0x46, 0x7c)

    private fun locked(px: (Int, Int) -> Boolean): Boolean {
        for (x0 in 60..118) {
            var ok = true
            for (i in LOCK.indices) {
                var col = 0
                for (y in 0 until 8) if (px(x0 + i, y)) col = col or (1 shl y)
                if (col != LOCK[i]) { ok = false; break }
            }
            if (ok) return true
        }
        return false
    }

    /**
     * Code DTMF reçu, affiché par le décodeur en direct (réglage 50 D Live) :
     * « DTMF <code> » en petite police à x = 2, gFrameBuffer[3] (ou [5] en simple veille).
     */
    private fun dtmf(px: (Int, Int) -> Boolean): String? {
        for (fbPage in intArrayOf(3, 5)) {
            val page = fbPage + 1
            if (!matchSmall(px, "DTMF", 2, page)) continue
            val sb = StringBuilder()
            var i = 5
            while (i < 18) {
                val ch = "0123456789ABCD*#".firstOrNull { matchSmallChar(px, it, 2 + i * 7, page) } ?: break
                sb.append(ch); i++
            }
            if (sb.isNotEmpty()) return sb.toString()
        }
        return null
    }

    private fun matchSmallChar(px: (Int, Int) -> Boolean, ch: Char, x0: Int, page: Int): Boolean =
        matchSmall(px, ch.toString(), x0, page)

    /**
     * Étiquette en négatif à gauche de chaque ligne (UI_PrintStringSmallNormalInverse,
     * gFrameBuffer[line + 1]) : « 0001 » de x = 0 à 30 en mode canal, « F1 » de x = 1 à 17
     * en mode fréquence. Le 8e bit (bas de la page) est allumé partout dans le négatif.
     */
    private fun mode(px: (Int, Int) -> Boolean, line: Int): Char? {
        val y = (line + 2) * 8 + 7
        var wide = 0
        for (x in 20..28) if (px(x, y)) wide++
        var narrow = 0
        for (x in 3..15) if (px(x, y)) narrow++
        return when {
            wide == 9 && narrow == 13 -> 'M'
            wide == 0 && narrow == 13 -> 'F'
            else -> null
        }
    }

    private fun mainVfo(px: (Int, Int) -> Boolean): Char? {
        fun count(page: Int): Int {
            var n = 0
            for (x in 0 until 7) for (bit in 0 until 8) if (px(x, page * 8 + bit)) n++
            return n
        }
        val a = count(1)
        val b = count(5)
        return when {
            a >= 12 && a > b + 4 -> 'A'
            b >= 12 && b > a + 4 -> 'B'
            else -> null
        }
    }

    /** line = 0 pour la ligne A, 4 pour la ligne B (indices gFrameBuffer du firmware). */
    private fun power(px: (Int, Int) -> Boolean, line: Int): String? {
        // Interface TINY : "L1".."H" en petite police
        val page = line + 3
        for (i in SHORT.indices) {
            if (matchSmall(px, SHORT[i], 42, page)) return LONG[i]
        }
        // Interface CLASSIC : "LOW1".."HIGH" en police 3x5 (on teste les plus longues d'abord)
        val y = if (line == 0) 25 else 57
        for (i in LONG.indices.sortedByDescending { LONG[it].length }) {
            if (matchSmallest(px, LONG[i], 24, y)) return LONG[i]
        }
        return null
    }

    /** Reproduit UI_PrintStringBuffer : colonnes de 7 bits, espacement 7, décalage 1. */
    private fun matchSmall(px: (Int, Int) -> Boolean, text: String, x0: Int, page: Int): Boolean {
        val font = FirmwareFonts.SMALL_PACKED
        var anyOn = false
        for (i in text.indices) {
            val c = text[i].code
            if (c <= 32 || c >= 127) return false
            var bit = (c - 33) * 6 * 7
            for (col in 0 until 6) {
                val byte = bit shr 3
                if (byte + 1 >= font.size) return false
                val packed = font[byte] or (font[byte + 1] shl 8)
                val column = (packed shr (bit and 7)) and 0x7F
                val x = x0 + i * 7 + 1 + col
                for (b in 0 until 7) {
                    val expected = (column shr b) and 1 == 1
                    if (expected) anyOn = true
                    if (px(x, page * 8 + b) != expected) return false
                }
                bit += 7
            }
        }
        return anyOn
    }

    /** Reproduit GUI_DisplaySmallest : 3 colonnes de 6 pixels, avance de 4. */
    private fun matchSmallest(px: (Int, Int) -> Boolean, text: String, x0: Int, y0: Int): Boolean {
        val font = FirmwareFonts.SMALLEST
        var x = x0
        var anyOn = false
        for (ch in text) {
            val c = ch.code - 32
            if (c < 0 || c >= 96) return false
            for (i in 0 until 3) {
                val column = font[c * 3 + i]
                for (j in 0 until 6) {
                    val expected = (column shr j) and 1 == 1
                    if (expected) anyOn = true
                    if (px(x + i, y0 + j) != expected) return false
                }
            }
            x += 4
        }
        return anyOn
    }

    /** "LOW3" -> "LOW 3" pour l'affichage. */
    fun pretty(power: String): String =
        if (power.startsWith("LOW") && power.length == 4) "LOW " + power[3] else power
}
