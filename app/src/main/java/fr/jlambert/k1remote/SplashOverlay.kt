package fr.jlambert.k1remote

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Écran d'ouverture (0.80, idée de D'jé) : une photo tirée au hasard, le nom en pochoir, et
 * l'état de la liaison pendant que l'appli se connecte et lit la radio en dessous.
 *
 * Règles de robustesse :
 *  - il ne bloque rien : il est posé AU-DESSUS de l'interface (sur la fenêtre), qui se construit
 *    et se connecte normalement ; un toucher le ferme tout de suite ;
 *  - 2 s, puis fondu ; seulement au lancement de l'appli (pas au retour d'une autre appli) ;
 *  - portrait comme paysage : la photo est recadrée autour de son point d'intérêt, à chaque
 *    changement de taille ; une photo illisible est simplement remplacée par un fond uni.
 */
class SplashOverlay(context: Context, private val status: () -> String) : View(context) {

    /**
     * Photo, et point d'intérêt (0..1) à garder dans le cadre, en portrait et en paysage.
     * [land] null : photo trop haute pour le paysage (visages coupés), jamais tirée en paysage.
     * Points vérifiés sur des recadrages réels (retour de D'jé : paysage mal découpé).
     */
    private class Shot(val file: String, val port: Pair<Float, Float>, val land: Pair<Float, Float>?,
                       /** Titre en haut (ciel libre) plutôt qu'en bas, où il cacherait les personnages. */
                       val topPort: Boolean = false, val topLand: Boolean = false)

    companion object {
        private val SHOTS = listOf(
            Shot("splash/glacier.jpg", 0.60f to 0.60f, 0.50f to 0.70f, topPort = true),
            Shot("splash/montee-soleil.jpg", 0.52f to 0.50f, 0.50f to 0.50f, topPort = true),
            Shot("splash/montee-seracs.jpg", 0.38f to 0.50f, null, topPort = true),   // paysage : alpiniste au centre, sous le texte
            Shot("splash/ski.jpg", 0.50f to 0.50f, 0.50f to 0.55f),
            Shot("splash/foret.jpg", 0.55f to 0.40f, null, topPort = true),
            Shot("splash/tireur.jpg", 0.55f to 0.40f, 0.55f to 0.20f, topLand = true),
        )
        const val SHOW_MS = 2_000L
        /** Faux pendant les captures automatiques (sinon il recouvrirait tous les écrans). */
        @JvmStatic var active = true
        /** Captures : photo imposée (index dans SHOTS) ; null = au hasard. */
        @JvmStatic var forcedShot: Int? = null
        private const val FADE_MS = 350L

        /** Pose l'écran d'ouverture sur la fenêtre de [act]. */
        fun show(act: Activity, status: () -> String): SplashOverlay {
            val v = SplashOverlay(act, status)
            (act.window.decorView as ViewGroup).addView(v, FrameLayout.LayoutParams(-1, -1))
            v.postDelayed({ v.close() }, SHOW_MS)
            return v
        }
    }

    private val d = resources.displayMetrics.density
    private val startLandscape = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
    private val shot = forcedShot?.let { SHOTS.getOrNull(it) } ?: SHOTS.filter { !startLandscape || it.land != null }.random()
    private val photo: Bitmap? = runCatching {
        // Décodage réduit : jamais plus grand que l'écran (mémoire)
        val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
        val screen = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        context.assets.open(shot.file).use { s ->
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(shot.file).use { BitmapFactory.decodeStream(it, null, probe) }
            while (maxOf(probe.outWidth, probe.outHeight) / (opts.inSampleSize * 2) >= screen) opts.inSampleSize *= 2
            BitmapFactory.decodeStream(s, null, opts)
        }
    }.getOrNull()
    private val matrix = Matrix()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint()
    private val topShade = Paint()
    // Ombre portée : texte lisible même sur la neige
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(245, 245, 240); typeface = Fonts.stencil(context)
        setShadowLayer(6 * resources.displayMetrics.density, 0f, 1.5f * resources.displayMetrics.density, Color.argb(160, 0, 0, 0))
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.stencilLight(context)
        setShadowLayer(4 * resources.displayMetrics.density, 0f, 1f * resources.displayMetrics.density, Color.argb(170, 0, 0, 0))
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = SystemClock.uptimeMillis()
    private var closing = false
    private var top = false

    init {
        isClickable = true
        setOnClickListener { close() }
        contentDescription = "UV-Kx OPS. Toucher pour continuer."
    }

