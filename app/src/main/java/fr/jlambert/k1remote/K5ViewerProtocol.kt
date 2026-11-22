package fr.jlambert.k1remote

/**
 * Protocole « K5Viewer » du firmware F4HWN Fusion (App/k5viewer.c et driver/vcp.c).
 *
 * Téléphone → radio
 *   Keepalive        : 55 AA 00 00         (à renvoyer en continu, sinon la radio arrête d'émettre)
 *   Appui court      : AA 55 03 <touche>
 *   Appui long       : AA 55 04 <touche>
 *
 * Radio → téléphone
 *   [F0|flags] AA 55 02 <lenH> <lenL> { <index chunk> <8 octets> } x N  0A
 *   flags : bit0 = veille profonde, bit1 = LED rouge (TX), bit2 = LED verte (RX)
 *   Les types 05/06 (journal RF) sont ignorés : on ne les active pas.
 */
object K1Keys {
    const val KEY_0 = 0
    const val MENU = 10
    const val UP = 11
    const val DOWN = 12
    const val EXIT = 13
    const val STAR = 14
    const val F = 15
    // PTT (16) est volontairement bloqué par le firmware sur la liaison série.
    const val SIDE2 = 17
    const val SIDE1 = 18
}

object K5ViewerProtocol {
    val KEEPALIVE = byteArrayOf(0x55, 0xAA.toByte(), 0x00, 0x00)
    fun keyPress(key: Int, long: Boolean) =
        byteArrayOf(0xAA.toByte(), 0x55, if (long) 0x04 else 0x03, key.toByte())

    /**
     * PTT à distance (firmware « Fusion PTT » uniquement) : AA 55 06 v (v xor A5).
     * « Maintenu » doit être répété toutes les 200 ms ; la radio relâche seule
     * le PTT après 0,9 s sans nouvelles, et au plus tard après 30 s.
     */
    /**
     * Commande directe (firmware « Fusion PTT ») : AA 55 08 len data… chk,
     * chk = 08 xor len xor data. La radio répond AA 55 09 00 len [op, état…] 0A.
     */
    fun command(vararg data: Int): ByteArray {
        val d = data.map { it.toByte() }.toByteArray()
        var chk = 0x08 xor d.size
        for (b in d) chk = chk xor (b.toInt() and 0xFF)
        return byteArrayOf(0xAA.toByte(), 0x55, 0x08, d.size.toByte()) + d + byteArrayOf(chk.toByte())
    }

    /** Réglage n° [menu] (numéro de la liste « All ») = [value]. */
    fun setMenu(menu: Int, value: Int) = command(0x01, menu, value and 0xFF, (value shr 8) and 0xFF)

    /** Fréquence exacte de la ligne active (mode fréquence), en Hz. */
    fun setFreq(hz: Long): ByteArray {
        val f = (hz / 10).toInt()
        return command(0x02, f and 0xFF, (f shr 8) and 0xFF, (f shr 16) and 0xFF, (f shr 24) and 0xFF)
    }

    /** Code DTMF « UP » envoyé au début de chaque émission (menu 45). */
    fun setUpCode(code: String) = command(0x03, *code.take(8).map { it.code }.toIntArray())

    val PING = command(0x04)

    fun ptt(hold: Boolean): ByteArray {
        val v = if (hold) 1 else 0
        return byteArrayOf(0xAA.toByte(), 0x55, 0x06, v.toByte(), (v xor 0xA5).toByte())
    }
}

class K5ViewerParser(
    private val onReply: (ByteArray) -> Unit = {},
    private val onFrame: (screen: ByteArray, flags: Int) -> Unit
) {
    /** Image brute : 128 chunks de 8 octets, dans l'ordre d'envoi du firmware. */
    private val chunks = ByteArray(128 * 8)
    private var flags = 0

    private enum class S { IDLE, H1, TYPE, LEN_H, LEN_L, PAYLOAD, END }
    private var state = S.IDLE
    private var type = 0
    private var len = 0
    private var payload = ByteArray(2048)
    private var pos = 0

    /** Moment de la dernière image reçue (pour repérer un redémarrage de la radio). */
    private var lastFrameMs = 0L

    @Synchronized
    fun feed(buf: ByteArray, n: Int) {
        for (i in 0 until n) step(buf[i].toInt() and 0xFF)
    }

    /**
     * Oublie l'image en mémoire. La radio n'envoie que les morceaux d'écran qui
     * ont changé : sans cette remise à zéro, après un redémarrage de la radio,
     * les zones restées vides garderaient l'ancien affichage (l'économiseur d'écran…).
     */
    @Synchronized
    fun reset() {
        chunks.fill(0)
        flags = 0
        state = S.IDLE
        pos = 0
        lastFrameMs = 0L
    }

    private fun step(b: Int) {
        when (state) {
            S.IDLE -> when {
                b == 0xAA -> state = S.H1
                b and 0xF0 == 0xF0 -> flags = b and 0x0F
            }
            S.H1 -> state = if (b == 0x55) S.TYPE else { if (b == 0xAA) S.H1 else S.IDLE }
            S.TYPE -> { type = b; state = S.LEN_H }
            S.LEN_H -> { len = b shl 8; state = S.LEN_L }
            S.LEN_L -> {
                len = len or b
                pos = 0
                if (len > payload.size) payload = ByteArray(len)
                state = if (len == 0) S.END else S.PAYLOAD
            }
            S.PAYLOAD -> {
                payload[pos++] = b.toByte()
                if (pos >= len) state = S.END
            }
            S.END -> {
                if (b == 0x0A && type == 0x02) applyDiff()
                if (b == 0x0A && type == 0x09) onReply(payload.copyOf(len))
                state = S.IDLE
            }
        }
    }

    private fun applyDiff() {
        // Pas de remise à zéro sur un simple silence (pendant une émission, la radio peut
        // se taire plusieurs secondes) : l'image est effacée seulement quand la liaison
        // ou la radio disparaît (voir reset()), et la radio renvoie alors tout l'écran.
        lastFrameMs = System.currentTimeMillis()
        if (len % 9 != 0) return
        var p = 0
        while (p < len) {
            val idx = payload[p].toInt() and 0xFF
            if (idx < 128) System.arraycopy(payload, p + 1, chunks, idx * 8, 8)
            p += 9
        }
        onFrame(toPixels(), flags)
    }

    /**
     * Décode les chunks en pixels 128x64 (1 = pixel allumé).
     * Chunk = ligne*16 + bit*2 + moitié ; octet j, bit k → x = moitié*64 + j*8 + k, y = ligne*8 + bit.
     */
    private fun toPixels(): ByteArray {
        val px = ByteArray(128 * 64)
        for (c in 0 until 128) {
            val line = c / 16
            val inLine = c % 16
            val bit = inLine / 2
            val half = inLine % 2
            val y = line * 8 + bit
            for (j in 0 until 8) {
                val v = chunks[c * 8 + j].toInt()
                for (k in 0 until 8) {
                    if (v and (1 shl k) != 0) px[y * 128 + half * 64 + j * 8 + k] = 1
                }
            }
        }
        return px
    }
}
