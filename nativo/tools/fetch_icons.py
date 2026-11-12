#!/usr/bin/env python3
"""Descarga íconos Material Symbols (Rounded, Apache 2.0) y los convierte a VectorDrawable de Android.
Uso: python tools/fetch_icons.py   (necesita internet; deja los .xml en app/src/main/res/drawable)"""
import re, sys, urllib.request, pathlib

B = 'https://raw.githubusercontent.com/google/material-design-icons/master/symbols/web'
# (nombre del drawable, ícono de Material, relleno 0/1)
ICONOS = [
    ('search', 'search', 0), ('add', 'add', 0), ('remove', 'remove', 0),
    ('my_location', 'my_location', 0), ('near_me', 'near_me', 1),
    ('dark_mode', 'dark_mode', 1), ('light_mode', 'light_mode', 1),
    ('settings', 'settings', 1), ('close', 'close', 0), ('folder_open', 'folder_open', 1),
    ('usb', 'usb', 0), ('star_fill', 'star', 1), ('star_line', 'star', 0), ('delete', 'delete', 0),
    ('route', 'route', 0), ('map', 'map', 1), ('info', 'info', 0), ('volume_up', 'volume_up', 1),
    ('directions', 'directions', 1), ('place', 'location_on', 1), ('check_circle', 'check_circle', 1),
    ('tune', 'tune', 0), ('history', 'history', 0), ('download', 'download', 0),
    ('chevron_right', 'chevron_right', 0), ('error', 'error', 1), ('language', 'language', 0),
    ('turn_left', 'turn_left', 0), ('turn_right', 'turn_right', 0), ('straight', 'straight', 0),
    ('u_turn_left', 'u_turn_left', 0), ('turn_slight_left', 'turn_slight_left', 0),
    ('turn_slight_right', 'turn_slight_right', 0), ('turn_sharp_left', 'turn_sharp_left', 0),
    ('turn_sharp_right', 'turn_sharp_right', 0), ('flag', 'flag', 1),
    ('edit', 'edit', 0), ('home', 'home', 1), ('work', 'work', 1),
    ('explore', 'explore', 1), ('navigation', 'navigation', 1), ('pan_tool', 'pan_tool', 1),
    ('folder', 'folder', 1), ('play_arrow', 'play_arrow', 1), ('schedule', 'schedule', 0),
]
out = pathlib.Path('app/src/main/res/drawable'); out.mkdir(parents=True, exist_ok=True)
fallos = []
for drw, nombre, fill in ICONOS:
    suf = '_fill1' if fill else ''
    url = f'{B}/{nombre}/materialsymbolsrounded/{nombre}{suf}_24px.svg'
    try:
        svg = urllib.request.urlopen(url, timeout=30).read().decode('utf-8')
    except Exception as e:
        fallos.append((drw, str(e))); continue
    paths = re.findall(r'<path[^>]*\sd="([^"]+)"', svg)
    if not paths: fallos.append((drw, 'sin path')); continue
    cuerpo = '\n'.join(f'        <path android:fillColor="#FF000000" android:pathData="{d}" />' for d in paths)
    (out / f'ic_{drw}.xml').write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp" android:height="24dp" android:viewportWidth="960" android:viewportHeight="960">\n'
        '    <group android:translateY="960">\n' + cuerpo + '\n    </group>\n</vector>\n', encoding='utf-8')
print('ok:', len(ICONOS) - len(fallos), 'fallos:', fallos)
sys.exit(1 if fallos else 0)
