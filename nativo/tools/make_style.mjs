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


// ───── Paletas propias (aspecto "premium": suaves, poco contraste en el terreno, carreteras claras) ─────
const DIA = {
  background: '#EEF1F4', earth: '#F3F1EC', water: '#B7D8EE',
  park_a: '#DCEAD5', park_b: '#CFE3C6', wood_a: '#D5E6CC', wood_b: '#C8DEBE',
  scrub_a: '#E0EBD2', scrub_b: '#D6E4C6', sand: '#F1EBD6', beach: '#F1EBD6',
  hospital: '#F1E3E1', industrial: '#E7E9EC', school: '#EFE9DD', pedestrian: '#F6F4EF',
  boundaries: '#A9B4C4',
  other: '#FFFFFF', minor_service: '#FFFFFF', minor_a: '#FFFFFF', minor_b: '#FFFFFF', link: '#FFFFFF',
  major: '#FFFFFF', highway: '#FFD28F',
  minor_service_casing: '#E4E0D6', minor_casing: '#E4E0D6', link_casing: '#E4E0D6',
  major_casing_early: '#D9D4C8', major_casing_late: '#D9D4C8',
  highway_casing_early: '#EDAB55', highway_casing_late: '#EDAB55',
  bridges_other: '#FFFFFF', bridges_minor: '#FFFFFF', bridges_link: '#FFFFFF', bridges_major: '#FFFFFF', bridges_highway: '#FFD28F',
  tunnel_other: '#EDE9E0', tunnel_minor: '#EDE9E0', tunnel_link: '#EDE9E0', tunnel_major: '#EDE9E0', tunnel_highway: '#F4DFB8',
  roads_label_minor: '#6B7280', roads_label_minor_halo: '#FFFFFF',
  roads_label_major: '#4B5563', roads_label_major_halo: '#FFFFFF',
  ocean_label: '#4F8FBF', subplace_label: '#64748B', subplace_label_halo: '#FFFFFF',
  city_label: '#1E293B', city_label_halo: '#FFFFFF',
  state_label: '#94A3B8', state_label_halo: '#FFFFFF', country_label: '#7C8AA0',
  landcover: {
    grassland: 'rgba(226, 238, 214, 1)', barren: 'rgba(244, 238, 224, 1)', urban_area: 'rgba(238, 236, 232, 1)',
    farmland: 'rgba(230, 240, 214, 1)', glacier: 'rgba(255, 255, 255, 1)', scrub: 'rgba(228, 236, 208, 1)',
    forest: 'rgba(208, 228, 200, 1)',
  },
};
const NOCHE = {
  background: '#0B1220', earth: '#141B2B', water: '#0C2340',
  park_a: '#15261F', park_b: '#173126', wood_a: '#14251E', wood_b: '#14251E',
  scrub_a: '#18261F', scrub_b: '#18261F', sand: '#1A2030', beach: '#1A2030',
  hospital: '#201C2A', industrial: '#171E2E', school: '#1B1E2C', pedestrian: '#161D2D',
  boundaries: '#5B6B8C',
  other: '#27324A', minor_service: '#27324A', minor_a: '#2A3550', minor_b: '#2F3B58', link: '#34416A',
  major: '#3C4A6B', highway: '#B98A3E',
  minor_service_casing: '#0E1524', minor_casing: '#0E1524', link_casing: '#0E1524',
  major_casing_early: '#1B2338', major_casing_late: '#1B2338',
  highway_casing_early: '#5B4120', highway_casing_late: '#5B4120',
  bridges_other: '#27324A', bridges_minor: '#2F3B58', bridges_link: '#34416A', bridges_major: '#3C4A6B', bridges_highway: '#B98A3E',
  tunnel_other: '#1E2840', tunnel_minor: '#1E2840', tunnel_link: '#1E2840', tunnel_major: '#243050', tunnel_highway: '#6B5430',
  roads_label_minor: '#8896B0', roads_label_minor_halo: '#141B2B',
  roads_label_major: '#A9B6CE', roads_label_major_halo: '#141B2B',
  ocean_label: '#4F7FB0', subplace_label: '#94A3B8', subplace_label_halo: '#0B1220',
  city_label: '#E2E8F0', city_label_halo: '#0B1220',
  state_label: '#64748B', state_label_halo: '#0B1220', country_label: '#8B9AB5',
  landcover: {
    grassland: 'rgba(20, 34, 28, 1)', barren: 'rgba(30, 30, 34, 1)', urban_area: 'rgba(24, 30, 44, 1)',
    farmland: 'rgba(22, 36, 30, 1)', glacier: 'rgba(40, 48, 64, 1)', scrub: 'rgba(24, 34, 28, 1)',
    forest: 'rgba(16, 32, 26, 1)',
  },
};

function estilo(flavor) {
  const base = namedFlavor(flavor);
  const ajuste = flavor === 'dark' ? NOCHE : DIA;
  const fl = { ...base, ...ajuste, landcover: { ...base.landcover, ...ajuste.landcover } };
  let L = layers('protomaps', fl, { lang: 'es' });
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
      paint: { 'circle-radius': 16, 'circle-color': dark ? '#60A5FA' : '#2563EB', 'circle-opacity': 0.20 } },
    { id: 'yo-punto', type: 'circle', source: 'yo',
      paint: { 'circle-radius': 8, 'circle-color': dark ? '#60A5FA' : '#2563EB', 'circle-stroke-color': dark ? '#0B1220' : '#ffffff', 'circle-stroke-width': 3 } }
  );
  // Marcador del lugar elegido en la búsqueda (círculo rojo con halo)
  L.push(
    { id: 'destino-halo', type: 'circle', source: 'destino',
      paint: { 'circle-radius': 20, 'circle-color': '#EF4444', 'circle-opacity': 0.22 } },
    { id: 'destino-punto', type: 'circle', source: 'destino',
      paint: { 'circle-radius': 9, 'circle-color': '#EF4444', 'circle-stroke-color': dark ? '#0B1220' : '#ffffff', 'circle-stroke-width': 3.5 } }
  );
  const s = {
    version: 8,
    name: 'gps-nativo-' + flavor,
    glyphs: 'asset://glyphs/{fontstack}/{range}.pbf',
    sources: {
      protomaps: { type: 'vector', url: 'pmtiles://file://__PMTILES__', attribution: '© OpenStreetMap' },
      yo: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
      destino: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
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
