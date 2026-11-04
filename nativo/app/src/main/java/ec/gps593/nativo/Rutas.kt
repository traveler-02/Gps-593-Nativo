package ec.gps593.nativo

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.channels.FileChannel
import java.util.Locale

/** Geometría básica (metros y rumbos sobre el plano local). */
object Geo {
    private const val RAD = Math.PI / 180.0

    fun dm(la1: Double, lo1: Double, la2: Double, lo2: Double): Double =
        Math.hypot((la2 - la1) * 111320.0, (lo2 - lo1) * 111320.0 * Math.cos((la1 + la2) * RAD / 2.0))

    /** Rumbo en grados (0 = norte, 90 = este). */
    fun rumbo(la1: Double, lo1: Double, la2: Double, lo2: Double): Double =
        (Math.atan2((lo2 - lo1) * Math.cos(la1 * RAD), la2 - la1) / RAD + 360.0) % 360.0

    fun dist(m: Double): String =
        if (m >= 1000.0) String.format(Locale("es"), if (m >= 10000.0) "%.0f km" else "%.1f km", m / 1000.0)
        else "${Math.round(m / 10.0) * 10} m"
}

/** Una maniobra de la ruta ("gira a la derecha hacia…"). `inicio` = metros desde el origen donde ocurre. */
class Paso(
    val tipo: String, val mod: String, val nombre: String, val distancia: Double,
    val lat: Double, val lon: Double
) {
    var inicio = 0.0

    private fun via(): String = if (nombre.isNotEmpty()) " hacia $nombre" else ""

    private fun dir(): String = when (mod) {
        "left" -> " a la izquierda"
        "right" -> " a la derecha"
        "straight" -> " recto"
        "slight left" -> " ligeramente a la izquierda"
        "slight right" -> " ligeramente a la derecha"
        "sharp left" -> " cerrado a la izquierda"
        "sharp right" -> " cerrado a la derecha"
        else -> ""
    }

    fun texto(): String = when (tipo) {
        "depart" -> "Inicie la ruta" + (if (nombre.isNotEmpty()) " por $nombre" else "")
        "arrive" -> "Ha llegado a su destino"
        "new name", "continue" ->
            "Continúe" + (if (mod.isNotEmpty() && mod != "straight") dir() else " recto") +
                (if (nombre.isNotEmpty()) " por $nombre" else "")
        else ->
            if (mod == "uturn") "Haga un giro en U" + via()
            else "Gire" + (if (dir().isNotEmpty()) dir() else " recto") + via()
    }

    fun icono(): Int = when (tipo) {
        "arrive" -> R.drawable.ic_flag
        "depart" -> R.drawable.ic_straight
        else -> when (mod) {
            "left" -> R.drawable.ic_turn_left
            "right" -> R.drawable.ic_turn_right
            "slight left" -> R.drawable.ic_turn_slight_left
            "slight right" -> R.drawable.ic_turn_slight_right
            "sharp left" -> R.drawable.ic_turn_sharp_left
            "sharp right" -> R.drawable.ic_turn_sharp_right
            "uturn" -> R.drawable.ic_u_turn_left
            else -> R.drawable.ic_straight
        }
    }
}

/** Posición sobre la ruta: distancia a la línea (m), tramo y metros recorridos. */
class Ubic(val d: Double, val i: Int, val along: Double)

