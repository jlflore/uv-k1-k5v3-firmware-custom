package fr.jlambert.k1remote

import fr.jlambert.k1remote.RadioSettings.Result
import fr.jlambert.k1remote.RadioSettings.Value
import fr.jlambert.k1remote.RadioStatus.LineKind
import fr.jlambert.k1remote.RadioStatus.Modulation
import fr.jlambert.k1remote.RadioStatus.Tone
import fr.jlambert.k1remote.RadioStatus.Width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Commande 0x0A (r30, contrat v1.3) : décodage strict des réponses, réunion des deux lectures,
 * comparaison au profil d'équipe et clé de stabilité de la ligne. Règle : rien d'inconnu ne
 * passe pour conforme.
 */
class RadioSettingsTest {

    private fun b(vararg v: Int) = v.map { it.toByte() }.toByteArray()
    private val first = RadioSettings.WHITELIST.take(5)     // 1 40 45 47 48
    private val second = RadioSettings.WHITELIST.drop(5)    // 49 50 54 67 68

    /** Trame de référence : VFO A, pas 9, bip OFF, code « A1 », PTT ID 1, D ST 0. */
    private val refFirst = b(0x0A, 0, 0, 5,
        1, 0, 2, 9, 0,
        40, 0, 2, 0, 0,
        45, 0, 2, 'A'.code, '1'.code,
        47, 0, 2, 1, 0,
        48, 0, 2, 0, 0)
    /** D Prel 60, D Live 1, Sql 3, SetOff 0, SetNFM 0. */
    private val refSecond = b(0x0A, 0, 0, 5,
        49, 0, 2, 60, 0,
        50, 0, 2, 1, 0,
        54, 0, 2, 3, 0,
        67, 0, 2, 0, 0,
        68, 0, 2, 0, 0)

    @Test fun requete_conforme() {
        val r = RadioSettings.request(first)
        // AA 55 08 len 0A n p1..p5 chk
        assertEquals(listOf(0xAA, 0x55, 0x08, 7, 0x0A, 5, 1, 40, 45, 47, 48), r.dropLast(1).map { it.toInt() and 0xFF })
        assertEquals(RadioSettings.WHITELIST.size, 10)
        assertTrue(RadioSettings.WHITELIST.chunked(5).all { it.size <= RadioSettings.MAX_PER_REQUEST })
    }

    @Test fun decodage_trames_de_reference() {
        val a = RadioSettings.decode(refFirst, first) as Result.Ok
        assertEquals(0, a.vfo)
        assertEquals(Value.Num(9), a.entries[0].value)
        assertEquals(Value.Text("A1"), a.entries[2].value)
        val r = RadioSettings.merge(listOf(a, RadioSettings.decode(refSecond, second)), 1L)!!
        assertEquals(10, r.values.size)
        assertEquals(Value.Num(60), r.values[RadioSettings.D_PRE])
    }

    @Test fun code_d_appel_vide_et_seize_caracteres() {
        val empty = RadioSettings.decode(b(0x0A, 0, 1, 1, 45, 0, 0), listOf(45)) as Result.Ok
        assertEquals(Value.Text(""), empty.entries[0].value)
        assertEquals(1, empty.vfo)
        val sixteen = b(0x0A, 0, 0, 1, 45, 0, 16) + ByteArray(16) { '7'.code.toByte() }
        assertEquals(Value.Text("7".repeat(16)), (RadioSettings.decode(sixteen, listOf(45)) as Result.Ok).entries[0].value)
        val tooLong = b(0x0A, 0, 0, 1, 45, 0, 17) + ByteArray(17) { '7'.code.toByte() }
        assertTrue(RadioSettings.decode(tooLong, listOf(45)) is Result.Invalid)
    }

