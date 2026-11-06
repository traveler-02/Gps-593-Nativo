package ec.gps593.nativo

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale

/** Un lugar encontrado (población, calle, comercio, servicio…). */
class Resultado(
    val nombre: String, val lat: Double, val lon: Double, val tipo: Int,
    val detalle: String = "", val categoria: String = "", val origen: String = ""
) {
    fun tipoTexto(): String = if (categoria.isNotEmpty()) categoria else when (tipo) {
        0 -> "Ciudad"
        1 -> "Pueblo"
        2 -> "Aldea"
        3 -> "Barrio"
        4 -> "Caserío"
        5 -> "Servicio"
        6 -> "Calle"
        7 -> "Comercio"
        8 -> "Comida"
        9 -> "Salud"
        10 -> "Educación"
        11 -> "Gasolinera"
        12 -> "Banco"
        13 -> "Alojamiento"
        14 -> "Turismo"
        15 -> "Transporte"
        16 -> "Parque"
        17 -> "Servicios"
        else -> "Lugar"
    }
}

object Texto {
    /** Minúsculas y sin tildes, para buscar "peru" y encontrar "Perú". */
    fun norm(s: String): String {
        val d = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(d.length)
        for (ch in d) {
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) sb.append(ch)
        }
        return sb.toString().lowercase(Locale.ROOT)
    }
}

private class IntVec {
    var a = IntArray(1 shl 16)
    var n = 0
    fun add(v: Int) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }
}

/**
 * Índice de lugares para buscar sin internet.
 * Se construye UNA vez a partir del bloque JSON de lugares del archivo .g593 (ver tools/build_graph.py)
 * y se guarda como `lugares.idx`, un archivo binario compacto que se carga en milisegundos.
 *
 * Formato v2 (little-endian): "L593", versión(2), n, bytesNombres, bytesNorm, bytesCategorias,
 *   lat[n], lon[n] (grados * 1e5), ctx[n], offNombre[n+1], offNorm[n+1], offCategoria[n+1],
 *   tipo[n] (1 byte), nombres, normalizados ("nombre|categoría"), categorías.
 */
class IndiceLugares private constructor(private val bytes: ByteArray) {
    private val bb: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val total: Int = bb.getInt(8)
    private val dispLen: Int = bb.getInt(12)
    private val normLen: Int = bb.getInt(16)
    private val posLat = 24
    private val posLon = posLat + 4 * total
    private val posCtx = posLon + 4 * total
    private val posDispOff = posCtx + 4 * total
    private val posNormOff = posDispOff + 4 * (total + 1)
    private val posSubOff = posNormOff + 4 * (total + 1)
    private val posTipo = posSubOff + 4 * (total + 1)
    private val baseDisp = posTipo + total
    private val baseNorm = baseDisp + dispLen
    private val baseSub = baseNorm + normLen

    private fun nombreDe(i: Int): String {
        val o0 = bb.getInt(posDispOff + 4 * i)
        val o1 = bb.getInt(posDispOff + 4 * (i + 1))
        return String(bytes, baseDisp + o0, o1 - o0, Charsets.UTF_8)
    }

    private fun categoriaDe(i: Int): String {
        val o0 = bb.getInt(posSubOff + 4 * i)
        val o1 = bb.getInt(posSubOff + 4 * (i + 1))
        return if (o1 > o0) String(bytes, baseSub + o0, o1 - o0, Charsets.UTF_8) else ""
    }

    private fun indexOf(a: Int, b: Int, t: ByteArray): Int {
        val m = t.size
        if (m == 0) return a
        val ultimo = b - m
        var s = a
        while (s <= ultimo) {
            if (bytes[s] == t[0]) {
                var k = 1
                while (k < m && bytes[s + k] == t[k]) k++
                if (k == m) return s
            }
            s++
        }
        return -1
    }

    private fun contieneTodos(a: Int, b: Int, tb: List<ByteArray>): Boolean {
        for (t in tb) if (indexOf(a, b, t) < 0) return false
        return true
    }