/** Una ruta calculada: línea, distancia, duración y maniobras. */
class Ruta(
    val lat: DoubleArray, val lon: DoubleArray, val distancia: Double, val duracion: Double,
    val pasos: List<Paso>
) {
    val acum = DoubleArray(lat.size)
    val total: Double

    init {
        for (i in 1 until lat.size) acum[i] = acum[i - 1] + Geo.dm(lat[i - 1], lon[i - 1], lat[i], lon[i])
        total = if (lat.isNotEmpty()) acum[lat.size - 1] else 0.0
        val k = if (distancia > 0.0) total / distancia else 1.0
        var a = 0.0
        for (p in pasos) {
            p.inicio = a * k
            a += p.distancia
        }
    }

    /** Punto más cercano de la ruta a (la, lo), buscando solo entre los tramos desde..hasta. */
    fun ubicar(la: Double, lo: Double, desde: Int, hasta: Int): Ubic {
        if (lat.size < 2) return Ubic(0.0, 0, 0.0)
        val cosL = Math.cos(la * Math.PI / 180.0)
        var bd = Double.MAX_VALUE
        var bi = 0
        var bt = 0.0
        var i = Math.max(0, desde)
        val fin = Math.min(lat.size - 2, hasta)
        while (i <= fin) {
            val ax = (lon[i] - lo) * cosL
            val ay = lat[i] - la
            val dx = (lon[i + 1] - lo) * cosL - ax
            val dy = (lat[i + 1] - la) - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 > 0.0) Math.max(0.0, Math.min(1.0, -(ax * dx + ay * dy) / l2)) else 0.0
            val d = Math.hypot(ax + t * dx, ay + t * dy) * 111320.0
            if (d < bd) {
                bd = d
                bi = i
                bt = t
            }
            i++
        }
        return Ubic(bd, bi, acum[bi] + bt * (acum[bi + 1] - acum[bi]))
    }

    /** Posición y rumbo a d metros del inicio (para simular el recorrido). */
    fun puntoEn(d0: Double): DoubleArray {
        val d = Math.min(d0, total)
        var i = 0
        while (i < acum.size - 2 && acum[i + 1] < d) i++
        val seg = acum[i + 1] - acum[i]
        val t = (d - acum[i]) / (if (seg > 0.0) seg else 1.0)
        return doubleArrayOf(
            lat[i] + (lat[i + 1] - lat[i]) * t,
            lon[i] + (lon[i + 1] - lon[i]) * t,
            Geo.rumbo(lat[i], lon[i], lat[i + 1], lon[i + 1])
        )
    }

    fun geojson(): String {
        val sb = StringBuilder(lat.size * 24 + 64)
        sb.append("{\"type\":\"LineString\",\"coordinates\":[")
        for (i in lat.indices) {
            if (i > 0) sb.append(',')
            sb.append('[').append(lon[i]).append(',').append(lat[i]).append(']')
        }
        sb.append("]}")
        return sb.toString()
    }
}

private class Ent(val s: Int, val dir: Int, val f0: Double, val f1: Double)
private class Target(val extra: Double, val e: Ent)
private class Pieza(val pts: List<DoubleArray>, val nombre: String, val len: Double, val time: Double, val n: Int)
private class Snap(val s: Int, val d: Double, val u: Int, val t: Double) {
    var frac = 0.0
}
private class Res(val bestN: Int, val prev: IntArray)

private class Heap {
    var f = DoubleArray(4096)
    var n = IntArray(4096)
    var size = 0

    fun top(): Double = f[0]

    fun push(a: Double, b: Int) {
        if (size == f.size) {
            f = f.copyOf(size * 2)
            n = n.copyOf(size * 2)
        }
        var i = size++
        while (i > 0) {
            val p = (i - 1) shr 1
            if (f[p] <= a) break
            f[i] = f[p]
            n[i] = n[p]
            i = p
        }
        f[i] = a
        n[i] = b
    }

    fun pop(): Int {
        val rn = n[0]
        size--
        if (size > 0) {
            val lf = f[size]
            val ln = n[size]
            var i = 0
            while (true) {
                var c = 2 * i + 1
                if (c >= size) break
                if (c + 1 < size && f[c + 1] < f[c]) c++
                if (f[c] >= lf) break
                f[i] = f[c]
                n[i] = n[c]
                i = c
            }
            f[i] = lf
            n[i] = ln
        }
        return rn
    }
}

/**
 * Motor de rutas sin internet. Lee el archivo .g593 (ver tools/build_graph.py) directamente del disco
 * (mapeado en memoria: no se copia al heap) y calcula rutas con A*.
 * Es un port a Kotlin de offline.js de la versión web.
 */
class GrafoRutas private constructor(private val bb: ByteBuffer, private val calles: Calles?) {
    private val nN: Int = bb.getInt(8)
    private val nS: Int = bb.getInt(12)
    private val nP: Int = bb.getInt(16)
    private val nA: Int = bb.getInt(20)

    private val nlat: IntBuffer
    private val nlon: IntBuffer
    private val adjS: IntBuffer
    private val adj: IntBuffer
    private val sFrom: IntBuffer
    private val sTo: IntBuffer
    private val sTime: FloatBuffer
    private val sDist: FloatBuffer
    private val sName: IntBuffer
    private val gOff: IntBuffer
    private val pts: IntBuffer
    private val posFlags: Int