    @Test fun refus_globaux_sur_deux_octets() {
        assertEquals(Result.Refused(4), RadioSettings.decode(b(0x0A, 4), first))   // spectre
        assertEquals(Result.Refused(1), RadioSettings.decode(b(0x0A, 1), first))   // trame mal formée
        assertEquals(Result.Refused(9), RadioSettings.decode(b(0x0A, 9), first))   // firmware < r30
        assertTrue(RadioSettings.decode(b(0x0A, 4, 0), first) is Result.Invalid)   // statut + données
    }

    @Test fun entrees_refusees_sans_valeur() {
        val r = RadioSettings.decode(b(0x0A, 0, 0, 2, 68, 3, 0, 200, 2, 0), listOf(68, 200)) as Result.Ok
        assertNull(r.entries[0].value); assertEquals(3, r.entries[0].status)
        assertNull(r.entries[1].value); assertEquals(2, r.entries[1].status)
        // Refus accompagné d'une valeur : trame incohérente
        assertTrue(RadioSettings.decode(b(0x0A, 0, 0, 1, 68, 3, 2, 0, 0), listOf(68)) is Result.Invalid)
    }

    @Test fun trames_corrompues_rejetees() {
        val bad = mapOf(
            "tronquée" to refFirst.copyOf(refFirst.size - 1),
            "octet en trop" to refFirst + b(0),
            "VFO 2" to refFirst.copyOf().also { it[2] = 2 },
            "nombre d'entrées" to refFirst.copyOf().also { it[3] = 4 },
            "position inattendue" to refFirst.copyOf().also { it[4] = 2 },
            "longueur numérique 1" to b(0x0A, 0, 0, 1, 40, 0, 1, 0),
            "autre code" to refFirst.copyOf().also { it[0] = 0x09 })
        for ((what, raw) in bad) assertTrue(what, RadioSettings.decode(raw, first) is Result.Invalid)
    }

    @Test fun reunion_refusee_si_vfo_change_ou_lecture_manquante() {
        val a = RadioSettings.decode(refFirst, first)
        val bOnB = RadioSettings.decode(refSecond.copyOf().also { it[2] = 1 }, second)
        assertNull(RadioSettings.merge(listOf(a, bOnB), 1L))
        assertNull(RadioSettings.merge(listOf(a, Result.Refused(4)), 1L))
        assertNull(RadioSettings.merge(emptyList(), 1L))
    }

    @Test fun comparaison_inconnu_jamais_conforme() {
        val read = RadioSettings.merge(listOf(RadioSettings.decode(refFirst, first), RadioSettings.decode(refSecond, second)), 1L)!!
        val want = mapOf(RadioSettings.SQL to Value.Num(3), RadioSettings.UPCODE to Value.Text("A1"))
        assertEquals(emptyList<String>(), RadioSettings.differences(want, read))
        assertEquals(listOf("squelch"), RadioSettings.differences(want + (RadioSettings.SQL to Value.Num(4)), read))
        val missing = read.copy(values = read.values - RadioSettings.SQL)
        assertEquals(listOf("squelch"), RadioSettings.differences(want, missing))
        val refused = read.copy(values = read.values + (RadioSettings.SQL to null))
        assertEquals(listOf("squelch"), RadioSettings.differences(want, refused))
    }

    private val line = RadioStatus.Line(LineKind.MEMORY, 12, 446_006_250, 2, Modulation.FM, Width.NARROW,
        rxOnly = false, txLock = false, hasShift = false, reverse = false, scanList = 0,
        rxTone = Tone(1, 885), txTone = Tone(1, 885))
    private val snap = RadioStatus.Snapshot(2, 0, keyLocked = false, dualWatch = false, crossBand = false,
        narrowerSetting = false, stealth = false, screenAsleep = false, function = RadioStatus.Function.IDLE,
        a = line, b = line.copy(number = 3), rxSource = RadioStatus.RxSource.NONE, spectrumActive = false)

    @Test fun cle_stable_ignore_l_activite_radio() {
        val k = RadioSettings.stableKey(snap)
        assertEquals(k, RadioSettings.stableKey(snap.copy(function = RadioStatus.Function.RECEIVE, rxSource = RadioStatus.RxSource.B)))
        assertEquals(k, RadioSettings.stableKey(snap.copy(b = line.copy(number = 99))))       // l'autre ligne
        assertEquals(k, RadioSettings.stableKey(snap.copy(keyLocked = true)))
    }

