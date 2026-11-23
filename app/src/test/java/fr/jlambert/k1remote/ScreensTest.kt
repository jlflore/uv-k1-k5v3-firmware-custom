package fr.jlambert.k1remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Captures d'écran de référence (aucune radio : la liaison n'est jamais ouverte).
 * Chaque thème × interface × orientation est dessiné sur un téléphone de 360 × 780 dp
 * (xxhdpi), puis enregistré dans app/build/screenshots, avec une page index.html.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreensTest {

    private val out = File("build/screenshots").apply { mkdirs() }
    private val shots = mutableListOf<Pair<String, String>>()

    private fun prefs(theme: ThemeId, night: Boolean, uiMode: Int) {
        val sp = RuntimeEnvironment.getApplication().getSharedPreferences("k1remote", Context.MODE_PRIVATE)
        sp.edit().clear()
            .putString("dayTheme", theme.name)
            .putBoolean("night", night)
            .putInt("uiMode", uiMode)
            .putBoolean("remotePtt", true)
            .putBoolean("autoNight", false)
            .commit()
    }

    private fun capture(name: String, label: String, landscape: Boolean) {
        RuntimeEnvironment.setQualifiers(if (landscape) "w780dp-h360dp-land-xxhdpi" else "w360dp-h780dp-port-xxhdpi")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val root: View = controller.get().window.decorView
        val dm = controller.get().resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shots += name to label
        controller.pause().stop().destroy()
    }

    /** Liste des canaux à partir d'une mémoire d'exemple (cas : écoute seule + listes). */
    private fun captureChannels(name: String, label: String) {
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-port-xxhdpi")
        val ctx = RuntimeEnvironment.getApplication()
        val mem = RadioMemory(ByteArray(RadioMemory.SIZE) { 0xFF.toByte() }, "F4HWN Fusion PTT r27", 0L, "Exemple")
        for (i in 0 until RadioMemory.CHANNELS) mem.deleteChannel(i)
        mem.setChannel(0, ChannelEdit(name = "Scorpio", rxHz = 446_006_250, scanList = 1))
        mem.setChannel(1, ChannelEdit(name = "PMR 2", rxHz = 446_018_750))
        mem.setChannel(2, ChannelEdit(name = "Écoute L3", rxHz = 446_031_250, rxOnly = true, scanList = 3))
        mem.setChannel(3, ChannelEdit(name = "VHF10", rxHz = 156_500_000, narrow = false, stepIdx = 5, rxOnly = true))
        mem.setChannel(4, ChannelEdit(name = "Tour Bron", rxHz = 118_100_000, mode = 1, narrow = false, stepIdx = 6, rxOnly = true, scanList = RadioMemory.LIST_ALL))
        "PMR".forEachIndexed { k, ch -> mem.image[0x880E + 2 * 4 + k] = ch.code.toByte() }   // liste 3 nommée « PMR »
        mem.image[0x880E + 2 * 4 + 3] = 0
        val f = File(RadioMemory.dir(ctx), "exemple.k1r").apply { outputStream().use { mem.write(it) } }
        val controller = Robolectric.buildActivity(ChannelsActivity::class.java, ChannelsActivity.intent(ctx, f)).setup()
        val act = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        val dm = act.resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val root = act.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h); root.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shots += name to label
        controller.pause().stop().destroy()
    }

    /** Journal des appels avec quelques appels d'exemple (aujourd'hui et hier). */
    private fun captureCallLog(name: String, label: String) {
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-port-xxhdpi")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = controller.get()
        CallLog.resetForTest()
        val now = System.currentTimeMillis()
        val y = now - 86_400_000L
        CallLog.rxStart(y); CallLog.caller("Charlie", "A3", y + 500); CallLog.rxEnd(y + 12_000)
        CallLog.txStart(now - 600_000); CallLog.txEnd(now - 596_000)
        CallLog.rxStart(now - 400_000); CallLog.caller("Bravo", "A2", now - 399_000); CallLog.rxEnd(now - 393_000)
        CallLog.rxStart(now - 200_000); CallLog.rxEnd(now - 197_000)
        CallLog.rxStart(now - 100_000); CallLog.caller(null, "B7", now - 99_500); CallLog.rxEnd(now - 95_000)
        CallLog.txStart(now - 60_000); CallLog.txEnd(now - 52_000)
        CallLogDialog.show(act, {}, {})
        shadowOf(Looper.getMainLooper()).idle()
        val dm = act.resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val root = act.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h); root.draw(c)
        c.drawColor(android.graphics.Color.argb(110, 0, 0, 0))
        val dlg = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val dv = dlg.window!!.decorView
        val dw = (w * 0.92).toInt()
        dv.measure(View.MeasureSpec.makeMeasureSpec(dw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
        dv.layout(0, 0, dw, dv.measuredHeight)
        c.save(); c.translate((w - dw) / 2f, (h - dv.measuredHeight) / 2f); dv.draw(c); c.restore()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shots += name to label
        dlg.dismiss()
        CallLog.resetForTest()
        controller.pause().stop().destroy()
    }

    /** Carte de réussite par-dessus l'écran principal (animation terminée). */
    private fun captureSuccess(name: String, label: String) = captureDialog(name, label) { act ->
        SuccessCard.show(act, "Équipe configurée", "« Équipe » est enregistrée dans le canal 1.\nVotre code d'appel A1 part au début de chaque émission.")
    }

    /** Fenêtres de choix (0.69) : canaux de la radio, bibliothèque, sauvegardes, équipe déjà réglée. */
    private fun captureSheets(tag: String, label: String) {
        captureDialog("choix_canaux_$tag", "Canaux de la radio · $label") { act ->
            ChoiceSheet.show(act, "Canaux de la radio",
                "Lecture seule : rien n'est modifié dans la radio. L'écran et le PTT de l'appli sont suspendus le temps de la lecture.",
                listOf(ChoiceSheet.Option("⇩", "Lire la radio", "Lit les 1 024 canaux et les enregistre dans une nouvelle sauvegarde. Quelques secondes par câble.", primary = true) {},
                    ChoiceSheet.Option("▤", "Dernière sauvegarde", "Airsoft · 22 nov. 2026 à 18:40 · 37 canaux\nSans relire la radio") {}), "Annuler")
        }
        captureDialog("choix_bibliotheque_$tag", "Bibliothèque · $label") { act ->
            ChoiceSheet.show(act, "Bibliothèque",
                "Listes de fréquences toutes prêtes. Touchez-en une pour voir ses canaux avant de l'ajouter au brouillon : rien n'est écrasé.",
                ChannelLibrary.PRESETS.map { pr -> ChoiceSheet.Option("+", pr.title, "${pr.channels.size} canaux · ${pr.source}") {} })
        }
        captureDialog("choix_sauvegardes_$tag", "Sauvegardes · $label") { act ->
            ChoiceSheet.show(act, "Sauvegardes", "Touchez une sauvegarde pour l'ouvrir, la nommer ou la supprimer. La plus récente est en haut.", listOf(
                ChoiceSheet.Option("▤", "Airsoft", "Dim. 22 nov. 2026 · 18:40 · 37 canaux", badge = "OUVERTE") {},
                ChoiceSheet.Option("▤", "Sam. 21 nov. 2026 · 09:12", "35 canaux") {},
                ChoiceSheet.Option("▤", "Écoute aviation", "Mar. 10 nov. 2026 · 20:05 · 112 canaux") {},
                ChoiceSheet.Option("!", "Lun. 2 nov. 2026 · 14:30", "fichier illisible") {}))
        }
        captureDialog("choix_sauvegarde_actions_$tag", "Actions sur une sauvegarde · $label") { act ->
            ChoiceSheet.show(act, "Écoute aviation", "112 canaux · 10 nov. 2026 à 20:05", listOf(
                ChoiceSheet.Option("▶", "Ouvrir", "Afficher ses canaux", primary = true) {},
                ChoiceSheet.Option("✎", "Nommer", "Ex. Airsoft, Écoute aviation…") {},
                ChoiceSheet.Option("✕", "Supprimer", "Efface le fichier du téléphone, la radio n'est pas concernée", danger = true) {}), "Retour")
        }
        captureDialog("choix_equipe_$tag", "Équipe déjà réglée · $label") { act ->
            ChoiceSheet.show(act, "Cette radio a déjà les réglages de l'équipe",
                "« Scorpio » : fréquence, sous-canaux, puissance et largeur sont déjà les bons.",
                listOf(ChoiceSheet.Option("↻", "Renvoyer quand même", "Réécrit aussi le canal 1 et le code d'appel, si l'un d'eux a été modifié à la main") {}),
                "Annuler")
        }
    }

    private fun captureDialog(name: String, label: String, show: (MainActivity) -> Unit) {
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-port-xxhdpi")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = controller.get()
        show(act)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
        val dm = act.resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val root = act.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        root.draw(c)
        c.drawColor(android.graphics.Color.argb(110, 0, 0, 0))
        val dlg = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val dv = dlg.window!!.decorView
        dv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
        dv.layout(0, 0, w, dv.measuredHeight)
        c.save(); c.translate(0f, (h - dv.measuredHeight) / 2f); dv.draw(c); c.restore()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shots += name to label
        dlg.dismiss()
        controller.pause().stop().destroy()
    }

    /** Préférences en entier (la page défilante est dessinée sur toute sa hauteur). */
    private fun capturePrefs(name: String, label: String) {
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-port-xxhdpi")
        val controller = Robolectric.buildActivity(PreferencesActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val content = controller.get().findViewById<android.view.ViewGroup>(android.R.id.content)
        val scroll = content.getChildAt(0) as android.view.ViewGroup
        val page = scroll.getChildAt(0)
        val w = controller.get().resources.displayMetrics.widthPixels
        page.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        page.layout(0, 0, w, page.measuredHeight)
        val bmp = Bitmap.createBitmap(w, page.measuredHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        scroll.background?.let { it.setBounds(0, 0, w, page.measuredHeight); it.draw(c) }
        page.draw(c)
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shots += name to label
        controller.pause().stop().destroy()
    }

    @Test
    fun allScreens() {
        val themes = ThemeId.values().filter { it != ThemeId.NUIT }
        for (t in themes) for (ui in 0..1) for (land in listOf(false, true)) {
            prefs(t, false, ui)
            capture("${t.name.lowercase()}_${if (ui == 0) "complete" else "operation"}_${if (land) "paysage" else "portrait"}",
                "${t.label} · ${if (ui == 0) "complète" else "en opération"} · ${if (land) "paysage" else "portrait"}", land)
        }
        for (land in listOf(false, true)) {
            prefs(ThemeId.CIEL, true, 0)
            capture("nuit_complete_${if (land) "paysage" else "portrait"}", "Nuit · complète · ${if (land) "paysage" else "portrait"}", land)
        }
        // Témoins des raccourcis : les 7 fonctions qui restent actives, allumées (0.65)
        for ((t, night, ui) in listOf(Triple(ThemeId.CIEL, false, 0), Triple(ThemeId.OTAN, false, 0),
                Triple(ThemeId.CIEL, true, 0), Triple(ThemeId.CIEL, false, 1), Triple(ThemeId.CIEL, true, 1))) {
            prefs(t, night, ui)
            val pr = Prefs(RuntimeEnvironment.getApplication())
            pr.screenDarkOffered = true
            Shortcut.save(pr, listOf(
                Shortcut(1, "Écran éteint", "action", "screendark"), Shortcut(2, "Furtif", "action", "stealth"),
                Shortcut(3, "Verrou", "action", "lock"), Shortcut(4, "Scan", "action", "scan"),
                Shortcut(5, "Spectre", "action", "spectrum"), Shortcut(6, "Inv. relais", "action", "reverse"),
                Shortcut(7, "Radio FM", "action", "fm"), Shortcut(8, "A ⇄ B", "action", "ab"),
                Shortcut(9, "Scorpio", "team", team = TeamProfile())))
            MainActivity.captureActive = ShortcutStates.TOGGLES.toSet() + "team"
            val tag = (if (night) "nuit" else t.name.lowercase()) + (if (ui == 0) "_complete" else "_operation")
            capture("temoins_$tag", "Témoins allumés · ${if (night) "Nuit (furtif)" else t.label} · ${if (ui == 0) "complète" else "en opération"}", false)
            MainActivity.captureActive = null
        }
        for ((t, night) in listOf(ThemeId.CIEL to false, ThemeId.FRANCE to false, ThemeId.CIEL to true)) {
            prefs(t, night, 0)
            captureCallLog("appels_${if (night) "nuit" else t.name.lowercase()}", "Journal des appels · ${if (night) "Nuit" else t.label}")
        }
        for (t in listOf(ThemeId.CIEL, ThemeId.FRANCE)) {
            prefs(t, false, 0)
            captureChannels("canaux_${t.name.lowercase()}", "Canaux · ${t.label}")
        }
        for ((t, night) in listOf(ThemeId.CIEL to false, ThemeId.FRANCE to false, ThemeId.CIEL to true)) {
            prefs(t, night, 0)
            captureSuccess("reussite_${if (night) "nuit" else t.name.lowercase()}", "Réussite · ${if (night) "Nuit" else t.label}")
        }
        for ((t, night) in listOf(ThemeId.CIEL to false, ThemeId.OTAN to false, ThemeId.CIEL to true)) {
            prefs(t, night, 0)
            captureSheets(if (night) "nuit" else t.name.lowercase(), if (night) "Nuit" else t.label)
        }
        for (t in listOf(ThemeId.CIEL, ThemeId.FRANCE)) {
            prefs(t, false, 0)
            capturePrefs("preferences_${t.name.lowercase()}", "Préférences · ${t.label}")
        }
        File(out, "index.html").writeText(buildString {
            append("<!doctype html><meta charset=utf-8><title>K1 Remote — captures</title>")
            append("<style>body{font:14px sans-serif;background:#eee}figure{display:inline-block;margin:8px;vertical-align:top}img{width:270px;border:1px solid #999}figure.l img{width:585px}</style>")
            for ((n, l) in shots) append("<figure class='${if (n.endsWith("paysage")) "l" else "p"}'><img src='$n.png'><figcaption>$l</figcaption></figure>")
        })
    }
}