    fun close() {
        if (closing) return
        closing = true
        animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            photo?.recycle()
        }.start()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val b = photo ?: return
        val focus = if (w > h) shot.land else shot.port
        if (focus == null) { close(); return }          // tournée en paysage avec une photo portrait
        val (fx, fy) = focus
        // Recadrage « remplir » autour du point d'intérêt
        val scale = maxOf(w / b.width.toFloat(), h / b.height.toFloat())
        val sw = b.width * scale; val sh = b.height * scale
        val dx = (w / 2f - fx * sw).coerceIn(w - sw, 0f)
        val dy = (h / 2f - fy * sh).coerceIn(h - sh, 0f)
        matrix.setScale(scale, scale); matrix.postTranslate(dx, dy)
        val ink = Color.rgb(8, 12, 10)
        fun a(x: Int) = (ink and 0x00FFFFFF) or (x shl 24)
        top = if (w > h) shot.topLand else shot.topPort
        if (top) {
            // Titre en haut : voile en haut, et un voile léger en bas pour la barre d'état
            topShade.shader = LinearGradient(0f, 0f, 0f, h * 0.50f, intArrayOf(a(0xE0), a(0x90), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            shade.shader = LinearGradient(0f, h * 0.78f, 0f, h.toFloat(), Color.TRANSPARENT, a(0xC8), Shader.TileMode.CLAMP)
        } else {
            shade.shader = LinearGradient(0f, h * 0.42f, 0f, h.toFloat(), intArrayOf(Color.TRANSPARENT, a(0xB0), a(0xF0)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            topShade.shader = LinearGradient(0f, 0f, 0f, h * 0.16f, a(0x70), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val landscape = w > h
        val b = photo
        if (b != null) c.drawBitmap(b, matrix, bmpPaint) else c.drawColor(Color.rgb(28, 36, 30))
        c.drawRect(0f, 0f, w, h, shade)
        c.drawRect(0f, 0f, w, h * (if (top) 0.50f else 0.16f), topShade)

        val m = (if (landscape) 40 else 28) * d
        // Bloc de texte en bas à gauche, mêmes proportions en portrait et en paysage
        title.textSize = (if (landscape) 52 else 46) * d
        val maxTitle = w - 2 * m
        if (title.measureText("UV-Kx OPS") > maxTitle) title.textSize *= maxTitle / title.measureText("UV-Kx OPS")
        val bottom = h - (if (landscape) 34 else 48) * d
        val barY = bottom - 30 * d
        val kickerY: Float; val titleY: Float; val byY: Float
        if (top) {
            // Sous la barre d'état du téléphone
            kickerY = (if (landscape) 44 else 72) * d
            titleY = kickerY + title.textSize + 2 * d
            byY = titleY + 30 * d
        } else {
            byY = barY - (if (landscape) 26 else 34) * d
            titleY = byY - 26 * d
            kickerY = titleY - title.textSize - 4 * d
        }

        small.textSize = 14 * d
        small.color = Color.rgb(240, 170, 60)
        c.drawText("FUSION PTT · K1 / K5 V3", m, kickerY, small)
        c.drawText("UV-Kx OPS", m - 2 * d, titleY, title)
        small.textSize = 15 * d
        small.color = Color.rgb(222, 226, 216)
        c.drawText("By D'Jé, Claude & ChatGPT · 2026", m, byY, small)

        // Barre de progression sur 2 s, puis l'état de la liaison
        val t = ((SystemClock.uptimeMillis() - start).toFloat() / SHOW_MS).coerceIn(0f, 1f)
        // En paysage, la barre ne dépasse pas le titre (le personnage est souvent au centre)
        val barW = if (landscape) minOf(w - 2 * m, title.measureText("UV-Kx OPS")) else w - 2 * m
        bar.color = Color.argb(70, 255, 255, 255)
        c.drawRoundRect(RectF(m, barY, m + barW, barY + 4 * d), 2 * d, 2 * d, bar)
        bar.color = Color.rgb(240, 170, 60)
        c.drawRoundRect(RectF(m, barY, m + barW * t, barY + 4 * d), 2 * d, 2 * d, bar)
        small.textSize = 13 * d
        small.color = Color.rgb(205, 210, 200)
        c.drawText(status(), m, barY + 22 * d, small)
        if (!closing) postInvalidateOnAnimation()
    }
}