    private var minLat = 0
    private var minLon = 0
    private var ancho = 1
    private var alto = 1
    private var cs = IntArray(0)
    private var ci = IntArray(0)

    private fun ints(o: Int, n: Int): IntBuffer {
        val d = bb.duplicate()
        d.order(ByteOrder.LITTLE_ENDIAN)
        d.position(o)
        d.limit(o + 4 * n)
        return d.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
    }

    private fun floats(o: Int, n: Int): FloatBuffer {
        val d = bb.duplicate()
        d.order(ByteOrder.LITTLE_ENDIAN)
        d.position(o)
        d.limit(o + 4 * n)
        return d.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    }

    init {
        var o = 28
        nlat = ints(o, nN); o += 4 * nN
        nlon = ints(o, nN); o += 4 * nN
        adjS = ints(o, nN + 1); o += 4 * (nN + 1)
        adj = ints(o, nA); o += 4 * nA
        sFrom = ints(o, nS); o += 4 * nS
        sTo = ints(o, nS); o += 4 * nS
        sTime = floats(o, nS); o += 4 * nS
        sDist = floats(o, nS); o += 4 * nS
        sName = ints(o, nS); o += 4 * nS
        gOff = ints(o, nS + 1); o += 4 * (nS + 1)
        pts = ints(o, 2 * nP); o += 8 * nP
        posFlags = o
    }

    private fun flags(s: Int): Int = bb.get(posFlags + s).toInt() and 0xFF
    private fun plat(i: Int): Double = pts.get(2 * i) * 1e-6
    private fun plon(i: Int): Double = pts.get(2 * i + 1) * 1e-6

    /** Cuadrícula de puntos para ubicar rápido la calle más cercana. */
    private fun construirRejilla() {
        if (nP <= 0) throw IOException("el archivo .g593 no tiene calles")
        var a = Int.MAX_VALUE
        var b = Int.MIN_VALUE
        var c = Int.MAX_VALUE
        var e = Int.MIN_VALUE
        for (i in 0 until nP) {
            val la = pts.get(2 * i)
            val lo = pts.get(2 * i + 1)
            if (la < a) a = la
            if (la > b) b = la
            if (lo < c) c = lo
            if (lo > e) e = lo
        }
        minLat = a
        minLon = c
        ancho = (e - c) / CS + 1
        alto = (b - a) / CS + 1
        cs = IntArray(ancho * alto + 1)
        for (i in 0 until nP) {
            val k = ((pts.get(2 * i) - a) / CS) * ancho + (pts.get(2 * i + 1) - c) / CS
            cs[k + 1]++
        }
        for (i in 0 until ancho * alto) cs[i + 1] += cs[i]
        ci = IntArray(nP)
        val fill = cs.copyOf()
        for (i in 0 until nP) {
            val k = ((pts.get(2 * i) - a) / CS) * ancho + (pts.get(2 * i + 1) - c) / CS
            ci[fill[k]++] = i
        }
    }

    private fun segOf(i: Int): Int {
        var lo = 0
        var hi = nS
        while (hi - lo > 1) {
            val m = (lo + hi) ushr 1
            if (gOff.get(m) <= i) lo = m else hi = m
        }
        return lo
    }

