package ec.gps593.nativo

/** Datos de la app y de dónde se descargan los mapas. */
object Config {
    const val CREADOR = "Ronn"
    const val PROPIETARIO = "RonnDu Corp."

    /**
     * Dirección del manifiesto de mapas (manifest.json). Con esta URL la app ofrece "Descargar mapas".
     * Déjala vacía para usar solo "Elegir archivos". Ver DISTRIBUCION.md. Ejemplos:
     *   Cloudflare R2:   "https://mapas.tudominio.com/manifest.json"
     *   GitHub Releases: "https://github.com/USUARIO/REPO/releases/download/mapas/manifest.json"
     */
    const val MANIFIESTO_URL = ""
}
