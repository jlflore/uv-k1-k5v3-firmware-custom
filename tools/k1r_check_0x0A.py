#!/usr/bin/env python3
"""Contrôle CI du contrat 0x0A (r30) : chaque réglage lu par K1R_ReadSetting (app.c) doit avoir
la même source que ce que le menu de la radio affiche (menu_settings.def ou
MENU_ShowCurrentSetting dans menu.c). Détecte une divergence lors d'une migration (Fusion 7.1)."""
import re, sys, pathlib
root = pathlib.Path(__file__).resolve().parent.parent
app = (root / "App/app/app.c").read_text(encoding="utf-8")
menu = (root / "App/app/menu.c").read_text(encoding="utf-8")
defs = (root / "App/app/menu_settings.def").read_text(encoding="utf-8")

WHITELIST = ["MENU_SQL", "MENU_BEEP", "MENU_SET_OFF", "MENU_SET_NFM", "MENU_STEP",
             "MENU_UPCODE", "MENU_PTT_ID", "MENU_D_ST", "MENU_D_PRE", "MENU_D_LIVE_DEC"]

body = app[app.index("static int8_t K1R_ReadSetting"):]
body = body[:body.index("\n}\n")]
reads = dict(re.findall(r"case (MENU_\w+):\s*v = ([^;]+);", body))
show = menu[menu.index("void MENU_ShowCurrentSetting"):]
shown = dict(re.findall(r"case (MENU_\w+):\s*gSubMenuSelection = ([^;]+);", show))
table = {m: v.strip() for m, v in re.findall(r"MENU_SETTING\((MENU_\w+),\s*([^,]+),", defs)}

# 0x0A lit les réglages de ligne sur vfo = &gEeprom.VfoInfo[TX_VFO] (revue r30, point a) ;
# le menu passe par gTxVfo, que RADIO_SelectVfos() fait pointer sur ce même VFO.
if not re.search(r"gTxVfo\s*=\s*&gEeprom\.VfoInfo\[gEeprom\.TX_VFO\]", (root / "App/radio.c").read_text(encoding="utf-8")):
    sys.exit("RADIO_SelectVfos ne fait plus pointer gTxVfo sur VfoInfo[TX_VFO] : revoir l'équivalence vfo/gTxVfo")
norm = lambda s: re.sub(r"\(uint16_t\)|\s", "", s).replace("vfo->", "gTxVfo->").replace("ggTxVfo->", "gTxVfo->")
errors = []
cases = set(re.findall(r"case (MENU_\w+):", body))
if cases != set(WHITELIST):
    errors.append(f"liste blanche app.c {sorted(cases)} ≠ contrat {sorted(WHITELIST)}")
for m, src in reads.items():
    ref = table.get(m) or shown.get(m)
    if ref is None:
        errors.append(f"{m} : aucune source dans menu.c / menu_settings.def")
    elif norm(ref) != norm(src):
        errors.append(f"{m} : 0x0A lit « {src} », le menu affiche « {ref} »")
if "MENU_UPCODE" not in cases or "DTMF_UP_CODE" not in body:
    errors.append("MENU_UPCODE : lecture de gEeprom.DTMF_UP_CODE absente")
# Écriture correspondante : 0x01 pour les menus numériques (aucun n'est refusé par l'op 01),
# 0x03 pour MENU_UPCODE.
op01 = app[app.index("case 0x01: {"):app.index("case 0x02")]
for m in WHITELIST:
    if m != "MENU_UPCODE" and re.search(rf"case {m}\s*:", op01.split("status = 3")[0]):
        errors.append(f"{m} : refusé en écriture par l'op 01")
if errors:
    print("Contrat 0x0A : ÉCHEC"); [print(" -", e) for e in errors]; sys.exit(1)
print(f"Contrat 0x0A : {len(WHITELIST)} réglages cohérents avec les menus de la radio")
