package fr.jlambert.k1remote

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Carte de réussite : une coche qui se dessine dans un disque, un titre et un message sur fond
 * clair (vert de jour, rouge sombre en mode nuit), un bouton. Se ferme d'un toucher, ou seule
 * au bout de 6 s. Utilisée quand un envoi à la radio s'est entièrement bien passé.
 */
object SuccessCard {

    fun show(act: Activity, title: String, message: String, onClose: (() -> Unit)? = null) {
        if (act.isFinishing) { onClose?.invoke(); return }
        val p = Prefs(act).palette()
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val dialog = Dialog(act)
        var closed = false
        fun close() {
            if (closed) return
            closed = true
            runCatching { dialog.dismiss() }
            onClose?.invoke()
        }

        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(26), dp(24), dp(18))
            background = Ui.shape(act, p.okBg, 26f, p.dot, 2f)
        }
        val check = CheckView(act, p.dot, if (p.night) Color.BLACK else Color.WHITE)
        card.addView(check, LinearLayout.LayoutParams(dp(76), dp(76)))
        card.addView(TextView(act).apply {
            text = title
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(p.okText)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        card.addView(TextView(act).apply {
            text = message
            textSize = 15f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setTextColor(if (p.night) p.text else p.okText)
            alpha = 0.9f
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        card.addView(TextView(act).apply {
            text = "Parfait"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (p.night) Color.BLACK else Color.WHITE)
            background = Ui.ripple(act, p.dot, 999f, p.dot, night = p.night)
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(20) })

        val frame = LinearLayout(act).apply {
            gravity = Gravity.CENTER
            setPadding(dp(28), 0, dp(28), 0)
            setOnClickListener { close() }
            addView(card, LinearLayout.LayoutParams(-1, -2))
        }
        dialog.setContentView(frame)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.setOnCancelListener { close() }
        dialog.show()

        // Entrée de la carte (léger rebond), puis la coche se dessine
        card.scaleX = 0.85f; card.scaleY = 0.85f; card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260)
            .setInterpolator(OvershootInterpolator(1.6f)).start()
        check.play()
        if (Build.VERSION.SDK_INT >= 30) card.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        else card.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        Handler(Looper.getMainLooper()).postDelayed({ close() }, 6000)
    }

    /** Disque qui grossit, puis coche tracée d'un trait. */
    private class CheckView(ctx: Context, private val disc: Int, private val ink: Int) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = disc }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private var t = 0f          // 0 → 1 : disque ; 1 → 2 : coche
        private val path = Path()
        private val part = Path()

        fun play() {
            ValueAnimator.ofFloat(0f, 2f).apply {
                duration = 650
                startDelay = 120
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = minOf(width, height) / 2f
            val disk = minOf(1f, t)
            c.drawCircle(cx, cy, r * (0.6f + 0.4f * disk) * (if (t < 1f) disk else 1f), fill)
            if (t <= 1f) return
            stroke.strokeWidth = r * 0.16f
            path.reset()
            path.moveTo(cx - r * 0.42f, cy + r * 0.02f)
            path.lineTo(cx - r * 0.12f, cy + r * 0.32f)
            path.lineTo(cx + r * 0.45f, cy - r * 0.30f)
            val pm = PathMeasure(path, false)
            part.reset()
            pm.getSegment(0f, pm.length * (t - 1f), part, true)
            c.drawPath(part, stroke)
        }
    }
}
