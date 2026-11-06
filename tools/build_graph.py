#!/usr/bin/env python3
"""GPS 593 — convierte un .osm.pbf en un archivo .g593 (grafo vial comprimido + lugares para buscar sin internet).
Uso: python build_graph.py ecuador-latest.osm.pbf ecuador.g593 [--sin-servicio] [--extra lugares_extra.jsonl]
Lugares que se pueden buscar: poblaciones, calles, y puntos de interés de OSM (comercios, comida, salud, educación,
gasolineras, bancos, hoteles, turismo, transporte, parques, servicios públicos), tanto puntos como edificios/áreas.
--extra suma lugares de otra fuente abierta (p. ej. tools/overture_places.py) sin duplicar los de OSM."""
import sys, re, json, math, unicodedata
from collections import defaultdict
import numpy as np, osmium

SPEED = {'motorway': 90, 'motorway_link': 60, 'trunk': 80, 'trunk_link': 50, 'primary': 70, 'primary_link': 45,
         'secondary': 60, 'secondary_link': 40, 'tertiary': 50, 'tertiary_link': 35, 'unclassified': 40,
         'residential': 30, 'living_street': 15, 'service': 20}
PENALTY = {'service': 2.0, 'living_street': 1.5}          # desincentiva atajos por vías menores
PLACE = {'city': 0, 'town': 1, 'village': 2, 'suburb': 3, 'neighbourhood': 3, 'hamlet': 4}

# ── Lugares de interés (se buscan sin internet). tipo: 5 servicio, 7 comercio, 8 comida, 9 salud, 10 educación,
#    11 gasolinera, 12 banco, 13 alojamiento, 14 turismo/ocio, 15 transporte, 16 parque/deporte, 17 servicios públicos, 18 otros
AMENITY = {
    'restaurant': (8, 'Restaurante'), 'fast_food': (8, 'Comida rápida'), 'cafe': (8, 'Café'), 'bar': (8, 'Bar'),
    'pub': (8, 'Bar'), 'ice_cream': (8, 'Heladería'), 'food_court': (8, 'Patio de comidas'), 'biergarten': (8, 'Bar'),
    'hospital': (9, 'Hospital'), 'clinic': (9, 'Clínica'), 'doctors': (9, 'Consultorio médico'), 'dentist': (9, 'Dentista'),
    'pharmacy': (9, 'Farmacia'), 'veterinary': (9, 'Veterinaria'),
    'school': (10, 'Escuela / colegio'), 'university': (10, 'Universidad'), 'college': (10, 'Instituto'),
    'kindergarten': (10, 'Jardín de infantes'), 'library': (10, 'Biblioteca'),
    'fuel': (11, 'Gasolinera'), 'charging_station': (11, 'Cargador eléctrico'),
    'bank': (12, 'Banco'), 'atm': (12, 'Cajero automático'),
    'bus_station': (15, 'Terminal de buses'), 'ferry_terminal': (15, 'Terminal marítimo'), 'taxi': (15, 'Cooperativa de taxis'),
    'parking': (15, 'Parqueadero'),
    'police': (17, 'Policía'), 'fire_station': (17, 'Bomberos'), 'townhall': (17, 'Municipio'), 'courthouse': (17, 'Juzgado'),
    'post_office': (17, 'Correo'), 'community_centre': (17, 'Centro comunitario'), 'place_of_worship': (17, 'Iglesia / templo'),
    'embassy': (17, 'Embajada'), 'marketplace': (7, 'Mercado'),
    'cinema': (14, 'Cine'), 'theatre': (14, 'Teatro'), 'nightclub': (14, 'Discoteca'), 'casino': (14, 'Casino'),
    'car_wash': (18, 'Lavadora de autos'), 'car_repair': (18, 'Taller mecánico'), 'car_rental': (18, 'Alquiler de autos'),
}
SHOP = {
    'supermarket': 'Supermercado', 'convenience': 'Tienda', 'mall': 'Centro comercial', 'department_store': 'Almacén',
    'bakery': 'Panadería', 'butcher': 'Carnicería', 'clothes': 'Ropa', 'shoes': 'Zapatería', 'hardware': 'Ferretería',
    'electronics': 'Electrónica', 'mobile_phone': 'Celulares', 'car': 'Concesionario', 'car_repair': 'Taller mecánico',
    'hairdresser': 'Peluquería', 'beauty': 'Belleza', 'optician': 'Óptica', 'books': 'Librería', 'furniture': 'Muebles',
    'jewelry': 'Joyería', 'florist': 'Florería', 'pet': 'Mascotas', 'stationery': 'Papelería', 'laundry': 'Lavandería',
    'greengrocer': 'Frutería', 'seafood': 'Mariscos', 'chemist': 'Droguería', 'copyshop': 'Copiadora', 'kiosk': 'Kiosco',
    'tyres': 'Llantas', 'motorcycle': 'Motos', 'bicycle': 'Bicicletas', 'sports': 'Deportes', 'toys': 'Juguetes',
    'gift': 'Regalos', 'wholesale': 'Mayorista', 'variety_store': 'Bazar', 'general': 'Tienda',
}
TURISMO = {
    'hotel': (13, 'Hotel'), 'hostel': (13, 'Hostal'), 'motel': (13, 'Motel'), 'guest_house': (13, 'Casa de huéspedes'),
    'apartment': (13, 'Apartamentos'), 'resort': (13, 'Resort'), 'attraction': (14, 'Atracción turística'),
    'museum': (14, 'Museo'), 'viewpoint': (14, 'Mirador'), 'zoo': (14, 'Zoológico'), 'theme_park': (14, 'Parque temático'),
    'gallery': (14, 'Galería'), 'camp_site': (14, 'Camping'),
}
OCIO = {
    'park': (16, 'Parque'), 'stadium': (16, 'Estadio'), 'sports_centre': (16, 'Centro deportivo'), 'golf_course': (16, 'Golf'),
    'garden': (16, 'Jardín'), 'nature_reserve': (16, 'Reserva natural'), 'fitness_centre': (16, 'Gimnasio'),
}

