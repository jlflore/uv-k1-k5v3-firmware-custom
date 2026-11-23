# Contrat K1R op 0x0A « lire des réglages » — v2.1 (r32, en revue ; v2.0 = r31 validée par ChatGPT le 23/11/2026)

0x0A renvoie les réglages **actuellement chargés** dans le firmware. Pour STEP et PTT ID, ce sont les valeurs du VFO sélectionné, pas forcément celles enregistrées dans le canal mémoire. Le contenu mémorisé se contrôle à part (voir plus bas).

Lecture seule. Aucune écriture, aucune reconfiguration radio, aucune actualisation d'écran. La commande ne touche ni `gSubMenuSelection`, ni `gMenuCursor`, ni `gScanUseCssResult`.

## Requête
`0A n p1 … pn`
- `n` vaut de 1 à 7, car le tampon de commande `gCmdBuf` fait 9 octets.
- La longueur de la commande doit valoir **exactement** `n + 2`. Sinon, la réponse est le statut global 1.
- Chaque `p` est une position de menu, comptée à partir de 1, avec le même adressage que l'op 01 (`MenuListId[p-1]`).
- Les doublons sont permis : chaque entrée reçoit sa propre réponse.

## Réponse
`0A g v n` puis `n` entrées `p s len valeur[len]`
- **g = statut global** :

  | g | Sens | Suite de la réponse |
  |---|---|---|
  | 0 | Commande traitée | Les entrées suivent |
  | 1 | Trame mal formée (n hors 1..7 ou longueur ≠ n + 2) | Réponse réduite à `0A 01`. Seulement pour une trame acceptée par l'analyseur bas niveau : une charge de plus de 9 octets y est rejetée sans réponse 0x0A. |
  | 4 | Spectre ouvert | Réponse réduite à `0A 04` |

- **v** = VFO concerné par les réglages de ligne (STEP, PTT ID), soit `gEeprom.TX_VFO`. Valeurs : 0 = A, 1 = B. STEP et PTT ID sont lus sur `gEeprom.VfoInfo[v]`, le même indice que celui annoncé (et non via `gTxVfo`).
- **s = statut de l'entrée** :

  | s | Sens | len |
  |---|---|---|
  | 0 | Valeur lue | Voir les formats ci-dessous |
  | 2 | Position hors liste | 0 |
  | 3 | Menu hors liste blanche, ou fonction non compilée | 0 |

## Liste blanche (r30 : 10 réglages ; r31 : 12 avec l'éclairage ; r32 : 13 avec la liste de scan)

Correction v1.3 : la configuration d'équipe règle **SetOff** (veille, menu 67), et non **BatSav** (MENU_SAVE), qui sort de la liste. Elle règle aussi **D ST** et **D Prel**, qui y entrent. La liste correspond désormais exactement aux réglages généraux envoyés par la configuration d'équipe.

| Menu (position Fusion v7) | Source | Format |
|---|---|---|
| MENU_STEP (1) | `FREQUENCY_GetSortedIdxFromStepIdx(VfoInfo[v].STEP_SETTING)` | u16 LE, réglage de ligne (VFO v) |
| MENU_BEEP (40) | `gEeprom.BEEP_CONTROL` (menu_settings.def) | u16 LE |
| MENU_UPCODE (45) | `gEeprom.DTMF_UP_CODE` (16 octets) | `len` de 0 à 16 : octets jusqu'au premier 0x00 ou 0xFF exclu. Aucun `strlen`. |
| MENU_PTT_ID (47) | `VfoInfo[v].DTMF_PTT_ID_TX_MODE` | u16 LE, réglage de ligne (VFO v) |
| MENU_D_ST (48) | `gEeprom.DTMF_SIDE_TONE` (menu_settings.def) | u16 LE |
| MENU_D_PRE (49) | `gEeprom.DTMF_PRELOAD_TIME / 10` (unité du menu, 10 ms) | u16 LE |
| MENU_D_LIVE_DEC (50) | `gSetting_live_DTMF_decoder` | u16 LE |
| MENU_ABR (36), r31 | `gBackLight ? gBacklightTimeOriginal : gEeprom.BACKLIGHT_TIME` (comme le menu : réglage d'origine si l'éclairage est surchargé) | u16 LE |
| MENU_ABR_ON_TX_RX (39), r31 | `gSetting_backlight_on_tx_rx` | u16 LE |
| MENU_S_LIST (18), r32 | `gEeprom.SCAN_LIST_DEFAULT` (menu_settings.def) : 1..24, 25 ALL, 26 MIX | u16 LE |
| MENU_SQL (54) | `gEeprom.SQUELCH_LEVEL` | u16 LE |
| MENU_SET_OFF (67) | `gSetting_set_off` (menu_settings.def, si ENABLE_FEAT_F4HWN_SLEEP) | u16 LE |
| MENU_SET_NFM (68) | `gSetting_set_nfm` (si ENABLE_FEAT_F4HWN_NARROWER) | u16 LE |

