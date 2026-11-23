# K1 Remote Essai · version 0.66-essai (firmware Fusion PTT r28)

## 0.66-essai
- Écran éteint + Furtif : une seule règle calcule l'éclairage de la radio pour la combinaison des deux (LightPlan). Sortir du furtif ne rallume plus l'écran quand « Écran éteint » est actif ; rallumer l'écran pendant le furtif ne casse plus le furtif. Contrôlée dans ses 72 combinaisons et dans toutes les suites de 4 appuis (LightPlanTest).
- Écran éteint et Furtif ne sont notés (témoin, thème nuit) qu'une fois tous les réglages acceptés par la radio : un refus ne laisse plus un témoin allumé sans effet réel.
- Spectre ouvert pendant la vérification du firmware (connexion, coupure du boîtier) : la réponse « occupée » n'est plus prise pour un firmware sans commandes directes. Avant, l'appli repassait aux appuis de touches, et « Écran éteint » tapait MENU, chiffres… dans le spectre, dont il changeait les réglages. L'appli attend désormais la fermeture du spectre pour finir la vérification, et refuse raccourcis et réglages tant qu'il est ouvert (les touches simples du clavier restent permises : elles pilotent le spectre).
- Boutons d'équipe : plus de pastille ◆, qui laissait croire que la ligne active était réglée pour l'équipe (l'appli ne le vérifie pas : le sous-canal n'est pas dans l'état 0x09 v1).
- Spectre ouvert : la radio refuse les réglages ; l'appli le dit clairement (« Spectre ouvert… quittez-le (EXIT) ») au lieu du message erroné « fréquence hors des bandes », et n'envoie rien.

## 0.65-essai
- Témoin du raccourci Furtif : allumé aussi pour le furtif de l'appli (réglages de menus). Il ne suivait que le furtif profond du firmware (écran entièrement noir), d'où un bouton Furtif qui restait éteint alors que le mode était actif.
- Toutes les fonctions qui restent actives ont leur témoin : Écran éteint, Furtif, Verrou, Scan, Spectre, et désormais Inv. relais (lu dans l'état 0x09 de la ligne active) et Radio FM (écran FM affiché, commande 06, lue seulement si un raccourci Radio FM existe). En furtif comme en jour.
- Touche F verrouillée en mode nuit / furtif : « F VERROU » dans le rouge du thème au lieu du cadenas jaune, qui faisait un point lumineux.
- Règle des témoins isolée (ShortcutStates) et contrôlée dans ses 816 combinaisons d'entrées (ShortcutStatesTest) ; captures avec les 7 témoins allumés (jour, camouflage, nuit/furtif, interfaces complète et en opération).

## 0.64-essai
- Mes canaux : « Passer la radio sur ce canal » (appui long sur un canal, ou bouton dans sa fiche). Mêmes touches que le raccourci « Aller à un canal », passage en mode canal compris ; avec le r28, le passage est confirmé par la radio. Si le canal a été modifié sans être envoyé, l'appli prévient que la radio utilisera la version enregistrée chez elle.
- Témoins sur les raccourcis : Verrou, Furtif, Scan et Spectre s'allument comme « Écran éteint » tant que la fonction est active sur la radio (avec le r28 ; sans lui, Verrou d'après l'écran et Furtif d'après l'appli), et s'éteignent dès qu'elle ne l'est plus.
- Clavier de la radio verrouillé : les raccourcis et touches de l'appli sont refusés aussi (« Clavier verrouillé : appui long sur F… »), sauf l'appui long sur F et le raccourci Verrou. Avant, un raccourci de plusieurs touches déverrouillait, agissait puis reverrouillait tout seul.
- Touche F du clavier de l'appli : « F 🔒 » tant que le clavier de la radio est verrouillé (appui long sur F pour le déverrouiller).
- Le raccourci « Aller à un canal » est lui aussi confirmé par la radio (r28).

## 0.63-essai — appli d'essai, installable à côté de K1 Remote
- Identité distincte (fr.jlambert.k1remote.essai, nom « K1 Remote Essai ») : elle s'installe à côté de l'appli habituelle, avec ses propres réglages (vierges au départ). Une fois validée, la version définitive reprendra l'identité de K1 Remote et ses réglages.
- État de la radio lu directement dans le firmware (commande 0x09, contrat v1 figé, firmware r28) : ligne active, puissance de chaque ligne, mode canal ou fréquence, verrou du clavier. Il remplace la lecture des pixels de l'écran, qui ne sert plus qu'en secours (firmware sans 0x09) et ne peut jamais écraser l'état du firmware.
- Lectures toutes les 1,5 s (3 s pendant le spectre, aucune en arrière-plan ni pendant le PTT, espacées si la radio ne répond plus), et tout de suite après chaque réglage.
- État trop ancien (plus de 3 s, hors émission) : la puissance s'affiche grisée, jamais présentée comme actuelle.
- Puissance et changement de ligne confirmés par la radio ; sans confirmation en 2 s : « non confirmé » (jamais « échec » sans preuve).
- Toutes les commandes vers la radio passent désormais une par une : le firmware n'a qu'une place pour la commande reçue, et la surveillance du journal pouvait rarement écraser un réglage.
- Contrôles automatiques : RadioStatusTest (11) et RadioStateTest (12), en plus des 29 existants.

## 0.62
- Listes de scan corrigées : dans Fusion v7, un canal appartient à UNE liste (1 à 24, ou toutes), pas à plusieurs. L'appli lisait l'octet comme un masque de 8 listes : un canal en liste 3 s'affichait « Liste 1,2 », et cocher « listes 1 et 3 » écrivait la liste 5 dans la radio. Désormais : choix unique (Aucune, Liste 1 à 24 avec leur nom lu dans la radio, Toutes les listes), affichage et export CSV conformes.
- La modification d'un canal conserve son exclusion temporaire du scan (bit 7 des attributs), au lieu de l'effacer.
- Aide de l'écran : la liste du canal (nom de 3 lettres, 01 à 24, ALL, OFF) décrite comme la radio l'affiche.

## 0.61
- Configuration d'équipe : SetNFM (menu 68) est mis sur NARROW, la bande étroite standard du PMR446. Sur NARROWER (« N+ » à l'écran), la radio resserre aussi son émission ; toutes les radios de l'équipe ont désormais le même réglage. Firmware Fusion PTT seulement (commande directe).

## 0.60
- Journal des appels : par jour, une ligne par appel (heure, appelant, durée), vos émissions comprises. Les ouvertures brèves du squelch sont ignorées, les reprises d'un même appelant fusionnées ; 300 entrées au plus. « Partager » : appels seuls ou rapport complet. « Effacer » avec confirmation. Le journal technique reste à un bouton (« Technique ») et à l'appui long sur le titre.
- Mode nuit : couper la bascule automatique rend le jour si c'est elle qui avait mis la nuit (l'écran restait en rouge sur noir).
- Noms de canaux : les accents sont retirés (« Écoute » → « Ecoute ») au lieu de faire disparaître la lettre.
- Mes canaux : la phrase « Consultation seulement : rien n'est modifié dans la radio » (d'avant l'éditeur) est remplacée.
- Partage du journal : limité au plus récent, avec message d'erreur au lieu de fermer l'appli (un journal technique trop long faisait refuser le partage par Android).
- Aide de l'écran : sur l'écran principal, la pastille « i aide » ouvre directement l'aide complète de tout ce qu'on y voit ; « N+ / NAR+ » (SetNFM sur NARROWER) y est expliqué.
- Contrôle automatique des fonctions (FunctionsTest, 25 contrôles sans radio) à chaque compilation.