def clasifica(t):
    """(tipo, etiqueta) de un lugar de interés según sus etiquetas OSM, o None si no es de interés."""
    a = t.get('amenity')
    if a in AMENITY: return AMENITY[a]
    sh = t.get('shop')
    if sh and sh != 'no': return (7, SHOP.get(sh, 'Tienda'))
    tu = t.get('tourism')
    if tu in TURISMO: return TURISMO[tu]
    le = t.get('leisure')
    if le in OCIO: return OCIO[le]
    hi = t.get('historic')
    if hi and hi != 'no': return (14, 'Sitio histórico')
    if t.get('aeroway') in ('aerodrome', 'terminal'): return (15, 'Aeropuerto')
    if t.get('railway') in ('station', 'halt'): return (15, 'Estación de tren')
    if t.get('public_transport') == 'station': return (15, 'Estación')
    na = t.get('natural')
    if na == 'volcano': return (14, 'Volcán')
    if na == 'peak': return (14, 'Cumbre')
    hc = t.get('healthcare')
    if hc and hc != 'no': return (9, 'Salud')
    of = t.get('office')
    if of and of != 'no': return (17, 'Oficina')
    return None

def norm(x):
    x = unicodedata.normalize('NFD', x)
    x = ''.join(c for c in x if unicodedata.category(c) != 'Mn').lower()
    return re.sub(r'[^a-z0-9]+', ' ', x).strip()

