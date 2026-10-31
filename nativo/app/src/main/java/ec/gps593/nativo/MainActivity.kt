package ec.gps593.nativo

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Prototipo GPS 593 nativo: solo mapa (ecuador.pmtiles) + posición GPS + panel de diagnóstico.
 * Sin AppCompat ni Compose: una sola Activity y vistas creadas por código, para arrancar rápido.
 */
class MainActivity : Activity(), LocationListener {

    private val ui = Handler(Looper.getMainLooper())

    // Tiempos (ms desde que Android creó el proceso de la app)
    private val tProc: Long =
        if (Build.VERSION.SDK_INT >= 24) android.os.Process.getStartElapsedRealtime()
        else SystemClock.elapsedRealtime()
    private fun ms(): Long = SystemClock.elapsedRealtime() - tProc
    private var tCreate = 0L
    private var tMapReady = 0L
    private var tStyle = 0L
    private var tIdle = 0L

    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var estilo: Style? = null

    private var mapPath: String? = null
    private var buscando = true
    private var night = false
    private var follow = true
    private var primerFix = false
    private var lastLoc: Location? = null
    private var gpsActivo = false

    private val frames = AtomicInteger(0)
    @Volatile private var fps = 0
    @Volatile private var copyStatus = ""
    @Volatile private var copiando = false
    private var errorMapa = ""

    private lateinit var diag: TextView
    private lateinit var aviso: TextView
    private lateinit var btnCopiar: Button
    private lateinit var barra: LinearLayout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ───────────────────────── ciclo de vida ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tCreate = ms()
        night = getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("night", false)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#e8e8e8"))
        setContentView(root)

        try {
            MapLibre.getInstance(this)
            val mv = MapView(this)
            mv.onCreate(savedInstanceState)
            root.addView(
                mv,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            mapView = mv
            mv.addOnDidFinishLoadingStyleListener { if (tStyle == 0L) tStyle = ms() }
            mv.addOnDidBecomeIdleListener { if (tIdle == 0L && tStyle > 0L) tIdle = ms() }
            mv.addOnWillStartRenderingFrameListener { frames.incrementAndGet() }
            mv.addOnDidFailLoadingMapListener { msg -> errorMapa = msg ?: "error desconocido" }
            mv.getMapAsync { m -> alEstarListoElMapa(m) }
        } catch (t: Throwable) {
            // Por ejemplo: falta la librería nativa para la arquitectura de la radio
            errorMapa = "No se pudo iniciar el motor de mapas: $t"
        }

        crearPanel(root)
        pedirPermisos()
    }