## 0.59
- Liste des canaux : un canal en écoute seule affiche aussi ses listes de scan (les étiquettes s'empilent à droite au lieu de se remplacer).

## 0.58
- Envoi réussi : une carte de réussite (coche qui se dessine, fond vert clair, rouge sombre en mode nuit) remplace les messages secs, après la configuration d'équipe et après l'envoi des canaux.
- « Réglage de la radio terminé » ne s'affiche plus quand un réglage a été interrompu : la réussite n'est annoncée que si tout est passé.
- À propos : « D'jé » dans les crédits.

## 0.57
- Interface « En opération » : le bouton Puissance suit de nouveau le passage de la ligne A à la ligne B (sa grille de raccourcis n'était jamais redessinée).
- Préférences › À propos : version, crédits (D'jé, Claude, Armel F4HWN, ChatGPT) et usage d'Internet décrit correctement (l'ancien texte disait à tort que l'appli n'y accède pas).
- Captures automatiques : écran des Préférences en entier ajouté.

## 0.56
- En-tête refait, vérifié sur captures d'écran Android réelles (tous les thèmes, portrait et paysage) :
  - ligne 1 : l'identité sur une seule ligne (drapeau, « K1 REMOTE » ou « K1 Remote », version, unité ou radio à droite). Avant, le titre se coupait sur deux ou trois lignes faute de place et la version disparaissait ;
  - ligne 2 : la pastille d'état et les cinq boutons. La pastille passe au vert en réception (nom de l'appelant, sinon « RÉCEPTION ») et au rouge en émission (« ÉMISSION ») ; les témoins RX/TX séparés disparaissent ;
  - en paysage, le bouton « ? » n'est plus coupé et la pastille réapparaît ; en camouflage, le pavé n'est plus écrasé.
- Marges extérieures réduites : l'écran du téléphone est mieux utilisé.
- Raccourcis un peu moins hauts dans les deux interfaces ; lien « ‹ Complète » en pastille en interface « En opération ».
- Compilation et captures automatiques sur GitHub (branche k1remote-app) : chaque version est compilée et ses écrans vérifiés avant livraison.

## 0.55
- Audit de non-régression 0.34 → 0.54 (chaque ligne retirée relue) : aucune fonction perdue. Un effet de bord corrigé : un raccourci « Réglage » visant un menu que le firmware réserve au clavier (13 TXLock, 16 ChDele, 17 ChName, 73 SetCfg, 75 F Lock, 76 350 En, 77 BatCal, 80 Reset) passe par le clavier au lieu d'être refusé.

## 0.54
- Configuration d'équipe de nouveau entièrement discrète avec le firmware r27 : l'enregistrement du canal (menu 15) repart en commande directe, sans parcourir les menus ni biper. Avec les firmwares r19 à r26, il reste fait au clavier (ces firmwares le refusent en commande directe).

## 0.53
- Raccourcis de réglage et configuration d'équipe : si la liaison vient de se rétablir (câble ou boîtier qui décroche), l'appli revérifie d'abord le firmware Fusion PTT (2 s au plus) au lieu de repartir en appuis de touches successifs.
- Éditeur de raccourcis : le nom proposé suit le type choisi (« Équipe », « Réglage », « Canal »…). Avant, un raccourci d'équipe créé ainsi gardait le nom de la première action libre (« Inv. relais »).

## 0.52
- Thèmes camouflage : écusson d'en-tête resserré (marges divisées par deux, drapeau plus petit, titre au pochoir et unité sans l'interligne de la police), d'environ un tiers moins haut.

## 0.51
- Raccourci Puissance : il suit de nouveau le passage de la ligne A à la ligne B (et inversement), même quand les deux lignes ont la même puissance.

## 0.50
- Appelant : le nom reste affiché tant qu'il parle et s'efface 1,5 s après la fin de la réception (3 s si son émission était déjà finie). Pendant qu'il parle, le bouton PTT l'annonce en vert : « ◉ Jérôme parle ».
- Puissance : appui long sur le raccourci Puissance → une colonne de puissances s'ouvre sous le doigt (HIGH en haut, LOW 1 en bas, avec les milliwatts) ; on glisse sans lâcher, chaque cran vibre légèrement ; au lâcher, la puissance choisie clignote, la colonne se ferme et la radio change (commande directe avec Fusion PTT, sinon appuis longs sur 6).
- Préférences › Journal › « Ouvrir le Journal » (toujours aussi par appui long sur le titre).

## 0.49
- Option « Vibrer aux appuis longs » (Préférences › Interface) : une vibration nette quand un appui long sur une touche ou un raccourci est reconnu, même si le retour tactile du téléphone est coupé. Jamais de son.

## 0.48
- Appelant : son nom s'affiche dans l'écran, sur une étiquette verte posée en haut de l'écran de la radio (interface complète) pendant 8 s, comme dans la bande de l'interface « En opération » ; plus de message noir en bas.
- Reconnaissance tolérante : un code doublé ou collé à une émission précédente (« AA1 », « A1AA1 », « A22A2 ») est reconnu ; le Journal note le code retenu et la chaîne reçue.
- Configuration d'équipe :
  - menu 67 SetOff sur OFF : la mise en veille automatique de F4HWN faisait écouter la radio environ toutes les 8 s seulement après quelques minutes d'inactivité, d'où des émissions qui « se perdaient » au bout d'un moment ;
  - attente avant le code d'appel portée à 600 ms (menu 49 D Prel) : le poste qui écoute, en économie de batterie, peut mettre jusqu'à 400 ms à se réveiller, d'où des codes amputés (« un seul bip »).

## 0.47
- Configuration d'équipe (firmware Fusion PTT) : l'enregistrement dans le canal se fait de nouveau au clavier (menu 15). Le firmware refuse, par sécurité, d'écrire les mémoires par commande directe : la configuration s'arrêtait donc avant d'enregistrer le canal d'équipe, et le code d'appel n'était jamais envoyé sur ce canal.
- Message plus juste quand la radio refuse un réglage direct (« réservé au clavier de la radio »).
- Mode d'emploi : installation des assets dans UV Studio après un firmware écrit dans un emplacement Multiboot.

## 0.46
- Retrait de l'installation des polices et textes (assets) par l'appli, ajoutée en 0.45 : l'installation complète d'un firmware se fait uniquement avec UV Studio.

## 0.45
- Préférences › Polices et textes de la radio : installe les « assets » d'un firmware (polices et textes de l'écran) par câble USB, sans UV Studio. L'appli lit leur état dans la radio, vérifie que le fichier .bin choisi est bien celui du firmware installé, efface la zone, écrit, fait valider : l'écran revient aussitôt. Jamais par le boîtier Bluetooth.

## 0.44
- Identification de l'appelant fiabilisée :
  - firmware r26 : la radio envoie elle-même le code reçu à l'appli (plus besoin de le lire sur son écran, qui ne l'affichait pas clavier verrouillé, en double veille complète, en furtif ou hors écran principal) ;
  - configuration d'équipe : D Live est activé même pour un joueur sans code à lui, et l'attente avant le code passe à 400 ms (à 200 ms, le poste qui écoute pouvait perdre le début du code) ;
  - un code reçu avec des caractères en trop est quand même reconnu ; un code inconnu invite à l'ajouter ; chaque code est noté dans le Journal ;
  - « En opération » : le nom s'affiche dans la bande verte (« RÉCEPTION · Paul »).

