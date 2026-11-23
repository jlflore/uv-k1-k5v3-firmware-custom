package fr.jlambert.k1remote

import fr.jlambert.k1remote.RadioStatus.LineKind
import fr.jlambert.k1remote.RadioStatus.Modulation
import fr.jlambert.k1remote.RadioStatus.Tone
import fr.jlambert.k1remote.RadioStatus.Width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Témoin « réglages d'équipe identiques » : il ne s'allume que si TOUT correspond.
 * Chaque champ est faussé isolément, puis rendu inconnu : le témoin doit s'éteindre à chaque fois.
 */
class TeamMatchTest {

    /** Équipe : 446,00625 MHz, RX CTCSS 88,5 Hz (n° 9), TX DCS D245 inversé, LOW 2, étroite, canal 12. */
    private val team = TeamProfile(freq = "446.00625", tone = "ctcss", toneIdx = 9, power = 2, narrow = true,
        channel = 12, txTone = "dcs", txIdx = 105 + 40)                 // 105 + indice 40 → code 0xA5 = D245 I

    private val txWanted = TeamProfile.expectedTone("dcs", 105 + 40)!!

    private val good = RadioStatus.Line(LineKind.MEMORY, 12, 446_006_250, 2, Modulation.FM, Width.NARROW,
        rxOnly = false, txLock = false, hasShift = false, reverse = false, scanList = 0,
        rxTone = Tone(1, 885), txTone = txWanted)

    @Test fun equipe_tout_identique_temoin_allume() {
        assertEquals(Tone(3, 0xA5), txWanted)
        assertTrue(team.matches(good))
        assertTrue(team.matches(good.copy(kind = LineKind.FREQUENCY, number = 1)))   // laissé en mode fréquence
    }

    @Test fun equipe_chaque_difference_eteint_le_temoin() {
        val variants = mapOf(
            "autre canal" to good.copy(number = 13),
            "canal météo" to good.copy(kind = LineKind.WEATHER),
            "fréquence" to good.copy(rxHz = 446_018_750),
            "puissance" to good.copy(power = 3),
            "largeur" to good.copy(width = Width.WIDE),
            "largeur N+" to good.copy(width = Width.NARROWER),
            "décalage relais" to good.copy(hasShift = true),
            "inversion" to good.copy(reverse = true),
            "CTCSS RX" to good.copy(rxTone = Tone(1, 948)),
            "RX sans sous-canal" to good.copy(rxTone = Tone(0, 0)),
            "TX polarité" to good.copy(txTone = Tone(2, 0xA5)),
            "TX code" to good.copy(txTone = Tone(3, 0xA9)))
        for ((what, l) in variants) assertFalse(what, team.matches(l))
    }

    @Test fun equipe_donnee_inconnue_jamais_de_temoin() {
        val unknown = mapOf(
            "ligne absente" to null,
            "fréquence inconnue" to good.copy(rxHz = null),
            "puissance inconnue" to good.copy(power = null),
            "largeur inconnue" to good.copy(width = Width.UNKNOWN),
            "format 1 (pas de sous-canaux)" to good.copy(rxTone = null, txTone = null),
            "valeur RX inconnue" to good.copy(rxTone = Tone(1, null)),
            "valeur TX inconnue" to good.copy(txTone = Tone(3, null)))
        for ((what, l) in unknown) assertFalse(what, team.matches(l))
    }

    @Test fun equipe_profil_incomplet_jamais_de_temoin() {
        assertFalse(team.copy(freq = "abc").matches(good))
        assertFalse(team.copy(tone = "ctcss", toneIdx = 0).matches(good))
        assertFalse(team.copy(txTone = "dcs", txIdx = 300).matches(good))
    }

    @Test fun equipe_sans_sous_canal() {
        val t = team.copy(tone = "none", toneIdx = 0, txTone = "none", txIdx = 0)
        assertTrue(t.matches(good.copy(rxTone = Tone(0, 0), txTone = Tone(0, 0))))
        assertFalse(t.matches(good))
    }
}