def merge_extra(places, path):
    """Suma lugares de un .jsonl ([nombre, lat, lon, tipo, etiqueta]) sin duplicar los que ya existen (mismo nombre a menos de ~100 m)."""
    grid = defaultdict(list)
    for p in places:
        if p[3] >= 5: grid[(int(p[1] * 1000), int(p[2] * 1000))].append(norm(p[0]))
    add = skip = 0
    with open(path, encoding='utf-8') as f:
        for line in f:
            if not line.strip(): continue
            e = json.loads(line); nm = e[0]; n = norm(nm)
            if len(n) < 2: continue
            cx, cy = int(e[1] * 1000), int(e[2] * 1000); dup = False
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    for o in grid.get((cx + dx, cy + dy), ()):
                        if o == n or (len(n) >= 6 and (n in o or o in n)): dup = True
            if dup: skip += 1; continue
            places.append([nm, e[1], e[2], e[3], e[4] if len(e) > 4 else '']); grid[(cx, cy)].append(n); add += 1
    print(f'lugares extra: +{add} (repetidos omitidos: {skip})')

NO_SERVICE = '--sin-servicio' in sys.argv                    # grafo más liviano: sin calles de servicio

def hav(a, b):
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(b[1] - a[1]) / 2) ** 2
    return 12742000 * math.asin(math.sqrt(h))

def maxspeed(t, default):
    m = re.match(r'\s*(\d+)\s*(mph)?', t.get('maxspeed', ''))
    if not m: return default
    v = int(m.group(1)) * (1.609 if m.group(2) else 1)
    return max(5, min(110, v))