## 0.43
- Carte en paysage : plus de grande barre en bas. Le partage devient un bouton rond (point et ondes) en bas de la colonne de droite, coloré pendant le partage ; l'état tient dans une petite étiquette en haut à gauche. La carte occupe tout l'écran.

## 0.42
- En opération : le grand PTT reste toujours entier en bas ; les raccourcis défilent s'ils manquent de place, et leurs boutons s'abaissent un peu au-delà de six raccourcis.
- Carte : nouveau bouton nord dessiné à la manière des cartes topographiques (flèche mi-pleine mi-claire surmontée d'un N), qui tourne pour montrer le nord ; bleu quand la carte suit le regard.

## 0.41
- Correctif de compilation de la 0.40 (carte : annotation mal placée dans la lecture de la boussole). Code désormais compilé et vérifié avant chaque envoi.

## 0.40
- Carte : bouton « N » pour orienter la carte dans la direction du regard (boussole du téléphone) ; le « N » montre toujours le nord, un nouvel appui remet le nord en haut. Les noms restent droits, la carte se déplace et se zoome normalement.
- Carte : bouton « ↔ » pour afficher la distance à chaque coéquipier (dans son étiquette, avec un trait pointillé depuis votre position).

## 0.39
- Interface « En opération » (au choix avec l'interface complète) : grande bande d'état (veille avec ligne et puissance, RÉCEPTION en vert, ÉMISSION en rouge), écran de la radio, raccourcis en gros boutons sur deux colonnes, grand PTT, clavier replié derrière un bouton. Bascule : bouton « En opération » au-dessus des raccourcis, lien « Interface complète » en haut, ou Préférences › Interface.
- Préférences › Touches latérales de la radio : F1 et F2 (court et long) et MENU long, fonctions écrites en clair ; le choix part aussitôt vers la radio (firmware Fusion PTT). Valeurs actuelles lues dans la dernière sauvegarde des canaux.

## 0.38
- Raccourci « Écran éteint » : coupe l'éclairage de la radio, même en réception et en émission, sans mode nuit dans l'appli ; réappuyer le rétablit. Ajouté une fois à vos raccourcis existants ; le bouton se colore tant qu'il est actif.
- Canaux « écoute seule » (firmware r25) : réglage « Émission sur ce canal : bloquée » dans la fiche. Les canaux aviation et fluviaux de la bibliothèque sont bloqués d'office. La radio refuse alors toute émission sur ce canal, quel que soit le plan de bandes.
- Revue de ChatGPT, points retenus :
  - la fiche d'un canal reste ouverte tant que la saisie est invalide (champ en erreur) ;
  - renommer la sauvegarde ouverte ne fait plus perdre les modifications en cours ;
  - brouillon enregistré à chaque modification et repris à la réouverture ;
  - avant d'écrire : même firmware et tous les canaux identiques à la lecture (protège contre un autre emplacement Multiboot) ;
  - autorisation BOOT relue toutes les 2 s si les notifications du boîtier manquent ;
  - carte d'équipe : messages du relais limités à 16 Ko ;
  - textes secondaires de la liste agrandis (14 sp) ;
  - mode d'emploi : passages hérités de la 0.7 corrigés (Internet, émission).

## 0.37
- Carte d'équipe : votre propre point porte maintenant votre nom (en bleu), ou « Moi » si le nom n'est pas réglé dans Préférences › Carte d'équipe.

## 0.36
- Mes canaux : le nom du canal devient le titre de sa ligne (fréquence dessous) ; la puissance est indiquée en milliwatts.
- Toucher un canal ouvre directement sa fiche de modification (plus d'écran intermédiaire) ; « Annuler » ressort sans rien changer. Les informations (émission, verrous, octets bruts) sont en bas de la fiche.
- Puissance : chaque niveau affiche sa valeur (LOW 1 < 20 mW … HIGH ≈ 5 W).
- Champ Nom vide : l'exemple s'affiche « Sans nom — ex. : SCORPIO 1 » (on le prenait pour un vrai nom).

## 0.35 (boîtier K1 Relais 1.5)
- Canaux : modification, création, suppression et bibliothèque (aviation Lyon, fluvial Saône, PMR446) dans « Mes canaux ».
- Envoi à la radio : sauvegarde complète juste avant, arrêt si la radio a changé depuis la lecture, écriture des seuls blocs modifiés, chacun relu et comparé. Par le boîtier : appui bref sur BOOT exigé (boîtier 1.5).
- Mode furtif : ne s'active plus sans radio connectée (on pouvait passer en furtif boîtier seul, puis le retour prenait du temps).

## 0.34
- Clavier : au retour de veille du téléphone, le pavé ne garde plus seulement des touches 1-2-3 géantes (pavé reconstruit en lignes et colonnes de taille égale).

## 0.33 (firmware Fusion PTT r24)
- Aide : chaque mention affichée est expliquée (spectre : indications en haut, à gauche, en bas, barres et seuil ; écoute d'une fréquence : S-mètre, dBm, gains ; écran principal : barre du haut et chaque ligne).
- Firmware r24 : la version complète arrive à l'appli (« v7.0.0 r23 » était tronqué en « r2 », ce qui privait aussi l'appli du furtif profond). L'appli compense ce défaut avec les firmwares r10 à r23.

## 0.32
- Journal : l'aide contextuelle note ce que la radio répond sur l'écran affiché (diagnostic de l'aide du spectre).

## Nouveautés de la 0.31 (firmware Fusion PTT r22)
- Aide du spectre : touchez l'écran (ou « i ») pendant le spectre : l'aide décrit ce qui est affiché (plage balayée, pas, nombre de points, signal le plus fort, sensibilité, modulation) et les touches du spectre. Nécessite le firmware r22 (avant, le spectre ne répondait pas et l'aide était celle de l'écran principal).
- Mes canaux : la fiche d'un canal et le renommage s'affichent aux couleurs de l'appli (texte illisible sur fond gris foncé corrigé).

## Nouveautés de la 0.30 : « Mes canaux » (lecture seule)
- Nouveau bouton ▤ en haut : lit les 1 024 canaux de la radio (protocole de programmation d'origine, commandes de lecture uniquement : rien n'est écrit dans la radio), les enregistre dans une sauvegarde datée et les affiche.
- Liste des canaux occupés : fréquence en grand, nom, mode, puissance, sous-tons, décalage, listes de scan ; pastille « Écoute » pour les canaux qui ne peuvent pas émettre (AM, USB).
- Recherche (nom, numéro, fréquence), filtres (Tous, Émission, Écoute seule, Avec nom), fiche détaillée d'un canal.
- Sauvegardes : liste, nom (« Airsoft », « Écoute »…), suppression ; export du fichier .k1r où l'on veut (Drive, Téléchargements), avec un tableau CSV lisible dedans.
- Pendant la lecture (une vingtaine de secondes), l'écran de la radio, le diagnostic et le PTT de l'appli sont suspendus.

## Nouveautés de la 0.28 et 0.27 (firmware Fusion PTT r21, boîtier 1.4.1)
- Firmware r21 : police « Pochoir » pour les grands chiffres (menu SetFnt, Font 13 à 15 ; les choix 1 à 12 sont inchangés).
- Furtif profond : sortie de secours sans téléphone en maintenant EXIT 2 s sur la radio (EXIT n'a pas d'autre effet en furtif profond).
- Sécurité (revue croisée avec ChatGPT) : les réglages directs refusent désormais la remise à zéro, le plan de bandes (F Lock, TX Lock, 350EN), les étalonnages, la banque de configuration et les mémoires ; le PTT à distance coupé au bout de 30 s exige un relâchement avant de réémettre ; une touche invalide ne compte plus comme activité de l'appli.
- Boîtier 1.3.2 : un boîtier jamais associé n'accepte un téléphone que pendant 2 min après sa mise sous tension (avant : sans limite).
- Boîtier 1.4.1 : retour à l'association par simple confirmation (la carte n'a pas d'écran pour afficher un code ; la 1.4 est à ne pas utiliser).
- Boîtier 1.3.3 : un téléphone qui se connecte sans terminer l'association est déconnecté au bout de 15 s (il ne peut plus occuper la place unique de connexion).

## Nouveautés de la 0.26 (et 0.25)
- Furtif profond (Préférences › Mode furtif › « Profond », firmware r18) : l'afficheur de la radio s'éteint complètement (plus aucun segment visible, même en plein jour) et les voyants rouge/vert restent éteints ; l'écran reste visible dans l'appli. Sécurité : si l'appli ne donne plus de nouvelles pendant 6 s, l'afficheur se rallume tout seul.
- Paysage : les raccourcis s'affichent en entier sous l'écran, sans défilement ; l'écran de la radio prend la hauteur restante.
- Revue croisée avec ChatGPT : diagnostic périodique allégé pendant un PTT de l'appli (la liaison reste au PTT) ; « liaison sécurisée » jamais supposée en cas de doute.
- Boîtier K1 Relais 1.3 (à flasher) : plus aucune commande tronquée (tout ou rien, refus signalé), fermeture USB protégée pendant une émission, débranchement pendant l'ouverture plus perdu, chiffrement exigé par les caractéristiques Bluetooth, mémoire d'appairage pleine : plus de remplacement hors fenêtre BOOT, veille reportée si D+ est déjà haut, pertes affichées sur l'écran du boîtier (« Perdus x/y »).
- La lune déclenche le mode furtif complet (appli en nuit + écran de la radio éteint), comme le raccourci Furtif.

## Nouveautés de la 0.24 (firmware Fusion PTT r16)
- Paysage : l'écran de la radio est ajusté à son contenu et ne prend plus que la moitié de la hauteur ; les raccourcis réapparaissent dessous ; les boutons du haut ne sont plus coupés (c'est la pastille d'état qui se rétrécit).
- PTT plus sûr (audit ChatGPT) : les maintiens ne s'empilent plus et sont abandonnés dès le relâchement ; le diagnostic « pourquoi pas d'émission » ne passe plus par la file PTT.
- Keepalive : un seul en vol, le suivant est sauté en cas de congestion au lieu de créer des fils en série.
- Bluetooth : statut de chaque écriture vérifié ; la liaison n'est déclarée prête que si le boîtier a accepté les notifications ; « liaison sécurisée » recoupée avec l'association Android.
- Firmware r16 : le mode furtif éteint de nouveau l'écran de la radio (éclairage et inversion appliqués après un réglage direct). Firmware r15 : correction de la marge de troncature (dernier caractère qui pouvait déborder d'un pixel).

## Nouveautés de la 0.23 (firmware Fusion PTT r14)
- Firmware r14 : affichage borné (texte coupé à la largeur de l'écran, positions volontaires sur la ligne suivante préservées), revue de ChatGPT.
- Firmware r13, compilé par GitHub Actions avec le Docker d'Armel (Arm GNU 13.3.rel1) : nom court « FusPTT r13 ». Corrige un débordement de l'écran d'accueil (texte « <édition> Edition » de plus de 18 caractères) qui écrasait des variables : « ScnRng » et fréquences absurdes, écran vide, émission impossible.
- Réponses de la radio : une seule demande à la fois par commande (la surveillance et le diagnostic du PTT ne se volent plus leurs réponses).
- Bluetooth : une écriture sans accusé de la pile en 300 ms est signalée comme ratée.
- Journal : horodatage sûr entre fils.

## Nouveautés de la 0.22
- Paysage : l'écran de la radio réapparaît (il était écrasé à une hauteur nulle). Il prend sa hauteur naturelle ; les raccourcis occupent la place restante et défilent si besoin.

## Nouveautés de la 0.21 (firmware Fusion PTT r11)
- Quand la radio disparaît du boîtier (redémarrage, autre emplacement Multiboot), l'appli revérifie son firmware : plus de « pas de réponse de la radio » après un passage sur la version témoin.
- Firmware r12 : le journal montre aussi l'état de la puce radio BK4819 (générateur de tonalité 1 et 2, fréquence, registres 70/71/47/30/50/31).
- Firmware r11 : le diagnostic dit d'où vient une « programmation PC » (n° de commande, prise jack ou USB).

## Nouveautés de la 0.20
- Journal de la radio (appui long sur le titre) : événements de l'appli (touches, commandes, PTT, liaison) et de la radio (LED émission / réception, veille) ; avec Fusion PTT r6+, surveillance continue de l'état (commande 05 toutes les 1,5 s, 0,4 s pendant une émission) et note de chaque changement. Gardé dans un fichier (600 lignes), bouton « Partager ».
- Firmware r10 : à la connexion de l'appli (commande 04), la radio renvoie l'écran entier d'un coup au lieu de le reconstruire morceau par morceau.
- Configuration d'équipe avec code d'appel : D ST OFF (on n'entend plus son propre code) et D Prel au minimum (30 ms).
- Pastille « i aide » : réellement visible (elle était coupée par le cadre), posée sur le coin bas droit du cadre ; le cadre retrouve son épaisseur normale.

## Nouveautés de la 0.19 (firmware Fusion PTT r9)
- Firmware r9 : le PTT à distance marche aussi quand la radio est en PTT « une pression » (il reste en « maintenir pour émettre »).
- Si la radio n'émet pas 0,8 s après l'appui sur le bouton PTT, l'appli lit son état et dit pourquoi (une pression, programmation PC, 1750 Hz, émission interdite, batterie).
- Toucher l'écran de l'appli quand la radio est dans un menu (ou FM, journal…) : ouvre l'aide de cet écran au lieu de chercher à changer de ligne A/B.
- Pastille « i aide » orange dans le « menton » du cadre de l'écran, arrondi calé sur le coin du cadre.

## Nouveautés de la 0.18 (firmware Fusion PTT r8)
- Firmware r8 : chaque réglage direct est enregistré aussitôt (comme après une touche). Avant, il restait en attente jusqu'à la touche suivante : un passage A/B l'appliquait à l'autre ligne et la ligne réglée était perdue.
- Réponses de la radio rangées par commande : l'aide « i », le diagnostic et la vérification du firmware ne se volent plus leurs réponses.
- Pastille « i » : couleurs de l'accent, à cheval sur le bord bas du cadre de l'écran.

## Nouveautés de la 0.17 (firmware Fusion PTT r7)
- Aide contextuelle : pastille « i » sur le bord du cadre de l'écran (ou « ? » puis l'écran). La radio indique l'écran affiché (commande 06 du firmware r7) ; fiche du réglage de menu en cours (rôle, valeurs, conseil airsoft) pour les ~80 réglages de Fusion v7, ou de l'écran (catégories, scanner, FM, journal).

## Nouveautés de la 0.16
- Configuration d'équipe : deux réglages de plus dans le profil (partagés par le lien / QR) : bip ON/OFF (OFF par défaut) et « après le réglage, la radio reste en mode fréquence (par défaut) / sur le canal ».
- Éditeur de raccourci : la page ne remonte plus en haut quand on change une valeur.
- Messages de l'écran (ex. première association du boîtier) : retour à la ligne automatique, texte toujours entier dans la vitre.

## Nouveautés de la 0.15 (firmware Fusion PTT r6)
- Diagnostic : appui long sur le titre → état d'émission lu en direct dans la radio (VfoState, F Lock, TXLock, fréquence TX, 1750 Hz, programmation PC, PTT physique / à distance, touches latérales, PTT ID, chrono TX). Bouton « Copier ».
- Firmware r6 : commande 05 (diagnostic) ; aucun autre changement de comportement par rapport à r5.

## Nouveautés de la 0.14 (boîtier K1 Relais 1.2, firmware Fusion PTT 4e version)
- Sous le titre : modèle de la radio (selon SetNav) et version du firmware Fusion PTT ; les flèches de l'appli suivent SetNav.
- Veille du boîtier (Préférences › Liaison) : jamais / 5 / 10 / 30 min sans radio ; réveil quand on allume la radio (ligne D+) ou par BOOT.
- Boîtier : journal discret (la LED bleue du port COM ne clignote plus en continu).
- Firmware numéroté : « Fusion PTT r4 » (fichier f4hwn.fusion-ptt-r4.v7.0.0.bin), révision affichée au démarrage, dans le Multiboot et sous le titre de l'appli.
- Mode d'emploi mis à jour (boîtier, firmware Fusion PTT, appelants, câbles).

## Nouveautés de la 0.13.1
- Détection du firmware Fusion PTT plus fiable (dès que l'écran arrive, en câble comme en Bluetooth) ; la pastille affiche « · PTT » quand il est reconnu.

## Nouveautés de la 0.13 (avec le firmware Fusion PTT, 3e version)
- Réglages directs : l'appli reconnaît le firmware Fusion PTT et règle la radio par commandes (réglage de menu, fréquence exacte, code DTMF), sans naviguer dans les menus. Configuration d'équipe en quelques secondes, mode furtif et réglages simples aussi.
- Les touches envoyées par le téléphone ne bipent plus (firmware Fusion PTT).
- Identification des appelants : « Mon code d'appel » (Préférences › Noms des appelants), réglé sur la radio par la configuration d'équipe (45 UPCode, 47 PTT ID, 50 D Live).
- Sans ce firmware, tout fonctionne comme avant (touches).
- Commandes : AA 55 08 len [01 n lo hi | 02 f(10 Hz, 4 o.) | 03 code… | 04] chk ; réponse AA 55 09 00 len [op, état…] 0A.

## Nouveautés de la 0.12.4
- Nouvelle action de raccourci « Bip off » (réglage 40 Beep sur OFF).

## Nouveautés de la 0.12.3
- Réception pendant un long réglage : l'appli attend la fin de l'appel puis recommence le réglage depuis le début (3 reprises au plus).
- Le bouton PTT de l'appli est bloqué pendant un réglage.

## Nouveautés de la 0.12.2
- Clavier de la radio verrouillé (cadenas) : les réglages et raccourcis déverrouillent (appui long sur F) puis reverrouillent automatiquement ; une touche isolée affiche « Clavier verrouillé ».

## Nouveautés de la 0.12.1
- Le bip des touches n'est plus jamais rallumé par l'appli (sortie du furtif, fin des réglages), sauf si « 40 · Beep » est réglé sur ON dans les Préférences.
- Les longs réglages coupent toujours le bip au début.
- La version de l'appli s'affiche à droite du titre, sur l'écran principal.

## Nouveautés de la 0.12
- Correction : après une émission, l'écran ne se vide plus pour se reconstruire ligne à ligne en 20 s.
- Firmware « Fusion PTT » (2e version) : l'écran continue d'être envoyé pendant l'émission (barre TX visible).
- Bip des touches : coupé en mode furtif ; coupé pendant la configuration d'équipe et les longs réglages (option) ; réglage normal « 40 · Beep ».

## Nouveautés de la 0.11
- Bouton PTT à distance (Préférences › Affichage), pour le firmware « Fusion PTT » (F4HWN v7.0.0 modifié) :
  trame AA 55 06 v (v xor A5) répétée toutes les 200 ms ; la radio relâche seule après 0,9 s sans nouvelles, 30 s au plus.

## Nouveautés de la 0.10.1
- Préférences : un réglage ne fait plus remonter l'écran tout en haut.
- Carte : commandes placées sous la barre d'état et au-dessus de la barre de navigation.

## Nouveautés de la 0.10
- Carte d'équipe (bouton ⌖) : fond IGN photo aérienne ou plan, ma position et celles des coéquipiers, direction, ancienneté.
- Partage par Internet (4G/5G) via un relais MQTT public, positions chiffrées AES-GCM avec la clé de l'équipe (QR code / lien k1remote://carte).
- Service de premier plan : le partage continue écran éteint ; notification avec bouton Arrêter.
- Préférences › Carte d'équipe : nom du joueur, intervalle d'envoi (5/15/30/60 s), créer / partager / rejoindre une équipe.
- Nouvelles autorisations : Internet, position, notifications.

## Nouveautés de la 0.9 (avec le boîtier K1 Relais 1.1)
- Liaison Bluetooth chiffrée et appairée ; un nouveau téléphone ne s'appaire qu'après un appui sur BOOT du boîtier.
- Lumière du boîtier réglable (éteinte / faible / normale), éteinte d'office en mode furtif ; couleurs plus lisibles.
- « Oublier ce boîtier » supprime aussi l'association Android.

## Nouveautés de la 0.8.1
- Correction : après un redémarrage de la radio, l'écran de l'appli ne garde plus de traces de l'ancien affichage (économiseur d'écran…).

## Nouveautés de la 0.8
- Liaison sans fil avec le boîtier **K1 Relais** (ESP32-S3) en Bluetooth : recherche, mémorisation et reconnexion automatiques.
- Préférences › « Liaison avec la radio » : Auto / Câble / Bluetooth, et « Oublier ce boîtier ».
- Le câble reste prioritaire : brancher la radio au téléphone quitte le Bluetooth.
- Compatible F4HWN v7.0.0 (numéros de menu, protocole et polices inchangés).


Télécommande Android pour Quansheng UV-K1 / UV-K5 V3 sous firmware **F4HWN Fusion**, par le port USB-C de la radio.

## Nouveautés de la 0.7
- **Mode d'emploi intégré** (assets/mode-emploi.html, hors ligne, sections en couleurs pastel, version rouge en mode nuit) : Préférences › À propos › Mode d'emploi, ou bouton « Mode d'emploi » dans le bandeau d'aide « ? ».

## Nouveautés de la 0.6
- Police pochoir **Black Ops One** intégrée (licence SIL OFL, `assets/fonts/OFL.txt`) pour les thèmes camouflage.
- Configuration d'équipe : sous-canal **réception** et **émission** réglables séparément (« Aucun » possible dans chaque sens) ; puissance affichée avec sa valeur approximative (LOW 3 ≈ 250 mW…) ; toute fréquence acceptée (arrondie au kHz si elle ne tombe sur aucun pas).

## Nouveautés de la 0.5
- **6 thèmes camouflage** en plus de Ciel et Clair minimal : Vegetato (Italie), OCP Scorpion (USA), CCE (France), Désert, Digital Flecktarn (Allemagne), Woodland (OTAN). Motif dessiné en fond, en-tête en écusson avec drapeau, touches claires sur panneaux foncés.
- **Sélecteur de thème** : Préférences › Thème de jour › l'aperçu défile d'un geste, « Choisir » valide celui qui est affiché.

## Nouveautés de la 0.4
- **Bouton « Configuration d'équipe »** (nouveau type dans l'éditeur, pastille ◆) : fréquence, sous-canal CTCSS/DCS, puissance, largeur, squelch, n° de canal. Un appui tape tout sur la radio (≈ 25 s), enregistre dans le canal (ChSave) puis passe dessus.
- **Partage** : appui long sur le bouton › « Partager à l'équipe » : QR code à scanner, ou lien `k1remote://equipe?…` à envoyer ; import par le QR code ou par « Coller un profil reçu » dans l'éditeur.
- **Noms des appelants** : si la radio affiche un code DTMF reçu (menu 50 D Live), l'appli affiche le prénom (liste dans Préférences).
- L'appli reconnaît si la ligne active est en mode canal ou fréquence ; « Aller à un canal » tape désormais 4 chiffres (F4HWN) et passe en mode canal si besoin.

## Réglages utilisés par la configuration d'équipe (F4HWN Fusion 6.2)
01 Step · 02 Power · 03 RxDCS · 04 RxCTCS · 05 TxDCS · 06 TxCTCS · 07 TxODir · 09 W/N · 15 ChSave · 54 Sql. Fréquence tapée en mode VFO (6 chiffres).

## Nouveautés de la 0.3
- **Toucher l'écran de la radio** : moitié haute = ligne A, moitié basse = ligne B.
- **Furtif** agit sur la radio : BLTxRx (39) OFF, BLMax (38) à 1 ou BLTime (36) OFF, SetInv (60) ON ; réappuyer rétablit les réglages normaux (Préférences › Réglages normaux de la radio).
- Avertissement si la radio est débranchée en mode furtif.
- Appui long sur un raccourci : 0,8 s ; action choisie bien visible dans l'éditeur.

## Nouveautés de la 0.2
- **Nouveau design** : thème **Ciel** (fond bleu ciel, touches blanches), thème **Clair minimal**, et **mode nuit** rouge sur noir.
- **Bouton lune ☾** pour passer en mode nuit ; bascule automatique possible selon des horaires (Préférences).
- **Raccourcis programmables** (9 au maximum), enregistrés sur le téléphone :
  - *Actions disponibles* : A ⇄ B, Puissance, Appel, Scan, Verrou, Inv. relais, Radio FM, Spectre, Furtif ;
  - *Réglage de menu* : n° de réglage + valeur (ex. 02 = 3 pour LOW 3) ;
  - *Aller à un canal* ;
  - *Suite de touches* enregistrée, avec appuis longs.
  - Ajout par « + Ajouter », modification par le bouton « Modifier » ou un appui long, pas de doublons.
- **Bouton Puissance** : affiche la puissance actuelle (pastille verte / orange / rouge), lue sur l'écran de la radio.
- **Mode aide « ? »** : touchez ensuite une touche ou un raccourci pour voir sa fiche ; « Aide complète » ouvre l'aide du firmware directement sur cette touche.
- **Étiquettes des touches** en français selon les fonctions F4HWN (5 · Spectre, 8 · Inv. relais, 9 · Appel…) ou inscriptions d'origine (Préférences).
- **F1 / F2** à la place de SIDE 1 / SIDE 2.
- **Icône** de l'appli (carré arrondi).
- Paysage : écran de la radio à gauche, clavier à droite.

## Liaison
- L'appli s'ouvre toute seule quand la radio est branchée (option OTG activée sur le téléphone).
- Toucher la pastille d'état connecte ; un **appui long** dessus déconnecte.

## Construire
Ouvrir le dossier `K1Remote` dans Code on the Go (ou Android Studio), puis compiler et installer comme pour la 0.1.

## À vérifier au premier essai (et me le dire)
0. Configuration d'équipe : la radio arrive-t-elle bien sur le canal, avec la bonne fréquence et le bon sous-canal ? (sinon, noter où ça décroche)
1. Le bouton Puissance affiche-t-il la bonne valeur, et change-t-il quand on appuie dessus ou sur la touche 6 de la radio ?
2. Le raccourci « Réglage de menu » (ex. 02 = 3) aboutit-il bien sur la radio ? Sinon, il faudra peut-être allonger les pauses entre touches.
3. Les flèches vont-elles dans le bon sens (sinon Préférences › Flèches).
4. L'aide complète s'ouvre-t-elle sur la touche consultée ?

## Fichiers
| Fichier | Rôle |
|---|---|
| `MainActivity.kt` | Écran principal, raccourcis, mode aide, éditeur |
| `PreferencesActivity.kt` | Préférences |
| `HelpActivity.kt` + `assets/aide.html` | Aide complète hors ligne |
| `Shortcuts.kt` | Raccourcis, actions, touches envoyées |
| `ScreenReader.kt` + `FirmwareFonts.kt` | Lecture de la puissance sur l'écran de la radio |
| `Theme.kt`, `Prefs.kt`, `KeyHelp.kt`, `LcdView.kt` | Thèmes, réglages, fiches d'aide, affichage de l'écran |
| `UsbCdc.kt`, `K5ViewerProtocol.kt` | Liaison USB et protocole du firmware |

## Protocole utilisé (firmware `App/k5viewer.c`, `App/driver/vcp.c`)
| Sens | Trame | Rôle |
|---|---|---|
| Tél → radio | `55 AA 00 00` | Keepalive, toutes les 250 ms |
| Tél → radio | `AA 55 03 <touche>` | Appui court |
| Tél → radio | `AA 55 04 <touche>` | Appui long |
| Radio → tél | `[F0\|flags] AA 55 02 <lenH> <lenL> {idx + 8 octets}… 0A` | Différence d'écran |

Le PTT est volontairement refusé par le firmware sur la liaison série : l'appli ne peut pas émettre.
