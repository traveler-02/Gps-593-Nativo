#!/usr/bin/env python3
"""Descarga lugares de interés de Overture Maps (datos abiertos) para una zona y los guarda como .jsonl.

Cada línea:  [nombre, lat, lon, tipo, etiqueta]   (mismo formato que usa tools/build_graph.py --extra)

Uso:
  python tools/overture_places.py --salida lugares_extra.jsonl                 # Ecuador, última versión
  python tools/overture_places.py --bbox=-81.1,-5.1,-75.1,1.5 --confianza 0.7

Requisitos: pip install duckdb
Licencia de los datos: Overture Places es mayormente CDLA-Permissive-2.0 (más Apache-2.0 y CC0 según la fuente):
hay que dar atribución a Overture Maps Foundation (ya incluida en Ajustes › Acerca de).
"""
import argparse
import json
import sys
import urllib.request

try:
    import duckdb
except ImportError:
    sys.exit('Falta duckdb: pip install duckdb')

S3 = 's3://overturemaps-us-west-2/release/{release}/theme=places/type=place/*'

# palabra clave de la categoría de Overture → (tipo, etiqueta en español). Se prueba en orden.
REGLAS = [
    (('pharmacy',), 9, 'Farmacia'), (('hospital',), 9, 'Hospital'), (('dentist',), 9, 'Dentista'),
    (('clinic', 'doctor', 'health', 'medical', 'veterinar'), 9, 'Salud'),
    (('gas_station', 'fuel', 'charging'), 11, 'Gasolinera'),
    (('bank', 'atm', 'financial'), 12, 'Banco'),
    (('hotel', 'hostel', 'lodging', 'motel', 'resort', 'bed_and_breakfast', 'guest_house'), 13, 'Alojamiento'),
    (('school', 'university', 'college', 'kindergarten', 'education'), 10, 'Educación'),
    (('restaurant', 'cafe', 'coffee', 'bar', 'fast_food', 'bakery', 'pizza', 'food', 'eatery', 'pub', 'ice_cream'), 8, 'Restaurante / café'),
    (('museum', 'tourist', 'attraction', 'cinema', 'theater', 'theatre', 'nightclub', 'casino', 'landmark', 'zoo'), 14, 'Turismo / ocio'),
    (('airport', 'bus_station', 'bus_stop', 'train', 'station', 'parking', 'taxi', 'transport'), 15, 'Transporte'),
    (('park', 'sport', 'gym', 'fitness', 'stadium', 'recreation', 'golf'), 16, 'Parque / deporte'),
    (('government', 'police', 'fire_station', 'post_office', 'church', 'religious', 'embassy', 'community', 'civic'), 17, 'Servicios'),
    (('shop', 'store', 'supermarket', 'market', 'retail', 'grocery', 'clothing', 'mall', 'hardware', 'pharmacy'), 7, 'Comercio'),
]


def clasifica(cat):
    c = (cat or '').lower()
    for palabras, tipo, etiqueta in REGLAS:
        if any(p in c for p in palabras):
            return tipo, etiqueta
    return 18, (c.replace('_', ' ').capitalize() if c else 'Negocio')


def ultima_version():
    try:
        with urllib.request.urlopen('https://labs.overturemaps.org/data/releases.json', timeout=20) as r:
            return json.load(r)['latest']
    except Exception as e:  # noqa: BLE001
        sys.exit(f'No pude consultar la última versión de Overture ({e}). Indícala con --release 2026-01-21.0')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--bbox', default='-92.3,-5.1,-74.9,1.8', help='lon_min,lat_min,lon_max,lat_max (Ecuador con Galápagos)')
    ap.add_argument('--salida', default='lugares_extra.jsonl')
    ap.add_argument('--confianza', type=float, default=0.65, help='confianza mínima de Overture (0 a 1)')
    ap.add_argument('--release', help='versión de Overture (por defecto, la última)')
    ap.add_argument('--fuente', help='ruta o patrón parquet propio (por defecto, el S3 público de Overture)')
    a = ap.parse_args()
    x0, y0, x1, y1 = [float(v) for v in a.bbox.split(',')]

    con = duckdb.connect()
    if a.fuente:
        fuente = a.fuente
    else:
        release = a.release or ultima_version()
        fuente = S3.format(release=release)
        con.execute('INSTALL httpfs; LOAD httpfs; SET s3_region=\'us-west-2\';')
        print(f'Overture release {release}', file=sys.stderr)

    cols = {r[0] for r in con.execute(f"DESCRIBE SELECT * FROM read_parquet('{fuente}', hive_partitioning=1)").fetchall()}
    # el esquema cambió entre versiones: "taxonomy" (nuevo) o "categories" (anterior)
    cat = 'taxonomy.primary' if 'taxonomy' in cols else ('categories.primary' if 'categories' in cols else 'NULL')
    filtros = ['names.primary IS NOT NULL', 'bbox.xmin BETWEEN ? AND ?', 'bbox.ymin BETWEEN ? AND ?']
    params = [x0, x1, y0, y1]
    if 'confidence' in cols:
        filtros.append('confidence >= ?')
        params.append(a.confianza)
    if 'operating_status' in cols:
        filtros.append("coalesce(operating_status, 'open') <> 'closed'")
    sql = f"""
        SELECT names.primary AS nombre, (bbox.ymin + bbox.ymax) / 2 AS lat, (bbox.xmin + bbox.xmax) / 2 AS lon,
               {cat} AS cat
        FROM read_parquet('{fuente}', hive_partitioning=1)
        WHERE {' AND '.join(filtros)}
    """
    n = 0
    vistos = set()
    with open(a.salida, 'w', encoding='utf-8') as f:
        for nombre, lat, lon, c in con.execute(sql, params).fetchall():
            nombre = (nombre or '').strip()
            if len(nombre) < 2:
                continue
            clave = (nombre.lower(), round(lat, 4), round(lon, 4))
            if clave in vistos:
                continue
            vistos.add(clave)
            tipo, etiqueta = clasifica(c)
            f.write(json.dumps([nombre, round(lat, 5), round(lon, 5), tipo, etiqueta], ensure_ascii=False) + '\n')
            n += 1
    print(f'{n} lugares -> {a.salida}')
    print('Atribución: © Overture Maps Foundation (CDLA-Permissive-2.0 y otras licencias abiertas)', file=sys.stderr)


if __name__ == '__main__':
    main()
