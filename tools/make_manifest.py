#!/usr/bin/env python3
"""Genera el manifest.json que usa la app para descargar los mapas.

Uso:
  python tools/make_manifest.py --base https://mapas.tudominio.com --version 2026.11.03 \
      ecuador.pmtiles ecuador.g593 > manifest.json

La app lee este archivo, compara `version` con la que tiene instalada y descarga cada archivo
(con reanudación) verificando `bytes` y `sha256`. `url` puede ser relativa al manifiesto.
"""
import argparse
import hashlib
import json
import os
import sys


def sha256(ruta):
    h = hashlib.sha256()
    with open(ruta, 'rb') as f:
        for bloque in iter(lambda: f.read(1 << 20), b''):
            h.update(bloque)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--base', required=True, help='URL pública donde estarán los archivos (sin barra final)')
    ap.add_argument('--version', required=True, help='versión de los datos, p. ej. 2026.11.03')
    ap.add_argument('archivos', nargs='+', help='.pmtiles y/o .g593')
    a = ap.parse_args()
    lista = []
    for ruta in a.archivos:
        nombre = os.path.basename(ruta)
        if nombre.endswith('.pmtiles'):
            tipo = 'mapa'
        elif nombre.endswith('.g593'):
            tipo = 'rutas'
        else:
            sys.exit(f'Tipo de archivo no reconocido: {nombre}')
        lista.append({
            'nombre': nombre,
            'tipo': tipo,
            'url': a.base.rstrip('/') + '/' + nombre,
            'bytes': os.path.getsize(ruta),
            'sha256': sha256(ruta),
        })
    json.dump({'formato': 1, 'version': a.version, 'archivos': lista}, sys.stdout, ensure_ascii=False, indent=2)
    print()


if __name__ == '__main__':
    main()
