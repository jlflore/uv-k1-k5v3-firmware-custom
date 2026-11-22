package fr.jlambert.k1remote

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Journal des appels, pour l'utilisateur : qui a parlé, quand, combien de temps.
 * Séparé du journal technique ([Journal]), qui garde tout pour le diagnostic.
 *
 * Règles (0.60) :
 *  - une réception ou une émission de moins de 0,4 s sans appelant identifié est ignorée
 *    (ouverture brève du squelch, rebond) ;
 *  - deux réceptions du même appelant séparées de moins de 2 s n'en font qu'une ;
 *  - un code d'appel reçu jusqu'à 4 s après la fin d'une réception s'y rattache ;
 *  - 300 entrées au plus, les plus anciennes partent d'abord.
 */
object CallLog {
    const val MAX = 300
    const val MIN_MS = 400L
    const val MERGE_GAP_MS = 2000L
    const val LATE_CODE_MS = 4000L

    enum class Kind { RX, TX }

    data class Entry(val start: Long, val kind: Kind, var end: Long, var name: String? = null, var code: String? = null) {
        val durMs: Long get() = (end - start).coerceAtLeast(0)
        fun line() = listOf(start, kind.name, end, name.orEmpty().replace('\t', ' '), code.orEmpty()).joinToString("\t")
        companion object {
            fun parse(l: String): Entry? = runCatching {
                val f = l.split('\t')
                Entry(f[0].toLong(), Kind.valueOf(f[1]), f[2].toLong(), f.getOrNull(3)?.ifEmpty { null }, f.getOrNull(4)?.ifEmpty { null })
            }.getOrNull()
        }
    }

    private val entries = ArrayList<Entry>()
    private var file: File? = null
    private var openRx: Entry? = null
    private var openTx: Entry? = null
    @Volatile var listener: (() -> Unit)? = null

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "appels.tsv")
        load()
    }

    /** Pour les tests : journal en mémoire, sans fichier. */
    @Synchronized
    fun resetForTest(f: File? = null) {
        entries.clear(); openRx = null; openTx = null; file = f
        if (f != null) load()
    }

    private fun load() {
        entries.clear()
        runCatching { file?.takeIf { it.exists() }?.readLines()?.mapNotNull { Entry.parse(it) }?.takeLast(MAX)?.let { entries.addAll(it) } }
    }

    private fun save() {
        runCatching { file?.writeText(entries.joinToString("") { it.line() + "\n" }) }
    }

    private fun changed() { save(); listener?.invoke() }

    @Synchronized
    fun rxStart(now: Long = System.currentTimeMillis()) {
        if (openRx != null) return
        openRx = Entry(now, Kind.RX, now)
    }

    @Synchronized
    fun rxEnd(now: Long = System.currentTimeMillis()) {
        val e = openRx ?: return
        openRx = null
        e.end = now
        commit(e)
    }

    @Synchronized
    fun txStart(now: Long = System.currentTimeMillis()) {
        if (openTx != null) return
        openTx = Entry(now, Kind.TX, now, name = "Vous")
    }

    @Synchronized
    fun txEnd(now: Long = System.currentTimeMillis()) {
        val e = openTx ?: return
        openTx = null
        e.end = now
        if (e.durMs < MIN_MS) return
        add(e)
    }

    /** Appelant reconnu (ou code inconnu, [name] nul) pendant ou juste après une réception. */
    @Synchronized
    fun caller(name: String?, code: String, now: Long = System.currentTimeMillis()) {
        val open = openRx
        if (open != null) { open.name = name; open.code = code; return }
        val last = entries.lastOrNull { it.kind == Kind.RX }
        if (last != null && last.code == null && now - last.end <= LATE_CODE_MS) {
            last.name = name; last.code = code; changed(); return
        }
        // Code lu sans réception signalée (lecture de l'écran) : appel de durée inconnue
        if (last != null && last.code == code && now - last.end <= LATE_CODE_MS) return
        add(Entry(now, Kind.RX, now, name, code))
    }

    private fun commit(e: Entry) {
        val prev = entries.lastOrNull()
        if (prev != null && prev.kind == Kind.RX && e.start - prev.end < MERGE_GAP_MS &&
            (e.code == null || prev.code == null || e.code == prev.code)) {
            prev.end = e.end
            if (prev.code == null && e.code != null) { prev.code = e.code; prev.name = e.name }
            changed(); return
        }
        if (e.durMs < MIN_MS && e.code == null) return
        add(e)
    }

    private fun add(e: Entry) {
        entries.add(e)
        while (entries.size > MAX) entries.removeAt(0)
        changed()
    }

    @Synchronized fun all(): List<Entry> = entries.map { it.copy() }
    @Synchronized fun size() = entries.size

    @Synchronized
    fun clear() { entries.clear(); openRx = null; openTx = null; changed() }

    // ---------------------------------------------------------------- présentation

    fun duration(ms: Long): String {
        val s = ((ms + 500) / 1000).toInt()
        return when {
            ms <= 0 -> ""
            s < 1 -> "< 1 s"
            s < 60 -> "$s s"
            else -> "${s / 60} min ${(s % 60).toString().padStart(2, '0')} s"
        }
    }

    /** « Appel de Bravo (A2) », « Réception · appelant non identifié », « Vous avez émis ». */
    fun label(e: Entry): String = when {
        e.kind == Kind.TX -> "Vous avez émis"
        e.name != null -> "Appel de ${e.name}" + (e.code?.let { " ($it)" } ?: "")
        e.code != null -> "Code ${e.code} (inconnu)"
        else -> "Réception · appelant non identifié"
    }

    fun dayTitle(t: Long, now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        fun dayNo(x: Calendar) = x.get(Calendar.YEAR) * 1000 + x.get(Calendar.DAY_OF_YEAR)
        val y = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -1) }
        return when (dayNo(c)) {
            dayNo(n) -> "Aujourd'hui"
            dayNo(y) -> "Hier"
            else -> SimpleDateFormat(if (c.get(Calendar.YEAR) == n.get(Calendar.YEAR)) "EEEE d MMMM" else "EEEE d MMMM yyyy", Locale.FRANCE)
                .format(Date(t)).replaceFirstChar { it.uppercase() }
        }
    }

    fun time(t: Long): String = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date(t))

    /** Texte à partager : par jour, du plus récent au plus ancien. */
    fun text(now: Long = System.currentTimeMillis()): String {
        val list = all().reversed()
        if (list.isEmpty()) return "Aucun appel enregistré."
        val sb = StringBuilder()
        var day = ""
        for (e in list) {
            val d = dayTitle(e.start, now)
            if (d != day) { if (day.isNotEmpty()) sb.append('\n'); sb.append(d).append('\n'); day = d }
            sb.append("  ").append(time(e.start)).append("  ").append(label(e))
            duration(e.durMs).takeIf { it.isNotEmpty() }?.let { sb.append(" · ").append(it) }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }
}