    @Test fun cle_stable_change_avec_la_configuration() {
        val k = RadioSettings.stableKey(snap)
        val changes = listOf(
            snap.copy(selected = 1), snap.copy(a = line.copy(number = 13)), snap.copy(a = line.copy(rxHz = 446_018_750)),
            snap.copy(a = line.copy(power = 3)), snap.copy(a = line.copy(width = Width.WIDE)),
            snap.copy(a = line.copy(rxTone = Tone(1, 948))), snap.copy(a = line.copy(txTone = Tone(0, 0))),
            snap.copy(a = line.copy(hasShift = true)), snap.copy(a = line.copy(reverse = true)),
            snap.copy(a = line.copy(modulation = Modulation.AM)), snap.copy(a = line.copy(kind = LineKind.FREQUENCY)))
        for (c in changes) assertNotEquals(k, RadioSettings.stableKey(c))
        assertNull(RadioSettings.stableKey(snap.copy(a = null)))
    }

    /** Revue 0.70 : une lecture ne vaut que pour la ligne qui l'a validée, et 45 s au plus. */
    @Test fun lecture_liee_a_la_ligne_et_a_son_age() {
        val r = RadioSettings.Reading(0, emptyMap(), at = 1_000, key = RadioSettings.stableKey(snap))
        assertTrue(RadioSettings.applies(r, snap, now = 1_000))
        assertTrue(RadioSettings.applies(r, snap.copy(function = RadioStatus.Function.RECEIVE), now = 46_000))  // réception : sans effet
        assertTrue(!RadioSettings.applies(r, snap, now = 46_001))                                               // trop ancienne
        assertTrue(!RadioSettings.applies(r, snap.copy(a = line.copy(number = 13)), now = 2_000))               // autre canal
        assertTrue(!RadioSettings.applies(r, snap.copy(selected = 1), now = 2_000))                             // autre ligne
        assertTrue(!RadioSettings.applies(r, null, now = 2_000))                                                // état inconnu
        assertTrue(!RadioSettings.applies(r.copy(key = null), snap, now = 2_000))                               // lecture non validée
    }

    @Test fun sans_code_d_appel_ses_options_ne_sont_pas_attendues() {
        val team = TeamProfile(freq = "446.00625", tone = "ctcss", toneIdx = 9, power = 2, narrow = true, channel = 12)
        assertEquals(6, team.expectedSettings(null).size)
        assertEquals(10, team.expectedSettings("A1").size)
        assertEquals(RadioSettings.WHITELIST.toSet(), team.expectedSettings("A1").keys)
    }

    /**
     * Ce que la configuration d'équipe écrit (op 01 / op 03 dans directSteps) = ce que l'appli
     * s'attend à relire (expectedSettings). Une évolution de l'une sans l'autre fait échouer ce test.
     */
    @Test fun attendus_identiques_aux_reglages_envoyes() {
        val team = TeamProfile(freq = "446.00625", tone = "ctcss", toneIdx = 9, power = 2, narrow = true, channel = 12, squelch = 5, beep = true)
        for (code in listOf(null, "A1")) {
            val written = HashMap<Int, Value>()
            for (st in team.directSteps(fromChannelMode = false, upCode = code, memSaveDirect = true)) {
                val cmd = st.cmd ?: continue
                val d = cmd.copyOfRange(4, cmd.size - 1).map { it.toInt() and 0xFF }
                when (d[0]) {
                    0x01 -> if (d[1] in RadioSettings.WHITELIST) written[d[1]] = Value.Num(d[2] or (d[3] shl 8))
                    0x03 -> written[RadioSettings.UPCODE] = Value.Text(String(d.drop(1).map { it.toChar() }.toCharArray()))
                }
            }
            assertEquals("code $code", written, team.expectedSettings(code))
        }
    }
}
