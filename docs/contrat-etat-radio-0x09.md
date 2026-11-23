# Contrat de protocole : commande 0x09 « état de la radio », format v1

*Fusion PTT r28 et K1 Remote. **Contrat figé le 23 novembre 2026**, après accord de ChatGPT : c'est le document de référence. Il reprend la version 1.2, rédigée par Claude le 23 novembre 2026, après la revue formelle de ChatGPT et la vérification de ses huit questions dans les sources r27 (§ 9). Précédente : version 1.1. Elle intègre la seconde revue de ChatGPT : valeurs inconnues selon le type, spectre, commandes au résultat indéterminé, ligne en réception. Le format binaire porte toujours le numéro 1 : il n'est pas encore figé.*

## 1. Principes

- **Lecture seule.** La commande ne modifie rien, ne réveille pas l'écran et ne relance pas le délai de veille.
- **Valeurs propres au protocole.** Les champs ne recopient jamais une valeur interne du firmware : le firmware traduit chaque valeur interne vers une énumération définie ici. Si F4HWN 7.1 change ses énumérations, seule la table de traduction du firmware change ; l'appli ne bouge pas.
- **Taille figée.** Le format v1 fait exactement 32 octets. Une version future ne pourra que s'allonger, avec des champs ajoutés à la fin. Les 32 premiers octets garderont le même sens.
- **Ordre des octets.** Tous les entiers de plusieurs octets sont non signés, en petit-boutiste (little-endian).
- **Valeur « inconnue » selon le type.** Elle vaut 0xFF pour une énumération ou un octet, 0xFFFF pour un entier 16 bits, 0xFFFFFFFF pour un entier 32 bits. Les champs de bits n'ont pas de valeur inconnue : ils ne valent que si le champ qui les porte est connu. Par exemple, si le type d'une ligne vaut 0xFF, tout le bloc de cette ligne est à ignorer. L'appli affiche alors « — » et ne devine pas.

## 2. Requête

Trame habituelle des commandes directes, avec une donnée d'un seul octet :

`AA 55 08 01 09 chk`, avec `chk = 08 xor 01 xor 09`.

## 3. Réponse

`AA 55 09 00 20 [32 octets] 0A` (0x20 = 32).

| Octet | Nom | Valeurs |
|---|---|---|
| 0 | opcode | 0x09 |
| 1 | statut | 0 = OK. Tout autre valeur : le reste est à ignorer. |
| 2 | format | 1 |
| 3 | ligne sélectionnée | 0 = A, 1 = B. C'est la ligne marquée ▶, celle qui émettra (`TX_VFO`). |
| 4 | indicateurs | voir § 3.1 |
| 5 | fonction | voir § 4.1 |
| 6–17 | ligne A | bloc de ligne, § 3.2 |
| 18–29 | ligne B | bloc de ligne, § 3.2 |
| 30 | ligne en réception | 0 = A, 1 = B, 2 = autre fréquence surveillée (veille étendue « Full Watch »), 0xFF = aucune ou inconnue. Renseignée seulement quand la fonction vaut 2 ou 3 ; sinon 0xFF. Voir § 5. |
| 31 | réservé | 0 |

L'octet 30 répond à la précaution A de ChatGPT. En double veille ou en bande croisée, la ligne qui reçoit (octet 30) peut différer de la ligne sélectionnée (octet 3).

### 3.1 Indicateurs (octet 4)

