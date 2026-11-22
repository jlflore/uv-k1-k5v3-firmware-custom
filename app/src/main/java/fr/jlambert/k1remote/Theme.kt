package fr.jlambert.k1remote

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable

/** Thèmes proposés dans les préférences. NUIT est le mode nuit (rouge sur noir). */
enum class ThemeId(val label: String, val blurb: String) {
    CIEL("Ciel", "Fond bleu ciel, touches blanches"),
    MINIMAL("Clair minimal", "Gris clair, sobre et plat"),
    ITALIE("Vegetato · Italie", "Camouflage de l'armée italienne"),
    USA("OCP Scorpion · USA", "Camouflage de l'armée américaine"),
    FRANCE("CCE · France", "Camouflage Centre Europe de l'armée de terre"),
    DESERT("Désert", "Camouflage désert trois tons"),
    DIGITAL("Digital · Flecktarn", "Flecktarn de la Bundeswehr, en pixels"),
    OTAN("Woodland · OTAN", "Le Woodland porté dans les armées de l'OTAN"),
    NUIT("Nuit rouge", "Rouge sur noir, pour la nuit");

    companion object {
        /** Thèmes de jour proposés dans le sélecteur, dans l'ordre. */
        val DAY = listOf(CIEL, MINIMAL, ITALIE, USA, FRANCE, DESERT, DIGITAL, OTAN)
    }
}

/** Une couche de taches : couleur, nombre, rayon mini/maxi (dp), irrégularité, étirement, rotation, points. */
class CamoLayer(val color: Int, val count: Int, val rMin: Float, val rMax: Float,
                val irr: Float = 0.5f, val sx: Float = 1f, val sy: Float = 1f, val rot: Float = 0.6f, val pts: Int = 8)

/** Motif de camouflage dessiné en fond d'écran (taches, ou pixels si [pixel] > 0). */
class Camo(val seed: Long, val base: Int, val layers: List<CamoLayer>, val extra: List<CamoLayer> = emptyList(),
           val blur: Boolean = false, val pixel: Int = 0, val flag: IntArray, val unit: String)

class Palette(
    val night: Boolean,
    val bg: Int, val surface: Int, val border: Int, val text: Int, val muted: Int, val sub: Int,
    val keyEdge: Int?,                       // liseré sous les touches (null = touches à plat)
    val chipBg: Int, val chipBorder: Int,
    val accentBg: Int, val accentBorder: Int, val accentText: Int,
    val sideBg: Int, val sideBorder: Int, val sideText: Int,
    val lcdFrame: Int, val lcdFrameBorder: Int, val lcdOff: Int, val lcdOffDim: Int, val lcdOn: Int,
    val primaryBg: Int, val primaryText: Int, val primaryBorder: Int,
    val sheet: Int, val scrim: Int,
    val rxOnBg: Int, val rxOnText: Int, val rxOnBorder: Int,
    val txOnBg: Int, val txOnText: Int, val txOnBorder: Int,
    val dot: Int, val danger: Int, val selBg: Int,
    val okBg: Int, val okText: Int,
    // Thèmes camouflage : motif de fond, en-tête en écusson, touches claires sur panneaux foncés
    val camo: Camo? = null,
    val headerBg: Int? = null, val headerBorder: Int? = null,
    keyBg: Int? = null, keyText: Int? = null, keyBorder: Int? = null,
    chipText: Int? = null, chipMuted: Int? = null
) {
    val keyBg: Int = keyBg ?: surface
    val keyText: Int = keyText ?: text
    val keyBorder: Int = keyBorder ?: border
    val chipText: Int = chipText ?: text
    val chipMuted: Int = chipMuted ?: muted
    /** Barres système foncées (icônes claires). */
    val darkChrome: Boolean get() = night || camo != null
}

object Themes {
    private fun c(hex: String) = Color.parseColor(hex)

    val CIEL = Palette(
        night = false,
        bg = c("#DCE8F2"), surface = c("#FFFFFF"), border = c("#C9D6E2"), text = c("#14263A"), muted = c("#4F6072"), sub = c("#A35400"),
        keyEdge = c("#C9D6E2"),
        chipBg = c("#E6F0FA"), chipBorder = c("#9DB8D3"),
        accentBg = c("#F2A23A"), accentBorder = c("#C47A12"), accentText = c("#1A1206"),
        sideBg = c("#FFF4E6"), sideBorder = c("#B35C00"), sideText = c("#8A4600"),
        lcdFrame = c("#26303A"), lcdFrameBorder = c("#26303A"), lcdOff = c("#B9D3A6"), lcdOffDim = c("#7E9472"), lcdOn = c("#17241B"),
        primaryBg = c("#14263A"), primaryText = c("#FFFFFF"), primaryBorder = c("#14263A"),
        sheet = c("#FFFFFF"), scrim = Color.argb(90, 20, 38, 58),
        rxOnBg = c("#CFF1DF"), rxOnText = c("#0E5A36"), rxOnBorder = c("#1F9D62"),
        txOnBg = c("#FAD4D1"), txOnText = c("#8E1B13"), txOnBorder = c("#D9564B"),
        dot = c("#1F9D62"), danger = c("#B3261E"), selBg = c("#E6F0FA"),
        okBg = c("#CFF1DF"), okText = c("#0E5A36")
    )

