package fr.jlambert.k1remote

import fr.jlambert.k1remote.ScanControl.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Raccourci Scan 0.77 : décisions de l'appui court et suivi du scan (bug du 23/11). */
class ScanControlTest {

    @Test fun appui_court_toutes_les_combinaisons() {
        for (scanning in listOf(true, false)) for (mode in listOf('M', 'F', null)) {
            val want = when {
                scanning -> Decision.STOP                  // un appui pendant le scan l'arrête, quel que soit le mode
                mode == 'M' -> Decision.START
                mode == 'F' -> Decision.ASK_CHANNEL_MODE
                else -> Decision.LINE_UNKNOWN
            }
            assertEquals("scan=$scanning mode=$mode", want, ScanControl.onShortPress(scanning, mode))
        }
    }

    @Test fun touches_de_lancement() {
        assertEquals(listOf(KeyStep("*", true)), ScanControl.startSteps(false))
        val f = ScanControl.startSteps(true)
        assertEquals(KeyStep("*", true), f.last())
        assertEquals(listOf("EXIT", "F", "3", KeyStep.WAIT), f.dropLast(1).map { it.keyId })
        assertEquals(listOf(KeyStep("EXIT", false)), ScanControl.STOP_STEPS)
    }

    @Test fun appuis_rapides_alternent_lancement_et_arret() {
        // Bug du 23/11 : le second appui renvoyait ✱ long (défilement des listes) au lieu d'arrêter
        val t = ScanTracker()
        var now = 1_000_000L
        val seen = mutableListOf<Decision>()
        repeat(4) {
            val d = ScanControl.onShortPress(t.running, 'M'); seen += d
            if (d == Decision.START) t.onStartSent(now) else t.onStopSent(now)
            // état 0x09 encore ancien (repos) juste après l'appui : ignoré
            t.onState(scanOrSpectrum = false, spectrum = false, quiet = true, now = now + 500)
            now += 800
        }
        assertEquals(listOf(Decision.START, Decision.STOP, Decision.START, Decision.STOP), seen)
    }

    @Test fun suivi_sur_etat_radio() {
        val t = ScanTracker()
        t.onState(true, false, false, 10_000); assertTrue("scan lancé à la radio", t.running)
        t.onState(false, false, false, 11_000); assertTrue("réception pendant le scan : il continue", t.running)
        t.onState(false, false, true, 12_000); assertFalse("repos : scan arrêté à la radio", t.running)
        t.onState(true, false, false, 13_000); assertTrue(t.running)
        t.onState(true, true, false, 14_000); assertFalse("spectre : pas un scan", t.running)
    }

    @Test fun etat_ancien_ignore_apres_arret() {
        val t = ScanTracker()
        t.onState(true, false, false, 10_000)
        t.onStopSent(20_000)
        t.onState(true, false, false, 21_000); assertFalse("état d'avant l'EXIT", t.running)
        t.onState(true, false, false, 23_500); assertTrue("état frais : la radio scanne encore", t.running)
        t.reset(); assertFalse(t.running)
    }

    @Test fun lectures_0x09_manquees_ne_changent_rien() {
        // Revue 0.77 (P1) : l'appli n'appelle onState que sur une réponse fraîche ; sans réponse,
        // le scan lancé reste « en cours » (l'appui suivant l'arrête, il ne renvoie pas ✱ long)
        val t = ScanTracker()
        t.onState(false, false, true, 1_000)          // repos, avant le lancement
        t.onStartSent(2_000)
        // 4 s sans aucune lecture 0x09 valide : aucun appel à onState
        assertEquals(Decision.STOP, ScanControl.onShortPress(t.running, 'M'))
    }
}
