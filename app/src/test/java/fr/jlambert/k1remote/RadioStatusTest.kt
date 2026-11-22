package fr.jlambert.k1remote

import fr.jlambert.k1remote.RadioStatus.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrôles du décodeur 0x09 (contrat v1 figé, § 7). Les trames de référence sont écrites
 * octet par octet, indépendamment du code de l'appli et du firmware.
 */
class RadioStatusTest {

    private fun hex(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Ligne B sélectionnée, réception sur A (double veille), SetNFM NARROWER.
     * A : canal mémoire 3, 446,00625 MHz, LOW 1, FM, N+, écoute seule, liste 3.
     * B : mode fréquence bande 1, 145,500 MHz, HIGH, FM, large, décalage relais, aucune liste.
     */
    private val REF = "09 00 01 01 0a 02 " +
        "00 03 00 31 8d a8 02 01 00 02 01 03 " +
        "01 01 00 f0 03 de 00 07 00 00 04 00 " +
        "00 00"

    private fun ok(r: ByteArray) = (RadioStatus.decode(r) as Result.Ok).snapshot
    private fun invalid(r: ByteArray) = assertTrue(RadioStatus.decode(r) is Result.Invalid)

    @Test fun etat_trame_de_reference_decodee_champ_par_champ() {
        val s = ok(hex(REF))
        assertEquals(1, s.format); assertEquals(1, s.selected)
        assertTrue(s.dualWatch); assertTrue(s.narrowerSetting); assertFalse(s.keyLocked); assertFalse(s.crossBand)
        assertEquals(RadioStatus.Function.RECEIVE, s.function)
        assertEquals(RadioStatus.RxSource.A, s.rxSource)
        val a = s.a!!
        assertEquals(RadioStatus.LineKind.MEMORY, a.kind); assertEquals(3, a.number); assertEquals(446_006_250L, a.rxHz)
        assertEquals(1, a.power); assertEquals(RadioStatus.Modulation.FM, a.modulation); assertEquals(RadioStatus.Width.NARROWER, a.width)
        assertTrue(a.rxOnly); assertFalse(a.txLock); assertEquals(3, a.scanList)
        val b = s.selectedLine!!
        assertEquals(RadioStatus.LineKind.FREQUENCY, b.kind); assertEquals(1, b.number); assertEquals(145_500_000L, b.rxHz)
        assertEquals(7, b.power); assertEquals(RadioStatus.Width.WIDE, b.width); assertTrue(b.hasShift); assertEquals(0, b.scanList)
    }

    @Test fun etat_encodeur_et_decodeur_concordent() {
        val s = ok(hex(REF))
        assertEquals(REF.replace(" ", ""), RadioStatus.encode(s).joinToString("") { String.format("%02x", it) })
    }

    @Test fun etat_frequences_limites() {
        for (hz in listOf(18_000_000L, 1_300_000_000L)) {
            val s = ok(hex(REF)); val r = RadioStatus.encode(s.copy(a = s.a!!.copy(rxHz = hz)))
            assertEquals(hz, ok(r).a!!.rxHz)
        }
    }

    @Test fun etat_valeurs_inconnues_selon_le_type() {
        val r = hex(REF)
        r[7] = 0xFF.toByte(); r[8] = 0xFF.toByte()                       // numéro A : 0xFFFF
        for (k in 9..12) r[k] = 0xFF.toByte()                             // fréquence A : 0xFFFFFFFF
        r[13] = 0xFF.toByte(); r[14] = 0xFF.toByte(); r[15] = 0xFF.toByte(); r[17] = 0xFF.toByte()
        r[5] = 0xFF.toByte(); r[30] = 0xFF.toByte()
        val s = ok(r); val a = s.a!!
        assertNull(a.number); assertNull(a.rxHz); assertNull(a.power); assertNull(a.scanList)
        assertEquals(RadioStatus.Modulation.UNKNOWN, a.modulation); assertEquals(RadioStatus.Width.UNKNOWN, a.width)
        assertEquals(RadioStatus.Function.UNKNOWN, s.function); assertEquals(RadioStatus.RxSource.NONE, s.rxSource)
    }

    @Test fun etat_type_de_ligne_inconnu_bloc_ignore() {
        val r = hex(REF); r[18] = 0xFF.toByte(); r[29] = 0x7F            // le reste du bloc B n'est pas lu
        val s = ok(r); assertNull(s.b); assertEquals(3, s.a!!.number)
    }

    @Test fun etat_veille_etendue_autre_frequence() {
        val r = hex(REF); r[30] = 2
        assertEquals(RadioStatus.RxSource.OTHER_WATCHED, ok(r).rxSource)
    }

    @Test fun etat_liste_toutes_et_liste_24() {
        val r = hex(REF); r[17] = 25; r[29] = 24
        val s = ok(r); assertEquals(RadioMemory.LIST_ALL, s.a!!.scanList); assertEquals(24, s.b!!.scanList)
        r[17] = 26; invalid(r)
    }

    @Test fun etat_longueurs_31_33_et_format_2() {
        invalid(hex(REF).copyOf(31))
        invalid(hex(REF) + byteArrayOf(0))                                // format 1 : exactement 32
        val f2 = hex(REF) + byteArrayOf(0x55); f2[2] = 2
        assertEquals(2, ok(f2).format)                                    // format 2 : 32 premiers octets lus
    }

    @Test fun etat_reponse_d_erreur_courte() {
        assertEquals(Result.Refused(9), RadioStatus.decode(hex("09 09")))  // firmware sans 0x09
        invalid(hex("09"))
        invalid(hex("05 00"))                                             // mauvais opcode
    }

    @Test fun etat_refus_des_valeurs_hors_contrat() {
        hex(REF).also { it[3] = 2; invalid(it) }                          // ligne sélectionnée
        hex(REF).also { it[4] = 0x40; invalid(it) }                       // indicateur réservé
        hex(REF).also { it[16] = 0x11; invalid(it) }                      // drapeau de canal réservé
        hex(REF).also { it[31] = 1; invalid(it) }                         // octet réservé
        hex(REF).also { it[5] = 6; invalid(it) }                          // fonction inconnue
        hex(REF).also { it[13] = 8; invalid(it) }                         // puissance
        hex(REF).also { it[7] = 0; it[8] = 0; invalid(it) }               // canal 0
        hex(REF).also { it[7] = 0x01; it[8] = 0x04; invalid(it) }         // canal 1025
    }

    @Test fun etat_requete_bien_formee() {
        assertEquals("aa 55 08 01 09 00", RadioStatus.REQUEST.joinToString(" ") { String.format("%02x", it) })
    }
}
