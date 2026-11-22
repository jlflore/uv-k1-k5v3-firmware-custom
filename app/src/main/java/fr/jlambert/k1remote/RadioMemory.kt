package fr.jlambert.k1remote

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Protocole de programmation d'origine des Quansheng (celui de CHIRP), tel qu'implémenté
 * dans App/app/uart.c du firmware : trame AB CD | longueur (LE) | charge + CRC16 brouillés
 * par XOR | DC BA. Ici, LECTURE SEULE : 0x0514 (ouverture de session) et 0x051B (lecture).
 */
object ProgProtocol {
    private val KEY = intArrayOf(0x16, 0x6C, 0x14, 0xE6, 0x2E, 0x91, 0x0D, 0x40,
                                 0x21, 0x35, 0xD5, 0x40, 0x13, 0x03, 0xE9, 0x80)

    const val ID_HELLO = 0x0514
    const val ID_VERSION = 0x0515
    const val ID_READ = 0x051B
    const val ID_READ_REPLY = 0x051C
    const val MAX_READ = 128

    /** CRC16 XMODEM (polynôme 0x1021, départ 0), comme CRC_Calculate du firmware. */
    fun crc16(b: ByteArray): Int {
        var crc = 0
        for (x in b) {
            crc = crc xor ((x.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return crc
    }

    private fun frame(payload: ByteArray): ByteArray {
        val crc = crc16(payload)
        val body = payload + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
        for (i in body.indices) body[i] = (body[i].toInt() xor KEY[i % 16]).toByte()
        val n = payload.size
        return byteArrayOf(0xAB.toByte(), 0xCD.toByte(), n.toByte(), (n shr 8).toByte()) + body +
            byteArrayOf(0xDC.toByte(), 0xBA.toByte())
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    fun hello(ts: Int) = frame(byteArrayOf(0x14, 0x05, 0x04, 0x00) + le32(ts))

    fun read(offset: Int, size: Int, ts: Int): ByteArray {
        require(size in 1..MAX_READ && offset in 0..0xFFFF)
        return frame(byteArrayOf(0x1B, 0x05, 0x08, 0x00,
            offset.toByte(), (offset shr 8).toByte(), size.toByte(), 0) + le32(ts))
    }

    const val ID_WRITE = 0x051D
    const val ID_WRITE_REPLY = 0x051E

    /**
     * Écriture (0x051D) : la taille doit être un multiple de 8 (le firmware écrit par blocs de 8).
     * Indicateur « bAllowPassword » à 1 : avec la carte mémoire logique de Fusion, l'ancienne
     * exception du mot de passe d'écran (0x0E98-0x0E9F) tombe dans le canal 233 ; sans cet
     * indicateur, ces 8 octets seraient ignorés en silence quand la radio est verrouillée.
     */
    fun write(offset: Int, data: ByteArray, ts: Int): ByteArray {
        require(data.size in 8..MAX_READ && data.size % 8 == 0 && offset % 8 == 0)
        val n = 8 + data.size
        return frame(byteArrayOf(0x1D, 0x05, n.toByte(), (n shr 8).toByte(),
            offset.toByte(), (offset shr 8).toByte(), data.size.toByte(), 1) + le32(ts) + data)
    }

    fun deobfuscate(b: ByteArray) { for (i in b.indices) b[i] = (b[i].toInt() xor KEY[i % 16]).toByte() }
}

/**
 * Découpe le flux reçu en réponses AB CD. Le pied DC BA est vérifié ; le « CRC » des réponses
 * n'est pas calculé par le firmware (FF brouillé), on ne le contrôle donc pas. Tout octet hors
 * trame (restes d'écran K5Viewer) est ignoré.
 */
class ProgParser(private val onMessage: (id: Int, body: ByteArray) -> Unit) {
    private enum class S { IDLE, H2, L1, L2, BODY, F1, F2 }
    private var s = S.IDLE
    private var len = 0
    private var pos = 0
    private var buf = ByteArray(0)

    @Synchronized
    fun feed(data: ByteArray, n: Int) {
        for (i in 0 until n) step(data[i].toInt() and 0xFF)
    }

    private fun step(b: Int) {
        when (s) {
            S.IDLE -> if (b == 0xAB) s = S.H2
            S.H2 -> s = if (b == 0xCD) S.L1 else if (b == 0xAB) S.H2 else S.IDLE
            S.L1 -> { len = b; s = S.L2 }
            S.L2 -> {
                len = len or (b shl 8)
                if (len < 4 || len > 300) { s = S.IDLE; return }   // réponse impossible : on resynchronise
                buf = ByteArray(len); pos = 0; s = S.BODY
            }
            S.BODY -> { buf[pos++] = b.toByte(); if (pos >= len) { pos = 0; s = S.F1 } }
            S.F1 -> { if (++pos >= 2) s = S.F2 }                   // 2 octets de « CRC » ignorés
            S.F2 -> {
                if (b == 0xDC) { pos = -1; s = S.F2 }
                else if (pos == -1 && b == 0xBA) {
                    ProgProtocol.deobfuscate(buf)
                    val id = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
                    onMessage(id, buf)
                    s = S.IDLE
                } else s = S.IDLE
            }
        }
    }
}

/** Un canal mémoire décodé (format F4HWN UV-K1 : 16 octets à 0x0000, nom à 0x4000, attributs à 0x8000). */
data class Channel(
    val number: Int,                 // 1..1024
    val name: String,
    val rxHz: Long,
    val txHz: Long,
    val mode: String,                // FM, AM, USB…
    val power: String,
    val narrow: Boolean,
    val rxTone: String?,
    val txTone: String?,
    val stepHz: Int,
    val bcl: Boolean,
    val txLock: Boolean,
    val scanLists: List<Int>,
    val raw: ByteArray,
    /** Octet 12, bit 7 : « écoute seule » (Fusion PTT r25+ : émission toujours refusée sur ce canal). */
    val rxOnly: Boolean = false
) {
    /** Le firmware n'émet jamais hors FM (radio.c) : AM et USB sont en écoute seule ; FM aussi si [rxOnly]. */
    val listenOnly: Boolean get() = mode != "FM" || rxOnly
    val shift: String get() = when {
        txHz == rxHz -> ""
        txHz > rxHz -> "+" + RadioMemory.mhz(txHz - rxHz)
        else -> "−" + RadioMemory.mhz(rxHz - txHz)
    }
}

/** Contenu modifiable d'un canal (fréquences en Hz). */
data class ChannelEdit(
    var name: String = "",
    var rxHz: Long = 446_006_250,
    var shiftDir: Int = 0,           // 0 aucun, 1 +, 2 −
    var shiftHz: Long = 0,
    var mode: Int = 0,               // 0 FM, 1 AM, 2 USB
    var power: Int = 1,              // 0 USER … 1 LOW 1 … 7 HIGH
    var narrow: Boolean = true,
    var txTone: String? = null,      // « 88,5 Hz », « D023N »… ; null = aucun
    var rxTone: String? = null,
    var stepIdx: Int = 4,            // 12,5 kHz
    var scanLists: Set<Int> = emptySet(),
    var rxOnly: Boolean = false
)

/** Image mémoire lue sur la radio (adresses logiques du firmware), et sa sauvegarde. */
class RadioMemory(val image: ByteArray, val firmware: String, val date: Long, var label: String) {

    companion object {
        const val SIZE = 0xA178
        /** Zones lues : canaux, noms, attributs + noms de listes, VFO, réglages. Rien d'autre. */
        val RANGES = listOf(0x0000 until 0x886E, 0x9000 until 0x90E9, 0xA000 until 0xA178)
        const val CHANNELS = 1024
        /** Seules zones que l'appli écrit : canaux, noms, attributs des canaux. Jamais le reste. */
        val WRITE_ZONES = listOf(0x0000 until 0x4000, 0x4000 until 0x8000, 0x8000 until 0x8800)
        val MODE_NAMES = listOf("FM", "AM", "USB")
        val POWER_NAMES = listOf("USER", "LOW 1", "LOW 2", "LOW 3", "LOW 4", "LOW 5", "MID", "HIGH")
        /** Puissances approximatives (UV-K1 / UV-K5 V3, F4HWN), dans l'ordre de [POWER_NAMES]. */
        val POWER_WATTS = listOf("selon SetPwr", "< 20 mW", "≈ 125 mW", "≈ 250 mW", "≈ 500 mW", "≈ 1 W", "≈ 2 W", "≈ 5 W")
        val POWER_LABELS = POWER_NAMES.indices.map { "${POWER_NAMES[it]}  ·  ${POWER_WATTS[it]}" }
        /**
         * Nom tel que la radio le stockera : 10 caractères ASCII. Les accents sont retirés
         * (« Écoute » → « Ecoute ») au lieu de faire disparaître la lettre (avant la 0.60).
         */
        fun radioName(s: String): String {
            val plain = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace("Œ", "OE").replace("œ", "oe").replace("Æ", "AE").replace("æ", "ae").replace("ß", "ss")
                .replace('’', '\'').replace('«', '"').replace('»', '"')
            return plain.filter { it.code in 32..126 }.take(10)
        }

        fun powerWatts(name: String) = POWER_WATTS.getOrNull(POWER_NAMES.indexOf(name)) ?: ""
        val STEP_KHZ = listOf("2,5", "5", "6,25", "10", "12,5", "25", "8,33", "0,01", "0,05", "0,1", "0,25", "0,5",
            "1", "1,25", "9", "15", "20", "30", "50", "100", "125", "200", "250", "500")

        /** Bande du firmware (frequencies.c, réception large) : 7 = canal vide. */
        fun bandOf(hz: Long): Int {
            val f = hz / 10
            return when {
                f < 10_800_000 -> 0; f < 13_700_000 -> 1; f < 17_400_000 -> 2
                f < 35_000_000 -> 3; f < 40_000_000 -> 4; f < 47_000_000 -> 5; else -> 6
            }
        }

        /** Toutes les valeurs de sous-ton proposées : CTCSS puis DCS normal puis DCS inversé. */
        fun toneChoices(): List<String> =
            (0 until 50).mapNotNull { tone(1, it) } + (0 until 104).mapNotNull { tone(2, it) } + (0 until 104).mapNotNull { tone(3, it) }

        /** « 88,5 Hz » → (1, index) ; « D023N » → (2, index) ; null → (0, 0). */
        fun toneCode(t: String?): Pair<Int, Int> {
            if (t == null) return 0 to 0
            for (type in 1..3) for (c in 0 until (if (type == 1) 50 else 104)) if (tone(type, c) == t) return type to c
            return 0 to 0
        }

        /** « 446,00625 », « 446.00625 », « 446 006 250 » (Hz) → Hz ; null si illisible. */
        fun parseMHz(s: String): Long? {
            val t = s.trim().replace(" ", "").replace(',', '.')
            if (t.isEmpty()) return null
            val v = t.toDoubleOrNull() ?: return null
            val hz = when {
                v < 2000 -> Math.round(v * 1_000_000)            // MHz
                v < 2_000_000 -> Math.round(v * 1_000)           // kHz
                else -> Math.round(v)                            // Hz
            }
            return if (hz in 18_000_000..1_300_000_000) hz else null
        }

        private val MODES = listOf("FM", "AM", "USB", "BYP", "RAW")
        private val POWERS = listOf("USER", "LOW 1", "LOW 2", "LOW 3", "LOW 4", "LOW 5", "MID", "HIGH")
        // Ordre de l'énumération STEP_Setting_t du firmware (frequencies.h), en centièmes de kHz
        private val STEPS = intArrayOf(250, 500, 625, 1000, 1250, 2500, 833, 1, 5, 10, 25, 50, 100, 125,
            900, 1500, 2000, 3000, 5000, 10000, 12500, 20000, 25000, 50000)
        private val CTCSS = intArrayOf(670, 693, 719, 744, 770, 797, 825, 854, 885, 915, 948, 974, 1000, 1035,
            1072, 1109, 1148, 1188, 1230, 1273, 1318, 1365, 1413, 1462, 1514, 1567, 1598, 1622, 1655, 1679,
            1713, 1738, 1773, 1799, 1835, 1862, 1899, 1928, 1966, 1995, 2035, 2065, 2107, 2181, 2257, 2291,
            2336, 2418, 2503, 2541)
        private val DCS_LOW = intArrayOf(0x13, 0x15, 0x16, 0x19, 0x1A, 0x1E, 0x23, 0x27, 0x29, 0x2B, 0x2C,
            0x35, 0x39, 0x3A, 0x3B, 0x3C, 0x4C, 0x4D, 0x4E, 0x52, 0x55, 0x59, 0x5A, 0x5C, 0x63, 0x65, 0x6A,
            0x6D, 0x6E, 0x72, 0x75, 0x7A, 0x7C, 0x85, 0x8A, 0x93, 0x95, 0x96, 0xA3, 0xA4, 0xA5, 0xA6, 0xA9,
            0xAA, 0xAD, 0xB1, 0xB3, 0xB5, 0xB6, 0xB9, 0xBC, 0xC6, 0xC9, 0xCD, 0xD5, 0xD9, 0xDA, 0xE3, 0xE6,
            0xE9, 0xEE, 0xF4, 0xF5, 0xF9, 0x09, 0x0A, 0x0B, 0x13, 0x19, 0x1A, 0x25, 0x26, 0x2A, 0x2C, 0x2D,
            0x32, 0x34, 0x35, 0x36, 0x43, 0x46, 0x4E, 0x53, 0x56, 0x5A, 0x66, 0x75, 0x86, 0x8A, 0x94, 0x97,
            0x99, 0x9A, 0xAC, 0xB2, 0xB4, 0xC3, 0xCA, 0xD3, 0xD9, 0xDA, 0xDC, 0xE3, 0xEC)

        fun mhz(hz: Long): String {
            val s = String.format(Locale.FRANCE, "%.5f", hz / 1_000_000.0).trimEnd('0')
            return if (s.endsWith(",")) s + "0" else s
        }

        private fun tone(type: Int, code: Int): String? = when (type) {
            1 -> if (code < CTCSS.size) String.format(Locale.FRANCE, "%.1f Hz", CTCSS[code] / 10.0) else null
            2, 3 -> if (code < DCS_LOW.size) {
                val v = DCS_LOW[code] or (if (code >= 64) 0x100 else 0)
                "D" + Integer.toOctalString(v).padStart(3, '0') + (if (type == 2) "N" else "I")
            } else null
            else -> null
        }

        fun dir(ctx: Context) = File(ctx.filesDir, "sauvegardes").apply { mkdirs() }

        fun list(ctx: Context): List<File> =
            (dir(ctx).listFiles { f -> f.name.endsWith(".k1r") } ?: emptyArray()).sortedByDescending { it.lastModified() }

        fun load(input: InputStream): RadioMemory {
            var image: ByteArray? = null
            var meta = JSONObject()
            ZipInputStream(input).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val data = z.readBytes()
                    when (e.name) {
                        "memoire.bin" -> image = data
                        "manifest.json" -> meta = JSONObject(String(data))
                    }
                }
            }
            val img = image ?: error("Fichier de sauvegarde incomplet")
            require(img.size == SIZE) { "Taille de mémoire inattendue" }
            val crc = CRC32().apply { update(img) }.value
            if (meta.has("crc32") && meta.optLong("crc32") != crc) error("Sauvegarde abîmée (contrôle CRC)")
            return RadioMemory(img, meta.optString("firmware"), meta.optLong("date"), meta.optString("nom"))
        }

        fun load(f: File): RadioMemory = f.inputStream().use { load(it) }
    }

    fun copy() = RadioMemory(image.copyOf(), firmware, date, label)

    fun edit(c: Channel): ChannelEdit = ChannelEdit(
        name = c.name, rxHz = c.rxHz,
        shiftDir = u8((c.number - 1) * 16 + 11) and 0x0F,
        shiftHz = u32((c.number - 1) * 16 + 4) * 10,
        mode = (u8((c.number - 1) * 16 + 11) shr 4).coerceIn(0, 2),
        power = (u8((c.number - 1) * 16 + 12) shr 2) and 7,
        narrow = c.narrow, txTone = c.txTone, rxTone = c.rxTone,
        stepIdx = u8((c.number - 1) * 16 + 14).let { if (it < STEP_KHZ.size) it else 4 },
        scanLists = c.scanLists.toSet(), rxOnly = c.rxOnly)

    fun firstFree(from: Int = 0): Int? = (from until CHANNELS).firstOrNull { channel(it) == null }

    /** Écrit un canal (index 0..1023) dans l'image, en gardant les octets qu'on ne gère pas. */
    fun setChannel(i: Int, e: ChannelEdit) {
        val base = i * 16
        val wasEmpty = channel(i) == null
        fun put32(a: Int, v: Long) { for (k in 0 until 4) image[a + k] = (v shr (8 * k)).toByte() }
        put32(base, e.rxHz / 10)
        put32(base + 4, if (e.shiftDir == 0) 0 else e.shiftHz / 10)
        val (txT, txC) = toneCode(e.txTone)
        val (rxT, rxC) = toneCode(e.rxTone)
        image[base + 8] = rxC.toByte()
        image[base + 9] = txC.toByte()
        image[base + 10] = ((txT shl 4) or rxT).toByte()
        image[base + 11] = ((e.mode shl 4) or e.shiftDir).toByte()
        val old = if (wasEmpty) 0x40 else u8(base + 12)          // TX Lock activé par défaut (plan de bandes)
        // On garde les bits 6 (TX Lock), 5 (canal occupé) et 0 (inversion) ; on pose écoute seule (bit 7),
        // puissance et largeur
        image[base + 12] = ((old and 0x61) or ((if (e.rxOnly) 1 else 0) shl 7) or ((e.power and 7) shl 2) or
            ((if (e.narrow) 1 else 0) shl 1)).toByte()
        if (wasEmpty) { image[base + 13] = 0; image[base + 15] = 0 }
        image[base + 14] = e.stepIdx.toByte()
        // Nom : 10 caractères ASCII, complétés par des zéros (comme SETTINGS_SaveChannelName)
        val nm = radioName(e.name).toByteArray(Charsets.US_ASCII)
        for (k in 0 until 16) image[0x4000 + base + k] = if (k < nm.size) nm[k] else 0
        // Attributs : bande (d'après la fréquence), listes de scan
        val band = bandOf(e.rxHz)
        val lists = e.scanLists.filter { it in 1..8 }.fold(0) { acc, l -> acc or (1 shl (l - 1)) }
        val attrOld = if (wasEmpty) 0 else u16(0x8000 + i * 2)
        val attr = (band and 7) or (attrOld and 0x18) or (lists shl 8)
        image[0x8000 + i * 2] = attr.toByte()
        image[0x8000 + i * 2 + 1] = (attr shr 8).toByte()
    }

    /** Efface un canal comme le firmware : enregistrement à FF, nom à 0, bande 7 (vide). */
    fun deleteChannel(i: Int) {
        for (k in 0 until 16) image[i * 16 + k] = 0xFF.toByte()
        for (k in 0 until 16) image[0x4000 + i * 16 + k] = 0
        image[0x8000 + i * 2] = 0x07
        image[0x8000 + i * 2 + 1] = 0
    }

    /**
     * Blocs à écrire pour passer de [base] à cette image : morceaux de 8 octets qui diffèrent,
     * regroupés (au plus 64 octets) sans jamais sortir des trois zones autorisées.
     */
    fun diff(base: RadioMemory): List<Pair<Int, ByteArray>> {
        val out = mutableListOf<Pair<Int, ByteArray>>()
        for (zone in WRITE_ZONES) {
            var a = zone.first
            while (a <= zone.last) {
                if (!(a until a + 8).all { image[it] == base.image[it] }) {
                    var end = a + 8
                    while (end <= zone.last && end - a < 64 &&
                        !(end until end + 8).all { image[it] == base.image[it] }) end += 8
                    out += a to image.copyOfRange(a, end)
                    a = end
                } else a += 8
            }
        }
        return out
    }

    private fun u8(a: Int) = image[a].toInt() and 0xFF
    private fun u16(a: Int) = u8(a) or (u8(a + 1) shl 8)
    private fun u32(a: Int): Long = (u16(a).toLong()) or (u16(a + 2).toLong() shl 16)

    fun channel(i: Int): Channel? {
        val base = i * 16
        val freq = u32(base)
        val attr = u16(0x8000 + i * 2)
        if (freq == 0xFFFFFFFFL || freq == 0L || (attr and 7) == 7) return null
        val rx = freq * 10
        val off = u32(base + 4) * 10
        val dirB = u8(base + 11)
        val tx = when (dirB and 0x0F) { 1 -> rx + off; 2 -> rx - off; else -> rx }
        val f = u8(base + 12)
        val nameBytes = image.copyOfRange(0x4000 + i * 16, 0x4000 + i * 16 + 10)
        val name = nameBytes.takeWhile { it in 32..126 }.map { it.toInt().toChar() }.joinToString("").trimEnd()
        val stepIdx = u8(base + 14)
        val lists = (0 until 8).filter { attr shr (8 + it) and 1 == 1 }.map { it + 1 }
        return Channel(
            number = i + 1, name = name, rxHz = rx, txHz = tx,
            mode = MODES.getOrElse(dirB shr 4) { "?" },
            power = POWERS.getOrElse((f shr 2) and 7) { "?" },
            narrow = (f shr 1) and 1 == 1,
            rxTone = tone(u8(base + 10) and 0x0F, u8(base + 8)),
            txTone = tone(u8(base + 10) shr 4, u8(base + 9)),
            stepHz = if (stepIdx < STEPS.size) STEPS[stepIdx] * 10 else 0,
            bcl = (f shr 5) and 1 == 1,
            txLock = (f shr 6) and 1 == 1,
            scanLists = lists,
            raw = image.copyOfRange(base, base + 16),
            rxOnly = (f shr 7) and 1 == 1
        )
    }

    fun channels(): List<Channel> = (0 until CHANNELS).mapNotNull { channel(it) }

    fun write(out: OutputStream) {
        val crc = CRC32().apply { update(image) }.value
        val meta = JSONObject().apply {
            put("format", "K1 Remote mémoire v1")
            put("modele", "UV-K1 / UV-K5 V3 · F4HWN Fusion")
            put("firmware", firmware)
            put("date", date)
            put("nom", label)
            put("zones", "0000-886D, 9000-90E8, A000-A177 (adresses logiques)")
            put("crc32", crc)
        }
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write(meta.toString(2).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("memoire.bin")); z.write(image); z.closeEntry()
            z.putNextEntry(ZipEntry("canaux.csv")); z.write(csv().toByteArray()); z.closeEntry()
        }
    }

    fun save(ctx: Context): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.FRANCE).format(Date(date))
        val f = File(dir(ctx), "$stamp.k1r")
        f.outputStream().use { write(it) }
        return f
    }

    /** Export lisible (colonnes proches de CHIRP). */
    fun csv(): String {
        val sb = StringBuilder("Canal;Nom;Réception (MHz);Émission (MHz);Mode;Puissance;Largeur;Sous-ton RX;Sous-ton TX;Pas (kHz);Listes;Écoute seule\n")
        for (c in channels()) {
            sb.append(c.number).append(';').append(c.name).append(';')
                .append(mhz(c.rxHz)).append(';').append(mhz(c.txHz)).append(';')
                .append(c.mode).append(';').append(c.power).append(';')
                .append(if (c.narrow) "étroite" else "large").append(';')
                .append(c.rxTone ?: "").append(';').append(c.txTone ?: "").append(';')
                .append(String.format(Locale.FRANCE, "%.2f", c.stepHz / 1000.0)).append(';')
                .append(c.scanLists.joinToString(" ")).append(';')
                .append(if (c.listenOnly) "oui" else "").append('\n')
        }
        return sb.toString()
    }
}