    /** Poblaciones primero, luego comercios y servicios, y las calles al final. */
    private fun rangoTipo(t: Int): Int = if (t <= 4) t else if (t == 6) 7 else 5

    /**
     * Busca lugares cuyo nombre o categoría contengan todas las palabras (sin tildes ni mayúsculas):
     * "farmacia" encuentra todas las farmacias aunque se llamen distinto. Con una posición de referencia,
     * entre resultados parecidos salen primero los más cercanos.
     */
    fun buscar(consulta: String, max: Int = 10, latRef: Double = Double.NaN, lonRef: Double = Double.NaN): List<Resultado> {
        val toks = Texto.norm(consulta).split(' ', '\t').filter { it.isNotEmpty() }
        if (toks.isEmpty()) return emptyList()
        val tb = toks.map { it.toByteArray(Charsets.UTF_8) }
        val nq = toks.joinToString(" ").toByteArray(Charsets.UTF_8)
        val conRef = !latRef.isNaN() && !lonRef.isNaN()
        val cosRef = if (conRef) Math.cos(latRef * Math.PI / 180.0) else 1.0
        val barra = '|'.code.toByte()
        var hits = LongArray(2048)
        var cnt = 0
        var i = 0
        while (i < total) {
            val a = baseNorm + bb.getInt(posNormOff + 4 * i)
            val b = baseNorm + bb.getInt(posNormOff + 4 * (i + 1))
            if (contieneTodos(a, b, tb)) {
                var sep = b
                var k = a
                while (k < b) {
                    if (bytes[k] == barra) {
                        sep = k
                        break
                    }
                    k++
                }
                val enNombre = sep == b || contieneTodos(a, sep, tb)
                var empieza = enNombre && sep - a >= nq.size
                if (empieza) {
                    for (q in nq.indices) {
                        if (bytes[a + q] != nq[q]) {
                            empieza = false
                            break
                        }
                    }
                }
                val match = if (empieza) 0 else if (enNombre) 1 else 2
                val rango = match * 10 + rangoTipo(bytes[posTipo + i].toInt())
                var dcode = 0
                if (conRef) {
                    val dx = (bb.getInt(posLon + 4 * i) / 1e5 - lonRef) * cosRef * 111320.0
                    val dy = (bb.getInt(posLat + 4 * i) / 1e5 - latRef) * 111320.0
                    dcode = Math.min(0xFFFFF, (Math.hypot(dx, dy) / 100.0).toInt())
                }
                if (cnt == hits.size) hits = hits.copyOf(cnt * 2)
                hits[cnt++] = (rango.toLong() shl 44) or (dcode.toLong() shl 24) or i.toLong()
            }
            i++
        }
        java.util.Arrays.sort(hits, 0, cnt)
        val vistos = HashSet<String>()
        val out = ArrayList<Resultado>()
        for (h in 0 until cnt) {
            if (out.size >= max) break
            val idx = (hits[h] and 0xFFFFFFL).toInt()
            val nom = nombreDe(idx)
            val ctx = bb.getInt(posCtx + 4 * idx)
            val completo = if (ctx >= 0 && ctx < total && ctx != idx) {
                val c = nombreDe(ctx)
                if (c != nom) "$nom, $c" else nom
            } else nom
            if (!vistos.add(completo)) continue
            out.add(
                Resultado(
                    completo,
                    bb.getInt(posLat + 4 * idx) / 1e5,
                    bb.getInt(posLon + 4 * idx) / 1e5,
                    bytes[posTipo + idx].toInt(),
                    "", categoriaDe(idx), "sin internet"
                )
            )
        }
        return out
    }

