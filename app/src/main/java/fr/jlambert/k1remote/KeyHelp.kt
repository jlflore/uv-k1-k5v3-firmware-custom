package fr.jlambert.k1remote

/** Fiches d'aide de chaque touche, en langage courant. */
class KeyHelpEntry(val title: String, val subtitle: String, val rows: List<Pair<String, String>>)

object KeyHelp {
    private const val COURT = "APPUI COURT"
    private const val LONG = "APPUI LONG"

    val ENTRIES: Map<String, KeyHelpEntry> = mapOf(
        "LCD" to KeyHelpEntry("Écran de la radio", "Copie en direct de l'écran", listOf(
            "TOUCHER EN HAUT" to "Rend la ligne A active (comme un appui long sur 2), si elle ne l'est pas déjà.",
            "TOUCHER EN BAS" to "Rend la ligne B active.",
            "À L'ÉCRAN · BARRE DU HAUT" to "Batterie (pourcentage ou tension) à droite ; prise USB quand la radio est branchée ; « F » quand la touche F vient d'être appuyée ; cadenas quand le clavier est verrouillé ; « DW » ou « XB » quand la double veille ou le mode croisé est actif ; « VOX » quand l'émission à la voix est active.",
            "À L'ÉCRAN · CHAQUE LIGNE (A, B)" to "Le triangle ▶ montre la ligne active. Grands chiffres : la fréquence, ou « CH-001 » et le nom du canal en mode canal. À droite et dessous : la puissance (L1 à L5 faible, M moyenne, H forte), la modulation (FM, AM, USB), la largeur (W ou WIDE : large ; N ou NAR : étroite ; N+ ou NAR+ : encore plus étroite, quand le réglage SetNFM est sur NARROWER), le sous-ton (CTCSS 88.5Hz, ou DCS D023N/D023I), « + » ou « − » s'il y a un décalage relais, « R » si l'inversion est active, « SCR » si le brouilleur est actif.",
            "À L'ÉCRAN · RÉCEPTION, SCAN" to "« RX » ou la barre du S-mètre quand un signal arrive (S1 faible à S9 fort, valeur en dBm) ; « TX » en émission ; « L1 » à « L5 » : listes de scan auxquelles appartient le canal ; « ScnRng » : balayage d'une plage de fréquences ; « MONI » : écoute forcée (squelch ouvert).",
            "À SAVOIR" to "Le toucher ne change de ligne que sur l'écran principal. Dans les menus et le spectre, toucher l'écran ouvre l'aide de cet écran (firmware r22 ou plus pour le spectre).")),
        "MENU" to KeyHelpEntry("MENU", "Réglages de la radio", listOf(
            COURT to "Ouvre le menu des réglages de la radio. Pendant un scan : arrête le scan et reste sur le canal trouvé.",
            LONG to "Lance l'action que vous avez choisie dans le menu de la radio, au réglage n°27 « M Long ».")),
        "LEFT" to KeyHelpEntry("Flèche ◀", "Se déplacer", listOf(
            COURT to "Descend d'un cran : fréquence ou canal précédent, ou ligne précédente dans le menu.",
            "F + FLÈCHE" to "Règle le squelch : le seuil à partir duquel la radio laisse passer le son. Plus il est haut, moins on entend de souffle, mais les signaux faibles sont coupés.")),
        "RIGHT" to KeyHelpEntry("Flèche ▶", "Se déplacer", listOf(
            COURT to "Monte d'un cran : fréquence ou canal suivant, ou ligne suivante dans le menu.",
            "F + FLÈCHE" to "Règle le squelch : le seuil à partir duquel la radio laisse passer le son.")),
        "EXIT" to KeyHelpEntry("EXIT", "Revenir, effacer", listOf(
            COURT to "Revient en arrière, ou efface le dernier chiffre tapé.",
            LONG to "Efface tout ce qui a été tapé.")),
        "F1" to KeyHelpEntry("F1 · touche latérale", "Vous choisissez son action", listOf(
            COURT to "Lance l'action choisie dans le menu de la radio, au réglage n°23 « F1Shrt ».",
            LONG to "Lance l'action choisie au réglage n°24 « F1Long ».",
            "F + F1" to "Augmente le pas : l'écart entre deux fréquences quand on utilise les flèches.")),
        "F2" to KeyHelpEntry("F2 · touche latérale", "Vous choisissez son action", listOf(
            COURT to "Lance l'action choisie dans le menu de la radio, au réglage n°25 « F2Shrt ».",
            LONG to "Lance l'action choisie au réglage n°26 « F2Long ».",
            "F + F2" to "Diminue le pas : l'écart entre deux fréquences quand on utilise les flèches.")),
        "1" to KeyHelpEntry("Touche 1 · Bande", "Chiffre 1, changer de bande", listOf(
            COURT to "Tape le chiffre 1.",
            "F + 1 OU APPUI LONG" to "Passe à la bande de fréquences suivante. En mode canal : recopie le canal affiché en mode fréquence, pour pouvoir le modifier.")),
        "2" to KeyHelpEntry("Touche 2 · A/B", "Chiffre 2, ligne A ou B", listOf(
            COURT to "Tape le chiffre 2.",
            "F + 2 OU APPUI LONG" to "L'écran montre deux lignes : A en haut, B en bas. Cette touche choisit la ligne sur laquelle on parle. C'est le raccourci « A ⇄ B ».")),
        "3" to KeyHelpEntry("Touche 3 · Fréq/Can", "Chiffre 3, fréquence ou canal", listOf(
            COURT to "Tape le chiffre 3.",
            "F + 3 OU APPUI LONG" to "Bascule entre le mode fréquence (on tape une fréquence) et le mode canal (on choisit un canal enregistré).")),
        "4" to KeyHelpEntry("Touche 4 · Copie", "Chiffre 4, copier une fréquence", listOf(
            COURT to "Tape le chiffre 4.",
            "F + 4 OU APPUI LONG" to "Faites émettre une autre radio tout près : la K1 trouve sa fréquence et son code. Appuyez ensuite sur MENU pour l'enregistrer.")),
        "5" to KeyHelpEntry("Touche 5 · Spectre", "Inscrit NOAA sur la radio (service météo américain, sans usage en France)", listOf(
            COURT to "Tape le chiffre 5.",
            "F + 5" to "Ouvre l'analyseur de spectre : un graphique qui montre l'activité sur les fréquences voisines.",
            LONG to "En mode canal : choisit la liste de scan de ce canal. En mode fréquence : prépare un scan entre deux fréquences.")),
        "6" to KeyHelpEntry("Touche 6 · Puissance", "Chiffre 6, puissance d'émission", listOf(
            COURT to "Tape le chiffre 6.",
            "F + 6 OU APPUI LONG" to "Puissance suivante : LOW 1 (la plus faible), LOW 2… LOW 5, MID, HIGH (la plus forte), puis on recommence.")),
        "7" to KeyHelpEntry("Touche 7 · VOX", "Chiffre 7, émission à la voix", listOf(
            COURT to "Tape le chiffre 7.",
            "F + 7" to "Lance un petit jeu de casse-briques, propre au firmware Fusion.",
            LONG to "Active ou coupe le VOX : la radio émet toute seule dès que vous parlez, sans appuyer sur le PTT.")),
        "8" to KeyHelpEntry("Touche 8 · Inv. relais", "Inscrit R (Reverse) sur la radio", listOf(
            COURT to "Tape le chiffre 8.",
            "F + 8" to "Allume ou éteint à la main l'éclairage de l'écran de la radio.",
            LONG to "Sur un canal qui passe par un relais, la radio écoute directement votre correspondant au lieu du relais. Si vous l'entendez, vous pouvez vous parler sans le relais. Cela ne touche pas à l'affichage.")),
        "9" to KeyHelpEntry("Touche 9 · Appel", "Inscrit CALL sur la radio", listOf(
            COURT to "Tape le chiffre 9.",
            "F + 9" to "Rend la main à l'éclairage automatique de l'écran.",
            LONG to "Va directement sur votre canal favori, appelé « canal d'appel ». Vous le choisissez une fois pour toutes dans le menu de la radio, au réglage n°44 « 1 Call ».")),
        "*" to KeyHelpEntry("Touche ✱ · Scan", "Codes, tonalités, scan", listOf(
            COURT to "Permet de taper un code DTMF : les bips de numérotation utilisés par certains relais.",
            "F + ✱" to "Cherche le code de tonalité (CTCSS ou DCS) utilisé sur la fréquence affichée. Ces codes servent à n'entendre que son groupe.",
            LONG to "Lance le scan : la radio parcourt les canaux ou les fréquences et s'arrête dès qu'elle entend quelqu'un.")),
        "0" to KeyHelpEntry("Touche 0 · Radio FM", "Chiffre 0, radio FM", listOf(
            COURT to "Tape le chiffre 0.",
            "F + 0 OU APPUI LONG" to "Allume ou éteint la radio FM : les stations de radio classiques, de 76 à 108 MHz.")),
        "F" to KeyHelpEntry("Touche F · Verrou", "Fonction, verrouillage", listOf(
            COURT to "Touche « fonction » : la touche suivante fera sa deuxième action, comme la touche Maj d'un clavier.",
            LONG to "Verrouille ou déverrouille le clavier, pour éviter les appuis accidentels."))
    )
}
