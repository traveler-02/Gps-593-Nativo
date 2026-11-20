# GPS 593 nativo 2.0-beta.1 (versionCode 11)

Interfaz (según las capturas de referencia)
- Navegación: tarjeta oscura arriba a la izquierda (flecha, distancia a la maniobra, calle); velocidad a la derecha.
- Tarjeta inferior izquierda: botón X para salir, tiempo restante en verde, hora de llegada · distancia.
- Botón de voz (silenciar/activar) arriba a la derecha durante la navegación.
- "Simular" salió de la pantalla de navegación: ahora está en Ajustes › Preferencias › Pruebas y diagnóstico.
- Pantalla principal: estado "Sin internet · mapa ✓ · rutas ✓ · búsqueda ✓" junto al estado GPS.
- Controles táctiles de mínimo 48 dp reales (dpT) aunque la pantalla sea pequeña.
- Búsqueda: categorías rápidas (gasolineras, restaurantes, hospitales, farmacias, parqueaderos, tiendas) con
  lugares cercanos desde el índice local, e iconos por categoría.

Auditoría, fase 1
- Búsqueda: resultados locales siempre y al instante; Photon solo si hay internet real; tareas anteriores se descartan.
- ETA: usa el tiempo acumulado por tramo (Ruta.tiempoRestante), no una proporción de la distancia.
- snap(): distancia máxima (250 m origen, 600 m destino); si no hay calle cercana se avisa.
- Voz: se prefieren voces sin conexión; aviso si no hay ninguna.
- Instalación/descarga: todo o nada, con copia de la versión anterior y reversión si algo falla; validación de PMTiles v3 y tamaño.
- Estilo del mapa: edificios 2D (zoom 15), lugares (15) y direcciones (17) recuperados.

Pendiente (no incluido)
- Restricciones de giro, direcciones por número, edificios Overture, paquetes regionales/ciudades, índice generado en CI,
  servicio de navegación en primer plano, firma de release, arm64-v8a, separar MainActivity en módulos.
