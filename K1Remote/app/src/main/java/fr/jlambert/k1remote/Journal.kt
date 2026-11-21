package fr.jlambert.k1remote

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal de bord de la liaison avec la radio : ce que fait l'appli (touches, PTT,
 * réglages) et ce que fait la radio (émission, réception, état lu par la commande 05
 * du firmware Fusion PTT). Gardé dans un fichier pour survivre à un redémarrage.
 */
object Journal {
    private const val MAX = 600
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.FRANCE)
    @Volatile var listener: (() -> Unit)? = null

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "journal.txt")
        file = f
        runCatching { if (f.exists()) f.readLines().takeLast(MAX).forEach { lines.addLast(it) } }
    }

    fun add(text: String) {
        synchronized(this) {
            // SimpleDateFormat n'est pas sûr entre fils : formaté sous le verrou
            val line = fmt.format(Date()) + "  " + text.replace("\n", "\n                      ")
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
            runCatching {
                file?.let { f ->
                    if (f.length() > 400_000) f.writeText(lines.joinToString("\n") + "\n")
                    else f.appendText(line + "\n")
                }
            }
        }
        listener?.invoke()
    }

    /** Du plus récent au plus ancien. */
    @Synchronized
    fun text(): String = lines.reversed().joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        runCatching { file?.writeText("") }
        listener?.invoke()
    }
}
