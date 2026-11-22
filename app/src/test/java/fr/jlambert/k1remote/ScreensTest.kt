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
        File(out, "index.html").writeText(buildString {
            append("<!doctype html><meta charset=utf-8><title>K1 Remote — captures</title>")
            append("<style>body{font:14px sans-serif;background:#eee}figure{display:inline-block;margin:8px;vertical-align:top}img{width:270px;border:1px solid #999}figure.l img{width:585px}</style>")
            for ((n, l) in shots) append("<figure class='${if (n.endsWith("paysage")) "l" else "p"}'><img src='$n.png'><figcaption>$l</figcaption></figure>")
        })
    }
}
