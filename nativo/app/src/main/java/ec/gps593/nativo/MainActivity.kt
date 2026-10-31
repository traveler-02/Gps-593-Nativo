package ec.gps593.nativo

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
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
import java.io.InputStream
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private const val VERSION = "0.4"
private const val REQ_MAPA = 77

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
    val fondo: Int, val fondoClaro: Int, val borde: Int, val texto: Int, val sub: Int,
    val acento: Int, val acento2: Int, val pantalla: Int, val pantalla2: Int
)

/**
 * GPS 593 nativo: mapa vectorial (ecuador.pmtiles) + GPS + panel de diagnóstico.
 * Una sola Activity, vistas por código, sin AppCompat ni Compose, para arrancar rápido.
 * La interfaz se escala según el alto de la pantalla (teléfono, tablet o radio).
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
    @Volatile private var copiando = false
    @Volatile private var progreso = 0f
    private var errorCopia = ""
    private var errorMapa = ""

    private var escala = 1f

    // Vistas
    private lateinit var raiz: FrameLayout
    private lateinit var splash: FrameLayout
    private lateinit var splashTitulo: TextView
    private lateinit var splashSub: TextView
    private lateinit var splashBarra: Barra
    private lateinit var tvVelocidad: TextView
    private lateinit var tvUnidad: TextView
    private lateinit var tvGps: TextView
    private lateinit var puntoGps: View
    private lateinit var tvBusqueda: TextView
    private lateinit var tvBanner: TextView
    private lateinit var tvMapaTitulo: TextView
    private lateinit var tvMapaTexto: TextView
    private lateinit var tvMapaError: TextView
    private lateinit var tvPct: TextView
    private lateinit var barraCopia: Barra
    private lateinit var filaBotonesMapa: LinearLayout
    private lateinit var tvDiag: TextView
    private lateinit var tarjetaDiag: LinearLayout
    private lateinit var tarjetaMapa: LinearLayout
    private lateinit var banner: LinearLayout
    private lateinit var btnNoche: Icono
    private lateinit var btnCentrar: Icono

    private val tarjetas = ArrayList<Pair<View, Int>>()        // vista + radio de esquina (dp)
    private val botones = ArrayList<Icono>()
    private val botonesTexto = ArrayList<Pair<TextView, Boolean>>()   // vista + es primario
    private val iconos = ArrayList<Icono>()
    private var splashOculto = false

    /** dp escalados según el tamaño de la pantalla. */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density * escala).toInt()

    private fun paleta(): Paleta =
        if (night) Paleta(
            fondo = Color.parseColor("#F0111827"), fondoClaro = Color.parseColor("#1B2535"),
            borde = Color.parseColor("#26FFFFFF"), texto = Color.parseColor("#F1F5F9"),
            sub = Color.parseColor("#94A3B8"), acento = Color.parseColor("#3B82F6"),
            acento2 = Color.parseColor("#6366F1"), pantalla = Color.parseColor("#0B1220"),
            pantalla2 = Color.parseColor("#16233F")
        ) else Paleta(
            fondo = Color.parseColor("#F2FFFFFF"), fondoClaro = Color.parseColor("#FFFFFF"),
            borde = Color.parseColor("#14000000"), texto = Color.parseColor("#0F172A"),
            sub = Color.parseColor("#64748B"), acento = Color.parseColor("#2563EB"),
            acento2 = Color.parseColor("#4F46E5"), pantalla = Color.parseColor("#F3F1EC"),
            pantalla2 = Color.parseColor("#E4EAF4")
        )

    // ───────────────────────── ciclo de vida ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tCreate = ms()

        // Escala de la interfaz según el lado corto de la pantalla (en dp)
        val dm = resources.displayMetrics
        val ladoCortoDp = Math.min(dm.heightPixels, dm.widthPixels) / dm.density
        escala = (ladoCortoDp / 460f).coerceIn(0.62f, 1.12f)

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

    // ───────────────────────── vistas propias ─────────────────────────

    /** Botón redondo flotante o ícono suelto, dibujado con Canvas. */
    private inner class Icono(ctx: Context, var tipo: Int, private val circular: Boolean) : View(ctx) {
        var activo = false        // ícono en color de acento
        var destacado = false     // botón relleno de acento con ícono blanco
        private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val pal = paleta()
            val col = if (destacado) Color.WHITE else if (activo) pal.acento else pal.texto
            trazo.color = col
            relleno.color = col
            val k = width / 48f
            c.save()
            c.scale(k, k)                       // dibujamos en una cuadrícula de 48 x 48
            trazo.strokeWidth = 3.4f
            when (tipo) {
                I_PLUS -> {
                    c.drawLine(24f, 14f, 24f, 34f, trazo)
                    c.drawLine(14f, 24f, 34f, 24f, trazo)
                }
                I_MINUS -> c.drawLine(14f, 24f, 34f, 24f, trazo)
                I_LOCATE -> {
                    c.drawCircle(24f, 24f, 9.5f, trazo)
                    c.drawCircle(24f, 24f, 3.6f, relleno)
                    c.drawLine(24f, 7f, 24f, 13f, trazo)
                    c.drawLine(24f, 35f, 24f, 41f, trazo)
                    c.drawLine(7f, 24f, 13f, 24f, trazo)
                    c.drawLine(35f, 24f, 41f, 24f, trazo)
                }
                I_MOON -> {
                    val a = Path().apply { addCircle(24f, 24f, 12f, Path.Direction.CW) }
                    val b = Path().apply { addCircle(31f, 18.5f, 10.5f, Path.Direction.CW) }
                    a.op(b, Path.Op.DIFFERENCE)
                    c.drawPath(a, relleno)
                }
                I_SUN -> {
                    c.drawCircle(24f, 24f, 7f, relleno)
                    for (i in 0 until 8) {
                        val ang = Math.toRadians(i * 45.0)
                        val x1 = (24 + 11.5 * Math.cos(ang)).toFloat()
                        val y1 = (24 + 11.5 * Math.sin(ang)).toFloat()
                        val x2 = (24 + 15.5 * Math.cos(ang)).toFloat()
                        val y2 = (24 + 15.5 * Math.sin(ang)).toFloat()
                        c.drawLine(x1, y1, x2, y2, trazo)
                    }
                }
                I_INFO -> {
                    c.drawCircle(24f, 24f, 13f, trazo)
                    c.drawLine(24f, 22f, 24f, 32f, trazo)
                    c.drawCircle(24f, 15.5f, 2.1f, relleno)
                }
                I_DOWNLOAD -> {
                    c.drawLine(24f, 10f, 24f, 29f, trazo)
                    c.drawLine(16f, 22f, 24f, 30f, trazo)
                    c.drawLine(32f, 22f, 24f, 30f, trazo)
                    c.drawLine(12f, 38f, 36f, 38f, trazo)
                }
                I_SEARCH -> {
                    c.drawCircle(21f, 21f, 11f, trazo)
                    c.drawLine(29f, 29f, 38.5f, 38.5f, trazo)
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

    /** Barra de progreso redondeada con degradado (determinada o animada). */
    private inner class Barra(ctx: Context) : View(ctx) {
        var progreso = 0f
        var indeterminada = false
        private val pista = Paint(Paint.ANTI_ALIAS_FLAG)
        private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val pal = paleta()
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val r = h / 2f
            pista.color = if (night) Color.parseColor("#33FFFFFF") else Color.parseColor("#1F0F172A")
            rect.set(0f, 0f, w, h)
            c.drawRoundRect(rect, r, r, pista)
            relleno.shader = LinearGradient(0f, 0f, w, 0f, pal.acento, pal.acento2, Shader.TileMode.CLAMP)
            if (indeterminada) {
                val fase = (SystemClock.uptimeMillis() % 1300L) / 1300f
                val largo = w * 0.35f
                val x = -largo + (w + largo) * fase
                rect.set(Math.max(0f, x), 0f, Math.min(w, x + largo), h)
                c.drawRoundRect(rect, r, r, relleno)
                postInvalidateOnAnimation()
            } else {
                val f = progreso.coerceIn(0f, 1f)
                if (f > 0f) {
                    rect.set(0f, 0f, Math.max(h, w * f), h)
                    c.drawRoundRect(rect, r, r, relleno)
                }
            }
        }
    }

    // ───────────────────────── interfaz ─────────────────────────

    private fun texto(sp: Float, fuente: String = "sans-serif"): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * escala)
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
        b.layoutParams = LinearLayout.LayoutParams(dp(tam), dp(tam)).apply { setMargins(0, dp(6), 0, dp(6)) }
        b.setOnClickListener { accion() }
        botones.add(b)
        iconos.add(b)
        return b
    }

    private fun botonTexto(t: String, primario: Boolean, accion: () -> Unit): TextView {
        val v = texto(15f, "sans-serif-medium")
        v.text = t
        v.gravity = Gravity.CENTER
        v.setPadding(dp(22), dp(13), dp(22), dp(13))
        v.isClickable = true
        v.setOnClickListener { accion() }
        v.setOnTouchListener { vista, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> vista.alpha = 0.85f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> vista.alpha = 1f
            }
            false
        }
        botonesTexto.add(Pair(v, primario))
        return v
    }

    private fun crearInterfaz() {
        val mw = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        val anchoPantalla = resources.displayMetrics.widthPixels

        // ── Fila superior: velocidad + búsqueda ──
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val cardVel = tarjeta(26).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(20), dp(10))
        }
        val filaVel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        tvVelocidad = texto(46f, "sans-serif-light").apply { text = "0" }
        tvUnidad = texto(13f, "sans-serif-medium").apply {
            text = "km/h"
            setPadding(dp(8), 0, 0, dp(7))
        }
        filaVel.addView(tvVelocidad)
        filaVel.addView(tvUnidad)
        val filaGps = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        puntoGps = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#F59E0B")) }
        }
        filaGps.addView(puntoGps, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(7) })
        tvGps = texto(12f, "sans-serif-medium").apply { text = "Buscando señal GPS…" }
        filaGps.addView(tvGps)
        cardVel.addView(filaVel)
        cardVel.addView(filaGps)
        fila.addView(cardVel, LinearLayout.LayoutParams(wc, wc))

        val pill = tarjeta(30).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(24), 0)
            setOnClickListener {
                Toast.makeText(this@MainActivity, "La búsqueda sin internet llega en la próxima versión", Toast.LENGTH_SHORT).show()
            }
        }
        val icoBuscar = Icono(this, I_SEARCH, false).also { iconos.add(it) }
        pill.addView(icoBuscar, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(14) })
        tvBusqueda = texto(16f).apply { text = "¿A dónde vamos?" }
        pill.addView(tvBusqueda)
        fila.addView(pill, LinearLayout.LayoutParams(0, dp(56), 1f).apply { leftMargin = dp(12) })

        raiz.addView(fila, FrameLayout.LayoutParams(mw, wc, Gravity.TOP).apply {
            setMargins(dp(14), dp(14), dp(14), 0)
        })

        // ── Abajo a la izquierda: modo día/noche e información ──
        val izqAbajo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        btnNoche = botonRedondo(I_MOON, 48) { alternarNoche() }
        izqAbajo.addView(btnNoche)
        izqAbajo.addView(botonRedondo(I_INFO, 48) {
            tarjetaDiag.visibility = if (tarjetaDiag.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        raiz.addView(izqAbajo, FrameLayout.LayoutParams(wc, wc, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(14), 0, 0, dp(30))
        })

        // ── Panel de diagnóstico (oculto; se abre con ⓘ) ──
        tarjetaDiag = tarjeta(18).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
        }
        tvDiag = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * escala)
            maxWidth = Math.min(dp(560), (anchoPantalla * 0.7f).toInt())
        }
        tarjetaDiag.addView(tvDiag)
        raiz.addView(tarjetaDiag, FrameLayout.LayoutParams(wc, wc, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(14), 0, 0, dp(150))
        })

        // ── Abajo a la derecha: zoom y centrar ──
        val derAbajo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        derAbajo.addView(botonRedondo(I_PLUS, 50) { map?.animateCamera(CameraUpdateFactory.zoomIn()) })
        derAbajo.addView(botonRedondo(I_MINUS, 50) { map?.animateCamera(CameraUpdateFactory.zoomOut()) })
        btnCentrar = botonRedondo(I_LOCATE, 62) { centrar() }
        btnCentrar.destacado = true
        derAbajo.addView(btnCentrar)
        raiz.addView(derAbajo, FrameLayout.LayoutParams(wc, wc, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(14), dp(24))
        })

        // ── Abajo al centro: copiar el mapa del pendrive al almacenamiento interno ──
        banner = tarjeta(28).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(24), dp(10))
            visibility = View.GONE
            setOnClickListener { copiarDesdePendrive() }
        }
        val icoBajar = Icono(this, I_DOWNLOAD, false).also { it.activo = true; iconos.add(it) }
        banner.addView(icoBajar, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(12) })
        tvBanner = texto(14f, "sans-serif-medium")
        banner.addView(tvBanner)
        raiz.addView(banner, FrameLayout.LayoutParams(wc, wc, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(26)
        })

        // ── Centro: instalar el mapa (cuando no hay) / progreso de copia ──
        tarjetaMapa = tarjeta(30).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            visibility = View.GONE
        }
        tvMapaTitulo = texto(22f, "sans-serif-medium").apply { text = "Instala el mapa de Ecuador" }
        tvMapaTexto = texto(15f).apply {
            setPadding(0, dp(10), 0, 0)
            setLineSpacing(0f, 1.15f)
        }
        tvMapaError = texto(13f, "sans-serif-medium").apply {
            setPadding(0, dp(10), 0, 0)
            setTextColor(Color.parseColor("#DC2626"))
            visibility = View.GONE
        }
        barraCopia = Barra(this).apply { visibility = View.GONE }
        tvPct = texto(13f, "sans-serif-medium").apply {
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        filaBotonesMapa = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filaBotonesMapa.addView(
            botonTexto("Elegir archivo del mapa", true) { elegirArchivo() },
            LinearLayout.LayoutParams(wc, wc).apply { rightMargin = dp(10) }
        )
        filaBotonesMapa.addView(botonTexto("Buscar en el pendrive", false) { buscarDeNuevo() })
        tarjetaMapa.addView(tvMapaTitulo)
        tarjetaMapa.addView(tvMapaTexto)
        tarjetaMapa.addView(tvMapaError)
        tarjetaMapa.addView(barraCopia, LinearLayout.LayoutParams(mw, dp(8)).apply { topMargin = dp(16) })
        tarjetaMapa.addView(tvPct)
        tarjetaMapa.addView(filaBotonesMapa, LinearLayout.LayoutParams(wc, wc).apply { topMargin = dp(18) })
        raiz.addView(
            tarjetaMapa,
            FrameLayout.LayoutParams(Math.min(dp(580), (anchoPantalla * 0.8f).toInt()), wc, Gravity.CENTER)
        )

        // ── Pantalla de carga con el logo (se desvanece cuando el mapa está dibujado) ──
        splash = FrameLayout(this)
        val centro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        centro.addView(logo, LinearLayout.LayoutParams(dp(120), dp(120)))
        splashTitulo = texto(30f, "sans-serif-medium").apply {
            text = "GPS 593"
            letterSpacing = 0.12f
            setPadding(0, dp(14), 0, 0)
        }
        splashSub = texto(14f).apply { text = "Versión nativa $VERSION"; setPadding(0, dp(6), 0, 0) }
        splashBarra = Barra(this).apply { indeterminada = true }
        centro.addView(splashTitulo)
        centro.addView(splashSub)
        centro.addView(splashBarra, LinearLayout.LayoutParams(dp(170), dp(4)).apply { topMargin = dp(22) })
        splash.addView(centro, FrameLayout.LayoutParams(wc, wc, Gravity.CENTER))
        raiz.addView(splash, FrameLayout.LayoutParams(mw, mw))
    }

    private fun pintarBoton(b: Icono) {
        val pal = paleta()
        b.background = if (b.destacado) {
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(pal.acento, pal.acento2)).apply {
                shape = GradientDrawable.OVAL
            }
        } else {
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(pal.fondoClaro, pal.fondo)).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), pal.borde)
            }
        }
        b.elevation = dp(7).toFloat()
        b.invalidate()
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
        for (b in botones) pintarBoton(b)
        for ((v, primario) in botonesTexto) {
            if (primario) {
                v.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(pal.acento, pal.acento2)).apply {
                    cornerRadius = dp(26).toFloat()
                }
                v.setTextColor(Color.WHITE)
                v.elevation = dp(4).toFloat()
            } else {
                v.background = GradientDrawable().apply {
                    cornerRadius = dp(26).toFloat()
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), pal.acento)
                }
                v.setTextColor(pal.acento)
                v.elevation = 0f
            }
        }
        tvVelocidad.setTextColor(pal.texto)
        tvUnidad.setTextColor(pal.sub)
        tvGps.setTextColor(pal.sub)
        tvBusqueda.setTextColor(pal.sub)
        tvBanner.setTextColor(pal.texto)
        tvMapaTitulo.setTextColor(pal.texto)
        tvMapaTexto.setTextColor(pal.sub)
        tvPct.setTextColor(pal.sub)
        tvDiag.setTextColor(pal.texto)
        splash.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(pal.pantalla, pal.pantalla2))
        splashTitulo.setTextColor(pal.texto)
        splashSub.setTextColor(pal.sub)
        btnNoche.tipo = if (night) I_SUN else I_MOON
        btnCentrar.destacado = follow
        pintarBoton(btnCentrar)
        for (i in iconos) i.invalidate()
        barraCopia.invalidate()
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
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && follow) {
                follow = false
                btnCentrar.destacado = false
                pintarBoton(btnCentrar)
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
                    ocultarSplash()
                } else {
                    cargarEstilo()
                }
                actualizarTarjetas()
            }
        }.start()
    }

    private fun buscarDeNuevo() {
        errorCopia = ""
        mapPath = null
        buscando = true
        actualizarTarjetas()
        pedirPermisos()
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
        btnCentrar.destacado = true
        pintarBoton(btnCentrar)
        val l = lastLoc ?: return
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(l.latitude, l.longitude), 15.0))
    }

    private fun esInterno(ruta: String): Boolean =
        ruta.startsWith(filesDir.absolutePath) ||
            (getExternalFilesDir(null)?.let { ruta.startsWith(it.absolutePath) } ?: false)

    /** Muestra u oculta la tarjeta de instalación del mapa y el aviso del pendrive. */
    private fun actualizarTarjetas() {
        val r = mapPath
        val mostrar = copiando || (r == null && !buscando)
        tarjetaMapa.visibility = if (mostrar) View.VISIBLE else View.GONE
        if (mostrar) {
            if (copiando) {
                tvMapaTitulo.text = "Preparando el mapa"
                tvMapaTexto.text = "Se copia una sola vez. Después el mapa abre al instante, sin pendrive."
                barraCopia.progreso = progreso
                barraCopia.visibility = View.VISIBLE
                barraCopia.invalidate()
                tvPct.text = "${(progreso * 100).toInt()} %"
                tvPct.visibility = View.VISIBLE
                filaBotonesMapa.visibility = View.GONE
                tvMapaError.visibility = View.GONE
            } else {
                tvMapaTitulo.text = "Instala el mapa de Ecuador"
                tvMapaTexto.text = "Elige el archivo ecuador.pmtiles (unos 121 MB) desde este equipo o desde el pendrive. " +
                    "Se copia una sola vez y después abre al instante."
                barraCopia.visibility = View.GONE
                tvPct.visibility = View.GONE
                filaBotonesMapa.visibility = View.VISIBLE
                if (errorCopia.isNotEmpty()) {
                    tvMapaError.text = errorCopia
                    tvMapaError.visibility = View.VISIBLE
                } else {
                    tvMapaError.visibility = View.GONE
                }
            }
        }
        if (r != null && !esInterno(r) && !copiando) {
            tvBanner.text = "Mapa en el pendrive · toca para copiarlo al interno"
            banner.visibility = View.VISIBLE
        } else {
            banner.visibility = View.GONE
        }
    }

    // ───────────────────────── instalar el mapa (copia única) ─────────────────────────

    private fun elegirArchivo() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, REQ_MAPA)
        } catch (e: Exception) {
            Toast.makeText(this, "Este equipo no tiene selector de archivos. Usa «Buscar en el pendrive».", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MAPA && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            copiarMapa({ contentResolver.openInputStream(uri) }, tamanoUri(uri))
        }
    }

    private fun tamanoUri(uri: Uri): Long {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) return c.getLong(i)
                }
            }
        } catch (e: Exception) { /* sin tamaño: la barra irá sin porcentaje exacto */ }
        return -1L
    }

    private fun copiarDesdePendrive() {
        val ruta = mapPath ?: return
        if (copiando) return
        val origen = File(ruta)
        copiarMapa({ FileInputStream(origen) }, origen.length())
    }

    /** Copia el mapa a la carpeta propia de la app (almacenamiento interno) y lo valida. */
    private fun copiarMapa(abrir: () -> InputStream?, total: Long) {
        if (copiando) return
        val carpeta = getExternalFilesDir(null) ?: filesDir
        val destino = File(carpeta, "ecuador.pmtiles")
        val tmp = File(carpeta, "ecuador.pmtiles.tmp")
        copiando = true
        progreso = 0f
        errorCopia = ""
        actualizarTarjetas()
        Thread {
            try {
                if (total > 0 && StatFs(carpeta.path).availableBytes < total + 50_000_000L) {
                    throw RuntimeException("no hay espacio libre suficiente")
                }
                val entrada = abrir() ?: throw RuntimeException("no pude abrir el archivo")
                entrada.use { inp ->
                    FileOutputStream(tmp).use { salida ->
                        val buf = ByteArray(1 shl 20)
                        var hecho = 0L
                        var ultimo = 0L
                        var n = inp.read(buf)
                        while (n > 0) {
                            salida.write(buf, 0, n)
                            hecho += n
                            if (total > 0) progreso = hecho.toFloat() / total.toFloat()
                            val ahora = SystemClock.elapsedRealtime()
                            if (ahora - ultimo > 200L) {
                                ultimo = ahora
                                ui.post { actualizarTarjetas() }
                            }
                            n = inp.read(buf)
                        }
                    }
                }
                // Un .pmtiles empieza con la palabra "PMTiles"
                val cab = ByteArray(7)
                val leidos = FileInputStream(tmp).use { it.read(cab) }
                if (leidos != 7 || String(cab, Charsets.US_ASCII) != "PMTiles") {
                    throw RuntimeException("ese archivo no es un mapa .pmtiles")
                }
                if (destino.exists()) destino.delete()
                if (!tmp.renameTo(destino)) throw RuntimeException("no pude guardar el archivo")
                ui.post {
                    copiando = false
                    progreso = 1f
                    mapPath = destino.absolutePath
                    cargarEstilo()
                    actualizarTarjetas()
                    Toast.makeText(this, "Mapa listo: desde ahora abre al instante", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                tmp.delete()
                ui.post {
                    copiando = false
                    errorCopia = "No pude instalar el mapa: ${e.message}"
                    actualizarTarjetas()
                }
            }
        }.start()
    }

    // ───────────────────────── GPS ─────────────────────────

    private fun tienePermisoGps(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun edadSeg(l: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000_000L

    private fun iniciarGps() {
        if (gpsActivo || !tienePermisoGps()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
                gpsActivo = true
            }
            if (lastLoc == null) {
                val k = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                // Solo si es reciente: una posición vieja no sirve para centrar el mapa
                if (k != null && edadSeg(k) < 120L) onLocationChanged(k)
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
            actualizarTarjetas()
            if (tarjetaDiag.visibility == View.VISIBLE) actualizarDiagnostico()
            ui.postDelayed(this, 1000L)
        }
    }

    private fun actualizarVelocidad() {
        val l = lastLoc
        val edad = if (l != null) edadSeg(l) else -1L
        val color: Int
        val msg: String
        when {
            !tienePermisoGps() -> { color = Color.parseColor("#EF4444"); msg = "Sin permiso de ubicación" }
            l == null -> {
                if (gpsActivo) { color = Color.parseColor("#F59E0B"); msg = "Buscando señal GPS…" }
                else { color = Color.parseColor("#EF4444"); msg = "GPS apagado" }
            }
            edad > 15 -> { color = Color.parseColor("#F59E0B"); msg = "Sin señal GPS reciente" }
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
            if (buscando) "Mapa: buscando…" else "Mapa: NO instalado"
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
