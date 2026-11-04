# Cómo reciben los mapas los usuarios (Play Store)

La app necesita dos archivos grandes: `ecuador.pmtiles` (mapa, ~120 MB) y `ecuador.g593` (rutas y búsqueda, ~87 MB).
No conviene meterlos en el APK. La app los **descarga una sola vez** desde internet y luego funciona sin conexión.

## Recomendación

**Cloudflare R2 (almacenamiento de objetos con descarga gratuita) + `manifest.json` + descarga dentro de la app.**
GitHub se queda como la "fábrica" que construye los archivos (tu flujo *Crear mapa y rutas de Ecuador*), no como el sitio de descarga.

Por qué:

| | GitHub Releases | **Cloudflare R2** | Play Asset Delivery | S3 / Firebase / Google Cloud |
|---|---|---|---|---|
| Costo con 10.000 descargas de 207 MB (~2 TB) | $0 | **$0** (la salida de datos es gratuita; almacenamiento ~0,2 GB dentro del nivel gratis) | $0 | Se paga por GB descargado (del orden de decenas de dólares por TB) |
| Riesgo para tu cuenta | **Medio/alto a escala**: las Releases son para distribuir versiones del software del repositorio; si se usan como almacenamiento gratuito, GitHub puede limitar o cerrar el repositorio | Ninguno (es un servicio comercial para esto) | Ninguno | Ninguno |
| Límite por archivo | 2 GiB | muy alto | 1,5 GB por paquete, 4 GB en total | muy alto |
| Funciona en radios / APK fuera de Play | Sí | **Sí** | **No** (solo instalaciones desde Google Play) | Sí |
| Actualizar mapas sin publicar una versión nueva de la app | Sí | **Sí** | No (van con las versiones de la app) | Sí |
| Descarga reanudable y verificable | Sí | Sí | La hace Google | Sí |

* **GitHub Releases** es perfecto para **probar** (lo que haces hoy) y como copia de respaldo. Para una app pública no lo uses como única fuente.
* **Play Asset Delivery** es una buena alternativa **solo para Play**, pero te ata a cada publicación de la app y no sirve para las radios con APK directo (que son tu prioridad).
* **R2** da un solo camino de código para Play, radios y APK directo, con actualizaciones mensuales sin tocar la app.

Verifica los precios vigentes antes de decidir: <https://developers.cloudflare.com/r2/pricing/>

## Cómo funciona (ya está hecho en la app)

```
GitHub Actions  →  construye ecuador.pmtiles + ecuador.g593  →  genera manifest.json (versión + SHA-256)
                                                              →  sube todo a R2 (y a la Release, de respaldo)
App del usuario →  lee manifest.json  →  descarga (reanudable)  →  verifica SHA-256  →  instala
```

* **Ajustes › Mapas** muestra "Descargar mapas" y, cuando subes datos nuevos, "Hay una versión nueva de los mapas".
* Si el usuario usa datos móviles, la app pregunta antes de bajar ~200 MB.
* Si se corta la conexión, al reintentar **continúa donde quedó**.
* Un archivo dañado se detecta por su SHA-256 y se descarta.

## Activarlo (una vez)

### A. Para probar hoy con GitHub Releases (sin R2)
1. Ejecuta *Crear mapa y rutas de Ecuador* con `dry_run` apagado. Ahora también publica `manifest.json` en la Release `mapas`.
2. En `app/src/main/java/ec/gps593/nativo/Config.kt` pon:
   `const val MANIFIESTO_URL = "https://github.com/TU_USUARIO/TU_REPO/releases/download/mapas/manifest.json"`
3. Compila la app: aparecerá el botón **Descargar mapas**.

### B. Para publicar (Cloudflare R2)
1. Crea una cuenta en Cloudflare y un **bucket** de R2 (p. ej. `gps593-mapas`).
2. Publica el bucket con un **dominio propio** (p. ej. `mapas.tudominio.com`). El subdominio de prueba `r2.dev` tiene límites de uso y no está pensado para producción.
3. Crea un **token de API de R2** (lectura y escritura sobre ese bucket).
4. En GitHub: *Settings › Secrets and variables › Actions*:
   * **Secrets**: `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`
   * **Variables**: `R2_BUCKET` (nombre del bucket), `R2_ENDPOINT` (`https://<ID_DE_CUENTA>.r2.cloudflarestorage.com`), `BASE_URL` (`https://mapas.tudominio.com`)
5. En `Config.kt`: `MANIFIESTO_URL = "https://mapas.tudominio.com/manifest.json"`.
6. Cada vez que ejecutes *Crear mapa y rutas de Ecuador* (por ejemplo, una vez al mes) los usuarios verán la actualización.

El manifiesto se sube **al final**, para que ningún usuario vea una versión a medias.

## Antes de publicar en Play Store

1. **Dos versiones de la app** (ya lo anotaste):
   * *Radio* (APK directo): `targetSdk 29` para leer el pendrive.
   * *Play*: `targetSdk` actual exigido por Google, sin permiso de almacenamiento ni "Buscar en el pendrive" (la descarga y "Elegir archivos" siguen funcionando con el almacenamiento propio de la app).
2. **Servicios públicos de demostración**: la búsqueda en línea de respaldo (Photon) no debe ir en la versión Play. Déjala solo sin internet (índice del `.g593`) o monta tu propio servidor.
3. **Datos de OpenStreetMap**: es obligatorio mostrar *© OpenStreetMap contributors* (ya está en Ajustes › Acerca de y en el mapa). Los mapas derivados de OSM se distribuyen bajo la licencia ODbL; menciónalo en la ficha de la tienda o en tu política de privacidad.
4. **Trámites en Play Console**: política de privacidad (la app usa ubicación; no hay anuncios ni rastreo en segundo plano), formulario de seguridad de los datos y cuenta de desarrollador. Las reglas para cuentas nuevas cambian: verifícalas ahí.
5. **Creador y propietario**: el creador y el propietario (RonnDu Corp.) se cambian en `Config.kt`.

## Formato de `manifest.json`

```json
{
  "formato": 1,
  "version": "2026.11.03",
  "archivos": [
    { "nombre": "ecuador.pmtiles", "tipo": "mapa",  "url": "https://mapas.tudominio.com/ecuador.pmtiles", "bytes": 126000000, "sha256": "…" },
    { "nombre": "ecuador.g593",    "tipo": "rutas", "url": "https://mapas.tudominio.com/ecuador.g593",    "bytes": 91000000,  "sha256": "…" }
  ]
}
```

Se genera con `python tools/make_manifest.py --base https://mapas.tudominio.com --version 2026.11.03 ecuador.pmtiles ecuador.g593 > manifest.json`.
