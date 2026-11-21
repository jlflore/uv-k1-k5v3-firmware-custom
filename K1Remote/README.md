# K1 Remote · version 0.23

## Nouveautés de la 0.23 (firmware Fusion PTT r13)
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
