# GPS 593 nativo — cambios de revisión 2.0-beta.2

## Cambios aplicados en esta entrega

- **Validación estructural del archivo G593 antes de mapearlo en memoria.** Ahora se comprueba el tamaño mínimo de cabecera, la firma, la versión del formato, los contadores y el tamaño total esperado de todos los bloques. Se rechazan archivos truncados, con contadores inválidos o con tamaño incompatible mediante un error controlado.
- **Protección frente a desbordamientos durante la validación.** Los tamaños se calculan en `Long` y se rechazan archivos que exceden el límite de `ByteBuffer` usado por Android.
- **Versión Android incrementada** a `versionCode 12` / `versionName 2.0-beta.2`.

## Mejoras de la auditoría que ya estaban presentes en el ZIP recibido

La revisión del código fuente encontró que varios puntos del documento de auditoría ya estaban implementados en esta base:

- La búsqueda muestra primero los resultados locales y solo consulta Photon como complemento cuando detecta conectividad.
- El estilo cartográfico ya no elimina las capas `buildings`, `address_label`, `pois` y `roads_oneway`; establece niveles mínimos de zoom para reducir carga.
- El motor de rutas ya aplica distancias máximas de asociación a carreteras (250 m origen / 600 m destino).
- La ETA ya utiliza tiempos y distancias acumulados por segmento cuando el grafo contiene esos datos.
- La selección de voz prioriza voces españolas offline y evita usar una voz que requiere red cuando no hay conexión.
- La instalación local utiliza archivos temporales y copias de respaldo para revertir una activación fallida.
- Los controles táctiles cuentan con un mínimo de 48 dp mediante `dpT()`.

## Pendiente: no se debe considerar resuelto en esta entrega

- **Restricciones de giro OSM:** el generador de grafo todavía no incorpora relaciones `type=restriction`; el motor puede proponer giros legalmente restringidos.
- **Navegación en segundo plano:** `onPause()` detiene el GPS. Para navegación con pantalla apagada hace falta implementar y probar un servicio en primer plano, con su notificación y permisos según la versión Android.
- **Firma de producción:** el proyecto todavía configura la variante release con la firma debug. No distribuir esta compilación como versión comercial ni como actualización pública; se necesita una clave de publicación estable y protegida.
- **Cartografía real:** el ZIP no contiene `ecuador.pmtiles` ni `ecuador.g593`; no se pudo certificar cobertura, integridad de datos, densidad de edificios/POI ni compatibilidad de versiones del conjunto cartográfico real.
- **Pruebas físicas:** no se pudo medir FPS, RAM, consumo, GPS, audio offline ni estabilidad prolongada en la radio objetivo.
- **ETA:** el cálculo segmentado depende de que el archivo G593 haya sido generado con tiempos adecuados; no reemplaza tráfico real ni velocidades observadas.

## Pruebas realizadas en esta entrega

- Inspección estática de los archivos Kotlin, Python, estilos y configuración Android.
- Verificación sintáctica de los generadores Python con `py_compile`.
- No se pudo ejecutar una compilación Android local: el ZIP no incluye `gradlew` y el entorno no tiene Gradle instalado. La compilación debe ejecutarse en el workflow de GitHub Actions o en un entorno Android configurado.

## Recomendación antes de producción

No publicar como navegador profesional hasta implementar las restricciones de giro, el servicio de navegación en segundo plano y la firma estable de producción; después realizar pruebas reales offline con los archivos cartográficos finales y en la radio de destino.
