package fr.jlambert.k1remote

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * « Mes canaux » : consultation d'une sauvegarde de la mémoire de la radio (lecture seule).
 * Liste virtualisée des canaux occupés, recherche, filtres, fiche détaillée, sauvegardes.
 */
class ChannelsActivity : Activity() {

    companion object {
        private const val EXTRA_FILE = "file"
        private const val REQ_EXPORT = 41
        private val CHIP_LABELS = listOf("Tous", "Émission", "Écoute seule", "Avec nom")
        fun intent(ctx: Context, f: File): Intent =
            Intent(ctx, ChannelsActivity::class.java).putExtra(EXTRA_FILE, f.absolutePath)
    }

    private lateinit var prefs: Prefs
    private var p: Palette = Themes.CIEL
    private lateinit var file: File
    private lateinit var mem: RadioMemory          // brouillon : ce qu'on modifie
    private lateinit var base: RadioMemory         // ce qui a été lu sur la radio
    private var all: List<Channel> = emptyList()
    private var shown: List<Channel> = emptyList()
    private var query = ""
    private var filter = 0          // 0 tous, 1 émission (FM), 2 écoute seule, 3 avec nom
    private val mono: Typeface = Typeface.MONOSPACE
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private lateinit var adapter: Adapter
    private lateinit var countText: TextView
    private lateinit var sendButton: TextView
    private val chips = mutableListOf<TextView>()

