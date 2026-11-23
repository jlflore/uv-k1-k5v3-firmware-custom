package fr.jlambert.k1remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Éclairage de la radio : toutes les combinaisons furtif × écran éteint × réglages du furtif
 * × réglages normaux, puis toutes les suites d'appuis sur Furtif / Écran éteint (0.66).
 */
class LightPlanTest {

    private val days = listOf(LightPlan.Day(3, 61, 10, false), LightPlan.Day(2, 6, 5, true), LightPlan.Day(0, 0, 1, false))

    private fun plan(stealth: Boolean, dark: Boolean, light: Int, inv: Boolean, day: LightPlan.Day) =
        LightPlan.target(stealth, dark, light, inv, day).toMap()

    @Test fun eclairage_toutes_les_combinaisons() {
        var n = 0
        for (stealth in listOf(false, true)) for (dark in listOf(false, true)) for (light in 0..2)
            for (inv in listOf(false, true)) for (day in days) {
                val t = plan(stealth, dark, light, inv, day); n++
                val ctx = "furtif=$stealth éteint=$dark lumière=$light inv=$inv jour=$day"
                // Règle attendue, écrite indépendamment
                assertEquals(ctx, if (stealth || dark) "0" else day.blTxRx.toString(), t[Shortcut.MENU_BLTXRX])
                assertEquals(ctx, if (dark || (stealth && light >= 1)) "00" else Shortcut.pad2(day.blTime.toString()), t[Shortcut.MENU_BLTIME])
                assertEquals(ctx, if (stealth && light == 0) "01" else Shortcut.pad2(day.blMax.toString()), t[Shortcut.MENU_BLMAX])
                assertEquals(ctx, if ((stealth && inv) || day.inv) "1" else "0", t[Shortcut.MENU_SETINV])
            }
        assertEquals(2 * 2 * 3 * 2 * 3, n)
    }

    /** Simule la radio : on applique chaque appui comme le fait l'appli, puis on vérifie l'état. */
    private class Radio(val light: Int, val inv: Boolean, val day: LightPlan.Day) {
        var stealth = false; var dark = false
        val menus = LightPlan.target(false, false, light, inv, day).toMap().toMutableMap()
        fun pressStealth() { stealth = !stealth; menus.putAll(LightPlan.target(stealth, dark, light, inv, day)) }
        fun pressDark() {
            dark = !dark
            menus.putAll(LightPlan.target(stealth, dark, light, inv, day)
                .filter { it.first == Shortcut.MENU_BLTXRX || it.first == Shortcut.MENU_BLTIME })
        }
        fun check(ctx: String) = assertEquals(ctx, LightPlan.target(stealth, dark, light, inv, day).toMap(), menus.toMap())
    }

    @Test fun eclairage_le_scenario_signale_ecran_eteint_puis_furtif_aller_retour() {
        for (light in 0..2) for (day in days) {
            val r = Radio(light, false, day)
            r.pressDark(); r.pressStealth(); r.pressStealth()           // éteint → furtif → sortie du furtif
            assertEquals("l'écran doit rester éteint", "0", r.menus[Shortcut.MENU_BLTXRX])
            assertEquals("l'écran doit rester éteint", "00", r.menus[Shortcut.MENU_BLTIME])
        }
    }

    @Test fun eclairage_toutes_les_suites_de_quatre_appuis() {
        // 2⁴ suites de 4 appuis (Furtif ou Écran éteint), pour chaque réglage : la radio reste toujours
        // dans l'état voulu par la combinaison courante, quel que soit l'ordre des appuis
        for (light in 0..2) for (inv in listOf(false, true)) for (day in days) for (seq in 0 until 16) {
            val r = Radio(light, inv, day)
            for (k in 0 until 4) {
                if (seq shr k and 1 == 1) r.pressStealth() else r.pressDark()
                r.check("lumière=$light inv=$inv jour=$day suite=$seq appui=$k")
            }
        }
    }

    /** 0.72 (r31) : témoin « Écran éteint » déduit de la radio ; inconnu ou ambigu = null. */
    @Test fun ecran_eteint_lu_dans_la_radio() {
        assertEquals(true, LightPlan.screenDarkFromRadio(0, 0, stealth = false))
        assertEquals(false, LightPlan.screenDarkFromRadio(12, 0, stealth = false))
        assertEquals(false, LightPlan.screenDarkFromRadio(0, 3, stealth = false))
        assertEquals(null, LightPlan.screenDarkFromRadio(0, 0, stealth = true))      // furtif : mêmes valeurs possibles
        assertEquals(null, LightPlan.screenDarkFromRadio(null, 0, stealth = false))
        assertEquals(null, LightPlan.screenDarkFromRadio(0, null, stealth = false))
        // Cohérence avec ce que LightPlan envoie pour « Écran éteint » (hors furtif)
        val day = LightPlan.Day(blTxRx = 3, blTime = 12, blMax = 10, inv = false)
        fun read(dark: Boolean) = LightPlan.target(false, dark, 1, false, day).toMap().let {
            LightPlan.screenDarkFromRadio(it.getValue(Shortcut.MENU_BLTIME).toInt(), it.getValue(Shortcut.MENU_BLTXRX).toInt(), false)
        }
        assertEquals(true, read(true)); assertEquals(false, read(false))
        assertEquals(RadioSettings.BL_TIME, Shortcut.MENU_BLTIME); assertEquals(RadioSettings.BL_TXRX, Shortcut.MENU_BLTXRX)
    }
}
