# GPS 593 nativo — prototipo 0.1 (solo mapa)

App Android **nativa** (Kotlin, sin AppCompat ni Compose) que dibuja `ecuador.pmtiles` con MapLibre Native
(OpenGL ES) y muestra tu posición GPS. Incluye un **panel de diagnóstico** para medir en la radio.

Objetivo de este prototipo: comprobar que el mapa nativo es claramente más fluido que la versión web
**antes** de portar rutas, búsqueda y voz.

## Cómo compilar (sin instalar nada)
1. Copia esta carpeta `nativo/` y `.github/workflows/android-nativo.yml` a la raíz de tu repositorio.
2. GitHub > Actions > **Compilar APK nativo** > Run workflow (deja `armeabi-v7a`).
3. Descarga `gps593-nativo.apk` de Releases > `android-nativo` y pásalo a la radio.
   Se instala aparte de la app actual (otro `applicationId`), así puedes comparar las dos.

El paso "Verificar" falla con un mensaje claro si el SDK de mapas no trae la librería para la arquitectura.

*Compilar en tu PC (Windows 10):* abre `nativo/` con Android Studio. Si pide Gradle, usa 8.9 (o ejecuta una vez
`gradle wrapper --gradle-version 8.9`).

## Cómo usarlo en la radio
1. Pon `ecuador.pmtiles` en la **raíz del pendrive** (sin espacios en el nombre) y conéctalo.
2. Abre la app y acepta los permisos (ubicación y almacenamiento).
3. Si el mapa está en el pendrive aparece **Copiar mapa al interno**: copia una sola vez y luego ya no depende del pendrive.
   Prueba ambas formas y compara los tiempos.
4. Botones: ＋ － zoom, **Centrar** (vuelve a seguir el GPS), **Día/Noche**, **Diag** (muestra/oculta el panel).

## Qué mirar en el panel (y qué anotar)
- `Desde el inicio`: pantalla / motor / estilo / **mapa completo** (ms desde que Android crea la app).
- `FPS`: míralo mientras arrastras y haces zoom. Meta: 25-30 o más.
- `Memoria de la app` y `RAM libre del sistema`: la hipótesis es que la RAM (2 GB) era el cuello de botella.
- Toma una foto del panel con el mapa quieto y otra moviéndolo.

## Si algo falla
- `ERROR: No se pudo iniciar el motor de mapas…` (UnsatisfiedLinkError): la radio no soporta esa ABI o falta la librería.
- Mapa gris sin calles: mira `Mapa:` en el panel (¿lo encontró?) y `ERROR:`. Revisa que sea un `.pmtiles` de Protomaps (esquema v4).
- Sin textos en el mapa: faltan glifos de fuentes; están en `app/src/main/assets/glyphs/`.
- No encuentra el pendrive: acepta el permiso de almacenamiento y revisa que el archivo sea `.pmtiles`.

## Estructura
- `app/src/main/java/ec/gps593/nativo/MainActivity.kt` — toda la lógica del prototipo.
- `app/src/main/assets/style-day.json | style-night.json` — estilos ligeros (43 capas, sin íconos ni edificios).
  Se regeneran con `npm i @protomaps/basemaps && node tools/make_style.mjs`.
- `app/src/main/assets/glyphs/` — fuentes del mapa (Noto Sans) para funcionar sin internet.

## Próximos pasos
Rutas (A* sobre `ecuador.g593` mapeado en memoria) → búsqueda sin internet → voz → navegación giro a giro.
