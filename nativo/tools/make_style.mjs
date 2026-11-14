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
  background: '#12213F', earth: '#1A2D52', water: '#0D3B7A',
  park_a: '#1E3A63', park_b: '#21406B', wood_a: '#1C3860', wood_b: '#1C3860',
  scrub_a: '#1F3A5F', scrub_b: '#1F3A5F', sand: '#243557', beach: '#243557',
  hospital: '#2A2F5C', industrial: '#1D2F55', school: '#22345F', pedestrian: '#1E3158',
  boundaries: '#6F86B8',
  other: '#34497B', minor_service: '#34497B', minor_a: '#3B5185', minor_b: '#41598F', link: '#47609A',
  major: '#5570AA', highway: '#E3A547',
  minor_service_casing: '#14213F', minor_casing: '#14213F', link_casing: '#14213F',
  major_casing_early: '#14213F', major_casing_late: '#14213F',
  highway_casing_early: '#7A5A22', highway_casing_late: '#7A5A22',
  bridges_other: '#34497B', bridges_minor: '#41598F', bridges_link: '#47609A', bridges_major: '#5570AA', bridges_highway: '#E3A547',
  tunnel_other: '#26396A', tunnel_minor: '#26396A', tunnel_link: '#2B3F72', tunnel_major: '#2F4478', tunnel_highway: '#8A6B3A',
  roads_label_minor: '#9DB2DD', roads_label_minor_halo: '#1A2D52',
  roads_label_major: '#C3D1EE', roads_label_major_halo: '#1A2D52',
  ocean_label: '#6C9AE0', subplace_label: '#A5B8E0', subplace_label_halo: '#12213F',
  city_label: '#F1F5FF', city_label_halo: '#12213F',
  state_label: '#8296C2', state_label_halo: '#12213F', country_label: '#9FB1D9',
  landcover: {
    grassland: 'rgba(30, 55, 92, 1)', barren: 'rgba(36, 49, 84, 1)', urban_area: 'rgba(28, 46, 84, 1)',
    farmland: 'rgba(32, 56, 96, 1)', glacier: 'rgba(70, 90, 130, 1)', scrub: 'rgba(31, 55, 94, 1)',
    forest: 'rgba(27, 50, 88, 1)',
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
  // Rutas alternativas (más tenues) y ruta elegida (línea con contorno), por debajo de los marcadores
  L.push(
    { id: 'ralt-contorno', type: 'line', source: 'rutaAlt',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': dark ? '#0B1730' : '#FFFFFF',
               'line-width': ['interpolate', ['linear'], ['zoom'], 6, 4, 12, 8, 17, 14] } },
    { id: 'ralt-linea', type: 'line', source: 'rutaAlt',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': dark ? '#7C8FB8' : '#94A3B8',
               'line-width': ['interpolate', ['linear'], ['zoom'], 6, 2.5, 12, 5, 17, 9] } }
  );
  L.push(
    { id: 'ruta-contorno', type: 'line', source: 'ruta',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': dark ? '#0B1730' : '#FFFFFF',
               'line-width': ['interpolate', ['linear'], ['zoom'], 6, 5, 12, 10, 17, 18] } },
    { id: 'ruta-linea', type: 'line', source: 'ruta',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': dark ? '#5B9BFF' : '#2563EB',
               'line-width': ['interpolate', ['linear'], ['zoom'], 6, 3, 12, 6.5, 17, 12] } }
  );
  // Tu posición: una imagen que la app dibuja (punto, flecha, triángulo, auto o taxi) y que gira con el rumbo
  L.push(
    { id: 'yo-icono', type: 'symbol', source: 'yo',
      layout: { 'icon-image': ['get', 'icono'], 'icon-rotate': ['coalesce', ['get', 'rumbo'], 0],
                'icon-rotation-alignment': 'map', 'icon-pitch-alignment': 'map',
                'icon-allow-overlap': true, 'icon-ignore-placement': true } }
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
      ruta: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
      rutaAlt: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
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