    val MINIMAL = Palette(
        night = false,
        bg = c("#EEF1F4"), surface = c("#FFFFFF"), border = c("#DCE2E8"), text = c("#1B2430"), muted = c("#5F6B78"), sub = c("#5F6B78"),
        keyEdge = null,
        chipBg = c("#E7EDF3"), chipBorder = c("#D3DBE3"),
        accentBg = c("#FBD9A6"), accentBorder = c("#E8B86B"), accentText = c("#1A1206"),
        sideBg = c("#FFFFFF"), sideBorder = c("#DCE2E8"), sideText = c("#1B2430"),
        lcdFrame = c("#FFFFFF"), lcdFrameBorder = c("#DCE2E8"), lcdOff = c("#C5DBB4"), lcdOffDim = c("#8FA383"), lcdOn = c("#17241B"),
        primaryBg = c("#1F5FA8"), primaryText = c("#FFFFFF"), primaryBorder = c("#1F5FA8"),
        sheet = c("#FFFFFF"), scrim = Color.argb(90, 27, 36, 48),
        rxOnBg = c("#1B7F4F"), rxOnText = c("#FFFFFF"), rxOnBorder = c("#1B7F4F"),
        txOnBg = c("#B3261E"), txOnText = c("#FFFFFF"), txOnBorder = c("#B3261E"),
        dot = c("#1F9D62"), danger = c("#B3261E"), selBg = c("#E7EDF3"),
        okBg = c("#DDF3E7"), okText = c("#155C3A")
    )

    val NUIT = Palette(
        night = true,
        bg = c("#000000"), surface = c("#0D0302"), border = c("#4A120D"), text = c("#E8483A"), muted = c("#B8392E"), sub = c("#B8392E"),
        keyEdge = null,
        chipBg = c("#1A0503"), chipBorder = c("#6A1A12"),
        accentBg = c("#3A0C08"), accentBorder = c("#E8483A"), accentText = c("#FF6355"),
        sideBg = c("#2A0805"), sideBorder = c("#B8392E"), sideText = c("#E8483A"),
        lcdFrame = c("#000000"), lcdFrameBorder = c("#4A120D"), lcdOff = c("#120302"), lcdOffDim = c("#080101"), lcdOn = c("#E8483A"),
        primaryBg = c("#3A0C08"), primaryText = c("#FF6355"), primaryBorder = c("#E8483A"),
        sheet = c("#0D0302"), scrim = Color.argb(170, 0, 0, 0),
        rxOnBg = c("#E8483A"), rxOnText = c("#000000"), rxOnBorder = c("#E8483A"),
        txOnBg = c("#FF6355"), txOnText = c("#000000"), txOnBorder = c("#FF6355"),
        dot = c("#E8483A"), danger = c("#FF6355"), selBg = c("#2A0805"),
        okBg = c("#3A0C08"), okText = c("#E8483A")
    )

    /** Palette d'un thème camouflage, à partir des couleurs des maquettes. */
    private fun camo(
        camo: Camo, patch: String, panel: String, panelBorder: String, panelText: String, panelSub: String,
        key: String, keyEdge: String, keyText: String, keySub: String,
        accent: String, accentEdge: String, accentText: String, chip: String, chipBorder: String
    ) = Palette(
        night = false,
        bg = c(patch), surface = c(panel), border = c(panelBorder), text = c(panelText), muted = c(panelSub), sub = c(keySub),
        keyEdge = c(keyEdge),
        chipBg = c(chip), chipBorder = c(chipBorder),
        accentBg = c(accent), accentBorder = c(accentEdge), accentText = c(accentText),
        sideBg = c(key), sideBorder = c(keyEdge), sideText = c(keySub),
        lcdFrame = c(patch), lcdFrameBorder = c(panelBorder), lcdOff = c("#B9D3A6"), lcdOffDim = c("#7E9472"), lcdOn = c("#17241B"),
        primaryBg = c(accent), primaryText = c(accentText), primaryBorder = c(accentEdge),
        sheet = c(panel), scrim = Color.argb(150, 0, 0, 0),
        rxOnBg = c("#CFF1DF"), rxOnText = c("#0E5A36"), rxOnBorder = c("#1F9D62"),
        txOnBg = c("#FAD4D1"), txOnText = c("#8E1B13"), txOnBorder = c("#D9564B"),
        dot = c("#5BD18B"), danger = c("#E5534B"), selBg = c(panelBorder),
        okBg = c("#CFF1DF"), okText = c("#0E5A36"),
        camo = camo, headerBg = c(patch), headerBorder = c(panelBorder),
        keyBg = c(key), keyText = c(keyText), keyBorder = c(keyEdge),
        chipText = c(keyText), chipMuted = c(keySub)
    )