    /** Calle más cercana a (lat, lon). */
    private fun snap(lat: Double, lon: Double): Snap? {
        val la = lat * 1e6
        val lo = lon * 1e6
        val cy = Math.floor((la - minLat) / CS).toInt()
        val cx = Math.floor((lo - minLon) / CS).toInt()
        val c = Math.cos(lat * Math.PI / 180.0) * 111320.0
        var best: Snap? = null
        var found = -1
        for (r in 0..12) {
            if (found >= 0 && r > found + 1) break
            for (y in cy - r..cy + r) {
                for (x in cx - r..cx + r) {
                    if (Math.max(Math.abs(y - cy), Math.abs(x - cx)) != r || y < 0 || x < 0 || y >= alto || x >= ancho) continue
                    val k = y * ancho + x
                    for (q in cs[k] until cs[k + 1]) {
                        val i = ci[q]
                        val s = segOf(i)
                        val g0 = gOff.get(s)
                        val g1 = gOff.get(s + 1) - 1
                        for (par in 0..1) {
                            val u = if (par == 0) i - 1 else i
                            val v = if (par == 0) i else i + 1
                            if (u < g0 || v > g1) continue
                            val ax = (plon(u) - lon) * c
                            val ay = (plat(u) - lat) * 111320.0
                            val bx = (plon(v) - lon) * c
                            val by = (plat(v) - lat) * 111320.0
                            val dx = bx - ax
                            val dy = by - ay
                            val l2 = dx * dx + dy * dy
                            val t = if (l2 > 0.0) Math.max(0.0, Math.min(1.0, -(ax * dx + ay * dy) / l2)) else 0.0
                            val dist = Math.hypot(ax + t * dx, ay + t * dy)
                            val actual = best
                            if (actual == null || dist < actual.d) best = Snap(s, dist, u, t)
                        }
                    }
                }
            }
            if (best != null && found < 0) found = r
        }
        val b = best ?: return null
        var antes = 0.0
        var total = 0.0
        for (i in gOff.get(b.s) until gOff.get(b.s + 1) - 1) {
            val l = Geo.dm(plat(i), plon(i), plat(i + 1), plon(i + 1))
            if (i < b.u) antes += l else if (i == b.u) antes += l * b.t
            total += l
        }
        b.frac = if (total > 0.0) antes / total else 0.0
        return b
    }

    private fun astar(starts: List<DoubleArray>, targets: HashMap<Int, Target>, gLat: Double, gLon: Double): Res? {
        val gS = DoubleArray(nN)
        gS.fill(Double.POSITIVE_INFINITY)
        val prev = IntArray(nN)
        prev.fill(-1)
        val heap = Heap()
        fun h(n: Int): Double = Geo.dm(nlat.get(n) * 1e-6, nlon.get(n) * 1e-6, gLat, gLon) / VMAX
        for (st in starts) {
            val n = st[0].toInt()
            val g = st[1]
            if (g < gS[n]) {
                gS[n] = g
                prev[n] = -2
                heap.push(g + h(n), n)
            }
        }
        var best = Double.POSITIVE_INFINITY
        var bestN = -1
        while (heap.size > 0) {
            val f = heap.top()
            if (f >= best) break
            val n = heap.pop()
            val g0 = gS[n]
            if (f - h(n) > g0 + 1e-6) continue
            val tg = targets[n]
            if (tg != null && g0 + tg.extra < best) {
                best = g0 + tg.extra
                bestN = n
            }
            for (k in adjS.get(n) until adjS.get(n + 1)) {
                val e = adj.get(k)
                if ((e and 1) == 0) continue
                val s = e shr 2
                val dir = (e shr 1) and 1
                val m = if (dir == 1) sFrom.get(s) else sTo.get(s)
                val ng = g0 + sTime.get(s)
                if (ng < gS[m]) {
                    gS[m] = ng
                    prev[m] = s * 2 + dir
                    heap.push(ng + h(m), m)
                }
            }
        }
        return if (bestN < 0) null else Res(bestN, prev)
    }

