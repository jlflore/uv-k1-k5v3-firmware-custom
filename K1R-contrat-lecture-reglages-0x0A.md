# Contrat K1R op 0x0A « lire des réglages » — v1.2 (r30, approuvé par ChatGPT le 23/11/2026)

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

- **v** = VFO concerné par les réglages de ligne (STEP, PTT ID), soit `gEeprom.TX_VFO`. Valeurs : 0 = A, 1 = B.
- **s = statut de l'entrée** :

  | s | Sens | len |
  |---|---|---|
  | 0 | Valeur lue | Voir les formats ci-dessous |
  | 2 | Position hors liste | 0 |
  | 3 | Menu hors liste blanche, ou fonction non compilée | 0 |

## Liste blanche r30 (8 réglages, figée pour r30)

| Menu | Source | Format |
|---|---|---|
| MENU_SQL | `gEeprom.SQUELCH_LEVEL` | u16 LE |
| MENU_BEEP | `gEeprom.BEEP_CONTROL` | u16 LE |
| MENU_SAVE | `gEeprom.BATTERY_SAVE` | u16 LE |
| MENU_SET_NFM | `gSetting_set_nfm` (si compilé) | u16 LE |
| MENU_STEP | `FREQUENCY_GetSortedIdxFromStepIdx(gTxVfo->STEP_SETTING)` | u16 LE, réglage de ligne (VFO v) |
| MENU_PTT_ID | `gTxVfo->DTMF_PTT_ID_TX_MODE` | u16 LE, réglage de ligne (VFO v) |
| MENU_D_LIVE_DEC | `gSetting_live_DTMF_decoder` | u16 LE |
| MENU_UPCODE | `gEeprom.DTMF_UP_CODE` (champ de 16 octets, chargé par `SETTINGS_LoadEepromDtmf`) | `len` de 0 à 16 : octets jusqu'au premier 0x00 ou 0xFF exclu, au plus 16. Aucun `strlen`. |

Les valeurs numériques sont celles que l'op 01 accepte pour le même menu : une lecture renvoie ce qu'il faut écrire pour retrouver le même réglage.

**Exception : MENU_UPCODE.**
- Le code se lit sur 16 caractères au plus.
- L'op 0x03 actuelle n'en écrit que 8 au plus (le tampon de 9 octets moins l'octet d'op).
- Un code de plus de 8 caractères reste lisible, mais l'op 0x03 ne peut pas le rétablir en entier.
- Le comparateur d'équipe vérifie toute la longueur lue.
- r30 ne change pas l'op 0x03.

## Taille
- Pire cas : 4 + 7 × (3 + 16) = 137 octets (doublons de MENU_UPCODE). Le tampon de réponse fait au moins 137 octets.
- Avant chaque écriture d'entrée, le firmware vérifie que `offset + 3 + len` reste dans le tampon.
- Une réponse d'erreur globale fait exactement 2 octets. Une réponse valide contient exactement `n` entrées, sans octet de plus.
- Ces 137 octets sont à vérifier sur les deux transports, USB et UART (boîtier).

## Ping
Le protocole passe à 5. L'appli n'envoie 0x0A que si le protocole est au moins 5.

## Côté appli : règle du témoin d'équipe
- La séquence de contrôle est : lecture 0x09, deux requêtes 0x0A (4 + 4 réglages), puis nouvelle lecture 0x09.
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
Une table explicite de correspondance est contrôlée : `MENU_*` → source firmware → valeur renvoyée par 0x0A → valeur attendue par l'op d'écriture.
- L'op d'écriture est 0x01 pour 7 réglages, et **0x03** pour MENU_UPCODE.
- `menu_settings.def` ne couvre que MENU_SAVE et MENU_BEEP. Les autres sont comparés aux branches de `MENU_ShowCurrentSetting`.

Cette table entre dans la dette déjà inscrite pour la migration 7.1.