class Reader(osmium.SimpleHandler):
    def __init__(s):
        super().__init__(); s.ways = []; s.use = defaultdict(int); s.places = []; s.streets = {}
    def node(s, n):
        nm = n.tags.get('name')
        if not nm and not n.tags.get('place'): nm = n.tags.get('brand')
        if not nm or not n.location.valid(): return
        p = n.tags.get('place')
        if p in PLACE: s.places.append([nm, n.location.lat, n.location.lon, PLACE[p]])
        else:
            c = clasifica(n.tags)
            if c: s.places.append([nm, n.location.lat, n.location.lon, c[0], c[1]])
    def area(s, a):
        """Edificios y terrenos con nombre (centros comerciales, hospitales, colegios, parques…): se usa el centro aproximado."""
        t = a.tags; nm = t.get('name') or t.get('brand')
        if not nm: return
        c = clasifica(t)
        if not c: return
        la = lo = 0.0; k = 0
        try:
            for ring in a.outer_rings():
                for r in ring: la += r.lat; lo += r.lon; k += 1
                break
        except osmium.InvalidLocationError: return
        if k: s.places.append([nm, la / k, lo / k, c[0], c[1]])
    def way(s, w):
        t = w.tags; hw = t.get('highway')
        if hw not in SPEED or (hw == 'service' and NO_SERVICE): return
        if t.get('access') in ('no', 'private') and t.get('motor_vehicle') not in ('yes', 'designated'): return
        if t.get('motor_vehicle') in ('no', 'private') or t.get('motorcar') in ('no', 'private'): return
        if hw == 'service' and t.get('service') in ('parking_aisle', 'driveway'): return
        try: pts = [(n.ref, n.lat, n.lon) for n in w.nodes]
        except osmium.InvalidLocationError: return
        if len(pts) < 2: return
        ow = t.get('oneway')
        if ow is None and (t.get('junction') == 'roundabout' or hw in ('motorway', 'motorway_link')): ow = 'yes'
        flags = 1 if ow in ('yes', 'true', '1') else 2 if ow in ('-1', 'reverse') else 3
        name = t.get('name') or t.get('ref') or ''
        for r, _, _ in pts: s.use[r] += 1
        s.ways.append((pts, flags, maxspeed(t, SPEED[hw]), PENALTY.get(hw, 1.0), name))
        if t.get('name') and hw != 'service':
            mid = pts[len(pts) // 2]
            s.streets.setdefault((name, round(mid[1], 2), round(mid[2], 2)), [name, mid[1], mid[2], 6])

def main(src, dst, extra=None):
    h = Reader(); h.apply_file(src, locations=True, idx='flex_mem')
    node_id, nlat, nlon = {}, [], []
    names, name_idx = [''], {'': 0}
    sf, st, sfl, stime, sdist, snm, goff, pts = [], [], [], [], [], [], [0], []
    def nid(r, lat, lon):
        i = node_id.get(r)
        if i is None: i = node_id[r] = len(nlat); nlat.append(round(lat * 1e6)); nlon.append(round(lon * 1e6))
        return i
    for wp, flags, sp, pen, name in h.ways:
        start = 0
        for i in range(1, len(wp)):
            if i != len(wp) - 1 and h.use[wp[i][0]] < 2: continue
            ch = wp[start:i + 1]; start = i
            dist = sum(hav(ch[k][1:], ch[k + 1][1:]) for k in range(len(ch) - 1))
            a, b = nid(ch[0][0], ch[0][1], ch[0][2]), nid(ch[-1][0], ch[-1][1], ch[-1][2])
            if a == b and dist < 5: continue
            if name not in name_idx: name_idx[name] = len(names); names.append(name)
            sf.append(a); st.append(b); sfl.append(flags); sdist.append(dist)
            stime.append(dist / (sp / 3.6) * pen); snm.append(name_idx[name])
            pts.extend((round(p[1] * 1e6), round(p[2] * 1e6)) for p in ch); goff.append(len(pts))
    nN, nS = len(nlat), len(sf)
    sf_a, st_a, fl = np.array(sf, np.uint32), np.array(st, np.uint32), np.array(sfl, np.uint8)
    seg = np.arange(nS, dtype=np.uint32)          # entrada de adyacencia = seg*4 + dir*2 + transitable
    nodes = np.concatenate([sf_a, st_a])
    vals = np.concatenate([seg * 4 + 0 + (fl & 1), seg * 4 + 2 + ((fl >> 1) & 1)]).astype(np.uint32)
    order = np.argsort(nodes, kind='stable'); adj = vals[order]
    adjs = np.concatenate([[0], np.cumsum(np.bincount(nodes, minlength=nN))]).astype(np.uint32)

    # Lugares: contexto = población (ciudad/pueblo/parroquia) más cercana, para distinguir calles homónimas
    places = h.places
    if extra: merge_extra(places, extra)
    grid = defaultdict(list)
    for i, p in enumerate(places):
        if p[3] <= 2: grid[(int(p[1] * 10), int(p[2] * 10))].append(i)
    def ctx(p):
        cx, cy = int(p[1] * 10), int(p[2] * 10); best, bd = -1, 1e18
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for i in grid.get((cx + dx, cy + dy), ()):
                    d = hav(p[1:3], places[i][1:3])
                    if d < bd: best, bd = i, d
        return best
    allp = [[p[0], round(p[1], 5), round(p[2], 5), p[3], ctx(p) if p[3] >= 5 else -1] + ([p[4]] if len(p) > 4 and p[4] else [])
            for p in places]
    base = len(places)
    for p in h.streets.values():
        allp.append([p[0], round(p[1], 5), round(p[2], 5), 6, ctx(p)])
    blob = json.dumps({'names': names, 'places': allp}, ensure_ascii=False, separators=(',', ':')).encode('utf-8')

    with open(dst, 'wb') as f:
        f.write(b'G593'); f.write(np.array([1, nN, nS, len(pts), len(adj), len(blob)], '<u4').tobytes())
        for arr in (np.array(nlat, '<i4'), np.array(nlon, '<i4'), adjs.astype('<u4'), adj.astype('<u4'),
                    sf_a.astype('<u4'), st_a.astype('<u4'), np.array(stime, '<f4'), np.array(sdist, '<f4'),
                    np.array(snm, '<u4'), np.array(goff, '<u4'), np.array(pts, '<i4').reshape(-1), fl):
            f.write(arr.tobytes())
        f.write(blob)
    print(f'nodos={nN} segmentos={nS} puntos={len(pts)} lugares={len(allp)} -> {dst}')

if __name__ == '__main__':
    ex = sys.argv[sys.argv.index('--extra') + 1] if '--extra' in sys.argv else None
    main(sys.argv[1], sys.argv[2], ex)