    private fun L(color: String, n: Int, a: Float, b: Float, irr: Float, sx: Float, sy: Float, rot: Float, pts: Int) =
        CamoLayer(c(color), n, a, b, irr, sx, sy, rot, pts)
    private fun flag(a: String, b: String, d: String) = intArrayOf(c(a), c(b), c(d))

    val ITALIE by lazy { camo(Camo(11, c("#9A9468"), listOf(
            L("#C2B58E", 70, 14f, 30f, 0.5f, 1.6f, 0.7f, 1.2f, 8), L("#5E6B3C", 90, 12f, 26f, 0.55f, 1.5f, 0.7f, 1.3f, 8),
            L("#6B4E31", 70, 9f, 20f, 0.6f, 1.6f, 0.6f, 1.3f, 7), L("#2B2B22", 80, 5f, 12f, 0.6f, 1.8f, 0.5f, 1.4f, 6)),
            flag = flag("#008C45", "#F4F5F0", "#CD212A"), unit = "ESERCITO · VEGETATO"),
        "#2B2B22", "#33361F", "#6E7046", "#ECE6CC", "#C9C29F", "#E3DAB8", "#8E865F", "#1E1E16", "#5E4A1E",
        "#B5262B", "#7A1519", "#FFFFFF", "#D6CDA6", "#7E7752") }

    val USA by lazy { camo(Camo(7, c("#A89D78"), listOf(
            L("#C9BC97", 26, 40f, 80f, 0.45f, 1f, 1.2f, 0.6f, 8), L("#7C7650", 24, 34f, 70f, 0.5f, 1.1f, 1.3f, 0.6f, 8),
            L("#8E7655", 22, 26f, 55f, 0.5f, 1f, 1.2f, 0.6f, 7)),
            extra = listOf(L("#4B4430", 60, 4f, 9f, 0.6f, 2.6f, 0.45f, 2.5f, 6), L("#E1D6B4", 45, 4f, 8f, 0.5f, 2.2f, 0.5f, 2.5f, 6)),
            blur = true, flag = flag("#B22234", "#FFFFFF", "#3C3B6E"), unit = "U.S. ARMY · OCP"),
        "#3B3626", "#3E3A2A", "#7C7650", "#F1EAD3", "#CFC4A0", "#E6DCBD", "#958A66", "#1F1C14", "#5C4A33",
        "#B8862E", "#7D5A1C", "#1A1206", "#D9CEAA", "#857A57") }

    val FRANCE by lazy { camo(Camo(23, c("#7E8457"), listOf(
            L("#4A5532", 30, 30f, 62f, 0.5f, 1.5f, 0.85f, 0.5f, 8), L("#6A5238", 26, 24f, 50f, 0.5f, 1.5f, 0.8f, 0.5f, 8),
            L("#9FA272", 18, 18f, 36f, 0.5f, 1.4f, 0.8f, 0.5f, 7), L("#1F211A", 34, 10f, 24f, 0.6f, 1.7f, 0.6f, 0.6f, 7)),
            flag = flag("#0055A4", "#FFFFFF", "#EF4135"), unit = "ARMÉE DE TERRE · CCE"),
        "#1F211A", "#2C3322", "#646B45", "#EDEFDD", "#C3C69C", "#DEDFC3", "#8A8D62", "#181A12", "#4A5532",
        "#1F3F8F", "#132A63", "#FFFFFF", "#D0D2AE", "#747852") }

    val DESERT by lazy { camo(Camo(5, c("#D6C39C"), listOf(
            L("#BFA67C", 22, 34f, 70f, 0.45f, 1.6f, 0.8f, 0.5f, 8), L("#8F6E4C", 20, 22f, 46f, 0.5f, 1.6f, 0.75f, 0.5f, 8)),
            flag = flag("#E8DCBD", "#BFA67C", "#8F6E4C"), unit = "DÉSERT · TROIS TONS"),
        "#4A3A28", "#5A4630", "#9C8461", "#F6EEDC", "#E0D1AE", "#F1E7CF", "#A88D64", "#2A2016", "#7A5A35",
        "#A0522D", "#6B3519", "#FFFFFF", "#E8DCBD", "#A88D64") }

