package fr.jlambert.k1remote

import fr.jlambert.k1remote.ShortcutStates.Inputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Témoins des raccourcis : TOUTES les combinaisons d'entrées sont parcourues (furtif ou non,
 * firmware avec ou sans 0x09, spectre, FM…) et comparées à la règle attendue, écrite ici
 * indépendamment du code de l'appli.
 */
class ShortcutStatesTest {

    private val base = (RadioStatus.decode(
        "09 00 01 00 00 00 00 03 00 31 8d a8 02 01 00 01 00 00 01 01 00 f0 03 de 00 07 00 00 00 00 ff 00"
            .split(' ').map { it.toInt(16).toByte() }.toByteArray()) as RadioStatus.Result.Ok).snapshot

    private fun snap(locked: Boolean, deepStealth: Boolean, scanning: Boolean, reverse: Boolean) = base.copy(
        keyLocked = locked, stealth = deepStealth,
        function = if (scanning) RadioStatus.Function.SCAN_OR_SPECTRUM else RadioStatus.Function.IDLE,
        a = base.a!!.copy(reverse = reverse))                     // ligne A sélectionnée

    /** Toutes les entrées possibles : 2×2×2×2 × 3 × (1 + 2⁴) = 816 combinaisons. */
    private fun all(): List<Pair<Inputs, Map<String, Boolean>>> {
        val out = mutableListOf<Pair<Inputs, Map<String, Boolean>>>()
        val bools = listOf(false, true)
        for (dark in bools) for (st in bools) for (pix in bools) for (busy in bools) for (fm in listOf(null, false, true)) {
            val snaps: List<Pair<RadioStatus.Snapshot?, List<Boolean>>> =
                listOf(null to listOf(false, false, false, false)) +
                    bools.flatMap { l -> bools.flatMap { d -> bools.flatMap { sc -> bools.map { rv ->
                        snap(l, d, sc, rv) to listOf(l, d, sc, rv) } } } }
            for ((s, f) in snaps) {
                val (l, d, sc, rv) = f
                val fw = s != null
                val expected = mapOf(
                    "screendark" to dark,
                    "stealth" to (st || (fw && d)),
                    "lock" to (if (fw) l else pix),              // firmware prioritaire sur les pixels
                    "scan" to (fw && sc && !busy),               // le spectre n'est pas un scan
                    "spectrum" to busy,
                    "reverse" to (fw && rv && !busy),
                    "fm" to (fm == true && !busy))
                out += Inputs(dark, st, pix, s, busy, fm) to expected
            }
        }
        return out
    }

    @Test fun temoins_toutes_les_combinaisons() {
        val cases = all()
        assertEquals(816, cases.size)
        var checked = 0
        for ((inp, exp) in cases) for (a in ShortcutStates.TOGGLES) {
            assertEquals("$a avec $inp", exp.getValue(a), ShortcutStates.active(a, inp)); checked++
        }
        assertEquals(816 * 7, checked)
    }

    @Test fun temoins_actions_ordinaires_jamais_allumees() {
        for ((inp, _) in all()) for (a in listOf("ab", "power", "call", "beepoff", "inconnue"))
            assertFalse(ShortcutStates.active(a, inp))
    }

    @Test fun temoin_furtif_de_l_appli_meme_sans_furtif_profond() {
        // Le bug signalé en 0.64 : furtif par réglages de menus, indicateur du firmware à 0
        assertTrue(ShortcutStates.active("stealth", Inputs(appStealth = true, snapshot = snap(false, false, false, false))))
    }

    @Test fun temoins_independants_en_furtif() {
        // En furtif, chaque autre fonction garde son propre témoin
        val s = snap(locked = true, deepStealth = false, scanning = true, reverse = true)
        val i = Inputs(appScreenDark = true, appStealth = true, snapshot = s, fmScreen = true)
        for (a in ShortcutStates.TOGGLES.filter { it != "spectrum" }) assertTrue(a, ShortcutStates.active(a, i))
        assertFalse(ShortcutStates.active("spectrum", i))
    }

    @Test fun temoins_spectre_eteint_scan_inversion_et_fm() {
        val i = Inputs(snapshot = snap(false, false, true, true), busy = true, fmScreen = true)
        assertTrue(ShortcutStates.active("spectrum", i))
        for (a in listOf("scan", "reverse", "fm")) assertFalse(a, ShortcutStates.active(a, i))
    }
}
