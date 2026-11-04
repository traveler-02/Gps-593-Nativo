# GPS 593 nativo — prototipo 0.7 (mapa, búsqueda, navegación, lugares guardados y descarga de mapas)

App Android **nativa** (Kotlin, sin AppCompat ni Compose) que dibuja `ecuador.pmtiles` con MapLibre Native
(OpenGL ES) y muestra tu posición GPS, con un **panel de diagnóstico** para medir en la radio.

Objetivo: comprobar que el mapa nativo es claramente más fluido que la versión web **antes** de portar
rutas, búsqueda y voz.

## Cómo compilar (sin instalar nada)
1. Copia esta carpeta `nativo/` y `.github/workflows/android-nativo.yml` a la raíz de tu repositorio (reemplaza lo anterior).
2. GitHub > Actions > **Compilar APK nativo** > Run workflow (deja `armeabi-v7a`).
3. Abre la ejecución terminada: abajo, en **Artifacts**, descarga `GPS593-NATIVO-apk` (es un zip con el APK dentro).
   También queda en Releases > `android-nativo` como `GPS593-NATIVO.apk`.
   En el resumen de la ejecución verás `package: ec.gps593.nativo` y `application-label: GPS NATIVO`.

La app anterior (web) es `ec.gps593.app`, se llama "GPS 593" y está en Releases > `android`.
La nativa es `ec.gps593.nativo` y se llama "GPS 593 nativo" (mismo logo). Para no confundirte mientras pruebas, desinstala la anterior.

*Compilar en tu PC (Windows 10):* abre `nativo/` con Android Studio. Si pide Gradle, usa 8.9
(o ejecuta una vez `gradle wrapper --gradle-version 8.9`).

## Cómo saber que abriste la app NATIVA (y no la web anterior)
- Al abrir: pantalla con el logo y "Versión nativa 0.7"; luego tarjeta de velocidad, barra "¿A dónde vamos?" y botones redondos.
- Ajustes › Acerca de muestra "Versión nativa 0.7" y el diagnóstico. La app web anterior dice "WebView Chrome".

## Instalar los archivos (una sola vez)
La primera vez aparece la tarjeta **"Instala el mapa de Ecuador"**; también está en **Ajustes › Mapas**.
- **Elegir archivos**: abre el selector de Android. Puedes escoger **`ecuador.pmtiles` y `ecuador.g593` a la vez**:
  la app reconoce cuál es cada uno por su cabecera y los copia al almacenamiento interno con barra de progreso.
- **Buscar en el pendrive**: encuentra y copia ambos archivos si el pendrive está conectado (radios).
Al instalar el `.g593` se prepara el índice de búsqueda (una sola vez, algunos segundos) y queda guardado como `lugares.idx`.

## Pantalla
- Arriba: velocidad y estado del GPS, y la barra de búsqueda.
- Abajo izquierda: ☾/☀ día-noche y ⚙ ajustes. Abajo derecha: ＋ －  y centrar.
- **Búsqueda**: lugares, barrios y calles sin internet (con el `.g593`); si no hay resultados, prueba con internet (Photon, solo demo).
- **Mantén pulsado el mapa** para elegir cualquier punto como destino.
- **Ajustes**: *Mapas*, *Lugares* (favoritos), *Preferencias* (modo, pantalla, mapa 3D, voz) y *Acerca de* (diagnóstico).

## Lugares guardados
- **Casa y trabajo**: en la búsqueda (al abrirla) y en Ajustes › Lugares. Toca "Casa" o "Trabajo" para ir; si no están definidos, "Toca para definir" abre la búsqueda y el lugar que elijas queda guardado (también con "Mi ubicación actual" o manteniendo pulsado el mapa).
- **Favoritos con nombre**: toca ☆ en la tarjeta de un lugar → eliges *Favorito, Casa o Trabajo* y le pones nombre. Se pueden renombrar (✎) o quitar en Ajustes › Lugares.
- Escribir "casa", "trabajo" o el nombre de un favorito en la búsqueda los muestra primero.

