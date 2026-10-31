// Genera los estilos ligeros (día/noche) para el mapa nativo a partir de @protomaps/basemaps.
// Uso:  npm i @protomaps/basemaps  &&  node tools/make_style.mjs
// Salida: app/src/main/assets/style-day.json y style-night.json
import { layers, namedFlavor } from '@protomaps/basemaps';
import { writeFileSync, mkdirSync } from 'node:fs';

// Capas que NO se dibujan (más ligeras = más cuadros por segundo en una radio modesta)
const QUITAR = [
  /^buildings$/, /^address_label$/, /^pois$/, /^roads_shields$/, /^roads_oneway$/,
  /^landuse_(zoo|pier|runway|aerodrome|school|hospital|industrial|beach|pedestrian)$/,
  /^roads_(runway|taxiway|pier|rail)$/,
  /^roads_tunnels_.*_casing$/, /^roads_bridges_.*_casing$/,
  /^water_stream$/, /^earth_label_islands$/,
];

function estilo(flavor) {
  let L = layers('protomaps', namedFlavor(flavor), { lang: 'es' });
  L = L.filter(l => !QUITAR.some(re => re.test(l.id)));
  // Sin íconos => sin sprites: se quitan las propiedades icon-* y las capas que eran solo ícono
  for (const l of L) {
    for (const k of ['layout', 'paint']) {
      if (l[k]) for (const p of Object.keys(l[k])) if (p.startsWith('icon-')) delete l[k][p];
    }
  }
  L = L.filter(l => l.type !== 'symbol' || (l.layout && l.layout['text-field']));
  const dark = flavor === 'dark';
  L.push(
    { id: 'yo-halo', type: 'circle', source: 'yo',
      paint: { 'circle-radius': 16, 'circle-color': '#1a73e8', 'circle-opacity': 0.22 } },
    { id: 'yo-punto', type: 'circle', source: 'yo',
      paint: { 'circle-radius': 7, 'circle-color': '#1a73e8', 'circle-stroke-color': dark ? '#101418' : '#ffffff', 'circle-stroke-width': 3 } }
  );
  const s = {
    version: 8,
    name: 'gps-nativo-' + flavor,
    glyphs: 'asset://glyphs/{fontstack}/{range}.pbf',
    sources: {
      protomaps: { type: 'vector', url: 'pmtiles://file://__PMTILES__', attribution: '© OpenStreetMap' },
      yo: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
    },
    layers: L,
  };
  // nombres de fuente sin espacios (rutas de assets más seguras)
  return JSON.stringify(s).replaceAll('Noto Sans ', 'NotoSans-');
}

mkdirSync('app/src/main/assets', { recursive: true });
for (const [flavor, nombre] of [['light', 'day'], ['dark', 'night']]) {
  const j = estilo(flavor);
  writeFileSync(`app/src/main/assets/style-${nombre}.json`, j);
  console.log(nombre, JSON.parse(j).layers.length, 'capas,', j.length, 'bytes');
}