    val DIGITAL by lazy { camo(Camo(31, c("#8C9469"), listOf(
            L("#6F7D4E", 60, 3f, 7f, 0f, 1f, 1f, 0f, 0), L("#3E4A30", 70, 2f, 5f, 0f, 1f, 1f, 0f, 0),
            L("#6B4E35", 55, 2f, 5f, 0f, 1f, 1f, 0f, 0), L("#1C1E17", 70, 1f, 3f, 0f, 1f, 1f, 0f, 0)),
            pixel = 5, flag = flag("#000000", "#DD0000", "#FFCE00"), unit = "BUNDESWEHR · FLECKTARN"),
        "#1C1E17", "#2B3222", "#626C45", "#EDF0DE", "#C2C79F", "#DCDFC4", "#868B62", "#171912", "#3E4A30",
        "#C9A227", "#87691A", "#1A1405", "#CED2AE", "#737A52") }

    val OTAN by lazy { camo(Camo(41, c("#9C8B66"), listOf(
            L("#4D5A37", 26, 34f, 70f, 0.5f, 1.7f, 0.75f, 0.6f, 8), L("#5C4330", 24, 26f, 54f, 0.55f, 1.7f, 0.7f, 0.6f, 8),
            L("#1E1C18", 40, 8f, 20f, 0.6f, 2.2f, 0.5f, 0.8f, 7)),
            flag = flag("#004990", "#FFFFFF", "#004990"), unit = "OTAN · WOODLAND"),
        "#1E1C18", "#2E3323", "#69704A", "#EFEEDF", "#C9C6A3", "#E2DDC2", "#8E8762", "#1B1A14", "#4D5A37",
        "#1D4E89", "#123259", "#FFFFFF", "#D5CFAF", "#7D7754") }

    fun of(id: ThemeId): Palette = when (id) {
        ThemeId.CIEL -> CIEL
        ThemeId.MINIMAL -> MINIMAL
        ThemeId.ITALIE -> ITALIE
        ThemeId.USA -> USA
        ThemeId.FRANCE -> FRANCE
        ThemeId.DESERT -> DESERT
        ThemeId.DIGITAL -> DIGITAL
        ThemeId.OTAN -> OTAN
        ThemeId.NUIT -> NUIT
    }
}

/** Petits outils de dessin partagés par les écrans. */
object Ui {
    fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density
    fun dpi(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun shape(ctx: Context, fill: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Float = 1f, dashed: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(ctx, radiusDp)
            setColor(fill)
            if (stroke != null) {
                val w = dp(ctx, strokeDp).toInt().coerceAtLeast(1)
                if (dashed) setStroke(w, stroke, dp(ctx, 5f), dp(ctx, 4f)) else setStroke(w, stroke)
            }
        }

    fun oval(fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    /** Fond cliquable avec effet d'onde. */
    fun ripple(ctx: Context, fill: Int, radiusDp: Float, stroke: Int? = null, dashed: Boolean = false, night: Boolean = false): Drawable {
        val content = shape(ctx, fill, radiusDp, stroke, 1f, dashed)
        val mask = shape(ctx, Color.WHITE, radiusDp)
        val wave = if (night) Color.argb(70, 232, 72, 58) else Color.argb(50, 20, 38, 58)
        return RippleDrawable(ColorStateList.valueOf(wave), content, mask)
    }

    /** Touche du clavier : face + liseré dessous (effet de relief), avec onde au toucher. */
    fun key(ctx: Context, fill: Int, border: Int, edge: Int?, radiusDp: Float, night: Boolean): Drawable {
        val face = shape(ctx, fill, radiusDp, border)
        val content: Drawable = if (edge != null) {
            val under = shape(ctx, edge, radiusDp)
            LayerDrawable(arrayOf(under, face)).apply { setLayerInset(1, 0, 0, 0, dpi(ctx, 2)) }
        } else face
        val mask = shape(ctx, Color.WHITE, radiusDp)
        val wave = if (night) Color.argb(70, 232, 72, 58) else Color.argb(50, 20, 38, 58)
        return RippleDrawable(ColorStateList.valueOf(wave), content, mask)
    }
}

/** Police pochoir Black Ops One (licence SIL OFL, assets/fonts/OFL.txt) pour les thèmes camouflage. */
object Fonts {
    @Volatile private var stencil: android.graphics.Typeface? = null
    fun stencil(ctx: Context): android.graphics.Typeface =
        stencil ?: (runCatching { android.graphics.Typeface.createFromAsset(ctx.assets, "fonts/BlackOpsOne-Regular.ttf") }
            .getOrNull() ?: android.graphics.Typeface.DEFAULT_BOLD).also { stencil = it }
}