## Voz
La voz usa el motor de texto a voz de Android en **español**. En Ajustes › Preferencias › Voz puedes: *Probar voz*, cambiar la velocidad (lenta/normal/rápida), elegir entre las voces en español instaladas y abrir los *ajustes de voz del sistema* para instalar una voz mejor.

## Descargar los mapas desde internet
Para publicar la app, los usuarios descargan los mapas dentro de la app. Lee **DISTRIBUCION.md** (recomendación, costos y pasos).
Se activa poniendo la URL del `manifest.json` en `Config.kt` (`MANIFIESTO_URL`).

## Navegación
1. Busca un lugar (o mantén pulsado el mapa) → **Cómo llegar** calcula la ruta sin internet y la dibuja con distancia, tiempo y hora de llegada.
2. **Iniciar**: el mapa te sigue y gira hacia donde avanzas; arriba ves la próxima maniobra y abajo la hora de llegada.
   La voz avisa a unos 400, 150 y 40 metros ("En 150 metros, gira a la derecha hacia…"). Si te sales de la ruta, recalcula sola.
3. **Simular** recorre la ruta a ~50 km/h para probar la voz y las instrucciones sin salir a la calle.
4. **Estoy aquí** (en la tarjeta de un lugar) fija una posición de prueba: sirve en interiores o para calcular rutas desde cualquier punto.

## Si el GPS no localiza
Ajustes › Acerca de muestra el permiso, los proveedores (GPS, red, fusionado) y cuántas posiciones llegaron de cada uno.
El GPS necesita cielo abierto; en interiores usa la ubicación aproximada de la red o la posición de prueba.

## Qué mirar en el panel ⓘ (y qué anotar)
- `Desde el inicio`: pantalla / motor / estilo / **mapa completo** (ms desde que Android crea la app).
- `FPS`: míralo mientras arrastras y haces zoom. Meta: 25-30 o más.
- `Memoria de la app` y `RAM libre del sistema`: la hipótesis es que la RAM (2 GB) era el cuello de botella.
- Toma una foto con el mapa quieto y otra moviéndolo.

## Si algo falla
- `ERROR: No se pudo iniciar el motor de mapas…` (UnsatisfiedLinkError): la radio no soporta esa ABI o falta la librería.
- Mapa sin calles: mira `Mapa:` en el panel (¿lo encontró?) y `ERROR:`. Debe ser un `.pmtiles` de Protomaps (esquema v4).
- Sin textos en el mapa: faltan glifos; están en `app/src/main/assets/glyphs/`.
- No encuentra el pendrive: acepta el permiso de almacenamiento y revisa que el archivo termine en `.pmtiles`.

## Estructura
- `app/src/main/java/ec/gps593/nativo/Config.kt` — creador, propietario y URL del manifiesto; `Descarga.kt` — descarga con reanudación y SHA-256.
- `app/src/main/java/ec/gps593/nativo/MainActivity.kt` — interfaz y lógica; `Indice.kt` — índice de búsqueda; `Rutas.kt` — motor de rutas (A*) sobre el `.g593` mapeado en memoria.
- `tools/make_manifest.py` — genera el `manifest.json` de la descarga.
- `tools/fetch_icons.py` — regenera los íconos (Material Symbols, Apache 2.0) en `res/drawable`.
- `app/src/main/assets/style-day.json | style-night.json` — estilos ligeros (43 capas, sin íconos ni edificios).
  Se regeneran con `npm i @protomaps/basemaps && node tools/make_style.mjs` (las paletas están en ese script).
- `app/src/main/assets/glyphs/` — fuentes del mapa (Noto Sans) para funcionar sin internet.

## Próximos pasos
Rutas en línea de respaldo, rotonda/salidas en las instrucciones, límites de velocidad y reportes.