Un menu non compilé répond s = 3.

Les valeurs numériques sont celles que l'op 01 accepte pour le même menu : une lecture renvoie ce qu'il faut écrire pour retrouver le même réglage.

**Exception : MENU_UPCODE.**
- Le code se lit sur 16 caractères au plus.
- L'op 0x03 actuelle n'en écrit que 8 au plus (le tampon de 9 octets moins l'octet d'op).
- Un code de plus de 8 caractères reste lisible, mais l'op 0x03 ne peut pas le rétablir en entier.
- Le comparateur d'équipe vérifie toute la longueur lue.
- r30 ne change pas l'op 0x03.

## Taille
- Pire cas : 4 + 7 × (3 + 16) = 137 octets (doublons de MENU_UPCODE). Le tampon de réponse fait au moins 137 octets.
- Une assertion de compilation garantit que le tampon couvre le pire cas.
- Avant chaque écriture d'entrée, le firmware vérifie que `offset + 3 + len` reste dans le tampon. En cas de dépassement (impossible par construction), il répond `0A 01` (2 octets) : jamais une réponse qui annonce n entrées sans les contenir.
- Une réponse d'erreur globale fait exactement 2 octets. Une réponse valide contient exactement `n` entrées, sans octet de plus.
- Ces 137 octets sont à vérifier sur les deux transports, USB et UART (boîtier).

## Ping
Protocole 5 = r30 (10 réglages) ; protocole 6 = r31 (12 réglages) ; protocole 7 = r32 (13 réglages). L'appli n'envoie 0x0A que si le protocole est au moins 5, et ne demande l'éclairage qu'à partir de 6.