    companion object {
        /** Carga un índice ya construido. Devuelve null si el archivo no es válido. */
        fun cargar(f: File): IndiceLugares? {
            val b = f.readBytes()
            if (b.size < 24 || String(b, 0, 4, Charsets.US_ASCII) != "L593") return null
            if (ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(4) != 2) return null   // versión anterior: se reconstruye
            return IndiceLugares(b)
        }

        /** Construye `destino` leyendo los lugares del .g593. Devuelve cuántos lugares indexó. */
        fun construir(g593: File, destino: File): Int {
            val longitud = g593.length()
            var jl = 0L
            RandomAccessFile(g593, "r").use { raf ->
                val h = ByteArray(28)
                raf.readFully(h)
                if (String(h, 0, 4, Charsets.US_ASCII) != "G593") throw IOException("no es un archivo .g593")
                jl = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt(24).toLong() and 0xFFFFFFFFL
            }
            if (jl <= 0L || jl > longitud) throw IOException("archivo .g593 dañado")

            val lat = IntVec()
            val lon = IntVec()
            val ctx = IntVec()
            val tipo = IntVec()
            val dispOff = IntVec()
            val normOff = IntVec()
            val disp = ByteArrayOutputStream(1 shl 20)
            val norm = ByteArrayOutputStream(1 shl 20)
            val nOff = IntVec()
            val nb = ByteArrayOutputStream(1 shl 20)
            val subOff = IntVec()
            val sb = ByteArrayOutputStream(1 shl 16)

            FileInputStream(g593).use { fis ->
                fis.channel.position(longitud - jl)
                JsonReader(InputStreamReader(BufferedInputStream(fis, 1 shl 16), Charsets.UTF_8)).use { r ->
                    r.beginObject()
                    while (r.hasNext()) {
                        val clave = r.nextName()
                        if (clave == "names") {
                            r.beginArray()
                            while (r.hasNext()) {
                                nOff.add(nb.size())
                                if (r.peek() == JsonToken.NULL) {
                                    r.nextNull()
                                } else {
                                    nb.write(r.nextString().toByteArray(Charsets.UTF_8))
                                }
                            }
                            r.endArray()
                        } else if (clave == "places") {
                            r.beginArray()
                            while (r.hasNext()) {
                                r.beginArray()
                                val nombre = r.nextString()
                                val la = r.nextDouble()
                                val lo = r.nextDouble()
                                val ti = r.nextInt()
                                val cx = r.nextInt()
                                var sub = ""
                                if (r.hasNext()) {
                                    if (r.peek() == JsonToken.NULL) r.nextNull() else sub = r.nextString()
                                }
                                r.endArray()
                                dispOff.add(disp.size())
                                disp.write(nombre.toByteArray(Charsets.UTF_8))
                                normOff.add(norm.size())
                                norm.write((Texto.norm(nombre.replace('|', ' ')) + "|" + Texto.norm(sub)).toByteArray(Charsets.UTF_8))
                                subOff.add(sb.size())
                                sb.write(sub.toByteArray(Charsets.UTF_8))
                                lat.add(Math.round(la * 1e5).toInt())
                                lon.add(Math.round(lo * 1e5).toInt())
                                tipo.add(ti)
                                ctx.add(cx)
                            }
                            r.endArray()
                        } else {
                            r.skipValue()          // la lista "names" no hace falta aquí
                        }
                    }
                    r.endObject()
                }
            }
            val n = lat.n
            dispOff.add(disp.size())
            normOff.add(norm.size())
            subOff.add(sb.size())
            val dBytes = disp.toByteArray()
            val nBytes = norm.toByteArray()
            val sBytes = sb.toByteArray()

            val tam = 24 + 4 * n * 3 + 4 * (n + 1) * 3 + n + dBytes.size + nBytes.size + sBytes.size
            val out = ByteBuffer.allocate(tam).order(ByteOrder.LITTLE_ENDIAN)
            out.put("L593".toByteArray(Charsets.US_ASCII))
            out.putInt(2)
            out.putInt(n)
            out.putInt(dBytes.size)
            out.putInt(nBytes.size)
            out.putInt(sBytes.size)
            for (i in 0 until n) out.putInt(lat.a[i])
            for (i in 0 until n) out.putInt(lon.a[i])
            for (i in 0 until n) out.putInt(ctx.a[i])
            for (i in 0..n) out.putInt(dispOff.a[i])
            for (i in 0..n) out.putInt(normOff.a[i])
            for (i in 0..n) out.putInt(subOff.a[i])
            for (i in 0 until n) out.put(tipo.a[i].toByte())
            out.put(dBytes)
            out.put(nBytes)
            out.put(sBytes)

            // Nombres de calles (los usa el motor de rutas para las instrucciones)
            nOff.add(nb.size())
            val cn = nOff.n - 1
            val cBytes = nb.toByteArray()
            val cOut = ByteBuffer.allocate(8 + 4 * (cn + 1) + cBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            cOut.put("N593".toByteArray(Charsets.US_ASCII))
            cOut.putInt(cn)
            for (i in 0..cn) cOut.putInt(nOff.a[i])
            cOut.put(cBytes)
            val cDestino = File(destino.parentFile, "calles.idx")
            val cTmp = File(cDestino.path + ".tmp")
            FileOutputStream(cTmp).use { it.write(cOut.array(), 0, cOut.capacity()) }
            if (cDestino.exists()) cDestino.delete()
            if (!cTmp.renameTo(cDestino)) throw IOException("no pude guardar los nombres de calles")

            val tmp = File(destino.path + ".tmp")
            FileOutputStream(tmp).use { it.write(out.array(), 0, tam) }
            if (destino.exists()) destino.delete()
            if (!tmp.renameTo(destino)) throw IOException("no pude guardar el índice")
            return n
        }
    }
}

/** Nombres de calles del .g593 (archivo calles.idx), consultables por número. */
class Calles private constructor(private val bytes: ByteArray) {
    private val bb: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val n: Int = bb.getInt(4)
    private val base = 8 + 4 * (n + 1)

