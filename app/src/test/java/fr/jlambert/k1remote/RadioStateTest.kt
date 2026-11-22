package fr.jlambert.k1remote

import fr.jlambert.k1remote.RadioState.Change
import fr.jlambert.k1remote.RadioState.Outcome
import fr.jlambert.k1remote.RadioState.Support
import fr.jlambert.k1remote.RadioStatus.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Règles de l'appli autour de l'état 0x09 (contrat v1 figé, § 6). */
class RadioStateTest {

    private val ref = (RadioStatus.decode(hex("09 00 01 01 0a 02 00 03 00 31 8d a8 02 01 00 02 01 03 01 01 00 f0 03 de 00 07 00 00 04 00 00 00")) as Result.Ok).snapshot
    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()
    private fun ok(s: RadioStatus.Snapshot = ref) = Result.Ok(s)

    @Test fun detection_reussie_puis_jamais_de_retour_aux_pixels() {
        val st = RadioState()
        assertEquals(Change.SUPPORT_FIRMWARE, st.onResult(ok(), 1000))
        repeat(5) { st.onTimeout() }
        assertEquals(Support.FIRMWARE, st.support)                       // périmé, pas de repli
        assertEquals(Change.NONE, st.onResult(Result.Refused(9), 2000))  // 9 après réussite : ignoré
        assertEquals(Support.FIRMWARE, st.support)
    }

    @Test fun detection_firmware_sans_0x09_statut_9() {
        val st = RadioState()
        assertEquals(Change.SUPPORT_SCREEN, st.onResult(Result.Refused(9), 1000))
        assertEquals(Support.SCREEN, st.support)
    }

    @Test fun detection_trois_delais_depasses_repli_sur_l_ecran() {
        val st = RadioState()
        assertEquals(Change.NONE, st.onTimeout()); assertEquals(Change.NONE, st.onTimeout())
        assertEquals(Change.SUPPORT_SCREEN, st.onTimeout())
    }

    @Test fun spectre_statut_4_radio_occupee_sans_repli() {
        val st = RadioState()
        repeat(5) { st.onResult(Result.Refused(4), 1000L * it) }
        assertEquals(Support.UNKNOWN, st.support)                        // ni échec, ni repli
        assertTrue(st.isBusy(5000)); assertEquals(RadioState.POLL_BUSY_MS, st.nextPollDelay(5000))
    }

    @Test fun trame_invalide_garde_le_dernier_etat() {
        val st = RadioState(); st.onResult(ok(), 1000)
        st.onResult(Result.Invalid("test"), 2000)
        assertEquals(ref, st.snapshot); assertEquals(1000L, st.receivedAt)
    }

    @Test fun fraicheur_et_exception_de_l_emission() {
        val st = RadioState(); st.onResult(ok(), 1000)
        assertTrue(st.isFresh(3999, false)); assertFalse(st.isFresh(4000, false))
        assertTrue(st.isFresh(20_000, transmitting = true))               // émission longue : pas grisé
    }

    @Test fun lectures_espacees_quand_la_radio_ne_repond_plus() {
        val st = RadioState(); st.onResult(ok(), 0)
        assertEquals(1500L, st.nextPollDelay(10_000))
        st.onTimeout(); st.onTimeout(); assertEquals(3000L, st.nextPollDelay(10_000))
        repeat(5) { st.onTimeout() }; assertEquals(RadioState.POLL_MAX_MS, st.nextPollDelay(10_000))
        st.onResult(ok(), 20_000); assertEquals(1500L, st.nextPollDelay(20_000))
    }

    @Test fun lecture_equivalente_a_l_ecran() {
        val st = RadioState(); st.onResult(ok(), 0)
        val r = st.reading()!!
        assertEquals('B', r.mainVfo); assertEquals("HIGH", r.mainPower); assertEquals("LOW1", r.powerA)
        assertEquals('F', r.mainMode); assertEquals('M', r.modeA); assertFalse(r.locked); assertNull(r.dtmf)
    }

    @Test fun commande_confirmee_par_l_etat_suivant() {
        val st = RadioState(); st.onResult(ok(), 0)
        var got: Outcome? = null
        st.expect(RadioState.Pending("P", 100, { it.selectedLine?.power == 3 }) { got = it })
        st.onResult(ok(), 200); assertNull(got)                           // pas encore la bonne valeur
        st.onResult(ok(ref.copy(b = ref.b!!.copy(power = 3))), 900)
        assertEquals(Outcome.CONFIRMED, got)
    }

    @Test fun commande_refusee_seulement_sur_preuve() {
        val st = RadioState(); st.onResult(ok(), 0)
        var got: Outcome? = null
        st.expect(RadioState.Pending("P", 100, { false }) { got = it })
        st.onAck(0); assertNull(got)
        st.onAck(3); assertEquals(Outcome.REFUSED, got)
    }

    @Test fun commande_sans_confirmation_indeterminee_jamais_echec() {
        val st = RadioState(); st.onResult(ok(), 0)
        var got: Outcome? = null
        st.expect(RadioState.Pending("P", 100, { false }) { got = it })
        st.checkTimeout(2099); assertNull(got)
        st.checkTimeout(2100); assertEquals(Outcome.UNDETERMINED, got)
    }

    @Test fun deconnexion_remet_tout_a_zero() {
        val st = RadioState(); st.onResult(ok(), 0)
        st.expect(RadioState.Pending("P", 0, { false }) { })
        st.reset()
        assertEquals(Support.UNKNOWN, st.support); assertNull(st.snapshot); assertNull(st.pending); assertNull(st.reading())
    }
}