    override fun onStart() { super.onStart(); mapView?.onStart() }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        iniciarGps()
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        detenerGps()
        mapView?.onPause()
        super.onPause()
    }

    override fun onStop() { mapView?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onDestroy() { mapView?.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }

    // ───────────────────────── interfaz ─────────────────────────

    private fun boton(texto: String, accion: () -> Unit): Button = Button(this).apply {
        text = texto
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        minHeight = dp(56)
        minimumHeight = dp(56)
        setOnClickListener { accion() }
    }

    private fun crearPanel(root: FrameLayout) {
        diag = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B0000000"))
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        root.addView(diag, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START
        ))

        aviso = TextView(this).apply {
            setTextColor(Color.parseColor("#222222"))
            setBackgroundColor(Color.parseColor("#F2FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            visibility = android.view.View.GONE
        }
        root.addView(aviso, FrameLayout.LayoutParams(
            dp(520), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))

        btnCopiar = boton("Copiar mapa al interno") { copiarAlInterno() }
        btnCopiar.visibility = android.view.View.GONE

        barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(btnCopiar)
            addView(boton("＋") { map?.animateCamera(CameraUpdateFactory.zoomIn()) })
            addView(boton("－") { map?.animateCamera(CameraUpdateFactory.zoomOut()) })
            addView(boton("Centrar") { centrar() })
            addView(boton("Día/Noche") { alternarNoche() })
            addView(boton("Diag") {
                diag.visibility = if (diag.visibility == android.view.View.VISIBLE) android.view.View.GONE
                else android.view.View.VISIBLE
            })
        }
        root.addView(barra, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END
        ))
    }

    private fun mostrarAviso(texto: String?) {
        if (texto == null) { aviso.visibility = android.view.View.GONE; return }
        aviso.text = texto
        aviso.visibility = android.view.View.VISIBLE
    }

    // ───────────────────────── permisos ─────────────────────────

    private fun pedirPermisos() {
        val faltan = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 23) {
            for (p in arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_EXTERNAL_STORAGE)) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) faltan.add(p)
            }
        }
        if (faltan.isEmpty()) iniciar() else requestPermissions(faltan.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        iniciar()
    }

    private fun iniciar() {
        buscarMapa()
        iniciarGps()
    }

    // ───────────────────────── mapa ─────────────────────────

    private fun alEstarListoElMapa(m: MapLibreMap) {
        map = m
        tMapReady = ms()
        m.setMinZoomPreference(5.0)
        m.setMaxZoomPreference(18.0)
        m.uiSettings.setRotateGesturesEnabled(false)
        m.uiSettings.setTiltGesturesEnabled(false)
        m.uiSettings.setCompassEnabled(false)
        m.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false
        }
        // Vista inicial: todo Ecuador
        val ll = lastLoc
        if (ll == null) {
            m.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                .target(LatLng(-1.65, -78.4)).zoom(6.2).build()
        }
        cargarEstilo()
    }

    /** Busca un .pmtiles: primero en la carpeta de la app (interno), luego en pendrives/tarjetas. */
    private fun localizarMapa(): String? {
        for (d in listOfNotNull(getExternalFilesDir(null), filesDir)) {
            val f = d.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".pmtiles", true) }
            if (f != null) return f.absolutePath
        }
        val saltar = setOf(
            "Android", "runtime", "user", "shell", "obb", "asec", "secure", "expand", "pass_through",
            "installer", "androidwritable", "LOST.DIR", "proc", "sys", "dev"
        )
        val cola = ArrayDeque<Pair<File, Int>>()
        for (r in arrayOf("/storage", "/mnt/media_rw", "/mnt")) cola.add(Pair(File(r), 0))
        val visto = HashSet<String>()
        var mejor: File? = null
        var visitados = 0
        while (cola.isNotEmpty() && visitados < 4000) {
            val (dir, nivel) = cola.removeFirst()
            val canon = try { dir.canonicalPath } catch (e: Exception) { continue }
            if (!visto.add(canon)) continue
            val lista = dir.listFiles() ?: continue
            visitados++
            for (f in lista) {
                if (f.isDirectory) {
                    if (nivel < 4 && f.name !in saltar) cola.add(Pair(f, nivel + 1))
                } else if (f.name.endsWith(".pmtiles", true)) {
                    val actual = mejor
                    if (actual == null || f.length() > actual.length()) mejor = f
                }
            }
        }
        return mejor?.absolutePath
    }

    private fun buscarMapa() {
        if (mapPath != null) return
        buscando = true
        Thread {
            val ruta = try { localizarMapa() } catch (e: Exception) { null }
            ui.post {
                buscando = false
                mapPath = ruta
                if (ruta == null) {
                    mostrarAviso(
                        "No encuentro el mapa (.pmtiles).\n\n" +
                            "Copia ecuador.pmtiles a la raíz del pendrive, conéctalo y vuelve a abrir la app."
                    )
                } else {
                    mostrarAviso(null)
                    cargarEstilo()
                }
                refrescarBotones()
            }
        }.start()
    }

    private fun cargarEstilo() {
        val m = map ?: return
        val ruta = mapPath ?: return
        val json = try {
            assets.open(if (night) "style-night.json" else "style-day.json")
                .bufferedReader().use { it.readText() }
                .replace("__PMTILES__", ruta.replace("\\", "\\\\").replace("\"", "\\\""))
        } catch (e: Exception) {
            errorMapa = "No pude leer el estilo: ${e.message}"
            return
        }
        m.setStyle(Style.Builder().fromJson(json)) { style ->
            estilo = style
            ponerPosicion(lastLoc)
        }
    }

    private fun alternarNoche() {
        night = !night
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putBoolean("night", night).apply()
        cargarEstilo()
    }

    private fun centrar() {
        follow = true
        val l = lastLoc ?: return
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(l.latitude, l.longitude), 15.0))
    }

    private fun esInterno(ruta: String): Boolean =
        ruta.startsWith(filesDir.absolutePath) ||
            (getExternalFilesDir(null)?.let { ruta.startsWith(it.absolutePath) } ?: false)

    private fun refrescarBotones() {
        val r = mapPath
        btnCopiar.visibility =
            if (r != null && !esInterno(r) && !copiando) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** Copia el mapa del pendrive al almacenamiento interno (una sola vez) para no depender de la lectura USB. */
    private fun copiarAlInterno() {
        val origen = mapPath?.let { File(it) } ?: return
        if (copiando) return
        val carpeta = getExternalFilesDir(null) ?: filesDir
        val destino = File(carpeta, origen.name.replace(" ", "_"))
        copiando = true
        btnCopiar.visibility = android.view.View.GONE
        Thread {
            try {
                val libre = StatFs(carpeta.path).availableBytes
                if (libre < origen.length() + 50_000_000L) throw RuntimeException("poco espacio libre")
                val tmp = File(carpeta, destino.name + ".tmp")
                val total = origen.length()
                FileInputStream(origen).use { entrada ->
                    FileOutputStream(tmp).use { salida ->
                        val buf = ByteArray(1 shl 20)
                        var hecho = 0L
                        var n = entrada.read(buf)
                        while (n > 0) {
                            salida.write(buf, 0, n)
                            hecho += n
                            copyStatus = "Copiando mapa… ${if (total > 0) hecho * 100 / total else 0} %"
                            n = entrada.read(buf)
                        }
                    }
                }
                if (!tmp.renameTo(destino)) throw RuntimeException("no pude renombrar el archivo")
                ui.post {
                    copyStatus = "Mapa copiado al interno"
                    copiando = false
                    mapPath = destino.absolutePath
                    cargarEstilo()
                    refrescarBotones()
                }
            } catch (e: Exception) {
                ui.post {
                    copyStatus = "Error al copiar: ${e.message}"
                    copiando = false
                    refrescarBotones()
                }
            }
        }.start()
    }

    // ───────────────────────── GPS ─────────────────────────

    private fun tienePermisoGps(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun iniciarGps() {
        if (gpsActivo || !tienePermisoGps()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
                gpsActivo = true
            }
            if (lastLoc == null) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
            }
        } catch (e: SecurityException) {
            gpsActivo = false
        }
    }

    private fun detenerGps() {
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        } catch (e: Exception) { /* nada */ }
        gpsActivo = false
    }

    private fun puntoJson(l: Location): String =
        "{\"type\":\"Point\",\"coordinates\":[${l.longitude},${l.latitude}]}"

    private fun ponerPosicion(l: Location?) {
        if (l == null) return
        estilo?.getSourceAs<GeoJsonSource>("yo")?.setGeoJson(puntoJson(l))
    }

    override fun onLocationChanged(location: Location) {
        lastLoc = location
        ponerPosicion(location)
        val m = map ?: return
        if (!follow) return
        val ll = LatLng(location.latitude, location.longitude)
        if (!primerFix) {
            primerFix = true
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(ll, 15.0))
        } else {
            m.animateCamera(CameraUpdateFactory.newLatLng(ll), 900)
        }
    }

    // Se declaran todos para que funcione también en Android antiguos (métodos abstractos antes de API 30)
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) { iniciarGps() }
    override fun onProviderDisabled(provider: String) { gpsActivo = false }

    // ───────────────────────── diagnóstico (cada segundo) ─────────────────────────

    private val tick = object : Runnable {
        override fun run() {
            fps = frames.getAndSet(0)
            actualizarDiagnostico()
            ui.postDelayed(this, 1000L)
        }
    }

    private fun t(v: Long): String = if (v > 0L) "$v ms" else "…"

    private fun actualizarDiagnostico() {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        val rt = Runtime.getRuntime()
        val javaMb = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val nativaMb = Debug.getNativeHeapAllocatedSize() / 1048576
        val sis = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(sis)

        val ruta = mapPath
        val lineaMapa = if (ruta == null) {
            if (buscando) "Mapa: buscando…" else "Mapa: NO encontrado"
        } else {
            val f = File(ruta)
            val donde = if (esInterno(ruta)) "interno" else "pendrive/externo"
            "Mapa: ${f.name} (${f.length() / 1048576} MB, $donde)"
        }
        val l = lastLoc
        val lineaGps = when {
            !tienePermisoGps() -> "GPS: sin permiso"
            l == null -> if (gpsActivo) "GPS: buscando señal…" else "GPS: apagado o sin señal"
            else -> String.format(
                java.util.Locale.US, "GPS: %.5f, %.5f  ±%.0f m  %.0f km/h  (%s)",
                l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 0f,
                if (l.hasSpeed()) l.speed * 3.6f else 0f, l.provider ?: "?"
            )
        }
        val sb = StringBuilder()
        sb.append("GPS 593 nativo ${BuildConfigInfo.VERSION}  |  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})  |  ")
            .append(Build.SUPPORTED_ABIS.firstOrNull() ?: "?").append('\n')
        sb.append(lineaMapa).append('\n')
        sb.append("Desde el inicio: pantalla ${t(tCreate)} · motor ${t(tMapReady)} · estilo ${t(tStyle)} · mapa completo ${t(tIdle)}\n")
        sb.append("FPS: $fps   |   Memoria de la app: ${mi.totalPss / 1024} MB (java $javaMb, nativa $nativaMb)\n")
        sb.append("RAM libre del sistema: ${sis.availMem / 1048576} MB de ${sis.totalMem / 1048576} MB\n")
        sb.append(lineaGps)
        if (copyStatus.isNotEmpty()) sb.append('\n').append(copyStatus)
        if (errorMapa.isNotEmpty()) sb.append("\nERROR: ").append(errorMapa)
        diag.text = sb.toString()
    }
}

/** Versión mostrada en el panel (coincide con versionName de build.gradle.kts). */
private object BuildConfigInfo {
    const val VERSION = "0.1"
}