| Bit | Sens quand il vaut 1 |
|---|---|
| 0 | clavier verrouillé (`gEeprom.KEY_LOCK` ; `gKeypadLocked` n'est que le compte à rebours du message « verrouillé », il ne sert pas ici) |
| 1 | double veille active |
| 2 | bande croisée active |
| 3 | SetNFM = NARROWER (la bande étroite devient « N+ ») |
| 4 | mode furtif K1 Remote actif |
| 5 | écran de la radio en veille (même information que le bit 0 des drapeaux du flux d'écran) |
| 6–7 | 0 (réservés) |

### 3.2 Bloc de ligne (12 octets, décalage relatif au début du bloc)

| Décalage | Nom | Valeurs |
|---|---|---|
| +0 | type | voir § 4.2 |
| +1–2 | numéro (u16) | canal mémoire : 1 à 1024 ; mode fréquence : bande 1 à 7 ; météo : 1 à 10 ; 0xFFFF = inconnu |
| +3–6 | fréquence de réception (u32) | en unités de 10 Hz (446,00625 MHz = 44 600 625) ; 0xFFFFFFFF = inconnue |
| +7 | puissance | voir § 4.3 |
| +8 | modulation | voir § 4.4 |
| +9 | largeur effective | voir § 4.5 |
| +10 | drapeaux du canal | voir § 3.3 |
| +11 | liste de scan | **un numéro, pas un masque** (vérifié dans le code : `ChannelAttributes_t.scanlist`, `RADIO_IsChannelInScanList`) : 0 = aucune ou mode fréquence, 1 à 24 = cette liste, 25 = toutes les listes, 0xFF = inconnue |

### 3.3 Drapeaux du canal (octet +10 du bloc de ligne)

| Bit | Sens quand il vaut 1 |
|---|---|
| 0 | écoute seule (RX_ONLY, propre à Fusion PTT) |
| 1 | émission verrouillée par le plan de bandes (TX_LOCK) |
| 2 | décalage relais présent |
| 3 | inversion active (R) |
| 4–7 | 0 |

## 4. Énumérations

### 4.1 Fonction (octet 5)

| Valeur | Sens |
|---|---|
| 0 | veille, squelch fermé |
| 1 | émission |
| 2 | réception d'un signal |
| 3 | écoute forcée (squelch ouvert) |
| 4 | économie de batterie (la radio sommeille entre deux écoutes) |
| 5 | spectre ou balayage |
| 0xFF | inconnue |

### 4.2 Type de ligne (octet +0)

| Valeur | Sens |
|---|---|
| 0 | canal mémoire |
| 1 | mode fréquence |
| 2 | canal météo |
| 0xFF | inconnu |

### 4.3 Puissance (octet +7)

| Valeur | Sens |
|---|---|
| 0 | USER (selon SetPwr) |
| 1 à 5 | LOW 1 à LOW 5 |
| 6 | MID |
| 7 | HIGH |
| 0xFF | inconnue |

### 4.4 Modulation (octet +8)

| Valeur | Sens |
|---|---|
| 0 | FM |
| 1 | AM |
| 2 | USB |
| 3 | autre |
| 0xFF | inconnue |

### 4.5 Largeur effective (octet +9)

| Valeur | Sens |
|---|---|
| 0 | large (W) |
| 1 | étroite (N) |
| 2 | encore plus étroite (N+ : étroite avec SetNFM = NARROWER) |
| 0xFF | inconnue |

## 5. Firmware : règles de construction

- La réponse est construite dans `K1R_RunCommand()`, qui s'exécute dans la boucle principale (`APP_TimeSlice10ms`), là où l'état radio est modifié. L'interruption d'horloge ne fait que lever des drapeaux. L'instantané est donc cohérent sans verrou, et sans attente ni accès mémoire externe pendant la collecte.
- Les lignes sont lues dans `gEeprom.VfoInfo[0]` et `[1]`, jamais au travers de `gRxVfo` : une appli en surimpression (overlay) peut détourner ce pointeur.
- **Ligne en réception (octet 30), vérifiée dans le code r27.** `gEeprom.RX_VFO` ne suffit pas. En veille étendue (« Full Watch »), il peut valoir A ou B alors que `gRxVfo` pointe sur une fréquence de fond (`gFullWatchBackgroundVfo[]`) ou de remplacement (`gFullWatchForegroundVfo`) ; une appli en surimpression le change aussi temporairement (`app_overlay.c`). La règle est donc :
  - `gRxVfo == &gEeprom.VfoInfo[0]` → 0 ;
  - `gRxVfo == &gEeprom.VfoInfo[1]` → 1 ;
  - `gRxVfo` égal à une fréquence de fond ou de remplacement de la veille étendue → 2 ;
  - tout autre cas (appli en surimpression, balise…) → 0xFF.
  
  Une version future du format pourra ajouter, en fin de trame, la fréquence de cette autre fréquence surveillée.
- Toute valeur interne hors des tables de traduction devient la valeur « inconnue » de son type.
- **Taille et cadrage vérifiés.** `K5VIEWER_SendReply` envoie `AA 55 09`, puis la longueur sur 16 bits poids fort en premier (`00 20`), le contenu et `0A`. La commande 05 envoie déjà 43 octets par ce chemin. Côté appli, `K5ViewerParser` agrandit son tampon à la longueur annoncée.
- **Une réponse d'erreur** (statut ≠ 0) ne fait que 2 octets : opcode et statut. Le statut 9 signifie « commande inconnue » (cas `default` de `K1R_RunCommand`).
- La commande 0x04 (présence) annonce la version 3 du protocole, pour dire que 0x09 est disponible. L'appli ne se fie pourtant qu'à la réussite d'une requête 0x09.

## 6. Appli : règles

### 6.1 Détection

À la connexion, l'appli envoie 0x09.

- **Réponse valide :** l'état vient du firmware (source FIRMWARE).
- **Statut 9** (commande inconnue), **ou pas de réponse à 3 essais :** repli sur la lecture de l'écran (source SCREEN_FALLBACK), sans réessayer pendant cette connexion.

### 6.1 ter Moments où la radio ne répond pas à 0x09 (constatés dans le code r28)

- **Spectre** : le firmware répond par le statut 4, « spectre en cours ». L'appli traite ce cas comme « radio occupée » : elle garde le dernier état, sans retour à la lecture de l'écran, et considère que la fonction est « spectre ou balayage ». Ce n'est pas un refus de commande.
- **Appli en surimpression** (overlay) : les commandes ne sont pas traitées. L'appli voit un délai dépassé et un état périmé, comme prévu au § 6.4.

Ces deux cas ne changent pas le format binaire.

### 6.1 bis Pas de retour en arrière

Après une première réponse 0x09 valide sur une connexion, l'appli ne repasse jamais à la lecture de l'écran à cause d'une réponse tardive ou perdue. Elle garde le dernier état, le signale comme périmé et réessaie en espaçant les tentatives. Une déconnexion remet la détection à zéro.

### 6.2 Validation

Une trame invalide est rejetée sans effacer le dernier état valable. Le statut est lu avant la longueur, puisqu'une réponse d'erreur est plus courte. Une réponse n'est acceptée que si :

- format 1 : exactement 32 octets ; format supérieur : au moins 32 octets, et seuls les 32 premiers sont lus ;
- son statut vaut 0 ;
- son format est supérieur ou égal à 1 ;
- chaque énumération est connue ou vaut 0xFF ;
- les octets réservés de la v1 valent 0.

Un format supérieur à 1 est accepté : seuls ses 32 premiers octets sont lus.

### 6.3 Priorité

- Un état FIRMWARE valide est toujours prioritaire.
- La lecture des pixels n'écrase jamais un état FIRMWARE.

### 6.4 Âge de l'état

- **Frais :** moins de 3 s.
- **Périmé :** au-delà de 3 s, connexion encore établie. L'affichage est grisé avec une mention discrète, jamais présenté comme actuel.
- **Déconnecté :** valeurs effacées.
- **Pendant une émission** (lectures suspendues), les données ne sont pas grisées, même au-delà de 3 s : l'absence de lecture est voulue. La liaison reste surveillée à part, par les images d'écran et les accusés du PTT. Une vraie perte de liaison pendant l'émission est donc toujours signalée.

### 6.5 Commandes en cours

Un réglage envoyé (puissance, ligne A/B, canal) est « demandé » tant qu'un état 0x09 ne l'a pas confirmé. Il aboutit à l'un de trois résultats :

- **Confirmé** : un état 0x09 reçu après l'accusé de la commande montre la nouvelle valeur.
- **Refusé** : l'accusé de la commande porte un statut non nul (réglage interdit, valeur hors limites…). L'appli le dit, avec la raison.
- **Indéterminé** : pas d'accusé, ou pas d'état confirmant la valeur dans les 2 s. La commande a peut-être été appliquée, et sa réponse s'est perdue. L'appli affiche « non confirmé », jamais « échec ». Elle relance une lecture 0x09, puis montre la valeur réelle de la radio dès qu'elle arrive.

La nouvelle valeur ne s'affiche qu'une fois confirmée.

### 6.6 Fréquence des lectures

| Situation | Lecture |
|---|---|
| connexion, retour au premier plan | immédiate |
| après une commande (puissance, A/B, canal, configuration d'équipe) | immédiate, après l'accusé de la commande |
| écran principal stable | toutes les 1,5 s |
| émission en cours | suspendue (le PTT est prioritaire), reprise 300 ms après la fin |
| spectre ou balayage (fonction = 5, ou écran spectre) | ralentie : toutes les 3 s, pour voir la fin du spectre ; une commande de l'utilisateur déclenche quand même une lecture immédiate pour sa confirmation, si la liaison est libre |
| appli en arrière-plan | aucune |

### 6.7 Modèle

- `RadioSnapshot` : ligne sélectionnée, ligne en réception, fonction, indicateurs, lignes A et B, heure de réception, source (FIRMWARE, SCREEN_FALLBACK, DEMO).
- À côté : l'état de la connexion et l'état des commandes en cours.
- Tout l'affichage lit ce modèle, sans condition de version dispersée dans le code.

## 7. Tests (CI, sans radio)

- **Décodage de trames fixes :**
  - fréquences minimale (18 MHz) et maximale (1 300 MHz) ;
  - ligne A puis ligne B sélectionnée ;
  - réception sur l'autre ligne (double veille) ;
  - canal mémoire et mode fréquence ;
  - écoute seule ;
  - verrou du plan de bandes ;
  - listes 1 et 8 ;
  - largeur N+ ;
  - valeurs 0xFF.
- **Trames refusées :** trame tronquée, statut non nul, énumération inconnue, octet réservé non nul.
- **Format 2 plus long :** les 32 premiers octets sont lus.
- **Priorité :** un état FIRMWARE n'est pas écrasé par la lecture de l'écran.
- **Âge de l'état :** frais, puis périmé, puis effacé.
- **Commandes en cours :** confirmé, refusé (statut non nul), indéterminé au bout de 2 s, puis valeur réelle affichée à la lecture suivante.
- **Trames de longueur limite :** 31 octets → refus ; 33 octets en format 1 → refus ; 33 octets en format 2 → accepté.
- **Réponse DTMF (08) intercalée** avant une réponse 0x09 : chacune va à son destinataire.
- **Après une réponse valide**, trois délais dépassés : pas de retour à la lecture de l'écran, état périmé.
- **Valeurs inconnues :** 0xFF, 0xFFFF, 0xFFFFFFFF ; bloc de ligne ignoré quand le type vaut 0xFF ; ligne en réception à 2 (veille étendue).
- **Non-régression par les captures :**
  - écran principal ;
  - « En opération » et Field Control ;
  - bouton Puissance sur A puis B ;
  - aide contextuelle ;
  - en portrait et en paysage.

## 8. Correspondance pour la migration vers F4HWN 7.1

| Propriété du protocole | Source dans r28 | Risque |
|---|---|---|
| ligne sélectionnée | `gEeprom.TX_VFO` | sens du champ |
| ligne en réception | `gRxVfo` comparé à `VfoInfo[0/1]` et aux fréquences de la veille étendue, + `gCurrentFunction` | mécanisme de veille étendue |
| fonction | `gCurrentFunction` (énumération `FUNCTION_*`) | table de traduction |
| type et numéro | `gEeprom.ScreenChannel[]`, `IS_MR_CHANNEL` / `IS_FREQ_CHANNEL` / `IS_NOAA_CHANNEL` | bornes des plages |
| fréquence | `VfoInfo[].freq_config_RX.Frequency` | structure interne |
| puissance | `VfoInfo[].OUTPUT_POWER` | table de traduction |
| modulation | `VfoInfo[].Modulation` (`MODULATION_*`) | table de traduction |
| largeur | `VfoInfo[].CHANNEL_BANDWIDTH` + `gSetting_set_nfm` | option NARROWER |
| écoute seule | `VfoInfo[].RX_ONLY` | ajout Fusion PTT à reporter |
| verrou du plan de bandes | `VfoInfo[].TX_LOCK` | |
| liste de scan | `VfoInfo[].SCANLIST_PARTICIPATION` (n° 0 à 25) | nombre de listes (24 dans v7) |
| verrou, double veille, bande croisée | `gEeprom.KEY_LOCK`, `DUAL_WATCH`, `CROSS_BAND_RX_TX` | |

Les numéros de menus utilisés par l'appli (2, 15, 47, 48, 49, 50, 54, 67, 68…) feront l'objet d'une liste séparée, vérifiée dans le code d'Armel et sur la radio à chaque migration.

## 9. Réponses aux huit questions de la revue formelle (vérifiées dans les sources r27)

1. **Contexte d'exécution.**
   - `K1R_RunCommand()` n'est appelée qu'à un seul endroit : `APP_TimeSlice10ms()`, dans la boucle principale.
   - Les interruptions ne modifient aucun champ lu par 0x09 :
     - `SysTick_Handler` ne fait que décompter des minuteries et relâcher le PTT à distance ;
     - `USBD_IRQHandler` ne fait que déposer la commande reçue dans un tampon, que la boucle principale lit (`KEYBOARD_TakeCommand`) ;
     - les deux interruptions DMA servent à la mémoire flash et à la voix.
   - `gCurrentFunction` n'est écrit que par `FUNCTION_Select()`, dans la boucle principale. Les lignes, `TX_VFO`, `RX_VFO` et `gRxVfo` le sont aussi.
   - L'instantané est donc cohérent. Il sera tout de même copié dans une structure locale avant l'encodage, sans verrou ni appel bloquant.
2. **Tampons.** Voir § 5 : 32 octets passent, et 43 passent déjà avec la commande 05.
3. **Mélange des réponses.**
   - Tous les envois du firmware partent de la boucle principale, l'un après l'autre : images d'écran, réponses, code DTMF 08. Une trame n'est jamais coupée par une autre.
   - L'appli trie les réponses par leur premier octet (opcode). Le code DTMF 08 a son propre chemin, et une seule requête par opcode est en cours à la fois.
4. **Ligne en réception.** Réglé en v1.1 (§ 5) : comparaison de `gRxVfo` aux lignes, à cause de la veille étendue et des applis en surimpression. Pendant un scan, `ScreenChannel[]` suit le canal parcouru, ce qui est le sens voulu.
5. **Listes de scan.**
   - Dans v7, c'est un **numéro de liste** (0 à 24, 25 = toutes) : un canal n'est que dans une liste. Les 24 noms de 3 caractères sont stockés à l'adresse 0x880E. Le champ +11 est corrigé en conséquence.
   - Cette vérification a révélé que l'appli 0.61 lisait et écrivait les listes comme un masque de 8 bits : un canal en liste 3 s'affichait « Liste 1,2 », et cocher les listes 1 et 3 écrivait la liste 5. **C'est corrigé dans l'appli 0.62**, avec trois contrôles automatiques.
6. **Verrou du clavier, veille, mode furtif.**
   - Verrou : `gEeprom.KEY_LOCK` seul. La v1 citait à tort `gKeypadLocked`, qui n'est que le compte à rebours de l'affichage du message.
   - Veille de l'écran : même source que le drapeau déjà envoyé avec chaque image d'écran.
   - Mode furtif : `gK1R_Stealth`.
7. **Énumérations.**
   - Chaque champ est traduit par une table du firmware vers les valeurs du § 4.
   - La puissance est la puissance **réglée** sur la ligne (USER = selon SetPwr), pas une mesure.
   - N+ est calculé : largeur étroite et `gSetting_set_nfm` = 1. Le firmware l'applique en FM et en USB, mais pas en AM, où la largeur passe par un filtre propre à l'AM (`RADIO_GetAMFilterBandwidth`). Le champ « largeur » d'une ligne en AM vaut donc 0xFF.
8. **Annonce de la version 3 dans la commande 04.** Sans risque : l'appli déployée ne vérifie que l'opcode et les lettres « K1R », pas l'octet de version.

---

# Format 2 (firmware r29) — figé le 23 novembre 2026

*Ajout validé par ChatGPT le 23 novembre 2026, après une revue des sources r28 : contrat, `app.c`, `spectrum.c`, `menu.c`, `backlight.c` et `dcs.c`. Les 32 premiers octets gardent exactement le sens du format 1 : seul l'octet 2 vaut 2. La réponse fait 48 octets.*

## Octets 32 à 47

| Octet | Contenu |
|---|---|
| 32 | indicateurs étendus. Bit 0 : réservé, à 0 (radio FM : dans Fusion, c'est une appli en surimpression, pendant laquelle la radio ne répond pas). Bit 1 : appli spectre active. Bits 2 à 7 : 0. |
| 33–38 | sous-canaux de la ligne A |
| 39–44 | sous-canaux de la ligne B |
| 45–47 | réservés, 0 |

Bloc sous-canaux d'une ligne (6 octets) :

| Décalage | Contenu |
|---|---|
| +0 | type en réception : 0 aucun, 1 CTCSS, 2 DCS normal, 3 DCS inversé, 0xFF inconnu |
| +1–2 | valeur en réception (u16, petit-boutiste) : 0 si aucun ; CTCSS en dixièmes de Hz (88,5 Hz → 885) ; DCS = valeur numérique du code en base 8, transportée comme entier binaire (D023 → 0x0013, D131 → 0x0059), jamais en chiffres décimaux ni en ASCII ; 0xFFFF = inconnue |
| +3 | type en émission |
| +4–5 | valeur en émission |

Côté firmware, l'indice est vérifié avant toute lecture de table : `Code < 50` pour `CTCSS_Options`, `Code < DCS_OPTION_COUNT` pour `DCS_GetOption`. Si le type d'une ligne vaut 0xFF, son bloc de sous-canaux est tout entier à 0xFF.

## Pendant le spectre (r29)

- La commande 0x09 reçoit une réponse au format 2. La fonction (octet 5) vaut 5, l'octet 32 a son bit 1 à 1, et la ligne en réception (octet 30) vaut 0xFF. L'encodeur est le même que hors spectre, avec un contexte explicite (`K1R_BuildStatus(d, inSpectrum)`).
- La commande 01 est acceptée pour une liste blanche fermée, définie par identifiant `MENU_*` et non par position : `MENU_ABR` (36), `MENU_ABR_MAX` (38), `MENU_ABR_ON_TX_RX` (39), `MENU_BEEP` (40), `MENU_SET_INV` (60).
- Les bornes sont contrôlées par `MENU_GetLimits`, avec refus si la valeur est hors bornes. Statuts : 0 accepté, 1 trame courte, 2 numéro ou valeur hors bornes, 4 menu refusé pendant le spectre.
- « Écran éteint » met la luminosité vraiment à 0 (`BACKLIGHT_SetBrightness(0)`), et le furtif profond reste prioritaire. `BACKLIGHT_TurnOff()` n'est pas modifiée.
- L'enregistrement en EEPROM est différé : `gRequestSaveSettings` est traité par la boucle principale au retour du spectre.
- Toute autre commande reste refusée pendant le spectre (statut 4).

## Appli

- **Format 2 incomplet** (moins de 48 octets, bit réservé ou octets réservés non nuls, sous-canal hors bornes) : la partie étendue est ignorée, la partie v1 est gardée, et il n'y a jamais de témoin d'équipe.
- **Témoin « réglages d'équipe identiques »** : il ne s'allume que si tout est connu et identique — canal (ou mode fréquence), fréquence, puissance, largeur, sous-canaux en réception et en émission — sans décalage relais ni inversion. Une seule donnée inconnue l'éteint. Ce n'est pas une garantie de liaison radio effective.
- **Spectre** : il est reconnu par l'octet 32 bit 1 (r29) ou par le statut 4 (r28).
- **La commande 04** annonce le protocole 4.
