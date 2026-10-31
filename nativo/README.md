# GPS 593 nativo — prototipo 0.4 (solo mapa)

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
- Al abrir: pantalla con el logo y "Versión nativa 0.4"; luego tarjeta de velocidad, barra "¿A dónde vamos?" y botones redondos.
- El botón ⓘ muestra un panel que empieza con `GPS 593 nativo 0.4 | Android …`. La app web anterior dice "WebView Chrome".

## Cómo instalar el mapa (una sola vez)
La primera vez aparece la tarjeta **"Instala el mapa de Ecuador"**:
1. **Elegir archivo del mapa**: abre el selector de Android; escoge `ecuador.pmtiles` (del teléfono, del pendrive o de la nube).
   Se copia al almacenamiento interno con una barra de progreso y se valida que sea un mapa real.
2. **Buscar en el pendrive**: lo busca solo si el pendrive está conectado (en radios).
Después el mapa abre al instante, sin pendrive ni copia. Si lo usas directo desde el pendrive, abajo aparece
"Mapa en el pendrive · toca para copiarlo al interno".

## Pantalla
Tarjeta de velocidad y estado del GPS (arriba), barra de búsqueda (aún sin función), ☾/☀ día-noche y ⓘ diagnóstico
(abajo izquierda), ＋ － y centrar (abajo derecha). La interfaz se escala sola según el tamaño de la pantalla.

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
- `app/src/main/java/ec/gps593/nativo/MainActivity.kt` — toda la lógica y la interfaz.
- `app/src/main/assets/style-day.json | style-night.json` — estilos ligeros (43 capas, sin íconos ni edificios).
  Se regeneran con `npm i @protomaps/basemaps && node tools/make_style.mjs` (las paletas están en ese script).
- `app/src/main/assets/glyphs/` — fuentes del mapa (Noto Sans) para funcionar sin internet.

## Próximos pasos
Rutas (A* sobre `ecuador.g593` mapeado en memoria, sin paso de "cargando") → búsqueda sin internet → voz → navegación giro a giro.