    private fun segGeom(s: Int, invertir: Boolean): ArrayList<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for (i in gOff.get(s) until gOff.get(s + 1)) out.add(doubleArrayOf(plat(i), plon(i)))
        if (invertir) out.reverse()
        return out
    }

    /** Tramo de la polilínea entre las fracciones f0..f1 de su longitud. */
    private fun cortar(p: List<DoubleArray>, f0: Double, f1: Double): ArrayList<DoubleArray> {
        if (p.size < 2) return ArrayList(p)
        val cum = DoubleArray(p.size)
        for (i in 1 until p.size) cum[i] = cum[i - 1] + Geo.dm(p[i - 1][0], p[i - 1][1], p[i][0], p[i][1])
        val largo = if (cum[p.size - 1] > 0.0) cum[p.size - 1] else 1.0
        fun en(t: Double): DoubleArray {
            var i = 1
            while (i < p.size - 1 && cum[i] < t) i++
            val den = cum[i] - cum[i - 1]
            val u = (t - cum[i - 1]) / (if (den > 0.0) den else 1.0)
            return doubleArrayOf(
                p[i - 1][0] + (p[i][0] - p[i - 1][0]) * u,
                p[i - 1][1] + (p[i][1] - p[i - 1][1]) * u
            )
        }
        val t0 = f0 * largo
        val t1 = f1 * largo
        val out = ArrayList<DoubleArray>()
        out.add(en(t0))
        for (i in 1 until p.size - 1) if (cum[i] > t0 && cum[i] < t1) out.add(p[i])
        out.add(en(t1))
        return out
    }

    /** Giro entre el final de P y el inicio de Q (+ = derecha). */
    private fun giro(p: List<DoubleArray>, q: List<DoubleArray>): Double {
        val e = p[p.size - 1]
        var a = p[0]
        for (i in p.size - 2 downTo 0) {
            if (Geo.dm(p[i][0], p[i][1], e[0], e[1]) >= 12.0) {
                a = p[i]
                break
            }
        }
        val s = q[0]
        var b = q[q.size - 1]
        for (i in 1 until q.size) {
            if (Geo.dm(s[0], s[1], q[i][0], q[i][1]) >= 12.0) {
                b = q[i]
                break
            }
        }
        return ((Geo.rumbo(s[0], s[1], b[0], b[1]) - Geo.rumbo(a[0], a[1], e[0], e[1]) + 540.0) % 360.0) - 180.0
    }

    private fun modificador(a: Double): String {
        val x = Math.abs(a)
        val lado = if (a > 0.0) "right" else "left"
        return if (x > 165.0) "uturn"
        else if (x < 20.0) "straight"
        else if (x < 60.0) "slight $lado"
        else if (x < 120.0) lado
        else "sharp $lado"
    }

    private fun pieza(s: Int, dir: Int, f0: Double, f1: Double, node: Int): Pieza {
        val p = cortar(segGeom(s, dir == 1), f0, f1)
        val k = f1 - f0
        val nombre = calles?.nombre(sName.get(s)) ?: ""
        return Pieza(p, nombre, sDist.get(s).toDouble() * k, sTime.get(s).toDouble() * k, node)
    }

    /** Calcula la ruta más rápida entre dos puntos. Lanza IOException con un mensaje claro si no se puede. */
    fun ruta(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Ruta {
        val a = snap(fromLat, fromLon) ?: throw IOException("Tu posición está fuera de la zona de los datos de rutas.")
        val b = snap(toLat, toLon) ?: throw IOException("El destino está fuera de la zona de los datos de rutas.")
        val fa = flags(a.s)
        val fb = flags(b.s)
        val ta = sTime.get(a.s).toDouble()
        val tb = sTime.get(b.s).toDouble()

        val piezas = ArrayList<Pieza>()
        if (a.s == b.s && (((fa and 1) != 0 && b.frac >= a.frac) || ((fa and 2) != 0 && b.frac <= a.frac))) {
            val fw = (fa and 1) != 0 && b.frac >= a.frac
            piezas.add(if (fw) pieza(a.s, 0, a.frac, b.frac, -1) else pieza(a.s, 1, 1 - a.frac, 1 - b.frac, -1))
        } else {
            val starts = ArrayList<DoubleArray>()
            val startEnt = HashMap<Int, Ent>()
            if ((fa and 1) != 0) {
                starts.add(doubleArrayOf(sTo.get(a.s).toDouble(), ta * (1 - a.frac)))
                startEnt[sTo.get(a.s)] = Ent(a.s, 0, a.frac, 1.0)
            }
            if ((fa and 2) != 0) {
                starts.add(doubleArrayOf(sFrom.get(a.s).toDouble(), ta * a.frac))
                startEnt[sFrom.get(a.s)] = Ent(a.s, 1, 1 - a.frac, 1.0)
            }
            val targets = HashMap<Int, Target>()
            if ((fb and 1) != 0) targets[sFrom.get(b.s)] = Target(tb * b.frac, Ent(b.s, 0, 0.0, b.frac))
            if ((fb and 2) != 0) {
                val x = Target(tb * (1 - b.frac), Ent(b.s, 1, 0.0, 1 - b.frac))
                val old = targets[sTo.get(b.s)]
                if (old == null || x.extra < old.extra) targets[sTo.get(b.s)] = x
            }
            val r = astar(starts, targets, toLat, toLon) ?: throw IOException("No hay ruta disponible entre esos puntos.")
            val mid = ArrayList<IntArray>()
            var n = r.bestN
            while (true) {
                val p = r.prev[n]
                if (p == -2 || p < 0) break
                mid.add(intArrayOf(p shr 1, p and 1, n))
                n = if ((p and 1) != 0) sTo.get(p shr 1) else sFrom.get(p shr 1)
            }
            val s0 = startEnt[n] ?: throw IOException("No pude reconstruir la ruta.")
            val t1 = (targets[r.bestN] ?: throw IOException("No pude reconstruir la ruta.")).e
            piezas.add(pieza(s0.s, s0.dir, s0.f0, s0.f1, -1))
            mid.reverse()
            for (m in mid) {
                val s = m[0]
                val dir = m[1]
                piezas.add(pieza(s, dir, 0.0, 1.0, if (dir == 1) sTo.get(s) else sFrom.get(s)))
            }
            piezas.add(pieza(t1.s, t1.dir, t1.f0, t1.f1, if (t1.dir == 1) sTo.get(t1.s) else sFrom.get(t1.s)))
        }

        // Maniobras: un giro (cruce con 3 o más salidas y ángulo >= 25°) o un cambio de nombre de calle
        val pasos = ArrayList<Paso>()
        var curDist = 0.0
        var curNombre = piezas[0].nombre
        var curTipo = "depart"
        var curMod = "straight"
        var curLat = piezas[0].pts[0][0]
        var curLon = piezas[0].pts[0][1]
        for (i in piezas.indices) {
            val p = piezas[i]
            if (i > 0) {
                val q = piezas[i - 1]
                val grado = if (p.n >= 0) adjS.get(p.n + 1) - adjS.get(p.n) else 0
                val ang = giro(q.pts, p.pts)
                val gira = grado >= 3 && Math.abs(ang) >= 25.0
                val renombra = p.nombre.isNotEmpty() && p.nombre != q.nombre
                if (gira || renombra) {
                    pasos.add(Paso(curTipo, curMod, curNombre, curDist, curLat, curLon))
                    curDist = 0.0
                    curNombre = p.nombre
                    curTipo = if (gira) "turn" else "new name"
                    curMod = if (gira) modificador(ang) else "straight"
                    curLat = p.pts[0][0]
                    curLon = p.pts[0][1]
                }
            }
            curDist += p.len
        }
        pasos.add(Paso(curTipo, curMod, curNombre, curDist, curLat, curLon))
        val ultimos = piezas[piezas.size - 1].pts
        val fin = ultimos[ultimos.size - 1]
        pasos.add(Paso("arrive", "", "", 0.0, fin[0], fin[1]))

        // Línea de la ruta (sin puntos repetidos)
        val cs2 = ArrayList<DoubleArray>()
        for (p in piezas) {
            for (c in p.pts) {
                val l = if (cs2.isEmpty()) null else cs2[cs2.size - 1]
                if (l == null || l[0] != c[0] || l[1] != c[1]) cs2.add(c)
            }
        }
        val la = DoubleArray(cs2.size)
        val lo = DoubleArray(cs2.size)
        for (i in cs2.indices) {
            la[i] = cs2[i][0]
            lo[i] = cs2[i][1]
        }
        var dTot = 0.0
        var tTot = 0.0
        for (p in piezas) {
            dTot += p.len
            tTot += p.time
        }
        return Ruta(la, lo, dTot, tTot, pasos)
    }

    companion object {
        private const val CS = 20000                 // celda de 0,02° (unidades de 1e-6°)
        private const val VMAX = 110.0 / 3.6         // velocidad máxima del heurístico (m/s)

        /** Abre el .g593 sin copiarlo al heap y prepara la cuadrícula de búsqueda de calles. */
        fun abrir(f: File, calles: Calles?): GrafoRutas {
            val raf = RandomAccessFile(f, "r")
            try {
                val ch = raf.channel
                val bb = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
                bb.order(ByteOrder.LITTLE_ENDIAN)
                if (bb.getInt(0) != 0x33393547) throw IOException("no es un archivo .g593")
                val g = GrafoRutas(bb, calles)
                g.construirRejilla()
                return g
            } finally {
                raf.close()
            }
        }
    }
}
