package fr.jlambert.k1remote

/**
 * Listes prêtes à ajouter. Sources et date indiquées : les fréquences changent parfois
 * (la tour de Saint-Exupéry est passée de 120,450 à l'indicatif 8,33 kHz « 120,455 »).
 */
object ChannelLibrary {

    class Preset(
        val title: String,
        val source: String,
        val warning: String?,
        val channels: List<ChannelEdit>
    )

    private fun ch(name: String, mhz: Double, mode: Int, narrow: Boolean, step: Int, power: Int = 1, rxOnly: Boolean = false) =
        ChannelEdit(name = name, rxHz = Math.round(mhz * 1_000_000), mode = mode, narrow = narrow,
            stepIdx = step, power = power, rxOnly = rxOnly)

    // Indicatif 8,33 kHz → fréquence porteuse réelle (ex. 126,180 → 126,175)
    private fun ch833(name: String, designator: Double): ChannelEdit {
        val khz = Math.round(designator * 1000)
        val base = khz / 25 * 25
        val carrier = base * 1000 + when (khz - base) { 5L -> 0L; 10L -> 8_333L; 15L -> 16_667L; else -> 0L }
        return ChannelEdit(name = name, rxHz = carrier, mode = 1, narrow = false, stepIdx = 6, power = 1, rxOnly = true)
    }

    val PRESETS = listOf(
        Preset("Aviation · Lyon",
            "AIP France (SIA), fiche LFLL du 19 mars 2026 et LFLY",
            "Écoute seule : la radio n'émet jamais en AM.",
            listOf(
                ch833("ATIS StEx", 126.180),
                ch833("Tour StEx", 120.455),
                ch833("Sol StEx", 121.830),
                ch833("Prevol SE", 121.655),
                ch("SIV Lyon", 135.200, 1, false, 6, rxOnly = true),
                ch833("ATIS Bron", 128.130),
                ch("Tour Bron", 118.100, 1, false, 6, rxOnly = true)
            )),
        Preset("Fluvial · Saône (Lyon, Rochetaillée)",
            "Guides de navigation (bateaux.com) et guide VNF cité par ChatGPT ; à confirmer sur place",
            "Écoute seulement : l'émission sur ces voies est réservée aux bateaux équipés et autorisés. " +
                "Ces canaux sont marqués « écoute seule » : le firmware Fusion PTT r25 ou plus y refuse toute émission. " +
                "Avec un firmware plus ancien, ce verrou est ignoré et la radio pourrait émettre.",
            listOf(
                ch("VHF10", 156.500, 0, false, 5, rxOnly = true),
                ch("VHF18 Lyon", 156.900, 0, false, 5, rxOnly = true),
                ch("VHF20", 157.000, 0, false, 5, rxOnly = true),
                ch("VHF22 Roch", 157.100, 0, false, 5, rxOnly = true)
            )),
        // 0.80 : demandé par D'jé. Sources vérifiées le 23/11/2026 (voir README).
        Preset("Secours montagne · Canal E",
            "Canal E 161,300 MHz : Haute-Savoie, Valais, Vallée d'Aoste (CAF Annecy, Région Vallée d'Aoste) ; " +
                "Suisse, réseau de la Rega (OFCOM). Sous-canal 123 Hz pour la France et l'Italie.",
            "DÉTRESSE UNIQUEMENT. Le 112 reste le premier réflexe. Le canal E ne couvre que certains massifs, " +
                "et rien ne garantit qu'il soit écouté à l'endroit où vous êtes. Restez à l'écoute après l'appel. " +
                "L'émission n'est pas bloquée : en détresse, il faut pouvoir appeler.",
            listOf(ch("Canal E", 161.300, 0, false, 4, power = 7).copy(txTone = "123,0 Hz"))),
        Preset("PMR446 · 16 canaux",
            "Plan de fréquences PMR446 (446,00625 à 446,19375 MHz, pas de 12,5 kHz)",
            "L'autorisation PMR446 vise des postes conformes à antenne intégrée : vérifiez l'usage de vos radios auprès de l'Arcep.",
            (0 until 16).map { i -> ch("PMR " + (i + 1).toString().padStart(2, '0'), 446.00625 + 0.0125 * i, 0, true, 4, power = 1) })
    )
}
