#!/usr/bin/env python3
"""Contrôle CI des positions de menu (contrat 0x0A v1.3, revue r30).

Les commandes K1R adressent les menus par leur position dans MenuListId (1 = premier).
Ce script reconstruit MenuListId exactement comme la compilation : menu_list.def est
prétraité avec les -D réels du preset (compile_commands.json de App/ui/menu.c). Il vérifie
que les positions utilisées par l'appli pointent sur les bons MENU_*.

Usage : k1r_check_positions.py <compile_commands.json> [RadioSettings.kt de l'appli]"""
import json, re, shlex, sys, pathlib

root = pathlib.Path(__file__).resolve().parent.parent
REFERENCE = {1: "MENU_STEP", 40: "MENU_BEEP", 45: "MENU_UPCODE", 47: "MENU_PTT_ID", 48: "MENU_D_ST",
             49: "MENU_D_PRE", 50: "MENU_D_LIVE_DEC", 54: "MENU_SQL", 67: "MENU_SET_OFF", 68: "MENU_SET_NFM",
             36: "MENU_ABR", 39: "MENU_ABR_ON_TX_RX"}   # r31 : éclairage
APP_NAMES = {"STEP": "MENU_STEP", "BEEP": "MENU_BEEP", "UPCODE": "MENU_UPCODE", "PTT_ID": "MENU_PTT_ID",
             "D_ST": "MENU_D_ST", "D_PRE": "MENU_D_PRE", "D_LIVE": "MENU_D_LIVE_DEC", "SQL": "MENU_SQL",
             "SET_OFF": "MENU_SET_OFF", "SET_NFM": "MENU_SET_NFM",
             "BL_TIME": "MENU_ABR", "BL_TXRX": "MENU_ABR_ON_TX_RX"}

cc = json.loads(pathlib.Path(sys.argv[1]).read_text())
entry = next(e for e in cc if e["file"].replace("\\", "/").endswith("App/ui/menu.c"))
args = entry.get("arguments") or shlex.split(entry["command"])
defines = {a[2:].split("=", 1)[0] for a in args if a.startswith("-D")}

def preprocess(text):
    out, stack = [], []          # pile de (actif, déjà_pris)
    active = lambda: all(a for a, _ in stack)
    for line in text.splitlines():
        s = line.split("//")[0].strip()
        m = re.match(r"#\s*(ifdef|ifndef|else|endif|if|elif)\b\s*(\w*)", s)
        if not m:
            if active(): out.append(s)
            continue
        kw, name = m.groups()
        if kw in ("if", "elif"):
            sys.exit(f"#{kw} non géré dans menu_list.def : à ajouter au script")
        if kw == "ifdef": stack.append(((name in defines), (name in defines)))
        elif kw == "ifndef": stack.append(((name not in defines), (name not in defines)))
        elif kw == "else": a, taken = stack.pop(); stack.append((not taken, True))
        elif kw == "endif": stack.pop()
    if stack: sys.exit("menu_list.def : #if non refermé")
    return out

lines = preprocess((root / "App/ui/menu_list.def").read_text(encoding="utf-8"))
ids = [m.group(1) for l in lines for m in [re.search(r"MENU_ITEM\([^,]+,\s*(MENU_\w+)\s*\)", l)] if m]
errors = []
for pos, want in REFERENCE.items():
    got = ids[pos - 1] if 0 < pos <= len(ids) else None
    if got != want:
        errors.append(f"position {pos} : {got} au lieu de {want}")

if len(sys.argv) > 2:
    kt = pathlib.Path(sys.argv[2]).read_text(encoding="utf-8")
    app = {APP_NAMES[n]: int(v) for n, v in re.findall(r"const val (\w+) = (\d+)", kt) if n in APP_NAMES}
    if set(app) != set(REFERENCE.values()):
        errors.append(f"appli : constantes {sorted(app)} ≠ liste blanche")
    for menu, pos in app.items():
        if REFERENCE.get(pos) != menu:
            errors.append(f"appli : {menu} à la position {pos}, le firmware y a {ids[pos-1] if 0 < pos <= len(ids) else '—'}")

if errors:
    print("Positions des menus : ÉCHEC"); [print(" -", e) for e in errors]; sys.exit(1)
print(f"Positions des menus : {len(REFERENCE)} positions conformes sur {len(ids)} menus"
      + (" (firmware et appli)" if len(sys.argv) > 2 else ""))