    private fun dp(v: Int) = Ui.dpi(this, v)
    private fun text(s: CharSequence, sp: Float, color: Int, face: Typeface? = null) = TextView(this).apply {
        text = s; textSize = sp; setTextColor(color); if (face != null) typeface = face
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        p = prefs.palette()
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        val path = intent.getStringExtra(EXTRA_FILE)
        if (path == null) { finish(); return }
        file = File(path)
        try {
            base = RadioMemory.load(file); mem = base.copy()
            // Brouillon laissé en cours (appli interrompue, téléphone en veille…) : on le reprend
            val d = draftFile()
            if (d.exists()) runCatching { RadioMemory.load(d) }.getOrNull()?.let { m ->
                if (m.image.size == mem.image.size) {
                    System.arraycopy(m.image, 0, mem.image, 0, m.image.size)
                    if (changedCount() > 0) toast("Modifications non envoyées retrouvées (${changedCount()} canal(aux))")
                }
            }
        } catch (e: Exception) {
            Sheet.message(this, "Sauvegarde illisible", e.message ?: "Fichier abîmé ou d'un autre format.",
                cancelable = false, onClose = { finish() })
            return
        }
        all = mem.channels()
        build()
    }

    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft + dp(14), insets.systemWindowInsetTop + dp(10),
                    insets.systemWindowInsetRight + dp(14), insets.systemWindowInsetBottom + dp(10))
                insets
            }
        }

        // En-tête
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("←", 20f, p.text).apply {
            gravity = Gravity.CENTER
            contentDescription = "Retour"
            background = Ui.ripple(this@ChannelsActivity, p.surface, 999f, p.border, night = p.night)
            setOnClickListener { @Suppress("DEPRECATION") onBackPressed() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titles.addView(text("Mes canaux", 22f, p.text, Typeface.DEFAULT_BOLD))
        val date = SimpleDateFormat("d MMM yyyy 'à' HH:mm", Locale.FRANCE).format(Date(mem.date))
        titles.addView(text((if (mem.label.isNotEmpty()) mem.label + " · " else "") + "lu le $date", 12f, p.muted))
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(head)

        // Bandeau d'information : d'où viennent ces canaux, et qu'on ne modifie rien
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.shape(this@ChannelsActivity, p.surface, 12f, p.border)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        countText = text("", 14f, p.text, medium)
        info.addView(countText)
        info.addView(text("Radio : " + mem.firmware.ifEmpty { "?" } +
            " · copie de sa mémoire de configuration active (celle du firmware qui tournait)", 12f, p.muted))
        info.addView(text("Vos modifications restent dans l'appli tant que vous n'appuyez pas sur « Envoyer … à la radio ».", 12f, p.muted))
        info.addView(text("Toucher un canal : le modifier. Appui long : passer la radio dessus.", 12f, p.muted))
        root.addView(info, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // Recherche
        val search = EditText(this).apply {
            hint = "Nom, numéro ou fréquence"
            setSingleLine()
            textSize = 15f
            setTextColor(p.text)
            setHintTextColor(p.muted)
            background = Ui.shape(this@ChannelsActivity, p.surface, 12f, p.border)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); refresh() }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // Filtres
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chips.clear()
        CHIP_LABELS.forEachIndexed { i, label ->
            val c = text(label, 13f, p.text, medium).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { filter = i; refresh() }
            }
            chips += c
            chipRow.addView(c, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(8) })
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // Liste (virtualisée : 1 024 canaux possibles)
        adapter = Adapter()
        val list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            this.adapter = this@ChannelsActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> editChannel(shown[pos].number - 1) }
            setOnItemLongClickListener { v, _, pos, _ ->
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                selectOnRadio(shown[pos].number); true
            }
            selector = android.graphics.drawable.ColorDrawable(0)
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        // Actions, à portée du pouce
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton("Sauvegardes", false) { showBackups() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        actions.addView(actionButton("Bibliothèque", false) { showLibrary() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        actions.addView(actionButton("+ Nouveau", false) { newChannel() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        sendButton = actionButton("", true) { prepareSend() }
        root.addView(sendButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })

        setContentView(root)
        refresh()
    }

    private fun actionButton(label: String, primary: Boolean, onClick: () -> Unit) = text(label, 15f,
        if (primary) p.primaryText else p.text, medium).apply {
        gravity = Gravity.CENTER
        background = if (primary) Ui.ripple(this@ChannelsActivity, p.primaryBg, 14f, p.primaryBorder, night = p.night)
                     else Ui.ripple(this@ChannelsActivity, p.surface, 14f, p.border, night = p.night)
        setOnClickListener { onClick() }
    }

    /**
     * Fenêtre aux couleurs du thème de l'appli : les dialogues Android par défaut ont leur
     * propre fond (souvent gris foncé), sur lequel nos textes devenaient illisibles.
     */
    /** [validate] : si fourni et faux, la fenêtre reste ouverte (saisie à corriger). */
    /**
     * Carte avec contenu libre (éditeur de canal, envoi, détail d'une liste). 0.76 : même carte et
     * mêmes boutons en pilule que [Sheet] ; hôte AlertDialog gardé pour le clavier des champs.
     */
    private fun themedDialog(title: String, content: View, positive: String, onPositive: (() -> Unit)? = null,
                             negative: String? = null, contentHeight: Int = -2, validate: (() -> Boolean)? = null) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.shape(this@ChannelsActivity, p.sheet, 22f, p.border)
            setPadding(dp(18), dp(18), dp(18), dp(14))
        }
        box.addView(text(title, 20f, p.text, Typeface.DEFAULT_BOLD))
        box.addView(content, LinearLayout.LayoutParams(-1, contentHeight).apply { topMargin = dp(10) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val dlg = AlertDialog.Builder(this).setView(box).create()
        if (negative != null) buttons.addView(Sheet.pill(this, p, Sheet.Btn(negative)) { dlg.dismiss() },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
        buttons.addView(Sheet.pill(this, p, Sheet.Btn(positive, Sheet.Style.PRIMARY)) {
            if (validate != null && !validate()) return@pill
            dlg.dismiss(); onPositive?.invoke() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dlg.show()
    }

    /** Brouillon enregistré à chaque modification, hors de la liste des sauvegardes. */
    private fun draftFile() = File(File(filesDir, "brouillons").apply { mkdirs() }, file.name)

    private fun saveDraft() {
        runCatching {
            if (changedCount() == 0) draftFile().delete()
            else draftFile().outputStream().use { mem.write(it) }
        }
    }

    private fun refresh() {
        saveDraft()
        all = mem.channels()
        val q = query.trim().lowercase(Locale.FRANCE).replace(',', '.')
        shown = all.filter { c ->
            (when (filter) {
                1 -> !c.listenOnly
                2 -> c.listenOnly
                3 -> c.name.isNotEmpty()
                else -> true
            }) && (q.isEmpty() || c.name.lowercase(Locale.FRANCE).contains(q) || c.number.toString() == q ||
                RadioMemory.mhz(c.rxHz).replace(',', '.').startsWith(q))
        }
        // Nombre de canaux par filtre (sur toute la mémoire, recherche non comprise)
        val counts = intArrayOf(all.size, all.count { !it.listenOnly }, all.count { it.listenOnly }, all.count { it.name.isNotEmpty() })
        chips.forEachIndexed { i, c ->
            val on = i == filter
            c.text = "${CHIP_LABELS[i]} · ${counts[i]}"
            c.setTextColor(if (on) p.accentText else p.text)
            c.background = Ui.shape(this, if (on) p.accentBg else p.surface, 999f, if (on) p.accentBorder else p.border)
        }
        countText.text = "${all.size} canaux occupés sur 1 024" + if (shown.size != all.size) " · ${shown.size} affichés" else ""
        val n = changedCount()
        sendButton.text = if (n == 0) "Exporter la sauvegarde" else "Envoyer $n modification${if (n > 1) "s" else ""} à la radio"
        adapter.notifyDataSetChanged()
    }

    private fun changed(n: Int): Boolean {
        val i = n - 1
        return (0 until 16).any { mem.image[i * 16 + it] != base.image[i * 16 + it] ||
                mem.image[0x4000 + i * 16 + it] != base.image[0x4000 + i * 16 + it] } ||
            mem.image[0x8000 + i * 2] != base.image[0x8000 + i * 2] || mem.image[0x8001 + i * 2] != base.image[0x8001 + i * 2]
    }

    private fun changedCount() = (1..RadioMemory.CHANNELS).count { changed(it) }

    private fun pill(label: String, bg: Int, fg: Int, border: Int) = text(label, 12f, fg, medium).apply {
        setPadding(dp(8), dp(2), dp(8), dp(2))
        background = Ui.shape(this@ChannelsActivity, bg, 999f, border)
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(i: Int) = shown[i]
        override fun getItemId(i: Int) = shown[i].number.toLong()
        override fun getView(i: Int, convertView: View?, parent: ViewGroup?): View {
            val c = shown[i]
            val card = LinearLayout(this@ChannelsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.shape(this@ChannelsActivity, p.surface, 12f, p.border)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            card.addView(text(c.number.toString().padStart(3, '0'), 12f, p.muted, mono),
                LinearLayout.LayoutParams(dp(38), -2))
            val mid = LinearLayout(this@ChannelsActivity).apply { orientation = LinearLayout.VERTICAL }
            // Le nom d'abord (c'est ce qu'on cherche des yeux), la fréquence juste dessous
            if (c.name.isNotEmpty()) {
                mid.addView(text(c.name, 18f, p.text, Typeface.DEFAULT_BOLD))
                mid.addView(text(RadioMemory.mhz(c.rxHz) + " MHz", 14f, p.text, mono))
            } else {
                mid.addView(text(RadioMemory.mhz(c.rxHz) + " MHz", 18f, p.text, Typeface.create(mono, Typeface.BOLD)))
            }
            val sub = listOfNotNull(c.mode,
                if (c.listenOnly) null else "${c.power} (${RadioMemory.powerWatts(c.power)})",
                c.txTone?.let { "TX $it" }, c.rxTone?.let { "RX $it" }, c.shift.ifEmpty { null }).joinToString(" · ")
            mid.addView(text(sub, 14f, p.muted))
            card.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
            // Étiquettes empilées à droite : un canal peut être à la fois modifié, en écoute seule et dans des listes
            val tags = listOfNotNull(
                if (changed(c.number)) pill("Modifié", p.accentBg, p.accentText, p.accentBorder) else null,
                if (c.listenOnly) pill("Écoute", p.chipBg, p.chipText, p.chipBorder) else null,
                mem.listLabel(c.scanList, short = true)?.let { pill(it, p.chipBg, p.chipText, p.chipBorder) })
            if (tags.isNotEmpty()) {
                val col = LinearLayout(this@ChannelsActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
                tags.forEachIndexed { k, t -> col.addView(t, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; if (k > 0) topMargin = dp(4) }) }
                card.addView(col, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            }
            return LinearLayout(this@ChannelsActivity).apply {
                setPadding(0, dp(3), 0, dp(3))
                addView(card, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }

    private fun showBackups() {
        val files = RadioMemory.list(this)
        if (files.isEmpty()) { android.widget.Toast.makeText(this, "Aucune sauvegarde", android.widget.Toast.LENGTH_SHORT).show(); return }
        val fmt = SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.FRANCE)
        val opts = files.mapIndexed { i, f ->
            val m = runCatching { RadioMemory.load(f) }.getOrNull()
            val date = fmt.format(Date(f.lastModified()))
            val name = m?.label?.ifEmpty { null }
            ChoiceSheet.Option(
                icon = if (m == null) "!" else "▤",
                title = name ?: date.replaceFirstChar { it.uppercase() },
                subtitle = listOfNotNull(if (name != null) date else null,
                    m?.let { "${it.channels().size} canaux" } ?: "fichier illisible").joinToString(" · "),
                badge = when { f == file -> "OUVERTE"; i == 0 -> "RÉCENTE"; else -> null },
                onClick = { backupActions(f) })
        }
        ChoiceSheet.show(this, "Sauvegardes", "Touchez une sauvegarde pour l'ouvrir, la nommer ou la supprimer. La plus récente est en haut.", opts)
    }

    private fun backupActions(f: File) {
        val m = runCatching { RadioMemory.load(f) }.getOrNull()
        val fmt = SimpleDateFormat("d MMM yyyy à HH:mm", Locale.FRANCE)
        val title = m?.label?.ifEmpty { null } ?: "Sauvegarde du " + fmt.format(Date(f.lastModified()))
        val info = (m?.let { "${it.channels().size} canaux" } ?: "Fichier illisible") +
            (if (m?.label?.isNotEmpty() == true) " · " + fmt.format(Date(f.lastModified())) else "")
        val opts = mutableListOf<ChoiceSheet.Option>()
        if (m != null) opts += ChoiceSheet.Option("▶", if (f == file) "Rester sur celle-ci" else "Ouvrir",
            if (f == file) "C'est la sauvegarde affichée" else "Afficher ses canaux", primary = true) {
            if (f != file) { startActivity(intent(this, f)); finish() } }
        if (m != null) opts += ChoiceSheet.Option("✎", "Nommer", "Ex. Airsoft, Écoute aviation…") { rename(f) }
        opts += ChoiceSheet.Option("✕", "Supprimer", "Efface le fichier du téléphone, la radio n'est pas concernée", danger = true) {
            ChoiceSheet.show(this, "Supprimer cette sauvegarde ?", "« $title » sera effacée du téléphone. La radio n'est pas concernée.",
                listOf(ChoiceSheet.Option("✕", "Supprimer définitivement", danger = true) {
                    f.delete(); if (f == file) finish() else showBackups() }), "Annuler")
        }
        ChoiceSheet.show(this, title, info, opts, "Retour")
    }

    private fun rename(f: File) {
        val m = runCatching { RadioMemory.load(f) }.getOrNull() ?: return
        val input = EditText(this).apply {
            setText(m.label); hint = "Airsoft, Écoute aviation…"; setSingleLine()
            setTextColor(p.text); setHintTextColor(p.muted); textSize = 16f
        }
        themedDialog("Nom de la sauvegarde", input, "Enregistrer", {
            m.label = input.text.toString().trim()
            f.outputStream().use { m.write(it) }
            // Sauvegarde ouverte : on change seulement son nom, sans toucher aux modifications en cours
            if (f == file) { base.label = m.label; mem.label = m.label; saveDraft() }
        }, "Annuler")
    }

    /** Copie la sauvegarde où l'on veut (Drive, Téléchargements…) : fichier .k1r avec un CSV lisible dedans. */
    private fun export() {
        val name = (mem.label.ifEmpty { "K1 canaux" } + " " +
            SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(Date(mem.date))).replace(Regex("[\\\\/:*?\"<>|]"), "_")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "$name.k1r")
        }, REQ_EXPORT)
    }

    // =====================================================================
    // Modification (brouillon dans l'appli ; rien ne part sans « Envoyer »)
    // =====================================================================

    private fun choiceRow(label: String, values: List<String>, current: () -> Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        row.addView(text(label, 12f, p.muted))
        val v = text(values.getOrElse(current()) { "?" } + "  ▾", 16f, p.text, medium).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = Ui.ripple(this@ChannelsActivity, p.bg, 10f, p.border, night = p.night)
        }
        v.setOnClickListener {
            Sheet.pick(this, label, values, current()) { w -> onPick(w); v.text = values[w] + "  ▾" }
        }
        row.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return row
    }

    private fun field(label: String, value: String, hint: String, numeric: Boolean): Pair<View, EditText> {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        row.addView(text(label, 12f, p.muted))
        val e = EditText(this).apply {
            setText(value); this.hint = hint; setSingleLine(); textSize = 18f
            setTextColor(p.text); setHintTextColor(p.muted)
            if (numeric) { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; typeface = mono }
            background = Ui.shape(this@ChannelsActivity, p.bg, 10f, p.border)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        row.addView(e, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return row to e
    }

    private fun newChannel() {
        val free = mem.firstFree() ?: run { toast("Plus aucune place libre (1 024 canaux)"); return }
        editChannel(free, ChannelEdit())
    }

    /** Fiche d'édition : réglages essentiels visibles, le reste en dessous. */
    private fun editChannel(i: Int, preset: ChannelEdit? = null) {
        val existing = mem.channel(i)
        val e = preset ?: existing?.let { mem.edit(it) } ?: ChannelEdit()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val (numRow, numEdit) = field("Numéro du canal (1 à 1 024)", (i + 1).toString(), "", true)
        numEdit.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        box.addView(numRow)
        val (nameRow, nameEdit) = field("Nom (10 caractères, accents retirés sur la radio)", e.name, "Sans nom — ex. : SCORPIO 1", false)
        nameEdit.filters = arrayOf(android.text.InputFilter.LengthFilter(10))
        box.addView(nameRow)
        box.addView(text("La radio montre le nom en mode canal si son menu ChDisp est sur NAME ou NAME + FREQ.", 12f, p.muted))
        val (fRow, fEdit) = field("Fréquence de réception (MHz)", RadioMemory.mhz(e.rxHz), "446,00625", true)
        box.addView(fRow)
        box.addView(choiceRow("Modulation", listOf("FM", "AM (écoute seule)", "USB (écoute seule)"), { e.mode }) { e.mode = it })
        box.addView(choiceRow("Puissance d'émission", RadioMemory.POWER_LABELS, { e.power }) { e.power = it })
        box.addView(choiceRow("Émission sur ce canal", listOf("Permise", "Bloquée : écoute seule"), { if (e.rxOnly) 1 else 0 }) { e.rxOnly = it == 1 })
        box.addView(text("Le blocage est appliqué par le firmware Fusion PTT r25 ou plus ; les versions antérieures l'ignorent.", 12f, p.muted))
        box.addView(choiceRow("Largeur de bande", listOf("Large (25 kHz)", "Étroite (12,5 kHz)"), { if (e.narrow) 1 else 0 }) { e.narrow = it == 1 })
        val tones = listOf("Aucun") + RadioMemory.toneChoices()
        box.addView(choiceRow("Sous-ton émis (CTCSS / DCS)", tones, { tones.indexOf(e.txTone ?: "Aucun").coerceAtLeast(0) }) {
            e.txTone = if (it == 0) null else tones[it] })
        box.addView(choiceRow("Sous-ton reçu (filtre)", tones, { tones.indexOf(e.rxTone ?: "Aucun").coerceAtLeast(0) }) {
            e.rxTone = if (it == 0) null else tones[it] })
        box.addView(text("RÉGLAGES AVANCÉS", 12f, p.muted, Typeface.DEFAULT_BOLD).apply { setPadding(0, dp(14), 0, 0) })
        box.addView(choiceRow("Décalage relais", listOf("Aucun", "+ (émission plus haute)", "− (émission plus basse)"), { e.shiftDir }) { e.shiftDir = it })
        val (sRow, sEdit) = field("Valeur du décalage (MHz)", if (e.shiftHz > 0) RadioMemory.mhz(e.shiftHz) else "", "0,6", true)
        box.addView(sRow)
        box.addView(choiceRow("Pas (kHz)", RadioMemory.STEP_KHZ, { e.stepIdx }) { e.stepIdx = it })
        val listsLabel = { mem.listLabel(e.scanList) ?: "Aucune" }
        val listsRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        listsRow.addView(text("Liste de scan (une seule par canal)", 12f, p.muted))
        val listsValue = text(listsLabel() + "  ▾", 16f, p.text, medium).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = Ui.ripple(this@ChannelsActivity, p.bg, 10f, p.border, night = p.night)
        }
        listsValue.setOnClickListener {
            // 0 = aucune, 1..24, 25 = toutes : l'index du choix est la valeur écrite dans la radio
            val names = listOf("Aucune") + (1..RadioMemory.LISTS).map { mem.listLabel(it)!! } + listOf("Toutes les listes")
            Sheet.pick(this, "Liste de scan", names, e.scanList.coerceIn(0, RadioMemory.LIST_ALL)) { w ->
                e.scanList = w; listsValue.text = listsLabel() + "  ▾"
            }
        }
        listsRow.addView(listsValue, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        box.addView(listsRow)
        if (existing != null) {
            box.addView(text("INFORMATIONS", 12f, p.muted, Typeface.DEFAULT_BOLD).apply { setPadding(0, dp(14), 0, dp(2)) })
            val info = listOf(
                "Émission : " + if (existing.listenOnly) "interdite (${existing.mode} : écoute seule)" else RadioMemory.mhz(existing.txHz) + " MHz",
                "Verrou canal occupé : " + if (existing.bcl) "oui" else "non",
                "TX Lock : " + if (existing.txLock) "oui (plan de bandes)" else "non")
            box.addView(text(info.joinToString("\n"), 13f, p.text))
            box.addView(text(existing.raw.joinToString(" ") { String.format("%02X", it) }, 11f, p.muted, mono).apply {
                setPadding(0, dp(4), 0, 0); setTextIsSelectable(true) })
        }
        if (existing != null) box.addView(text("▶  Passer la radio sur ce canal", 15f, p.text, medium).apply {
            setPadding(0, dp(16), 0, dp(2))
            setOnClickListener { selectOnRadio(i + 1) }
        })
        if (existing != null) box.addView(text("Supprimer ce canal", 15f, p.danger, medium).apply {
            setPadding(0, dp(16), 0, dp(6))
            setOnClickListener {
                Sheet.confirm(this@ChannelsActivity, "Supprimer le canal ${i + 1} ?",
                    "Il sera retiré du brouillon. La radio n'est modifiée qu'à l'envoi.", "Supprimer") {
                    mem.deleteChannel(i); refresh()
                }
            }
        })
        themedDialog(if (existing == null) "Nouveau canal" else "Canal ${i + 1}",
            android.widget.ScrollView(this).apply { addView(box) }, "Enregistrer", null, "Annuler",
            contentHeight = (resources.displayMetrics.heightPixels * 0.62f).toInt(), validate = {
                val target = (numEdit.text.toString().toIntOrNull() ?: (i + 1)) - 1
                val hz = RadioMemory.parseMHz(fEdit.text.toString())
                when {
                    target !in 0 until RadioMemory.CHANNELS -> { numEdit.error = "Numéro de 1 à 1 024"; false }
                    hz == null -> { fEdit.error = "Fréquence illisible ou hors de la plage (18 à 1 300 MHz)"; false }
                    target != i && mem.channel(target) != null -> { numEdit.error = "Canal ${target + 1} déjà occupé"; false }
                    else -> {
                        e.name = nameEdit.text.toString().trim()
                        e.rxHz = hz
                        e.shiftHz = RadioMemory.parseMHz(sEdit.text.toString())?.let { it }
                            ?: (sEdit.text.toString().replace(',', '.').toDoubleOrNull()?.let { Math.round(it * 1_000_000) } ?: 0)
                        if (e.shiftHz == 0L) e.shiftDir = 0
                        if (target != i && existing != null) mem.deleteChannel(i)   // déplacement
                        mem.setChannel(target, e)
                        refresh()
                        true
                    }
                }
            })
    }

    /** Passe la radio sur le canal n, tel qu'il est enregistré dans la radio (0.64). */
    private fun selectOnRadio(n: Int) {
        fun go() {
            val sel = MemoryBridge.channelSelector
            if (sel == null) {
                // Écran principal fermé par Android : on y retourne, il passera sur le canal dès que la radio est prête
                MemoryBridge.pendingChannel = n
                Journal.add("Mes canaux : écran principal fermé, canal $n transmis à son retour")
                toast("Retour à l'écran principal : passage au canal $n dès que la radio est prête")
                startActivity(android.content.Intent(this, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
                return
            }
            val why = sel(n)
            if (why == null) { toast("Radio : passage au canal $n"); finish() } else toast(why)
        }
        if (changed(n)) {
            Sheet.confirm(this, "Canal $n modifié, pas encore envoyé",
                "La radio passera sur ce canal tel qu'il est enregistré dans la radio, pas sur votre version modifiée. " +
                    "Pour utiliser la nouvelle version, envoyez d'abord les modifications.",
                "Passer quand même", danger = false) { go() }
        } else go()
    }

    private fun showLibrary() {
        val presets = ChannelLibrary.PRESETS
        ChoiceSheet.show(this, "Bibliothèque",
            "Listes de fréquences toutes prêtes. Touchez-en une pour voir ses canaux avant de l'ajouter au brouillon : rien n'est écrasé.",
            presets.map { pr ->
                ChoiceSheet.Option("+", pr.title, "${pr.channels.size} canaux" + (if (pr.channels.all { it.rxOnly }) " · écoute seule" else "") + " · voir le détail") { addPreset(pr) }
            })
    }

    /** Ajoute une liste dans des places libres, à partir du numéro choisi. Rien n'est écrasé. */
    private fun addPreset(pr: ChannelLibrary.Preset) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text(pr.channels.joinToString("\n") { "${RadioMemory.mhz(it.rxHz)}  ${it.name}" }, 14f, p.text, mono))
        box.addView(text("Source : " + pr.source, 12f, p.muted).apply { setPadding(0, dp(10), 0, 0) })
        if (pr.warning != null) box.addView(text(pr.warning, 12f, p.text).apply { setPadding(0, dp(8), 0, 0) })
        val (startRow, startEdit) = field("Placer à partir du canal", "101", "101", true)
        startEdit.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        box.addView(startRow)
        themedDialog(pr.title, android.widget.ScrollView(this).apply { addView(box) }, "Ajouter au brouillon", {
            var from = ((startEdit.text.toString().toIntOrNull() ?: 101) - 1).coerceIn(0, RadioMemory.CHANNELS - 1)
            val placed = mutableListOf<Int>()
            for (c in pr.channels) {
                val slot = mem.firstFree(from) ?: break
                mem.setChannel(slot, c.copy())
                placed += slot + 1
                from = slot + 1
            }
            refresh()
            toast(if (placed.size == pr.channels.size) "Ajoutés aux canaux ${placed.first()} à ${placed.last()} (brouillon)"
                  else "Seulement ${placed.size} canaux ajoutés : plus de place libre")
        }, "Annuler", contentHeight = (resources.displayMetrics.heightPixels * 0.5f).toInt())
    }

    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show()

    /** Résumé avant envoi : ce qui change, canal par canal. */
    private fun prepareSend() {
        val changedList = (1..RadioMemory.CHANNELS).filter { changed(it) }
        if (changedList.isEmpty()) { export(); return }
        val lines = changedList.joinToString("\n") { n ->
            val before = base.channel(n - 1); val after = mem.channel(n - 1)
            when {
                before == null -> "＋ $n  ${RadioMemory.mhz(after!!.rxHz)} ${after.name}"
                after == null -> "－ $n  ${RadioMemory.mhz(before.rxHz)} ${before.name} (supprimé)"
                else -> "✎ $n  ${RadioMemory.mhz(after.rxHz)} ${after.name}"
            }
        }
        val blocks = mem.diff(base)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text(lines, 14f, p.text, mono))
        box.addView(text("\nAvant d'écrire, l'appli relit la radio et en garde une sauvegarde. Elle écrit ensuite " +
            "${blocks.size} petit${if (blocks.size > 1) "s" else ""} bloc${if (blocks.size > 1) "s" else ""}, les relit un par un, " +
            "et n'annonce la réussite qu'après vérification. Seuls les canaux, leurs noms et leurs listes sont écrits. " +
            "Ensuite, éteignez puis rallumez la radio.\n\nPar le boîtier K1 Relais : appuyez brièvement sur son bouton BOOT " +
            "quand l'appli le demande (lumière orange clignotante). Sans cet appui, le boîtier refuse toute écriture.", 12f, p.muted))
        themedDialog("Envoyer à la radio", android.widget.ScrollView(this).apply { addView(box) }, "Envoyer", {
            val w = MemoryBridge.writer
            if (w == null) toast("Revenez à l'écran principal de l'appli, radio connectée, puis réessayez")
            else w(this, base, mem) { ok, newFile ->
                if (ok && newFile != null) {
                    draftFile().delete()
                    // Revue 0.76 : écran déjà fermé → pas de navigation depuis une activité morte
                    if (!isFinishing && !isDestroyed) { startActivity(intent(this, newFile)); finish() }
                }
            }
        }, "Annuler", contentHeight = (resources.displayMetrics.heightPixels * 0.5f).toInt())
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::mem.isInitialized && changedCount() > 0) {
            Sheet.confirm(this, "Modifications non envoyées",
                "${changedCount()} canal(aux) modifié(s) dans l'appli mais pas dans la radio. Les abandonner ?",
                "Abandonner", cancel = "Rester") { draftFile().delete(); finish() }
        } else {
            @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    @Deprecated("API Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK) return
        val ok = runCatching { contentResolver.openOutputStream(uri)?.use { mem.write(it) } != null }.getOrDefault(false)
        android.widget.Toast.makeText(this, if (ok) "Sauvegarde exportée" else "Export impossible", android.widget.Toast.LENGTH_SHORT).show()
    }
}