## Usage de l'éclairage (r31, appli 0.72)
- Lecture de [36, 39] à chaque connexion, derrière un écran de démarrage (touches et PTT de l'appli bloqués, 3 s au plus), puis toutes les 15 s au plus, appli à l'écran, jamais pendant le PTT.
- Écran éteint = BLTime 0 et BLTxRx 0. C'est la **configuration** « Écran éteint », pas l'état physique des LED à l'instant : un allumage manuel (SIDE2, spectre) n'est pas « corrigé » en modifiant les réglages enregistrés.
- En furtif (préférence de l'appli pour le furtif léger, drapeau 0x10 de 0x09 pour le furtif profond), l'état est ambigu : l'appli garde sa préférence.
- Spectre ouvert : 0x0A répond 4. L'état précédent est gardé, et la synchronisation n'est pas déclarée réussie.
- Le verrou de démarrage est posé avant le lancement de la surveillance. Il est levé à la première lecture exploitable, après 3 s au plus, ou à la perte de la liaison. Une réponse d'une liaison qui n'est plus active ne recale rien.

## Côté appli : règle du témoin d'équipe
- La séquence de contrôle est : lecture 0x09, deux requêtes 0x0A (5 + 5 réglages), puis nouvelle lecture 0x09.
- Le témoin s'allume seulement si :
  - les champs **de configuration stables** sont identiques dans les deux lectures 0x09 : VFO sélectionné, type et numéro de canal, fréquence, puissance, modulation, largeur, sous-canaux, décalage, inversion. Les indicateurs d'activité (réception, squelch, fonction courante, ligne écoutée) sont exclus de la comparaison : une réception n'éteint pas le témoin ;
  - `v` est le même dans les deux réponses 0x0A ;
  - le VFO de 0x09 correspond à `v` ;
  - toutes les entrées ont s = 0 et la valeur attendue.
- Un changement de VFO pendant la séquence invalide le contrôle, s'il se voit dans les valeurs lues. Un aller-retour A → B → A entre deux lectures peut passer inaperçu. Ce risque résiduel est accepté pour une vérification de configuration, mais le contrôle **n'est pas atomique** et ne doit pas être présenté comme tel.
- Une donnée absente, inconnue ou incohérente laisse le témoin éteint.
- Tant que le canal mémoire n'a pas été relu, l'appli ne présente pas son contenu comme vérifié.

## Canal d'équipe en mémoire (sans changement firmware, étape ultérieure)
- Lecture EEPROM ponctuelle (0x0514 puis 0x051B) des seuls blocs du canal, en session exclusive : pas de 0x09, pas de touches, pas de PTT, aucune séquence en cours.
- À la fin, le parseur K1R est restauré et 0x09 est relu.
- En Multiboot, il faut vérifier la banque active.
- La création d'un op 0x0B est différée.

## CI
Trois contrôles tournent à chaque compilation du firmware :
- **`tools/k1r_check_0x0A.py`** (avant la compilation). Il échoue si :
  - la liste blanche d'app.c diffère de celle du contrat (10 réglages) ;
  - une source lue par 0x0A diffère de celle du menu (`menu_settings.def` pour BEEP, D ST et SetOff ; `MENU_ShowCurrentSetting` pour les autres). L'équivalence `VfoInfo[TX_VFO]` = `gTxVfo` est vérifiée dans `RADIO_SelectVfos` ;
  - un des 9 menus numériques est refusé en écriture par l'op 0x01. Le code d'appel s'écrit par l'op 0x03.
- **`tools/test_k1r_upcode.c`**, compilé et lancé sur PC. Il teste `K1R_CopyUpCode` (App/app/k1r_upcode.h) sur 7 cas : 16 caractères sans terminateur, champ plus long que 16, code vide, mémoire effacée (0xFF), terminateur en position 2, 0xFF en position 3, champ de 8 octets.
- **`tools/k1r_check_positions.py`** (après la compilation). Il reconstruit `MenuListId` à partir de `menu_list.def`, prétraité avec les `-D` réels de la compilation (`compile_commands.json` de App/ui/menu.c). Il vérifie les 10 positions (1, 40, 45, 47, 48, 49, 50, 54, 67, 68), puis les constantes de l'appli (`RadioSettings.kt`, branche k1remote-app).
  - Côté appli, `RadioSettingsTest` vérifie que les réglages attendus correspondent exactement à ceux qu'écrit la configuration d'équipe.

## Tests d'intégration (radio réelle)
- Réponse longue (7 × MENU_UPCODE) reçue intacte par USB et par le boîtier K1 Relais (Bluetooth, découpage à la MTU). L'appli 0.70 fait ce test une fois par connexion et l'inscrit au journal.
  - La longueur vaut 4 + 7 × (3 + longueur du code), soit 81 octets au plus avec un code écrit par l'op 0x03 (8 caractères). Les 137 octets exigent un code de 16 caractères, que seul le clavier de la radio peut saisir.
  - Le transport est déjà éprouvé au-delà : les images d'écran K5Viewer, bien plus longues, passent par le même chemin et le même boîtier.
- Témoin d'équipe stable pendant une réception radio.

## Liste de scan (r32, appli 0.74)
- Lue avec l'éclairage (requête [36, 39, 18]), aux mêmes moments. Elle n'est pas un réglage d'équipe et n'entre pas dans le témoin d'équipe.
- Écrite par l'op 0x01 (menu 18), comme au menu ScList. Refusée pendant le spectre (hors liste blanche du spectre).
