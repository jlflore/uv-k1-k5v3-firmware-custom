package fr.jlambert.k1remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Contrôle automatique des fonctions de l'appli qui ne demandent pas de radio branchée.
 * Chaque test porte le nom de la fonction contrôlée ; le rapport de la CI les liste un par un.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FunctionsTest {

    // ------------------------------------------------------------ Journal des appels

    private val T0 = 1_763_800_000_000L

    @Test fun journal_appel_identifie_pendant_la_reception() {
        CallLog.resetForTest()
        CallLog.rxStart(T0); CallLog.caller("Bravo", "A2", T0 + 800); CallLog.rxEnd(T0 + 6_000)
        val e = CallLog.all().single()
        assertEquals("Appel de Bravo (A2)", CallLog.label(e)); assertEquals("6 s", CallLog.duration(e.durMs))
    }

    @Test fun journal_code_recu_juste_apres_la_reception() {
        CallLog.resetForTest()
        CallLog.rxStart(T0); CallLog.rxEnd(T0 + 3_000); CallLog.caller("Bravo", "A2", T0 + 5_000)
        assertEquals("Bravo", CallLog.all().single().name)
    }

    @Test fun journal_ignore_les_ouvertures_breves_du_squelch() {
        CallLog.resetForTest()
        CallLog.rxStart(T0); CallLog.rxEnd(T0 + 150)
        CallLog.txStart(T0 + 10_000); CallLog.txEnd(T0 + 10_200)
        assertEquals(0, CallLog.size())
    }

    @Test fun journal_fusionne_les_reprises_du_meme_appelant() {
        CallLog.resetForTest()
        CallLog.rxStart(T0); CallLog.caller("Bravo", "A2", T0 + 500); CallLog.rxEnd(T0 + 3_000)
        CallLog.rxStart(T0 + 3_800); CallLog.rxEnd(T0 + 7_000)
        assertEquals(1, CallLog.size()); assertEquals(7_000L, CallLog.all().single().durMs)
    }

    @Test fun journal_separe_deux_appelants() {
        CallLog.resetForTest()
        CallLog.rxStart(T0); CallLog.caller("Bravo", "A2", T0 + 500); CallLog.rxEnd(T0 + 3_000)
        CallLog.rxStart(T0 + 3_500); CallLog.caller("Charlie", "A3", T0 + 4_000); CallLog.rxEnd(T0 + 6_000)
        assertEquals(listOf("Bravo", "Charlie"), CallLog.all().map { it.name })
    }

    @Test fun journal_note_mes_emissions() {
        CallLog.resetForTest()
        CallLog.txStart(T0); CallLog.txEnd(T0 + 4_000)
        assertEquals("Vous avez émis", CallLog.label(CallLog.all().single()))
    }

    @Test fun journal_limite_a_300_et_garde_les_plus_recents() {
        CallLog.resetForTest()
        repeat(320) { CallLog.txStart(T0 + it * 10_000L); CallLog.txEnd(T0 + it * 10_000L + 1_000) }
        assertEquals(300, CallLog.size()); assertEquals(T0 + 20 * 10_000L, CallLog.all().first().start)
    }

    @Test fun journal_survit_au_redemarrage() {
        val f = File.createTempFile("appels", ".tsv")
        CallLog.resetForTest(f)
        CallLog.rxStart(T0); CallLog.caller(null, "B7", T0 + 100); CallLog.rxEnd(T0 + 2_000)
        CallLog.resetForTest(f)
        assertEquals("Code B7 (inconnu)", CallLog.label(CallLog.all().single()))
    }

    @Test fun journal_texte_par_jour_et_effacement() {
        CallLog.resetForTest()
        CallLog.txStart(T0); CallLog.txEnd(T0 + 2_000)
        val txt = CallLog.text(T0 + 60_000)
        assertTrue(txt, txt.startsWith("Aujourd'hui") && txt.contains("Vous avez émis · 2 s"))
        CallLog.clear(); assertEquals(0, CallLog.size())
    }

    // ------------------------------------------------------------ Canaux (mémoire de la radio)

    private fun emptyMemory() = RadioMemory(ByteArray(RadioMemory.SIZE) { 0xFF.toByte() }, "F4HWN Fusion PTT r27", 0L, "test")
        .also { m -> for (i in 0 until RadioMemory.CHANNELS) m.deleteChannel(i) }

    @Test fun canal_ecrit_puis_relu_a_l_identique() {
        val m = emptyMemory()
        m.setChannel(4, ChannelEdit(name = "Scorpio", rxHz = 446_006_250, power = 2, narrow = true,
            txTone = "88,5 Hz", rxTone = "D023N", scanLists = setOf(1, 3), shiftDir = 1, shiftHz = 600_000))
        val c = m.channel(4)!!
        assertEquals(5, c.number); assertEquals("Scorpio", c.name); assertEquals(446_006_250L, c.rxHz)
        assertEquals(446_606_250L, c.txHz); assertEquals("LOW 2", c.power); assertTrue(c.narrow)
        assertEquals("88,5 Hz", c.txTone); assertEquals("D023N", c.rxTone); assertEquals(listOf(1, 3), c.scanLists)
        assertFalse(c.listenOnly)
    }

    @Test fun canal_ecoute_seule_garde_ses_listes() {
        val m = emptyMemory()
        m.setChannel(0, ChannelEdit(name = "VHF10", rxHz = 156_500_000, rxOnly = true, scanLists = setOf(2)))
        val c = m.channel(0)!!
        assertTrue(c.rxOnly); assertTrue(c.listenOnly); assertEquals(listOf(2), c.scanLists)
    }

    @Test fun canal_am_toujours_en_ecoute_seule() {
        val m = emptyMemory()
        m.setChannel(0, ChannelEdit(name = "Tour", rxHz = 118_100_000, mode = 1))
        assertTrue(m.channel(0)!!.listenOnly)
    }

    @Test fun canal_nom_sans_accents_sans_perte_de_lettre() {
        assertEquals("Ecoute L1", RadioMemory.radioName("Écoute L1"))
        assertEquals("Equipe 2", RadioMemory.radioName("Équipe 2"))
        assertEquals("Bete noire", RadioMemory.radioName("Bête noire"))
        assertEquals(10, RadioMemory.radioName("Une phrase beaucoup trop longue").length)
    }

    @Test fun canal_suppression() {
        val m = emptyMemory()
        m.setChannel(7, ChannelEdit(name = "X", rxHz = 446_100_000)); m.deleteChannel(7)
        assertNull(m.channel(7))
    }

    @Test fun sauvegarde_ecrite_puis_relue() {
        val m = emptyMemory()
        m.setChannel(0, ChannelEdit(name = "Alpha", rxHz = 446_006_250))
        val out = ByteArrayOutputStream(); m.write(out)
        val back = RadioMemory.load(ByteArrayInputStream(out.toByteArray()))
        assertEquals("Alpha", back.channel(0)!!.name); assertEquals(1, back.channels().size)
    }

    @Test fun envoi_ne_touche_que_les_zones_des_canaux() {
        val base = emptyMemory(); val m = base.copy()
        m.setChannel(10, ChannelEdit(name = "Z", rxHz = 446_150_000, scanLists = setOf(1)))
        val d = m.diff(base)
        assertTrue(d.isNotEmpty())
        for ((addr, bytes) in d) assertTrue("écriture hors zone à $addr",
            RadioMemory.WRITE_ZONES.any { addr in it && addr + bytes.size - 1 in it })
    }

    @Test fun frequences_saisies_en_mhz_khz_hz() {
        assertEquals(446_006_250L, RadioMemory.parseMHz("446,00625"))
        assertEquals(446_006_250L, RadioMemory.parseMHz("446.00625"))
        assertNull(RadioMemory.parseMHz("abc"))
    }

    // ------------------------------------------------------------ Commandes directes (Fusion PTT)

    private fun checksumOk(f: ByteArray): Boolean {
        var x = 0; for (i in 2 until f.size - 1) x = x xor (f[i].toInt() and 0xFF)
        return f[0] == 0xAA.toByte() && f[1] == 0x55.toByte() && x == (f.last().toInt() and 0xFF)
    }

    @Test fun commande_reglage_de_menu() {
        val f = K5ViewerProtocol.setMenu(54, 3)
        assertTrue(checksumOk(f)); assertEquals(listOf(0x01, 54, 3, 0), f.slice(4..7).map { it.toInt() and 0xFF })
    }

    @Test fun commande_frequence() {
        val f = K5ViewerProtocol.setFreq(446_006_250)
        assertTrue(checksumOk(f))
        val v = (f[5].toInt() and 0xFF) or ((f[6].toInt() and 0xFF) shl 8) or ((f[7].toInt() and 0xFF) shl 16) or ((f[8].toInt() and 0xFF) shl 24)
        assertEquals(44_600_625, v)
    }

    @Test fun commande_code_d_appel() {
        val f = K5ViewerProtocol.setUpCode("A1")
        assertTrue(checksumOk(f)); assertEquals("A1", String(f, 5, 2))
    }

    @Test fun commande_ptt() {
        assertEquals((1 xor 0xA5).toByte(), K5ViewerProtocol.ptt(true)[4])
        assertEquals(0xA5.toByte(), K5ViewerProtocol.ptt(false)[4])
    }

    // ------------------------------------------------------------ Configuration d'équipe

    /** Menus refusés par le firmware en commande directe (sauf ChSave 15 permis depuis r27). */
    private val FORBIDDEN = setOf(13, 73, 75, 76, 77, 80)

    private fun menusOf(steps: List<KeyStep>) = steps.mapNotNull { s -> s.cmd?.takeIf { it[4].toInt() == 0x01 }?.let { it[5].toInt() and 0xFF } }

    @Test fun equipe_r27_enregistre_le_canal_en_direct_sans_touches() {
        val t = TeamProfile(channel = 12, tone = "ctcss", toneIdx = 5, txTone = "ctcss", txIdx = 5)
        val steps = t.directSteps(false, "A1", memSaveDirect = true)
        val menus = menusOf(steps)
        assertTrue(15 in menus); assertTrue(menus.none { it in FORBIDDEN })
        assertTrue("aucune touche MENU", steps.none { it.keyId == "MENU" })
        val save = steps.first { s -> s.cmd?.let { it[4].toInt() == 1 && it[5].toInt() == 15 } == true }.cmd!!
        assertEquals(11, save[6].toInt())   // canal 12 → 11 (numérotation interne)
    }

    @Test fun equipe_r26_enregistre_le_canal_au_clavier() {
        val steps = TeamProfile(channel = 3).directSteps(false, null, memSaveDirect = false)
        assertFalse(15 in menusOf(steps))
        assertEquals(listOf("MENU", "1", "5", "MENU", "0", "0", "0", "3", "MENU", "MENU"),
            steps.map { it.keyId }.dropWhile { it != "MENU" }.take(10))
    }

    @Test fun equipe_reglages_contre_les_pertes_de_code() {
        val menus = menusOf(TeamProfile().directSteps(false, "A1", true))
        for (m in listOf(50, 67, 47, 48, 49)) assertTrue("menu $m absent", m in menus)
    }

    @Test fun equipe_controle_des_saisies() {
        assertNull(TeamProfile().problem())
        assertNotNull(TeamProfile(freq = "abc").problem())
        assertNotNull(TeamProfile(channel = 0).problem())
        assertNotNull(TeamProfile(tone = "ctcss", toneIdx = 0).problem())
    }
}
