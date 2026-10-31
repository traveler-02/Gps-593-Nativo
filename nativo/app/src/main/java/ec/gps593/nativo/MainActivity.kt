package ec.gps593.nativo

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private const val VERSION = "0.2"

// Tipos de ícono (dibujados con Canvas: sin imágenes ni librerías)
private const val I_PLUS = 0
private const val I_MINUS = 1
private const val I_LOCATE = 2
private const val I_MOON = 3
private const val I_SUN = 4
private const val I_INFO = 5
private const val I_DOWNLOAD = 6
private const val I_SEARCH = 7

/** Colores de la interfaz según modo día/noche. */
private class Paleta(
    val fondo: Int, val borde: Int, val texto: Int, val sub: Int, val acento: Int, val pantalla: Int
)

/**
 * GPS 593 nativo: mapa vectorial (ecuador.pmtiles) + GPS + panel de diagnóstico.
 * Una sola Activity, vistas por código, sin AppCompat ni Compose, para arrancar rápido.
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

    // Vistas
    private lateinit var raiz: FrameLayout
    private lateinit var splash: FrameLayout
    private lateinit var splashTitulo: TextView
    private lateinit var splashSub: TextView
    private lateinit var tvVelocidad: TextView
    private lateinit var tvUnidad: TextView
    private lateinit var tvGps: TextView
    private lateinit var puntoGps: View
    private lateinit var tvBusqueda: TextView
    private lateinit var tvBanner: TextView
    private lateinit var tvAvisoTitulo: TextView
    private lateinit var tvAvisoTexto: TextView
    private lateinit var tvDiag: TextView
    private lateinit var tarjetaDiag: LinearLayout
    private lateinit var banner: LinearLayout
    private lateinit var tarjetaAviso: LinearLayout
    private lateinit var btnNoche: Icono
    private lateinit var btnCentrar: Icono

    private val tarjetas = ArrayList<Pair<View, Int>>()   // vista + radio de esquina (dp)
    private val botones = ArrayList<Icono>()
    private val iconos = ArrayList<Icono>()
    private var splashOculto = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun paleta(): Paleta =
        if (night) Paleta(
            fondo = Color.parseColor("#F0111827"), borde = Color.parseColor("#26FFFFFF"),
            texto = Color.parseColor("#F1F5F9"), sub = Color.parseColor("#94A3B8"),
            acento = Color.parseColor("#60A5FA"), pantalla = Color.parseColor("#0B1220")
        ) else Paleta(
            fondo = Color.parseColor("#F2FFFFFF"), borde = Color.parseColor("#14000000"),
            texto = Color.parseColor("#0F172A"), sub = Color.parseColor("#64748B"),
            acento = Color.parseColor("#2563EB"), pantalla = Color.parseColor("#F3F1EC")
        )

    // ───────────────────────── ciclo de vida ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tCreate = ms()

        // Modo inicial: lo último elegido; si nunca eligió, automático por hora
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        night = if (prefs.contains("night")) prefs.getBoolean("night", false) else {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            h < 6 || h >= 18
        }

        raiz = FrameLayout(this)
        setContentView(raiz)

        try {
            MapLibre.getInstance(this)
            val mv = MapView(this)
            mv.onCreate(savedInstanceState)
            raiz.addView(
                mv,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            mapView = mv
            mv.addOnDidFinishLoadingStyleListener { if (tStyle == 0L) tStyle = ms() }
            mv.addOnDidBecomeIdleListener {
                if (tIdle == 0L && tStyle > 0L) {
                    tIdle = ms()
                    ocultarSplash()
                }
            }
            mv.addOnWillStartRenderingFrameListener { frames.incrementAndGet() }
            mv.addOnDidFailLoadingMapListener { msg -> errorMapa = msg ?: "error desconocido" }
            mv.getMapAsync { m -> alEstarListoElMapa(m) }
        } catch (t: Throwable) {
            // Por ejemplo: falta la librería nativa para la arquitectura de la radio
            errorMapa = "No se pudo iniciar el motor de mapas: $t"
        }

        crearInterfaz()
        aplicarTema()
        pedirPermisos()
        // Si por algún motivo el mapa no termina de dibujarse, no dejes la pantalla de carga para siempre
        ui.postDelayed({ ocultarSplash() }, 12000L)
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

    /** Botón redondo flotante o ícono suelto, dibujado con Canvas. */
    private inner class Icono(ctx: Context, var tipo: Int, private val circular: Boolean) : View(ctx) {
        var activo = false
        private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val pal = paleta()
            val col = if (activo) pal.acento else pal.texto
            trazo.color = col
            relleno.color = col
            val k = width / 48f
            c.save()
            c.scale(k, k)                       // dibujamos en una cuadrícula de 48 x 48
            trazo.strokeWidth = 2.8f
            when (tipo) {
                I_PLUS -> {
                    c.drawLine(24f, 14f, 24f, 34f, trazo)
                    c.drawLine(14f, 24f, 34f, 24f, trazo)
                }
                I_MINUS -> c.drawLine(14f, 24f, 34f, 24f, trazo)
                I_LOCATE -> {
                    c.drawCircle(24f, 24f, 9f, trazo)
                    c.drawCircle(24f, 24f, 3.2f, relleno)
                    c.drawLine(24f, 8f, 24f, 13f, trazo)
                    c.drawLine(24f, 35f, 24f, 40f, trazo)
                    c.drawLine(8f, 24f, 13f, 24f, trazo)
                    c.drawLine(35f, 24f, 40f, 24f, trazo)
                }
                I_MOON -> {
                    val a = Path().apply { addCircle(24f, 24f, 11f, Path.Direction.CW) }
                    val b = Path().apply { addCircle(30f, 19f, 10f, Path.Direction.CW) }
                    a.op(b, Path.Op.DIFFERENCE)
                    c.drawPath(a, relleno)
                }
                I_SUN -> {
                    c.drawCircle(24f, 24f, 6f, relleno)
                    for (i in 0 until 8) {
                        val ang = Math.toRadians(i * 45.0)
                        val x1 = (24 + 10.5 * Math.cos(ang)).toFloat()
                        val y1 = (24 + 10.5 * Math.sin(ang)).toFloat()
                        val x2 = (24 + 14.5 * Math.cos(ang)).toFloat()
                        val y2 = (24 + 14.5 * Math.sin(ang)).toFloat()
                        c.drawLine(x1, y1, x2, y2, trazo)
                    }
                }
                I_INFO -> {
                    c.drawCircle(24f, 24f, 12f, trazo)
                    c.drawLine(24f, 22f, 24f, 32f, trazo)
                    c.drawCircle(24f, 16f, 1.9f, relleno)
                }
                I_DOWNLOAD -> {
                    c.drawLine(24f, 11f, 24f, 29f, trazo)
                    c.drawLine(16f, 22f, 24f, 30f, trazo)
                    c.drawLine(32f, 22f, 24f, 30f, trazo)
                    c.drawLine(13f, 37f, 35f, 37f, trazo)
                }
                I_SEARCH -> {
                    c.drawCircle(21f, 21f, 10f, trazo)
                    c.drawLine(28.5f, 28.5f, 38f, 38f, trazo)
                }
            }
            c.restore()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!circular) return super.onTouchEvent(e)
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    animate().scaleX(0.9f).scaleY(0.9f).setDuration(80L).start()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                    return true
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private fun texto(sp: Float, fuente: String = "sans-serif"): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.create(fuente, Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun tarjeta(radio: Int): LinearLayout {
        val t = LinearLayout(this)
        tarjetas.add(Pair(t, radio))
        return t
    }

    private fun botonRedondo(tipo: Int, tam: Int, accion: () -> Unit): Icono {
        val b = Icono(this, tipo, true)
        b.layoutParams = LinearLayout.LayoutParams(dp(tam), dp(tam)).apply { setMargins(0, dp(7), 0, dp(7)) }
        b.setOnClickListener { accion() }
        botones.add(b)
        iconos.add(b)
        return b
    }

    private fun crearInterfaz() {
        val mw = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT

        // ── Columna izquierda: velocidad + diagnóstico ──
        val izquierda = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val cardVel = tarjeta(24).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(22), dp(14))
        }
        val filaVel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        tvVelocidad = texto(50f, "sans-serif-light").apply { text = "0" }
        tvUnidad = texto(14f, "sans-serif-medium").apply {
            text = "km/h"
            setPadding(dp(8), 0, 0, dp(8))
        }
        filaVel.addView(tvVelocidad)
        filaVel.addView(tvUnidad)
        val filaGps = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        puntoGps = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#F59E0B")) }
        }
        filaGps.addView(puntoGps, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(8) })
        tvGps = texto(13f, "sans-serif-medium").apply { text = "Buscando señal GPS…" }
        filaGps.addView(tvGps)
        cardVel.addView(filaVel)
        cardVel.addView(filaGps)
        izquierda.addView(cardVel, LinearLayout.LayoutParams(wc, wc))

        tarjetaDiag = tarjeta(18).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
        }
        tvDiag = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxWidth = dp(620)
        }
        tarjetaDiag.addView(tvDiag)
        izquierda.addView(tarjetaDiag, LinearLayout.LayoutParams(wc, wc).apply { topMargin = dp(10) })

        raiz.addView(izquierda, FrameLayout.LayoutParams(wc, wc, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(16), dp(16), 0, 0)
        })

        // ── Arriba al centro: barra de búsqueda (aún sin función) ──
        val pill = tarjeta(30).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(24), 0)
            setOnClickListener {
                Toast.makeText(this@MainActivity, "La búsqueda sin internet llega en la próxima versión", Toast.LENGTH_SHORT).show()
            }
        }
        val icoBuscar = Icono(this, I_SEARCH, false).also { iconos.add(it) }
        pill.addView(icoBuscar, LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(14) })
        tvBusqueda = texto(17f).apply { text = "¿A dónde vamos?" }
        pill.addView(tvBusqueda)
        raiz.addView(pill, FrameLayout.LayoutParams(dp(420), dp(60), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(16)
        })

        // ── Derecha arriba: modo día/noche e información ──
        val derArriba = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        btnNoche = botonRedondo(I_MOON, 56) { alternarNoche() }
        derArriba.addView(btnNoche)
        derArriba.addView(botonRedondo(I_INFO, 56) {
            tarjetaDiag.visibility = if (tarjetaDiag.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        raiz.addView(derArriba, FrameLayout.LayoutParams(wc, wc, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(10), dp(16), 0)
        })

        // ── Derecha abajo: zoom y centrar ──
        val derAbajo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        derAbajo.addView(botonRedondo(I_PLUS, 60) { map?.animateCamera(CameraUpdateFactory.zoomIn()) })
        derAbajo.addView(botonRedondo(I_MINUS, 60) { map?.animateCamera(CameraUpdateFactory.zoomOut()) })
        btnCentrar = botonRedondo(I_LOCATE, 68) { centrar() }
        btnCentrar.activo = true
        derAbajo.addView(btnCentrar)
        raiz.addView(derAbajo, FrameLayout.LayoutParams(wc, wc, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(16), dp(20))
        })

        // ── Abajo al centro: aviso para copiar el mapa al almacenamiento interno ──
        banner = tarjeta(28).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(24), dp(10))
            visibility = View.GONE
            setOnClickListener { if (!copiando) copiarAlInterno() }
        }
        val icoBajar = Icono(this, I_DOWNLOAD, false).also { it.activo = true; iconos.add(it) }
        banner.addView(icoBajar, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(12) })
        tvBanner = texto(15f, "sans-serif-medium")
        banner.addView(tvBanner)
        raiz.addView(banner, FrameLayout.LayoutParams(wc, wc, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(28)
        })

        // ── Centro: mapa no encontrado ──
        tarjetaAviso = tarjeta(26).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            visibility = View.GONE
        }
        tvAvisoTitulo = texto(22f, "sans-serif-medium").apply { text = "Falta el mapa" }
        tvAvisoTexto = texto(16f).apply {
            text = "Copia ecuador.pmtiles a la raíz del pendrive, conéctalo y vuelve a abrir la app."
            setPadding(0, dp(10), 0, 0)
            setLineSpacing(0f, 1.15f)
        }
        tarjetaAviso.addView(tvAvisoTitulo)
        tarjetaAviso.addView(tvAvisoTexto)
        raiz.addView(tarjetaAviso, FrameLayout.LayoutParams(dp(520), wc, Gravity.CENTER))

        // ── Pantalla de carga con la marca (se desvanece cuando el mapa está dibujado) ──
        splash = FrameLayout(this)
        val centro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        splashTitulo = texto(38f, "sans-serif-medium").apply { text = "GPS 593"; letterSpacing = 0.12f }
        splashSub = texto(15f).apply { text = "Cargando mapa…"; setPadding(0, dp(10), 0, 0) }
        centro.addView(splashTitulo)
        centro.addView(splashSub)
        splash.addView(centro, FrameLayout.LayoutParams(wc, wc, Gravity.CENTER))
        raiz.addView(splash, FrameLayout.LayoutParams(mw, mw))
    }

    /** Aplica los colores del modo actual a toda la interfaz. */
    private fun aplicarTema() {
        val pal = paleta()
        raiz.setBackgroundColor(pal.pantalla)
        for ((v, r) in tarjetas) {
            v.background = GradientDrawable().apply {
                cornerRadius = dp(r).toFloat()
                setColor(pal.fondo)
                setStroke(dp(1), pal.borde)
            }
            v.elevation = dp(8).toFloat()
        }
        for (b in botones) {
            b.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(pal.fondo)
                setStroke(dp(1), pal.borde)
            }
            b.elevation = dp(7).toFloat()
        }
        tvVelocidad.setTextColor(pal.texto)
        tvUnidad.setTextColor(pal.sub)
        tvGps.setTextColor(pal.sub)
        tvBusqueda.setTextColor(pal.sub)
        tvBanner.setTextColor(pal.texto)
        tvAvisoTitulo.setTextColor(pal.texto)
        tvAvisoTexto.setTextColor(pal.sub)
        tvDiag.setTextColor(pal.texto)
        splash.setBackgroundColor(pal.pantalla)
        splashTitulo.setTextColor(pal.texto)
        splashSub.setTextColor(pal.sub)
        btnNoche.tipo = if (night) I_SUN else I_MOON
        btnCentrar.activo = follow
        for (i in iconos) i.invalidate()
    }

    private fun ocultarSplash() {
        if (splashOculto || !::splash.isInitialized) return
        splashOculto = true
        splash.animate().alpha(0f).setDuration(350L).withEndAction { splash.visibility = View.GONE }.start()
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
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                follow = false
                btnCentrar.activo = false
                btnCentrar.invalidate()
            }
        }
        // Vista inicial: todo Ecuador
        if (lastLoc == null) {
            m.cameraPosition = CameraPosition.Builder().target(LatLng(-1.65, -78.4)).zoom(6.2).build()
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
                    tarjetaAviso.visibility = View.VISIBLE
                    ocultarSplash()
                } else {
                    tarjetaAviso.visibility = View.GONE
                    cargarEstilo()
                }
                actualizarBanner()
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
        aplicarTema()
        cargarEstilo()
    }

    private fun centrar() {
        follow = true
        btnCentrar.activo = true
        btnCentrar.invalidate()
        val l = lastLoc ?: return
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(l.latitude, l.longitude), 15.0))
    }

    private fun esInterno(ruta: String): Boolean =
        ruta.startsWith(filesDir.absolutePath) ||
            (getExternalFilesDir(null)?.let { ruta.startsWith(it.absolutePath) } ?: false)

    private fun actualizarBanner() {
        val r = mapPath
        if (copiando) {
            banner.visibility = View.VISIBLE
            tvBanner.text = if (copyStatus.isNotEmpty()) copyStatus else "Copiando mapa…"
        } else if (r != null && !esInterno(r)) {
            banner.visibility = View.VISIBLE
            tvBanner.text = "Mapa en el pendrive · toca para copiarlo al interno"
        } else {
            banner.visibility = View.GONE
        }
    }

    /** Copia el mapa del pendrive al almacenamiento interno (una sola vez) para no depender de la lectura USB. */
    private fun copiarAlInterno() {
        val origen = mapPath?.let { File(it) } ?: return
        if (copiando) return
        val carpeta = getExternalFilesDir(null) ?: filesDir
        val destino = File(carpeta, origen.name.replace(" ", "_"))
        copiando = true
        copyStatus = "Copiando mapa… 0 %"
        actualizarBanner()
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
                    copyStatus = ""
                    copiando = false
                    mapPath = destino.absolutePath
                    cargarEstilo()
                    actualizarBanner()
                    Toast.makeText(this, "Mapa copiado: ahora carga desde el almacenamiento interno", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                ui.post {
                    copyStatus = ""
                    copiando = false
                    actualizarBanner()
                    Toast.makeText(this, "No pude copiar el mapa: ${e.message}", Toast.LENGTH_LONG).show()
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
        actualizarVelocidad()
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

    // ───────────────────────── velocímetro y diagnóstico (cada segundo) ─────────────────────────

    private val tick = object : Runnable {
        override fun run() {
            fps = frames.getAndSet(0)
            actualizarVelocidad()
            actualizarBanner()
            if (tarjetaDiag.visibility == View.VISIBLE) actualizarDiagnostico()
            ui.postDelayed(this, 1000L)
        }
    }

    private fun actualizarVelocidad() {
        val l = lastLoc
        val edad = if (l != null) (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000_000L else -1L
        val color: Int
        val msg: String
        when {
            !tienePermisoGps() -> { color = Color.parseColor("#EF4444"); msg = "Sin permiso de ubicación" }
            l == null -> {
                if (gpsActivo) { color = Color.parseColor("#F59E0B"); msg = "Buscando señal GPS…" }
                else { color = Color.parseColor("#EF4444"); msg = "GPS apagado" }
            }
            edad > 5 -> { color = Color.parseColor("#F59E0B"); msg = "Señal débil · hace ${edad} s" }
            else -> {
                color = Color.parseColor("#22C55E")
                msg = if (l != null && l.hasAccuracy()) "GPS ±${l.accuracy.toInt()} m" else "GPS"
            }
        }
        val kmh = if (l != null && edad <= 5 && l.hasSpeed()) Math.round(l.speed * 3.6f) else 0
        tvVelocidad.text = kmh.toString()
        tvGps.text = msg
        (puntoGps.background as? GradientDrawable)?.setColor(color)
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
        val lineaGps = if (l == null) "GPS: sin posición" else String.format(
            Locale.US, "GPS: %.5f, %.5f  %.0f km/h  (%s)",
            l.latitude, l.longitude, if (l.hasSpeed()) l.speed * 3.6f else 0f, l.provider ?: "?"
        )
        val sb = StringBuilder()
        sb.append("GPS 593 nativo $VERSION  |  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})  |  ")
            .append(Build.SUPPORTED_ABIS.firstOrNull() ?: "?").append('\n')
        sb.append(lineaMapa).append('\n')
        sb.append("Desde el inicio: pantalla ${t(tCreate)} · motor ${t(tMapReady)} · estilo ${t(tStyle)} · mapa completo ${t(tIdle)}\n")
        sb.append("FPS: $fps   |   Memoria de la app: ${mi.totalPss / 1024} MB (java $javaMb, nativa $nativaMb)\n")
        sb.append("RAM libre del sistema: ${sis.availMem / 1048576} MB de ${sis.totalMem / 1048576} MB\n")
        sb.append(lineaGps)
        if (errorMapa.isNotEmpty()) sb.append("\nERROR: ").append(errorMapa)
        tvDiag.text = sb.toString()
    }
}
