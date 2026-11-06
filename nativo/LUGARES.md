# Por qué faltaban lugares en la búsqueda y cómo completarlos

**Causa**: el proceso que genera `ecuador.g593` (`tools/build_graph.py`) solo guardaba como lugares los *puntos* OSM con `place=*`
y 5 tipos de servicio (gasolinera, hospital, clínica, farmacia, policía), más las calles. No entraban restaurantes, tiendas, hoteles,
colegios, bancos, parques… ni los lugares mapeados como **edificios/áreas** (centros comerciales, hospitales, universidades).
El mapa y las rutas no tenían problema: faltaban en el índice de búsqueda.

## Qué cambió
1. `build_graph.py` ahora incluye puntos de interés de OpenStreetMap (puntos y áreas) en 12 categorías, con su etiqueta en español
   (Farmacia, Supermercado, Centro comercial, Hotel…). Si un lugar no tiene `name` pero sí `brand`, usa la marca.
2. La app busca también por categoría ("farmacia" encuentra todas) y ordena por cercanía.
3. Opcional: sumar **Overture Maps** (datos abiertos con ~64 millones de lugares en el mundo, muchos comercios que no están en OSM).

## Cómo aplicarlo
1. Ejecuta el flujo **Crear mapa y rutas de Ecuador** con `dry_run` apagado (y, si quieres, `overture` activado).
2. Instala/descarga el nuevo `ecuador.g593` en la app (Ajustes › Mapas). Prepara el índice sola (unos segundos, una vez).

## Overture Maps (opcional)
* Se descarga con `tools/overture_places.py` (usa DuckDB; filtra por zona, confianza y comercios cerrados) y se mezcla con
  `build_graph.py --extra lugares_extra.jsonl`, **sin duplicar** lo que ya está en OSM (mismo nombre a menos de ~100 m).
* Licencia: Overture Places es mayormente CDLA-Permissive-2.0 (más Apache-2.0 y CC0 según la fuente): hay que dar atribución
  a *Overture Maps Foundation* (ya está en Ajustes › Acerca de).
* **Probado aquí solo con datos sintéticos**: no pude conectarme al servidor público de Overture desde mi entorno. La primera vez
  ejecútalo con el flujo y revisa el conteo; el paso tiene `continue-on-error`, así que si falla no rompe la generación del mapa.
* El esquema de Overture cambia entre versiones (`categories` → `taxonomy`); el script detecta cuál hay.

## Más allá
Direcciones con número de casa (addr:housenumber) y un geocodificador propio serían el siguiente salto si lo necesitas.
