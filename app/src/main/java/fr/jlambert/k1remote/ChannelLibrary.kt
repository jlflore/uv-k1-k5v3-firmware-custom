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
        // 0.81 : demandées par D'jé. Fréquences relevées le 23/11/2026 sur des bases publiques
        // (pas l'AIP suisse elle-même, réservée aux comptes skybriefing) : à vérifier.
        Preset("Aviation · Genève",
            "Bases aéronautiques publiques (metarcentral, airport-lookup), relevé du 23/11/2026 ; à vérifier sur l'AIP suisse (skyguide)",
            "Écoute seule : la radio n'émet jamais en AM. Les fréquences d'un aéroport changent parfois : vérifiez-les avant de compter dessus.",
            listOf(
                ch833("ATIS GVA", 135.575),
                ch833("Tour GVA", 118.700),
                ch833("Sol GVA", 121.675),
                ch833("Approche", 120.300),
                ch833("Depart GVA", 119.525),
                ch833("Arrivee", 136.250),
                ch("Detresse", 121.500, 1, false, 6, rxOnly = true)
            )),
        Preset("Aviation · Zermatt et Alpes suisses",
            "Air Zermatt (consignes d'approche de l'héliport, 1er contact « Heli 1 ou VHF 130.005 ») ; OFAC, fréquence de montagne suisse 130,355 (juin 2026)",
            "Écoute seule. La fréquence de montagne est celle où les pilotes s'annoncent autour des places d'atterrissage en montagne : utile pour entendre arriver un hélicoptère.",
            listOf(
                ch833("Zermatt H", 130.005),
                ch833("Mont. CH", 130.355)
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
        Preset("Secours montagne · Canal E (Alpes FR-IT-CH)",
            "Canal E 161,300 MHz : Haute-Savoie, Valais, Vallée d'Aoste (CAF Annecy, Région Vallée d'Aoste) ; " +
                "Suisse, réseau de la Rega (OFCOM). Sous-canal 123 Hz pour la France et l'Italie.",
            "DÉTRESSE UNIQUEMENT. Le 112 reste le premier réflexe. Le canal E ne couvre que certains massifs, " +
                "et rien ne garantit qu'il soit écouté à l'endroit où vous êtes. Restez à l'écoute après l'appel. " +
                "L'émission n'est pas bloquée : en détresse, il faut pouvoir appeler.",
            listOf(ch("Canal E", 161.300, 0, false, 4, power = 7).copy(txTone = "123,0 Hz"))),
        // 0.81 : réseaux de randonneurs bénévoles en PMR446 (pas des secours officiels)
        Preset("Montagne · réseaux bénévoles PMR446",
            "Rete Radio Montana (Italie) : « canale 8-16 » = PMR 8 + CTCSS 16 ; « Canal 7-7 » (initiative née aux Canaries, relayée en France) = PMR 7 + CTCSS 7. " +
                "Codes CTCSS numérotés selon la table PMR habituelle (7 = 85,4 Hz, 16 = 114,8 Hz).",
            "Ce ne sont PAS des canaux de secours officiels : seulement d'autres randonneurs, s'ils écoutent. " +
                "Le 112 reste le premier réflexe. Dans les Pyrénées, aucun canal radio public de secours n'a été trouvé : 112 ou PGHM.",
            listOf(
                ch("RRM 8-16", 446.09375, 0, true, 4).copy(txTone = "114,8 Hz", rxTone = "114,8 Hz"),
                ch("Canal 7-7", 446.08125, 0, true, 4).copy(txTone = "85,4 Hz", rxTone = "85,4 Hz")
            )),
        // 0.83 : règle de D'jé : seuls les canaux de secours émettent (le secours prime).
        Preset("Mer · France (VHF marine)",
            "Canaux et usages : ANFR (manuel CRR maritime : voie 79 = 156,975 / 161,575 MHz) et CROSS Gris-Nez ; " +
                "bulletins météo des CROSS sur 79, 80 et 63 (voie de réception des stations côtières). Relevé du 24/11/2026.",
            "Écoute seule, SAUF le canal 16 (détresse, sécurité, appel), où l'émission reste possible : en détresse, il faut pouvoir appeler. " +
                "Le permis plaisance couvre l'opérateur, mais l'UV-K1 n'est pas une VHF marine certifiée : pour le trafic courant, une VHF marine reste nécessaire. " +
                "À terre ou en mer, le 196 (CROSS) et le 112 restent les premiers réflexes.",
            listOf(
                ch("C16 Detres", 156.800, 0, false, 5, power = 7),
                ch("C09 Port", 156.450, 0, false, 5, rxOnly = true),
                ch("C06 Nav", 156.300, 0, false, 5, rxOnly = true),
                ch("C08 Nav", 156.400, 0, false, 5, rxOnly = true),
                ch("C72 Nav", 156.625, 0, false, 5, rxOnly = true),
                ch("C77 Nav", 156.875, 0, false, 5, rxOnly = true),
                ch("C10 Semaph", 156.500, 0, false, 5, rxOnly = true),
                ch("C12 Portu", 156.600, 0, false, 5, rxOnly = true),
                ch("C14 Portu", 156.700, 0, false, 5, rxOnly = true),
                ch("C67 CROSS", 156.375, 0, false, 5, rxOnly = true),
                ch("C79 Meteo", 161.575, 0, false, 5, rxOnly = true),
                ch("C80 Meteo", 161.625, 0, false, 5, rxOnly = true),
                ch("C63 Meteo", 160.775, 0, false, 5, rxOnly = true)
            )),
        // Relais radioamateurs : écoute seule (choix de D'jé), fréquence de SORTIE du relais.
        Preset("Relais · Lyon et Val de Saône",
            "RepeaterBook (liste France) et ADRA01 (fiche F1ZFH : entrée 145,0875, sortie 145,6875), relevé du 24/11/2026. " +
                "Listes publiques parfois anciennes : un relais peut avoir changé ou s'être tu.",
            "Écoute seule. Relais FM analogiques seulement (le K1 ne fait ni DMR, ni D-STAR, ni C4FM).",
            listOf(
                ch("F1ZFG Lyon", 145.600, 0, true, 4, rxOnly = true),
                ch("F1ZFH Yzer", 145.6875, 0, true, 4, rxOnly = true),
                ch("F5ZXY Lyon", 145.4125, 0, true, 4, rxOnly = true),
                ch("F5ZTI Lyon", 431.625, 0, true, 4, rxOnly = true)
            )),
        Preset("Relais · Mont-Blanc et Arve",
            "REF 74 (F1ZJV pointe des Brasses, sortie 145,7875, analogique, CTCSS 74,4 Hz), relevé du 24/11/2026. " +
                "Aucun relais FM analogique public trouvé dans la vallée de Chamonix elle-même.",
            "Écoute seule. F1ZJV : usage libre mais prioritaire ADRASEC (secourisme radioamateur).",
            listOf(ch("F1ZJV Bras", 145.7875, 0, true, 4, rxOnly = true))),
        Preset("Relais · Haut-Jura et Pays de Gex",
            "RepeaterBook (F1ZJD Nurieux 145,6375 ; F1ZOH Crozet 430,225 ; F5ZBP Mont Poupet 145,775), Radio-club du CERN (F1ZOH), " +
                "ADRA01 (F5ZBP : entrée 145,175, sortie 145,775), relevé du 24/11/2026. Aucun relais FM analogique trouvé à Saint-Claude même.",
            "Écoute seule. Mont Poupet est à environ 80 km de Saint-Claude : réception selon le relief.",
            listOf(
                ch("F1ZJD Nuri", 145.6375, 0, true, 4, rxOnly = true),
                ch("F1ZOH Croz", 430.225, 0, true, 4, rxOnly = true),
                ch("F5ZBP Poup", 145.775, 0, true, 4, rxOnly = true)
            )),
        Preset("PMR446 · 16 canaux",
            "Plan de fréquences PMR446 (446,00625 à 446,19375 MHz, pas de 12,5 kHz)",
            "L'autorisation PMR446 vise des postes conformes à antenne intégrée : vérifiez l'usage de vos radios auprès de l'Arcep.",
            (0 until 16).map { i -> ch("PMR " + (i + 1).toString().padStart(2, '0'), 446.00625 + 0.0125 * i, 0, true, 4, power = 1) })
    )
}