    fun nombre(i: Int): String {
        if (i < 0 || i >= n) return ""
        val o0 = bb.getInt(8 + 4 * i)
        val o1 = bb.getInt(8 + 4 * (i + 1))
        return String(bytes, base + o0, o1 - o0, Charsets.UTF_8)
    }

    companion object {
        fun cargar(f: File): Calles? {
            if (!f.exists()) return null
            val b = f.readBytes()
            if (b.size < 8 || String(b, 0, 4, Charsets.US_ASCII) != "N593") return null
            return Calles(b)
        }
    }
}

/**
 * Búsqueda en línea de respaldo (Photon, datos de OpenStreetMap), solo si no hay índice sin internet.
 * Es un servicio público pensado para demostraciones: no sirve para publicar la app a gran escala.
 */
object BusquedaEnLinea {
    fun buscar(q: String): List<Resultado> {
        val url = "https://photon.komoot.io/api/?q=" + URLEncoder.encode(q, "UTF-8") +
            "&limit=8&lat=-1.8&lon=-78.4&bbox=-92.2,-5.1,-75.0,1.7"
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 5000
            con.readTimeout = 6000
            con.setRequestProperty("User-Agent", "GPS593-nativo/0.5 (prototipo)")
            if (con.responseCode != 200) return emptyList()
            val texto = con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val feats = JSONObject(texto).optJSONArray("features") ?: return emptyList()
            val out = ArrayList<Resultado>()
            for (i in 0 until feats.length()) {
                val f = feats.getJSONObject(i)
                val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
                val p = f.optJSONObject("properties") ?: continue
                val partes = ArrayList<String>()
                for (k in arrayOf("name", "street", "city", "county", "state")) {
                    val v = p.optString(k, "")
                    if (v.isNotEmpty() && !partes.contains(v)) partes.add(v)
                }
                if (partes.isEmpty()) continue
                out.add(Resultado(partes.joinToString(", "), coords.getDouble(1), coords.getDouble(0), 6, "", "", "en línea"))
            }
            return out
        } finally {
            con.disconnect()
        }
    }
}
