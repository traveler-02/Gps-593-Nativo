package ec.gps593.nativo

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Descarga de mapas dentro de la app: lee un manifiesto (manifest.json) y baja cada archivo con
 * reanudación (si se corta, continúa donde quedó) y verificación SHA-256.
 */
object Descarga {
    class Archivo(val nombre: String, val url: String, val bytes: Long, val sha256: String)

    class Manifiesto(val version: String, val archivos: List<Archivo>) {
        fun totalBytes(): Long {
            var t = 0L
            for (a in archivos) t += a.bytes
            return t
        }
    }

    class Cancelada : IOException("Descarga cancelada")

    /** Lee el manifiesto. Las URL de los archivos pueden ser absolutas o relativas a la del manifiesto. */
    fun leerManifiesto(url: String): Manifiesto {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 8000
            con.readTimeout = 10000
            con.setRequestProperty("User-Agent", "GPS593-nativo")
            con.setRequestProperty("Cache-Control", "no-cache")
            if (con.responseCode != 200) throw IOException("El servidor respondió ${con.responseCode}")
            val texto = con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val j = JSONObject(texto)
            val arr = j.getJSONArray("archivos")
            val base = URL(url)
            val lista = ArrayList<Archivo>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                lista.add(
                    Archivo(
                        o.getString("nombre"), URL(base, o.getString("url")).toString(),
                        o.optLong("bytes", 0L), o.optString("sha256", "")
                    )
                )
            }
            if (lista.isEmpty()) throw IOException("El manifiesto no lista archivos")
            return Manifiesto(j.optString("version", ""), lista)
        } finally {
            con.disconnect()
        }
    }

    /** Baja `a` a `parte` (archivo .part). Si ya existe a medias, reanuda con "Range". */
    fun bajar(a: Archivo, parte: File, cancelar: () -> Boolean, progreso: (Long, Long) -> Unit) {
        var hecho = if (parte.exists()) parte.length() else 0L
        if (a.bytes > 0L && hecho > a.bytes) {
            parte.delete()
            hecho = 0L
        }
        if (a.bytes <= 0L || hecho < a.bytes) {
            val con = URL(a.url).openConnection() as HttpURLConnection
            try {
                con.connectTimeout = 15000
                con.readTimeout = 25000
                con.instanceFollowRedirects = true
                con.setRequestProperty("User-Agent", "GPS593-nativo")
                if (hecho > 0L) con.setRequestProperty("Range", "bytes=$hecho-")
                val codigo = con.responseCode
                if (codigo == 200 && hecho > 0L) {
                    hecho = 0L                                  // el servidor ignoró "Range": se empieza de nuevo
                    parte.delete()
                } else if (codigo != 200 && codigo != 206) {
                    throw IOException("El servidor respondió $codigo")
                }
                val largo = con.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                val total = if (a.bytes > 0L) a.bytes else if (largo > 0L) largo + hecho else -1L
                FileOutputStream(parte, hecho > 0L).use { salida ->
                    con.inputStream.use { entrada ->
                        val buf = ByteArray(1 shl 16)
                        var n = entrada.read(buf)
                        while (n > 0) {
                            if (cancelar()) throw Cancelada()
                            salida.write(buf, 0, n)
                            hecho += n
                            progreso(hecho, total)
                            n = entrada.read(buf)
                        }
                    }
                }
            } finally {
                con.disconnect()
            }
        }
        if (a.bytes > 0L && parte.length() != a.bytes) {
            throw IOException("La descarga quedó incompleta; vuelve a intentarlo y continuará donde quedó")
        }
        if (a.sha256.isNotEmpty()) {
            val h = sha256(parte)
            if (!h.equals(a.sha256, ignoreCase = true)) {
                parte.delete()
                throw IOException("El archivo descargado está dañado (la suma de verificación no coincide)")
            }
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { i ->
            val buf = ByteArray(1 shl 16)
            var n = i.read(buf)
            while (n > 0) {
                md.update(buf, 0, n)
                n = i.read(buf)
            }
        }
        val sb = StringBuilder()
        for (b in md.digest()) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
