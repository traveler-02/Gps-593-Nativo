package ec.gps593.nativo

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
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

private const val VERSION = "1.0"
private const val REQ_ARCHIVOS = 77
private const val REQ_PERM_UBICACION = 1
private const val REQ_PERM_ALMACEN = 2
private const val PROV_FUSED = "fused"          // LocationManager.FUSED_PROVIDER (Android 12+)

/** Colores de la interfaz según modo día/noche. */
private class Paleta(
    val fondo: Int, val fondoClaro: Int, val borde: Int, val texto: Int, val sub: Int,
    val acento: Int, val acento2: Int, val pantalla: Int, val pantalla2: Int, val hoja: Int
)

/** Un archivo que se va a instalar (del selector de Android o del pendrive). */
private class Fuente(val nombre: String, val total: Long, val abrir: () -> InputStream?)

/**
 * GPS 593 nativo: mapa vectorial (ecuador.pmtiles) + búsqueda y rutas (ecuador.g593) + GPS.
 * Una sola Activity, vistas por código, sin AppCompat ni Compose, para arrancar rápido.
 * La interfaz se escala según el tamaño de la pantalla (teléfono, tablet o radio).
 */
class MainActivity : Activity(), LocationListener {

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

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

    // Archivos instalados
    private var mapPath: String? = null
    private var rutasPath: String? = null
    private var indice: IndiceLugares? = null
    @Volatile private var indexando = false
    private var errorIndice = ""

    // Instalación de archivos
    @Volatile private var instalando = false
    @Volatile private var progreso = 0f
    @Volatile private var textoInstal = ""
    private var errorInstal = ""

    // Preferencias y estado
    private var modoTema = 0                 // 0 automático, 1 día, 2 noche
    private var night = false
    private var pantallaOn = true
    private var vozOn = true
    private var follow = true
    private var primerFix = false
    private var lastLoc: Location? = null
    private var gpsActivo = false
    private var destino: Resultado? = null
    private var tts: TextToSpeech? = null

    // Navegación
    private var grafo: GrafoRutas? = null
    private var calles: Calles? = null
    @Volatile private var cargandoGrafo = false
    private var errorGrafo = ""
    private var ruta: Ruta? = null
    private var modoRuta = 0                  // 0 sin ruta, 1 vista previa, 2 navegando
    private var calculando = false
    private var rutasAlt: List<Ruta> = emptyList()      // opciones de ruta de la vista previa
    private var idxSel = 0
    private var autoIniciar = false                     // empezar a navegar apenas se calcule la ruta
    private val etiquetasSub = ArrayList<TextView>()
    private var pasoAnunciado = IntArray(0)
    private var fueraCont = 0
    private var idxRuta = 0
    private var simulando = false
    private var simDist = 0.0
    private var ttsListo = false
    private var pendienteVoz: String? = null
    private var inclinado = true
    private var tBusquedaGps = 0L
    private val contFix = HashMap<String, Int>()

    // Descarga de mapas
    private var manifiesto: Descarga.Manifiesto? = null
    @Volatile private var bajando = false
    @Volatile private var cancelarBajada = false
    private var consultandoManifiesto = false
    private var errorManifiesto = ""

    // Casa / trabajo (null = no se está definiendo ninguno)
    private var definiendo: String? = null

    // Marcador de tu posición (0 punto, 1 flecha, 2 triángulo, 3 auto, 4 taxi) y rumbo
    private var marcadorTipo = 1
    private var ultimoRumbo = 0f

    // Movimiento suave de la posición y orientación de la vista
    private var velMs = 0f
    private var ultimaFix: Location? = null
    private var posMostrada: DoubleArray? = null
    private var rumboMostrado = 0f
    private var animDesde = DoubleArray(2)
    private var animHasta = DoubleArray(2)
    private var animRumboDesde = 0f
    private var animRumboHasta = 0f
    private var animT0 = 0L
    private var animDur = 1000L
    private var animActiva = false
    private var zoomObjetivo = 17.0
    private var zoomActual = 15.0
    private var rumboArriba = false          // true = el mapa gira con el rumbo; false = norte arriba

    // Posición de referencia de la última búsqueda (para mostrar distancias y ordenar por cercanía)
    private var refLat = Double.NaN
    private var refLon = Double.NaN

    // Voz
    private var velVoz = 0.95f
    private var vozNombre = ""
    private var vocesEs: List<Voice> = emptyList()

    private val frames = AtomicInteger(0)
    @Volatile private var fps = 0
    private var errorMapa = ""

    private var escala = 1f
    private var anchoPx = 0
    private var altoPx = 0

    // Vistas principales
    private lateinit var raiz: FrameLayout
    private lateinit var splash: FrameLayout
    private lateinit var splashTitulo: TextView
    private lateinit var splashSub: TextView
    private lateinit var tvVelocidad: TextView
    private lateinit var tvUnidad: TextView
    private lateinit var tvGps: TextView
    private lateinit var puntoGps: View
    private lateinit var tvBusqueda: TextView
    private lateinit var btnNoche: Icono
    private lateinit var btnCentrar: Icono
    private lateinit var btnBrujula: Icono
    private lateinit var izqAbajo: LinearLayout
    private lateinit var derAbajo: LinearLayout
    private lateinit var pillBusqueda: LinearLayout
    private lateinit var bannerNav: LinearLayout
    private lateinit var icoNav: Icono
    private lateinit var tvNavDist: TextView
    private lateinit var tvNavTexto: TextView
    private lateinit var barraNav: LinearLayout
    private lateinit var tvNavEta: TextView
    private lateinit var tvNavDistR: TextView
    private lateinit var tvNavTiempo: TextView
    private lateinit var barraProgreso: Barra
    private lateinit var filaAlt: LinearLayout
    private lateinit var etDlg2: EditText
    private lateinit var scrollDlgListas: HorizontalScrollView
    private lateinit var filaDlgListas: LinearLayout
    private lateinit var btnPrimario: TextView
    private lateinit var btnSimular: TextView
    private lateinit var btnDescargar: TextView
    private lateinit var btnCancelarBajada: TextView
    private lateinit var panelDialogo: FrameLayout
    private lateinit var cardDlg: LinearLayout
    private lateinit var tvDlgTitulo: TextView
    private lateinit var tvDlgTexto: TextView
    private lateinit var etDlg: EditText
    private lateinit var filaDlgChips: LinearLayout
    private lateinit var btnDlgOk: TextView
    private lateinit var btnDlgCancel: TextView

    // Tarjeta de instalación inicial / progreso
    private lateinit var tarjetaMapa: LinearLayout
    private lateinit var tvMapaTitulo: TextView
    private lateinit var tvMapaTexto: TextView
    private lateinit var tvMapaError: TextView
    private lateinit var barraCopia: Barra
    private lateinit var tvPct: TextView
    private lateinit var filaBotonesMapa: LinearLayout

    // Tarjeta del lugar elegido
    private lateinit var tarjetaLugar: LinearLayout
    private lateinit var tvLugarTitulo: TextView
    private lateinit var tvLugarSub: TextView
    private lateinit var btnFav: Icono

    // Búsqueda
    private lateinit var panelBusqueda: FrameLayout
    private lateinit var cardBusqueda: LinearLayout
    private lateinit var etBusqueda: EditText
    private lateinit var listaResultados: LinearLayout
    private lateinit var scrollResultados: ScrollView
    private var busquedaId = 0
    private var ultimosResultados: List<Resultado> = emptyList()
    private var ultimaConsulta = ""
    private var ultimoOrigen = ""

    // Ajustes
    private lateinit var panelAjustes: FrameLayout
    private lateinit var hojaAjustes: LinearLayout
    private lateinit var contenidoAjustes: LinearLayout
    private lateinit var filaTabs: LinearLayout
    private var tabActual = 0
    private var tvDiagAcerca: TextView? = null
    private var barraTab: Barra? = null
    private var tvTabInstal: TextView? = null

    private val tarjetas = ArrayList<Pair<View, Int>>()                    // vista + radio de esquina (dp)
    private val botones = ArrayList<Icono>()
    private val botonesTexto = ArrayList<Pair<TextView, Boolean>>()        // vista + es primario
    private val iconos = ArrayList<Icono>()
    private var splashOculto = false

    /** dp escalados según el tamaño de la pantalla. */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density * escala).toInt()

    private fun paleta(): Paleta =
        if (night) Paleta(
            fondo = Color.parseColor("#F0162750"), fondoClaro = Color.parseColor("#1F3263"),
            borde = Color.parseColor("#33FFFFFF"), texto = Color.parseColor("#F1F5FF"),
            sub = Color.parseColor("#A3B3D6"), acento = Color.parseColor("#4C8DF6"),
            acento2 = Color.parseColor("#7C6CF2"), pantalla = Color.parseColor("#12213F"),
            pantalla2 = Color.parseColor("#1F3566"), hoja = Color.parseColor("#122244")
        ) else Paleta(
            fondo = Color.parseColor("#F2FFFFFF"), fondoClaro = Color.parseColor("#FFFFFF"),
            borde = Color.parseColor("#14000000"), texto = Color.parseColor("#0F172A"),
            sub = Color.parseColor("#64748B"), acento = Color.parseColor("#2563EB"),
            acento2 = Color.parseColor("#4F46E5"), pantalla = Color.parseColor("#F3F1EC"),
            pantalla2 = Color.parseColor("#E4EAF4"), hoja = Color.parseColor("#F4F6FA")
        )

    private fun calcularNoche(): Boolean = when (modoTema) {
        1 -> false
        2 -> true
        else -> {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            h < 6 || h >= 18
        }
    }

    private fun carpetaApp(): File = getExternalFilesDir(null) ?: filesDir

    private fun memoriaTotalMb(): Long {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.totalMem / 1048576
    }

    private fun aviso(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    }

    // ───────────────────────── ciclo de vida ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tCreate = ms()

        val dm = resources.displayMetrics
        val ladoCortoDp = Math.min(dm.heightPixels, dm.widthPixels) / dm.density
        escala = (ladoCortoDp / 460f).coerceIn(0.62f, 1.12f)

        modoTema = prefs.getInt("tema", 0)
        pantallaOn = prefs.getBoolean("pantalla", true)
        vozOn = prefs.getBoolean("voz", true)
        inclinado = prefs.getBoolean("inclinado", memoriaTotalMb() >= 3500L)
        marcadorTipo = prefs.getInt("marcador", 1)
        velVoz = prefs.getFloat("voz_vel", 0.95f)
        vozNombre = prefs.getString("voz_nombre", "") ?: ""
        night = calcularNoche()
        if (pantallaOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
        ajustarAnchos()
        aplicarTema()
        leerInstalados()
        pedirPermisoUbicacion()
        // Si por algún motivo el mapa no termina de dibujarse, no dejes la pantalla de carga para siempre
        ui.postDelayed({ ocultarSplash() }, 12000L)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ajustarAnchos()
        ajustarColumnas()
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
    override fun onDestroy() {
        tts?.shutdown()
        mapView?.onDestroy()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            panelDialogo.visibility == View.VISIBLE -> cerrarDialogo()
            panelBusqueda.visibility == View.VISIBLE -> cerrarBusqueda()
            panelAjustes.visibility == View.VISIBLE -> cerrarAjustes()
            tarjetaLugar.visibility == View.VISIBLE -> ocultarLugar()
            else -> super.onBackPressed()
        }
    }

    // ───────────────────────── vistas propias ─────────────────────────

    /** Botón redondo flotante o ícono suelto: dibuja un vector (Material Symbols) teñido según el tema. */
    private inner class Icono(
        ctx: Context, var res: Int, private val circular: Boolean, private val relleno: Float = 0f
    ) : View(ctx) {
        var activo = false        // ícono en color de acento
        var destacado = false     // botón relleno de acento con ícono blanco
        var tinte = 0             // color propio (0 = según tema)
        private var cacheRes = 0
        private var cache: Drawable? = null

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (cache == null || cacheRes != res) {
                cache = context.getDrawable(res)?.mutate()
                cacheRes = res
            }
            val d = cache ?: return
            val pal = paleta()
            val col = when {
                destacado -> Color.WHITE
                tinte != 0 -> tinte
                activo -> pal.acento
                else -> pal.texto
            }
            d.setTint(col)
            val p = (width * relleno).toInt()
            d.setBounds(p, p, width - p, height - p)
            d.draw(c)
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

    /** Interruptor (activado/desactivado) con el estilo de la app. */
    private inner class Interruptor(ctx: Context, var on: Boolean) : View(ctx) {
        var cambio: ((Boolean) -> Unit)? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        init {
            setOnClickListener {
                on = !on
                invalidate()
                cambio?.invoke(on)
            }
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val pal = paleta()
            val w = width.toFloat()
            val h = height.toFloat()
            p.color = if (on) pal.acento else if (night) Color.parseColor("#475569") else Color.parseColor("#CBD5E1")
            rect.set(0f, 0f, w, h)
            c.drawRoundRect(rect, h / 2f, h / 2f, p)
            p.color = Color.WHITE
            val r = h / 2f - dp(3)
            c.drawCircle(if (on) w - h / 2f else h / 2f, h / 2f, r, p)
        }
    }

    // ───────────────────────── constructores de interfaz ─────────────────────────

    private fun texto(sp: Float, fuente: String = "sans-serif"): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * escala)
        typeface = Typeface.create(fuente, Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun txt(sp: Float, fuente: String, color: Int): TextView =
        texto(sp, fuente).also { it.setTextColor(color) }

    /** Tarjeta que se vuelve a pintar sola al cambiar de tema. */
    private fun tarjeta(radio: Int): LinearLayout {
        val t = LinearLayout(this)
        tarjetas.add(Pair(t, radio))
        return t
    }

    /** Tarjeta para el contenido de los paneles (se reconstruye al cambiar de tema). */
    private fun caja(radio: Int, padH: Int, padV: Int): LinearLayout {
        val pal = paleta()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
            background = GradientDrawable().apply {
                cornerRadius = dp(radio).toFloat()
                setColor(pal.fondoClaro)
                setStroke(dp(1), pal.borde)
            }
        }
    }

    private fun botonRedondo(res: Int, tam: Int, accion: () -> Unit): Icono {
        val b = Icono(this, res, true, 0.27f)
        b.layoutParams = LinearLayout.LayoutParams(dp(tam), dp(tam)).apply { setMargins(0, dp(6), 0, dp(6)) }
        b.setOnClickListener { accion() }
        botones.add(b)
        iconos.add(b)
        return b
    }

    private fun estilarBotonTexto(v: TextView, primario: Boolean) {
        val pal = paleta()
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

    private fun crearBotonTexto(t: String, primario: Boolean, accion: () -> Unit): TextView {
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
        estilarBotonTexto(v, primario)
        return v
    }

    /** Botón de texto que se vuelve a pintar solo al cambiar de tema (para vistas permanentes). */
    private fun botonTexto(t: String, primario: Boolean, accion: () -> Unit): TextView {
        val v = crearBotonTexto(t, primario, accion)
        botonesTexto.add(Pair(v, primario))
        return v
    }

    private fun chip(t: String, activo: Boolean, accion: () -> Unit): TextView {
        val pal = paleta()
        val v = texto(14f, "sans-serif-medium")
        v.text = t
        v.gravity = Gravity.CENTER
        v.setPadding(dp(16), dp(9), dp(16), dp(9))
        v.isClickable = true
        v.setOnClickListener { accion() }
        if (activo) {
            v.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(pal.acento, pal.acento2)).apply {
                cornerRadius = dp(20).toFloat()
            }
            v.setTextColor(Color.WHITE)
        } else {
            v.background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(pal.fondoClaro)
                setStroke(dp(1), pal.borde)
            }
            v.setTextColor(pal.sub)
        }
        return v
    }

    private fun icono(res: Int, tam: Int, tinte: Int = 0): Icono {
        val i = Icono(this, res, false, 0f)
        i.tinte = tinte
        i.layoutParams = LinearLayout.LayoutParams(dp(tam), dp(tam))
        return i
    }

    // ───────────────────────── interfaz principal ─────────────────────────

    private fun crearInterfaz() {
        val mw = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT

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
            setOnClickListener { abrirBusqueda() }
        }
        val icoBuscar = icono(R.drawable.ic_search, 24).also { iconos.add(it) }
        (icoBuscar.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14)
        pill.addView(icoBuscar)
        tvBusqueda = texto(16f).apply { text = "¿A dónde vamos?" }
        pill.addView(tvBusqueda)
        fila.addView(pill, LinearLayout.LayoutParams(0, dp(56), 1f).apply { leftMargin = dp(12) })
        pillBusqueda = pill

        // Banner de la próxima maniobra (reemplaza a la búsqueda mientras navegas)
        bannerNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(18), dp(10))
            visibility = View.GONE
            elevation = dp(8).toFloat()
        }
        icoNav = icono(R.drawable.ic_straight, 46)
        icoNav.destacado = true
        (icoNav.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14)
        iconos.add(icoNav)
        bannerNav.addView(icoNav)
        val colNav = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvNavDist = texto(30f, "sans-serif-medium").apply { setTextColor(Color.WHITE) }
        tvNavTexto = texto(14f).apply {
            setTextColor(Color.parseColor("#E6FFFFFF"))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }
        colNav.addView(tvNavDist)
        colNav.addView(tvNavTexto)
        bannerNav.addView(colNav, LinearLayout.LayoutParams(0, wc, 1f))
        fila.addView(bannerNav, LinearLayout.LayoutParams(0, wc, 1f).apply { leftMargin = dp(12) })
        raiz.addView(fila, FrameLayout.LayoutParams(mw, wc, Gravity.TOP).apply {
            setMargins(dp(14), dp(14), dp(14), 0)
        })

        // ── Abajo a la izquierda: modo día/noche y ajustes ──
        izqAbajo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        btnNoche = botonRedondo(R.drawable.ic_dark_mode, 48) { alternarNoche() }
        izqAbajo.addView(btnNoche)
        izqAbajo.addView(botonRedondo(R.drawable.ic_settings, 48) { abrirAjustes(0) })
        raiz.addView(izqAbajo, FrameLayout.LayoutParams(wc, wc, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(14), 0, 0, dp(30))
        })

        // ── Abajo a la derecha: zoom y centrar ──
        derAbajo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        btnBrujula = botonRedondo(R.drawable.ic_explore, 50) { alternarOrientacion() }
        btnBrujula.activo = true
        derAbajo.addView(btnBrujula)
        derAbajo.addView(botonRedondo(R.drawable.ic_add, 50) { map?.animateCamera(CameraUpdateFactory.zoomIn()) })
        derAbajo.addView(botonRedondo(R.drawable.ic_remove, 50) { map?.animateCamera(CameraUpdateFactory.zoomOut()) })
        btnCentrar = botonRedondo(R.drawable.ic_near_me, 62) { centrar() }
        btnCentrar.destacado = true
        derAbajo.addView(btnCentrar)
        raiz.addView(derAbajo, FrameLayout.LayoutParams(wc, wc, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(14), dp(24))
        })

        crearTarjetaLugar()
        crearBarraNav()
        crearTarjetaMapa()
        crearPanelBusqueda()
        crearPanelAjustes()
        crearPanelDialogo()
        crearSplash()
    }

    private fun crearTarjetaLugar() {
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        tarjetaLugar = tarjeta(26).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(14), dp(14))
            visibility = View.GONE
        }
        val arriba = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ico = icono(R.drawable.ic_place, 28, Color.parseColor("#EF4444"))
        (ico.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(12)
        arriba.addView(ico)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvLugarTitulo = texto(17f, "sans-serif-medium").apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        tvLugarSub = texto(12f).apply { setPadding(0, dp(3), 0, 0) }
        col.addView(tvLugarTitulo)
        col.addView(tvLugarSub)
        arriba.addView(col, LinearLayout.LayoutParams(0, wc, 1f))
        val cerrar = icono(R.drawable.ic_close, 30)
        iconos.add(cerrar)
        cerrar.setOnClickListener { ocultarLugar() }
        arriba.addView(cerrar)
        tarjetaLugar.addView(arriba)
        filaAlt = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            visibility = View.GONE
        }
        tarjetaLugar.addView(filaAlt)

        val abajo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        btnPrimario = botonTexto("Cómo llegar", true) { accionPrimaria() }
        abajo.addView(btnPrimario)
        abajo.addView(
            botonTexto("Estoy aquí", false) { fijarPosicionPrueba() },
            LinearLayout.LayoutParams(wc, wc).apply { leftMargin = dp(8) }
        )
        abajo.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        btnFav = icono(R.drawable.ic_star_line, 34)
        iconos.add(btnFav)
        btnFav.setOnClickListener { alternarFavorito() }
        abajo.addView(btnFav)
        tarjetaLugar.addView(abajo)
        raiz.addView(tarjetaLugar, FrameLayout.LayoutParams(dp(480), wc, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(24)
        })
    }

    private fun columnaDato(etiqueta: String, valor: TextView): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val e = texto(11.5f, "sans-serif-medium").apply {
            text = etiqueta
            letterSpacing = 0.08f
        }
        etiquetasSub.add(e)
        col.addView(e)
        valor.setPadding(0, dp(2), 0, 0)
        col.addView(valor)
        return col
    }

    /** Panel inferior durante la navegación: avance de la ruta, hora de llegada, distancia y tiempo restantes. */
    private fun crearBarraNav() {
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        val mw = ViewGroup.LayoutParams.MATCH_PARENT
        barraNav = tarjeta(26).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(16), dp(12))
            visibility = View.GONE
        }
        barraProgreso = Barra(this)
        barraNav.addView(barraProgreso, LinearLayout.LayoutParams(mw, dp(6)))
        tvNavEta = texto(21f, "sans-serif-medium")
        tvNavDistR = texto(21f, "sans-serif-medium")
        tvNavTiempo = texto(21f, "sans-serif-medium")
        val datos = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        datos.addView(columnaDato("LLEGADA", tvNavEta), LinearLayout.LayoutParams(0, wc, 1f))
        datos.addView(columnaDato("DISTANCIA", tvNavDistR), LinearLayout.LayoutParams(0, wc, 1f))
        datos.addView(columnaDato("TIEMPO", tvNavTiempo), LinearLayout.LayoutParams(0, wc, 1f))
        barraNav.addView(datos)
        val acciones = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }
        btnSimular = botonTexto("Simular", false) { alternarSimulacion() }
        acciones.addView(btnSimular, LinearLayout.LayoutParams(wc, wc).apply { rightMargin = dp(8) })
        acciones.addView(botonTexto("Salir", true) { finalizarNavegacion(false) })
        barraNav.addView(acciones)
        raiz.addView(barraNav, FrameLayout.LayoutParams(dp(480), wc, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(24)
        })
    }

    private fun crearTarjetaMapa() {
        val mw = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
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
        val conDescarga = Config.MANIFIESTO_URL.isNotEmpty()
        filaBotonesMapa = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        btnDescargar = botonTexto("Descargar mapas", true) { descargarMapas() }
        btnDescargar.visibility = if (conDescarga) View.VISIBLE else View.GONE
        filaBotonesMapa.addView(btnDescargar, LinearLayout.LayoutParams(wc, wc).apply { bottomMargin = dp(10) })
        val fila2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila2.addView(
            botonTexto("Elegir archivos", !conDescarga) { elegirArchivos() },
            LinearLayout.LayoutParams(wc, wc).apply { rightMargin = dp(10) }
        )
        fila2.addView(botonTexto("Buscar en el pendrive", false) { buscarEnPendrive() })
        filaBotonesMapa.addView(fila2)
        btnCancelarBajada = botonTexto("Cancelar descarga", false) {
            cancelarBajada = true
            aviso("Cancelando…")
        }
        btnCancelarBajada.visibility = View.GONE
        tarjetaMapa.addView(tvMapaTitulo)
        tarjetaMapa.addView(tvMapaTexto)
        tarjetaMapa.addView(tvMapaError)
        tarjetaMapa.addView(barraCopia, LinearLayout.LayoutParams(mw, dp(8)).apply { topMargin = dp(16) })
        tarjetaMapa.addView(tvPct)
        tarjetaMapa.addView(btnCancelarBajada, LinearLayout.LayoutParams(wc, wc).apply { topMargin = dp(14) })
        tarjetaMapa.addView(filaBotonesMapa, LinearLayout.LayoutParams(wc, wc).apply { topMargin = dp(18) })
        raiz.addView(tarjetaMapa, FrameLayout.LayoutParams(dp(560), wc, Gravity.CENTER))
    }

    // ───────────────────────── diálogo propio (guardar, renombrar, confirmar) ─────────────────────────

    private fun crearPanelDialogo() {
        val mw = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        panelDialogo = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#99000000"))
            isClickable = true
            setOnClickListener { cerrarDialogo() }
        }
        cardDlg = tarjeta(28).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(18))
            isClickable = true
        }
        tvDlgTitulo = texto(20f, "sans-serif-medium")
        tvDlgTexto = texto(14f).apply {
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(0f, 1.15f)
        }
        filaDlgChips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        etDlg = EditText(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * escala)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnEditorActionListener { _, _, _ ->
                btnDlgOk.performClick()
                true
            }
        }
        etDlg2 = EditText(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * escala)
            hint = "Lista (por ejemplo: Restaurantes)"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnEditorActionListener { _, _, _ ->
                btnDlgOk.performClick()
                true
            }
        }
        filaDlgListas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scrollDlgListas = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(10), 0, 0)
        }
        scrollDlgListas.addView(filaDlgListas)
        val filaBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnDlgCancel = botonTexto("Cancelar", false) { cerrarDialogo() }
        btnDlgOk = botonTexto("Guardar", true) {
            val t = etDlg.text.toString().trim()
            dlgListaTexto = etDlg2.text.toString().trim()
            val cb = dlgOk
            val cat = dlgCat
            cerrarDialogo()
            cb?.invoke(t, cat)
        }
        filaBtns.addView(btnDlgCancel, LinearLayout.LayoutParams(wc, wc).apply { rightMargin = dp(10) })
        filaBtns.addView(btnDlgOk)
        cardDlg.addView(tvDlgTitulo)
        cardDlg.addView(tvDlgTexto)
        cardDlg.addView(filaDlgChips)
        cardDlg.addView(etDlg, LinearLayout.LayoutParams(mw, wc).apply { topMargin = dp(14) })
        cardDlg.addView(etDlg2, LinearLayout.LayoutParams(mw, wc).apply { topMargin = dp(10) })
        cardDlg.addView(scrollDlgListas, LinearLayout.LayoutParams(mw, wc))
        cardDlg.addView(filaBtns, LinearLayout.LayoutParams(mw, wc).apply { topMargin = dp(18) })
        panelDialogo.addView(cardDlg, FrameLayout.LayoutParams(dp(520), wc, Gravity.CENTER))
        raiz.addView(panelDialogo, FrameLayout.LayoutParams(mw, mw))
    }

    private var dlgOk: ((String, Int) -> Unit)? = null
    private var dlgCat = 0
    private var dlgCats: List<String>? = null
    private var dlgCampoSoloFav = false
    private var dlgTieneCampo = false
    private var dlgListaInicial: String? = null
    private var dlgListaTexto = ""

    private fun actualizarDialogo() {
        filaDlgChips.removeAllViews()
        val cats = dlgCats
        if (cats == null) {
            filaDlgChips.visibility = View.GONE
        } else {
            filaDlgChips.visibility = View.VISIBLE
            for (i in cats.indices) {
                filaDlgChips.addView(
                    chip(cats[i], i == dlgCat) {
                        dlgCat = i
                        actualizarDialogo()
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        rightMargin = dp(8)
                    }
                )
            }
        }
        etDlg.visibility = if (!dlgTieneCampo || (dlgCampoSoloFav && dlgCat != 0)) View.GONE else View.VISIBLE
        val verLista = dlgListaInicial != null && dlgTieneCampo && (!dlgCampoSoloFav || dlgCat == 0)
        etDlg2.visibility = if (verLista) View.VISIBLE else View.GONE
        scrollDlgListas.visibility = if (verLista) View.VISIBLE else View.GONE
        if (verLista) {
            filaDlgListas.removeAllViews()
            val actual = etDlg2.text.toString().trim()
            for (n in leerListas()) {
                filaDlgListas.addView(
                    chip(n, n == actual) {
                        etDlg2.setText(n)
                        etDlg2.setSelection(n.length)
                        actualizarDialogo()
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        rightMargin = dp(8)
                    }
                )
            }
        }
    }

    /** Muestra un diálogo con título, texto, campo opcional, categorías opcionales y lista opcional. */
    private fun mostrarDialogo(
        titulo: String, texto: String, campo: String?, categorias: List<String>?,
        textoOk: String, campoSoloFav: Boolean = false, listaInicial: String? = null, ok: (String, Int) -> Unit
    ) {
        tvDlgTitulo.text = titulo
        tvDlgTexto.text = texto
        tvDlgTexto.visibility = if (texto.isEmpty()) View.GONE else View.VISIBLE
        dlgCats = categorias
        dlgCat = 0
        dlgCampoSoloFav = campoSoloFav
        dlgTieneCampo = campo != null
        dlgListaInicial = listaInicial
        dlgListaTexto = listaInicial ?: ""
        etDlg.setText(campo ?: "")
        etDlg.setSelection(etDlg.text.length)
        etDlg2.setText(listaInicial ?: "")
        btnDlgOk.text = textoOk
        dlgOk = ok
        actualizarDialogo()
        panelDialogo.visibility = View.VISIBLE
        panelDialogo.alpha = 0f
        panelDialogo.animate().alpha(1f).setDuration(140L).start()
        if (campo != null) {
            etDlg.requestFocus()
            ui.postDelayed({
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(etDlg, InputMethodManager.SHOW_IMPLICIT)
            }, 150L)
        }
    }

    private fun cerrarDialogo() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etDlg.windowToken, 0)
        panelDialogo.animate().alpha(0f).setDuration(120L).withEndAction { panelDialogo.visibility = View.GONE }.start()
    }

    private fun crearPanelBusqueda() {
        val mw = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        panelBusqueda = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#80000000"))
            isClickable = true
            setOnClickListener { cerrarBusqueda() }
        }
        cardBusqueda = tarjeta(26).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
        }
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(6), dp(10), dp(6))
        }
        val lupa = icono(R.drawable.ic_search, 24).also { iconos.add(it) }
        (lupa.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(12)
        fila.addView(lupa)
        etBusqueda = EditText(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * escala)
            hint = "Busca un lugar, barrio o calle"
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(0, dp(10), 0, dp(10))
            setOnEditorActionListener { _, _, _ ->
                ocultarTeclado()
                true
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    ui.removeCallbacks(ejecutarBusqueda)
                    ui.postDelayed(ejecutarBusqueda, 180L)
                }
            })
        }
        fila.addView(etBusqueda, LinearLayout.LayoutParams(0, wc, 1f))
        val borrar = icono(R.drawable.ic_close, 32).also { iconos.add(it) }
        borrar.setOnClickListener {
            if (etBusqueda.text.isNotEmpty()) etBusqueda.setText("") else cerrarBusqueda()
        }
        fila.addView(borrar)
        cardBusqueda.addView(fila)
        scrollResultados = ScrollView(this)
        listaResultados = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollResultados.addView(listaResultados, FrameLayout.LayoutParams(mw, wc))
        cardBusqueda.addView(scrollResultados, LinearLayout.LayoutParams(mw, dp(300)))
        panelBusqueda.addView(cardBusqueda, FrameLayout.LayoutParams(dp(640), wc, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(14)
        })
        raiz.addView(panelBusqueda, FrameLayout.LayoutParams(mw, mw))
    }

    private fun crearPanelAjustes() {
        val mw = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        panelAjustes = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#80000000"))
            isClickable = true
            setOnClickListener { cerrarAjustes() }
        }
        hojaAjustes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            elevation = dp(12).toFloat()
        }
        val cab = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(16), dp(12), dp(6))
        }
        val titulo = texto(22f, "sans-serif-medium").apply { text = "Ajustes" }
        tituloAjustes = titulo
        cab.addView(titulo, LinearLayout.LayoutParams(0, wc, 1f))
        val cerrar = botonRedondo(R.drawable.ic_close, 42) { cerrarAjustes() }
        (cerrar.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        botones.remove(cerrar)             // el panel de ajustes lo pinta él mismo
        cerrarAjustesBtn = cerrar
        cab.addView(cerrar)
        hojaAjustes.addView(cab)

        val scrollTabs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        filaTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(6), dp(18), dp(10))
        }
        scrollTabs.addView(filaTabs)
        hojaAjustes.addView(scrollTabs)

        val scroll = ScrollView(this)
        contenidoAjustes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(0), dp(18), dp(24))
        }
        scroll.addView(contenidoAjustes, FrameLayout.LayoutParams(mw, wc))
        hojaAjustes.addView(scroll, LinearLayout.LayoutParams(mw, 0, 1f))

        panelAjustes.addView(hojaAjustes, FrameLayout.LayoutParams(dp(560), mw, Gravity.END).apply {
            setMargins(dp(10), dp(10), dp(10), dp(10))
        })
        raiz.addView(panelAjustes, FrameLayout.LayoutParams(mw, mw))
    }

    private var tituloAjustes: TextView? = null
    private var cerrarAjustesBtn: Icono? = null

    private fun crearSplash() {
        val mw = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        splash = FrameLayout(this)
        // Una columna que ocupa todo el ancho: cada elemento queda centrado sobre el mismo eje que el logo
        val centro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        centro.addView(logo, LinearLayout.LayoutParams(dp(124), dp(124)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        splashTitulo = texto(30f, "sans-serif-medium").apply {
            text = "GPS 593"
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            // El espaciado entre letras también se añade tras la última: se compensa para que quede centrado de verdad
            setPadding((textSize * 0.12f).toInt(), dp(16), 0, 0)
        }
        splashSub = texto(14f).apply {
            text = "Versión nativa $VERSION"
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, dp(6), 0, 0)
        }
        val barra = Barra(this).apply { indeterminada = true }
        centro.addView(splashTitulo, LinearLayout.LayoutParams(mw, wc))
        centro.addView(splashSub, LinearLayout.LayoutParams(mw, wc))
        centro.addView(barra, LinearLayout.LayoutParams(dp(170), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(24)
        })
        splash.addView(centro, FrameLayout.LayoutParams(mw, wc, Gravity.CENTER))
        raiz.addView(splash, FrameLayout.LayoutParams(mw, mw))
    }

    /** Ajusta anchos al tamaño actual de la pantalla (también al girarla). */
    private fun ajustarAnchos() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        anchoPx = w
        altoPx = h
        (tarjetaMapa.layoutParams as FrameLayout.LayoutParams).width = Math.min(dp(560), (w * 0.88f).toInt())
        (hojaAjustes.layoutParams as FrameLayout.LayoutParams).width =
            if (w > h) Math.min(dp(560), (w * 0.62f).toInt()) else ViewGroup.LayoutParams.MATCH_PARENT
        (cardBusqueda.layoutParams as FrameLayout.LayoutParams).width = Math.min(dp(640), w - dp(28))
        (scrollResultados.layoutParams as LinearLayout.LayoutParams).height = Math.min(dp(320), (h * 0.5f).toInt())
        val anchoInf = if (w < dp(760)) w - dp(28) else Math.min(dp(520), w - dp(180))
        (tarjetaLugar.layoutParams as FrameLayout.LayoutParams).width = anchoInf
        (barraNav.layoutParams as FrameLayout.LayoutParams).width = anchoInf
        (cardDlg.layoutParams as FrameLayout.LayoutParams).width = Math.min(dp(520), w - dp(28))
        cardDlg.requestLayout()
        barraNav.requestLayout()
        tarjetaMapa.requestLayout()
        hojaAjustes.requestLayout()
        cardBusqueda.requestLayout()
        tarjetaLugar.requestLayout()
    }

    /** En pantallas estrechas, sube los botones laterales cuando la tarjeta del lugar ocupa el fondo. */
    private fun ajustarColumnas() {
        tarjetaLugar.post {
            val estrecho = anchoPx < dp(760)
            val inf: View? = if (barraNav.visibility == View.VISIBLE) barraNav
            else if (tarjetaLugar.visibility == View.VISIBLE) tarjetaLugar else null
            val sube = if (estrecho && inf != null) -(inf.height + dp(10)).toFloat() else 0f
            derAbajo.animate().translationY(sube).setDuration(150L).start()
            izqAbajo.animate().translationY(sube).setDuration(150L).start()
        }
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
        for ((v, primario) in botonesTexto) estilarBotonTexto(v, primario)
        hojaAjustes.background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(pal.hoja)
            setStroke(dp(1), pal.borde)
        }
        tituloAjustes?.setTextColor(pal.texto)
        cerrarAjustesBtn?.let { b ->
            b.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(pal.fondoClaro)
                setStroke(dp(1), pal.borde)
            }
            b.invalidate()
        }
        tvVelocidad.setTextColor(pal.texto)
        tvUnidad.setTextColor(pal.sub)
        tvGps.setTextColor(pal.sub)
        tvBusqueda.setTextColor(pal.sub)
        tvMapaTitulo.setTextColor(pal.texto)
        tvMapaTexto.setTextColor(pal.sub)
        tvPct.setTextColor(pal.sub)
        tvLugarTitulo.setTextColor(pal.texto)
        tvLugarSub.setTextColor(pal.sub)
        bannerNav.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(pal.acento, pal.acento2)).apply {
            cornerRadius = dp(26).toFloat()
        }
        tvNavEta.setTextColor(pal.texto)
        tvNavDistR.setTextColor(pal.texto)
        tvNavTiempo.setTextColor(pal.texto)
        for (e in etiquetasSub) e.setTextColor(pal.sub)
        etBusqueda.setTextColor(pal.texto)
        etBusqueda.setHintTextColor(pal.sub)
        etDlg.setTextColor(pal.texto)
        etDlg.setHintTextColor(pal.sub)
        etDlg.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(pal.fondoClaro)
            setStroke(dp(1), pal.borde)
        }
        etDlg2.setTextColor(pal.texto)
        etDlg2.setHintTextColor(pal.sub)
        etDlg2.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(pal.fondoClaro)
            setStroke(dp(1), pal.borde)
        }
        tvDlgTitulo.setTextColor(pal.texto)
        tvDlgTexto.setTextColor(pal.sub)
        splash.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(pal.pantalla, pal.pantalla2))
        splashTitulo.setTextColor(pal.texto)
        splashSub.setTextColor(pal.sub)
        btnNoche.res = if (night) R.drawable.ic_light_mode else R.drawable.ic_dark_mode
        btnCentrar.destacado = follow
        btnCentrar.res = if (follow) R.drawable.ic_near_me else R.drawable.ic_my_location
        pintarBoton(btnCentrar)
        actualizarBotonCentrar()
        for (i in iconos) i.invalidate()
        barraCopia.invalidate()
        if (panelAjustes.visibility == View.VISIBLE) refrescarAjustes()
        if (panelBusqueda.visibility == View.VISIBLE) mostrarResultados(ultimosResultados, ultimaConsulta, ultimoOrigen)
        actualizarEstrella()
    }

    private fun ocultarSplash() {
        if (splashOculto || !::splash.isInitialized) return
        splashOculto = true
        splash.animate().alpha(0f).setDuration(350L).withEndAction { splash.visibility = View.GONE }.start()
    }

    private fun alternarNoche() {
        modoTema = if (night) 1 else 2
        prefs.edit().putInt("tema", modoTema).apply()
        aplicarModo()
    }

    private fun aplicarModo() {
        night = calcularNoche()
        aplicarTema()
        cargarEstilo()
    }

    private fun ocultarTeclado() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etBusqueda.windowToken, 0)
    }

    // ───────────────────────── permisos ─────────────────────────

    private fun tienePermisoGps(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun tieneAlgunPermiso(): Boolean =
        Build.VERSION.SDK_INT < 23 || tienePermisoGps() ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pedirPermisoUbicacion() {
        if (!tieneAlgunPermiso()) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_PERM_UBICACION
            )
        } else {
            iniciarGps()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM_ALMACEN) escanearPendrive() else iniciarGps()
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
                actualizarBotonCentrar()
            }
        }
        // Mantener pulsado = elegir ese punto del mapa (destino o posición de prueba)
        m.addOnMapLongClickListener { p ->
            seleccionarResultado(Resultado("Punto elegido en el mapa", p.latitude, p.longitude, 99))
            true
        }
        // Vista inicial: todo Ecuador
        if (lastLoc == null) {
            m.cameraPosition = CameraPosition.Builder().target(LatLng(-1.65, -78.4)).zoom(6.2).build()
        }
        cargarEstilo()
    }

    private fun actualizarBotonCentrar() {
        btnCentrar.destacado = follow
        btnCentrar.res = if (follow) R.drawable.ic_near_me else R.drawable.ic_my_location
        pintarBoton(btnCentrar)
        // Brújula: vista libre (mano), norte arriba (brújula) o rumbo de marcha (flecha)
        btnBrujula.res = if (!follow) R.drawable.ic_pan_tool else if (rumboArriba) R.drawable.ic_navigation else R.drawable.ic_explore
        btnBrujula.activo = follow
        btnBrujula.invalidate()
    }

    /** Alterna la vista: norte arriba → rumbo de marcha → vista libre → norte arriba… */
    private fun alternarOrientacion() {
        val msg: String
        if (!follow) {
            follow = true
            rumboArriba = false
            msg = "Norte arriba"
        } else if (!rumboArriba) {
            rumboArriba = true
            msg = "Rumbo de marcha"
        } else {
            follow = false
            rumboArriba = false
            msg = "Vista libre"
        }
        actualizarBotonCentrar()
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        val m = map
        val p = posMostrada
        if (follow && p != null) {
            dibujar(p[0], p[1], rumboMostrado)
        } else if (!follow && m != null) {
            m.animateCamera(
                CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(m.cameraPosition).bearing(0.0).tilt(0.0).build()),
                400
            )
        }
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
            aplicarMarcador(style)
            ponerPosicion(lastLoc)
            ponerDestino()
            ponerRuta()
        }
    }

    private fun centrar() {
        follow = true
        actualizarBotonCentrar()
        if (!tieneAlgunPermiso()) {
            aviso("Permite el acceso a la ubicación para centrar el mapa en tu posición.")
            pedirPermisoUbicacion()
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = lastLoc
        if (l != null && (esSintetica(l) || edadSeg(l) <= 120L)) {
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(l.latitude, l.longitude), 15.0))
            return
        }
        val gpsOn = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (e: Exception) { false }
        val redOn = try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { false }
        if (!gpsOn && !redOn) {
            aviso("La ubicación del equipo está apagada. Actívala para localizarte.")
            try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (e: Exception) { /* sin pantalla de ajustes */ }
            return
        }
        aviso(
            "Aún sin posición. Permiso: ${permisoTexto()}. Proveedores: ${proveedoresTexto()}. " +
                "Si estás en interiores, sal a cielo abierto o mantén pulsado el mapa y toca «Estoy aquí»."
        )
        gpsActivo = false
        tBusquedaGps = 0L
        iniciarGps()
    }

    // ───────────────────────── instalar archivos (mapa y rutas) ─────────────────────────

    private fun leerInstalados() {
        val carpeta = carpetaApp()
        val m = File(carpeta, "ecuador.pmtiles")
        val r = File(carpeta, "ecuador.g593")
        mapPath = if (m.exists() && m.length() > 1000L) m.absolutePath else null
        rutasPath = if (r.exists() && r.length() > 1000L) r.absolutePath else null
        if (mapPath == null) ocultarSplash() else cargarEstilo()
        actualizarTarjetas()
        prepararIndice()
    }

    private fun elegirArchivos() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(i, REQ_ARCHIVOS)
        } catch (e: Exception) {
            aviso("Este equipo no tiene selector de archivos. Usa «Buscar en el pendrive».")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_ARCHIVOS || resultCode != Activity.RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else {
            data.data?.let { uris.add(it) }
        }
        if (uris.isEmpty()) return
        instalar(uris.map { u -> Fuente(nombreUri(u), tamanoUri(u)) { contentResolver.openInputStream(u) } })
    }

    private fun nombreUri(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && !c.isNull(i)) return c.getString(i)
                }
            }
        } catch (e: Exception) { /* sin nombre */ }
        return uri.lastPathSegment ?: "archivo"
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

    private fun buscarEnPendrive() {
        if (instalando) return
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_PERM_ALMACEN)
            return
        }
        escanearPendrive()
    }

    /** Busca el .pmtiles y el .g593 más grandes en pendrives y tarjetas, y los instala. */
    private fun escanearPendrive() {
        if (instalando) return
        instalando = true
        progreso = 0f
        textoInstal = "Buscando archivos en el pendrive…"
        errorInstal = ""
        actualizarTarjetas()
        refrescarAjustes()
        Thread {
            var mapa: File? = null
            var rutas: File? = null
            try {
                val saltar = setOf(
                    "Android", "runtime", "user", "shell", "obb", "asec", "secure", "expand", "pass_through",
                    "installer", "androidwritable", "LOST.DIR", "proc", "sys", "dev"
                )
                val cola = ArrayDeque<Pair<File, Int>>()
                for (r in arrayOf("/storage", "/mnt/media_rw", "/mnt")) cola.add(Pair(File(r), 0))
                val visto = HashSet<String>()
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
                            val a = mapa
                            if (a == null || f.length() > a.length()) mapa = f
                        } else if (f.name.endsWith(".g593", true)) {
                            val a = rutas
                            if (a == null || f.length() > a.length()) rutas = f
                        }
                    }
                }
            } catch (e: Exception) { /* seguimos con lo encontrado */ }
            val fuentes = ArrayList<Fuente>()
            mapa?.let { f -> fuentes.add(Fuente(f.name, f.length()) { FileInputStream(f) }) }
            rutas?.let { f -> fuentes.add(Fuente(f.name, f.length()) { FileInputStream(f) }) }
            ui.post {
                instalando = false
                if (fuentes.isEmpty()) {
                    errorInstal = "No encontré archivos .pmtiles ni .g593 en el pendrive. " +
                        "Conéctalo o usa «Elegir archivos»."
                    actualizarTarjetas()
                    refrescarAjustes()
                } else {
                    instalar(fuentes)
                }
            }
        }.start()
    }

    /** Copia los archivos a la carpeta propia de la app (una sola vez), reconociendo cuál es mapa y cuál rutas. */
    private fun instalar(fuentes: List<Fuente>) {
        if (instalando) return
        instalando = true
        progreso = 0f
        textoInstal = "Preparando…"
        errorInstal = ""
        actualizarTarjetas()
        refrescarAjustes()
        val carpeta = carpetaApp()
        Thread {
            val errores = ArrayList<String>()
            var nuevoMapa = false
            var nuevasRutas = false
            for ((k, f) in fuentes.withIndex()) {
                val tmp = File(carpeta, "instalando.tmp")
                try {
                    // 1) Reconocer el tipo por su cabecera
                    val cab = ByteArray(8)
                    val s0 = f.abrir() ?: throw RuntimeException("no pude abrir «${f.nombre}»")
                    val leidos = s0.use { inp ->
                        var n = 0
                        while (n < 8) {
                            val r = inp.read(cab, n, 8 - n)
                            if (r < 0) break
                            n += r
                        }
                        n
                    }
                    val tipo = when {
                        leidos >= 7 && String(cab, 0, 7, Charsets.US_ASCII) == "PMTiles" -> 0
                        leidos >= 4 && String(cab, 0, 4, Charsets.US_ASCII) == "G593" -> 1
                        else -> throw RuntimeException("«${f.nombre}» no es un archivo .pmtiles ni .g593")
                    }
                    val destinoArchivo = File(carpeta, if (tipo == 0) "ecuador.pmtiles" else "ecuador.g593")
                    val etiqueta = if (tipo == 0) "el mapa" else "las rutas y la búsqueda"
                    if (f.total > 0 && StatFs(carpeta.path).availableBytes < f.total + 50_000_000L) {
                        throw RuntimeException("no hay espacio libre suficiente")
                    }
                    // 2) Copiar con progreso
                    val entrada = f.abrir() ?: throw RuntimeException("no pude abrir «${f.nombre}»")
                    entrada.use { inp ->
                        FileOutputStream(tmp).use { salida ->
                            val buf = ByteArray(1 shl 20)
                            var hecho = 0L
                            var ultimo = 0L
                            var n = inp.read(buf)
                            while (n > 0) {
                                salida.write(buf, 0, n)
                                hecho += n
                                progreso = if (f.total > 0) hecho.toFloat() / f.total.toFloat() else 0f
                                textoInstal = "Instalando $etiqueta (${k + 1} de ${fuentes.size})…"
                                val ahora = SystemClock.elapsedRealtime()
                                if (ahora - ultimo > 200L) {
                                    ultimo = ahora
                                    ui.post { actualizarTarjetas() }
                                }
                                n = inp.read(buf)
                            }
                        }
                    }
                    if (destinoArchivo.exists()) destinoArchivo.delete()
                    if (!tmp.renameTo(destinoArchivo)) throw RuntimeException("no pude guardar el archivo")
                    if (tipo == 0) {
                        nuevoMapa = true
                    } else {
                        nuevasRutas = true
                        File(carpeta, "lugares.idx").delete()      // se vuelve a construir con el archivo nuevo
                    }
                } catch (e: Exception) {
                    tmp.delete()
                    errores.add(e.message ?: "error desconocido")
                }
            }
            ui.post { finalizarInstalacion(errores, nuevoMapa, nuevasRutas) }
        }.start()
    }

    // ───────────────────────── descargar mapas desde internet ─────────────────────────

    private fun consultarManifiesto() {
        if (Config.MANIFIESTO_URL.isEmpty() || consultandoManifiesto) return
        consultandoManifiesto = true
        errorManifiesto = ""
        Thread {
            try {
                val m = Descarga.leerManifiesto(Config.MANIFIESTO_URL)
                ui.post {
                    manifiesto = m
                    consultandoManifiesto = false
                    refrescarAjustes()
                }
            } catch (e: Exception) {
                ui.post {
                    consultandoManifiesto = false
                    errorManifiesto = "No pude consultar la versión de los mapas (¿sin internet?)"
                    refrescarAjustes()
                }
            }
        }.start()
    }

    private fun versionInstalada(): String = prefs.getString("ver_mapas", "") ?: ""

    private fun hayActualizacion(): Boolean {
        val m = manifiesto ?: return false
        return m.version.isNotEmpty() && m.version != versionInstalada()
    }

    private fun descargarMapas() {
        if (Config.MANIFIESTO_URL.isEmpty() || instalando) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val conectado = try { cm.activeNetworkInfo?.isConnected == true } catch (e: Exception) { true }
        if (!conectado) {
            aviso("No hay conexión a internet.")
            return
        }
        val medido = try { cm.isActiveNetworkMetered } catch (e: Exception) { false }
        val mb = (manifiesto?.totalBytes() ?: 0L) / 1048576
        if (medido) {
            mostrarDialogo(
                "Descargar con datos móviles",
                "Se descargarán " + (if (mb > 0L) "unos $mb MB" else "los mapas") + ". Se recomienda usar Wi‑Fi.",
                null, null, "Descargar"
            ) { _, _ -> iniciarDescarga() }
        } else {
            iniciarDescarga()
        }
    }

    private fun iniciarDescarga() {
        if (instalando) return
        instalando = true
        bajando = true
        cancelarBajada = false
        progreso = 0f
        textoInstal = "Buscando la última versión…"
        errorInstal = ""
        actualizarTarjetas()
        refrescarAjustes()
        val carpeta = carpetaApp()
        Thread {
            val errores = ArrayList<String>()
            var nuevoMapa = false
            var nuevasRutas = false
            var versionNueva = ""
            try {
                val man = manifiesto ?: Descarga.leerManifiesto(Config.MANIFIESTO_URL)
                versionNueva = man.version
                val totalGeneral = man.totalBytes()
                var antes = 0L
                for ((k, a) in man.archivos.withIndex()) {
                    val etiqueta = if (a.nombre.endsWith(".pmtiles", true)) "el mapa" else "las rutas y la búsqueda"
                    textoInstal = "Descargando $etiqueta (${k + 1} de ${man.archivos.size})…"
                    val parte = File(carpeta, a.nombre + ".part")
                    if (a.bytes > 0L && StatFs(carpeta.path).availableBytes < a.bytes - parte.length() + 50_000_000L) {
                        throw RuntimeException("no hay espacio libre suficiente")
                    }
                    var ultimo = 0L
                    Descarga.bajar(a, parte, { cancelarBajada }) { hecho, total ->
                        progreso = if (totalGeneral > 0L) (antes + hecho).toFloat() / totalGeneral.toFloat()
                        else if (total > 0L) hecho.toFloat() / total.toFloat() else 0f
                        val ahora = SystemClock.elapsedRealtime()
                        if (ahora - ultimo > 250L) {
                            ultimo = ahora
                            ui.post { actualizarTarjetas() }
                        }
                    }
                    antes += a.bytes
                    // Reconocer el tipo por la cabecera antes de instalarlo
                    val cab = ByteArray(8)
                    val leidos = FileInputStream(parte).use { it.read(cab) }
                    val tipo = when {
                        leidos >= 7 && String(cab, 0, 7, Charsets.US_ASCII) == "PMTiles" -> 0
                        leidos >= 4 && String(cab, 0, 4, Charsets.US_ASCII) == "G593" -> 1
                        else -> -1
                    }
                    if (tipo < 0) {
                        parte.delete()
                        throw RuntimeException("«${a.nombre}» no es un archivo de mapa válido")
                    }
                    val destinoArchivo = File(carpeta, if (tipo == 0) "ecuador.pmtiles" else "ecuador.g593")
                    if (destinoArchivo.exists()) destinoArchivo.delete()
                    if (!parte.renameTo(destinoArchivo)) throw RuntimeException("no pude guardar «${a.nombre}»")
                    if (tipo == 0) {
                        nuevoMapa = true
                    } else {
                        nuevasRutas = true
                        File(carpeta, "lugares.idx").delete()
                    }
                }
            } catch (e: Descarga.Cancelada) {
                errores.add("descarga cancelada (si la repites, continúa donde quedó)")
            } catch (e: Exception) {
                errores.add(e.message ?: "error desconocido")
            }
            ui.post {
                bajando = false
                if (errores.isEmpty() && versionNueva.isNotEmpty()) prefs.edit().putString("ver_mapas", versionNueva).apply()
                finalizarInstalacion(errores, nuevoMapa, nuevasRutas)
            }
        }.start()
    }

    /** Se ejecuta en el hilo principal al terminar de instalar (copia o descarga) uno o más archivos. */
    private fun finalizarInstalacion(errores: List<String>, nuevoMapa: Boolean, nuevasRutas: Boolean) {
        val carpeta = carpetaApp()
        instalando = false
        progreso = 1f
        errorInstal = if (errores.isEmpty()) "" else "No pude instalar: " + errores.joinToString("; ")
        val m = File(carpeta, "ecuador.pmtiles")
        val r = File(carpeta, "ecuador.g593")
        mapPath = if (m.exists() && m.length() > 1000L) m.absolutePath else null
        rutasPath = if (r.exists() && r.length() > 1000L) r.absolutePath else null
        if (nuevoMapa) cargarEstilo()
        if (nuevasRutas) {
            indice = null
            grafo = null
            calles = null
            errorGrafo = ""
            prepararIndice()
        }
        if (errores.isEmpty() && (nuevoMapa || nuevasRutas)) aviso("Listo: los archivos quedaron instalados.")
        actualizarTarjetas()
        refrescarAjustes()
    }

    /** Construye (la primera vez) y carga el índice de búsqueda sin internet. */
    private fun prepararIndice() {
        val g = rutasPath?.let { File(it) } ?: return
        if (indice != null || indexando) return
        indexando = true
        errorIndice = ""
        refrescarAjustes()
        Thread {
            try {
                val idx = File(g.parentFile, "lugares.idx")
                val nombresCalles = File(g.parentFile, "calles.idx")
                if (!idx.exists() || !nombresCalles.exists() || idx.lastModified() < g.lastModified()) {
                    IndiceLugares.construir(g, idx)
                }
                var cargado = IndiceLugares.cargar(idx)
                if (cargado == null) {                       // índice de una versión anterior: se reconstruye una vez
                    IndiceLugares.construir(g, idx)
                    cargado = IndiceLugares.cargar(idx)
                }
                val ind: IndiceLugares = cargado ?: throw RuntimeException("índice no válido")
                val cl = Calles.cargar(nombresCalles)
                ui.post {
                    indice = ind
                    calles = cl
                    indexando = false
                    cargandoGrafo = true
                    refrescarAjustes()
                    if (panelBusqueda.visibility == View.VISIBLE) mostrarResultados(ultimosResultados, ultimaConsulta, ultimoOrigen)
                }
                // Motor de rutas: mapea el .g593 y prepara la cuadrícula de calles
                try {
                    val gr = GrafoRutas.abrir(g, cl)
                    ui.post {
                        grafo = gr
                        cargandoGrafo = false
                        refrescarAjustes()
                    }
                } catch (e2: Throwable) {
                    ui.post {
                        cargandoGrafo = false
                        errorGrafo = if (e2 is OutOfMemoryError) "sin memoria para preparar las rutas" else (e2.message ?: "error")
                        refrescarAjustes()
                    }
                }
            } catch (e: Throwable) {
                ui.post {
                    indexando = false
                    errorIndice = if (e is OutOfMemoryError) "sin memoria para preparar la búsqueda" else (e.message ?: "error")
                    refrescarAjustes()
                }
            }
        }.start()
    }

    /** Muestra u oculta la tarjeta central de instalación y actualiza barras de progreso. */
    private fun actualizarTarjetas() {
        val r = mapPath
        val panelAbierto = panelAjustes.visibility == View.VISIBLE || panelBusqueda.visibility == View.VISIBLE
        val mostrar = (instalando || r == null) && !panelAbierto
        tarjetaMapa.visibility = if (mostrar) View.VISIBLE else View.GONE
        if (mostrar) {
            if (instalando) {
                tvMapaTitulo.text = "Instalando archivos"
                tvMapaTexto.text = "Se copian una sola vez. Después todo abre al instante, sin pendrive."
                barraCopia.progreso = progreso
                barraCopia.visibility = View.VISIBLE
                barraCopia.invalidate()
                tvPct.text = textoInstal + (if (progreso > 0f) "  ${(progreso * 100).toInt()} %" else "")
                tvPct.visibility = View.VISIBLE
                filaBotonesMapa.visibility = View.GONE
                btnCancelarBajada.visibility = if (bajando) View.VISIBLE else View.GONE
                tvMapaError.visibility = View.GONE
            } else {
                tvMapaTitulo.text = "Instala el mapa de Ecuador"
                tvMapaTexto.text = if (Config.MANIFIESTO_URL.isNotEmpty())
                    "Descarga el mapa y las rutas de Ecuador (se hace una sola vez; después funciona sin internet). " +
                        "También puedes elegir los archivos ecuador.pmtiles y ecuador.g593 si ya los tienes."
                else "Elige los archivos ecuador.pmtiles (mapa) y ecuador.g593 (rutas y búsqueda). " +
                    "Puedes escoger los dos a la vez; la app los reconoce sola."
                barraCopia.visibility = View.GONE
                tvPct.visibility = View.GONE
                filaBotonesMapa.visibility = View.VISIBLE
                btnCancelarBajada.visibility = View.GONE
                if (errorInstal.isNotEmpty()) {
                    tvMapaError.text = errorInstal
                    tvMapaError.visibility = View.VISIBLE
                } else {
                    tvMapaError.visibility = View.GONE
                }
            }
        }
        barraTab?.let { b ->
            b.progreso = progreso
            b.invalidate()
        }
        tvTabInstal?.text = textoInstal + (if (progreso > 0f && progreso < 1f) "  ${(progreso * 100).toInt()} %" else "")
    }

    // ───────────────────────── búsqueda ─────────────────────────

    private val ejecutarBusqueda = Runnable {
        val q = etBusqueda.text.toString().trim()
        if (q.length < 2) {
            busquedaId++
            mostrarResultados(emptyList(), q, "")
        } else {
            val id = ++busquedaId
            val ind = indice
            // Referencia para ordenar por cercanía: tu posición o, si no la hay, el centro del mapa
            val l = lastLoc
            val centroMapa = map?.cameraPosition?.target
            val rLat = if (l != null) l.latitude else if (centroMapa != null) centroMapa.latitude else Double.NaN
            val rLon = if (l != null) l.longitude else if (centroMapa != null) centroMapa.longitude else Double.NaN
            refLat = rLat
            refLon = rLon
            Thread {
                val lista = ArrayList<Resultado>()
                var fallo = false
                try {
                    if (ind != null) lista.addAll(ind.buscar(q, 10, rLat, rLon))
                } catch (e: Throwable) {
                    fallo = true
                }
                // Si hay pocos resultados sin internet, se completa con la búsqueda en línea (si hay conexión)
                if (lista.size < 6 && q.length >= 3) {
                    try {
                        for (r in BusquedaEnLinea.buscar(q)) {
                            val nr = Texto.norm(r.nombre)
                            val repetido = lista.any { x ->
                                Texto.norm(x.nombre) == nr || Geo.dm(x.lat, x.lon, r.lat, r.lon) < 80.0
                            }
                            if (!repetido) lista.add(r)
                        }
                    } catch (e: Throwable) {
                        if (lista.isEmpty()) fallo = true
                    }
                }
                val guardados = coincidenciasGuardadas(q)
                val todos = guardados + lista.filter { r -> guardados.none { g -> mismoLugar(g, r) } }
                ui.post { if (id == busquedaId) mostrarResultados(todos, q, if (fallo && lista.isEmpty()) "error" else "") }
            }.start()
        }
    }

    private fun abrirBusqueda() {
        if (panelAjustes.visibility == View.VISIBLE) return
        panelBusqueda.visibility = View.VISIBLE
        panelBusqueda.alpha = 0f
        panelBusqueda.animate().alpha(1f).setDuration(160L).start()
        val dfn = definiendo
        etBusqueda.hint = if (dfn != null) "Dirección de tu ${etiquetaLugar(dfn).lowercase(Locale.ROOT)}" else "Busca un lugar, barrio o calle"
        etBusqueda.setText("")
        mostrarResultados(emptyList(), "", "")
        actualizarTarjetas()
        etBusqueda.requestFocus()
        ui.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etBusqueda, InputMethodManager.SHOW_IMPLICIT)
        }, 150L)
    }

    private fun cerrarBusqueda() {
        definiendo = null
        ocultarTeclado()
        panelBusqueda.animate().alpha(0f).setDuration(140L).withEndAction {
            panelBusqueda.visibility = View.GONE
            actualizarTarjetas()
        }.start()
    }

    private fun mostrarResultados(res: List<Resultado>, q: String, origen: String) {
        ultimosResultados = res
        ultimaConsulta = q
        ultimoOrigen = origen
        val pal = paleta()
        listaResultados.removeAllViews()
        if (res.isEmpty()) {
            if (q.length < 2) agregarAtajos(pal)
            val msg = when {
                q.length < 2 && indice != null ->
                    "Escribe un lugar, barrio o calle.\nBúsqueda sin internet lista · ${indice?.total ?: 0} lugares."
                q.length < 2 && indexando -> "Preparando la búsqueda sin internet… (una sola vez)"
                q.length < 2 && rutasPath == null ->
                    "Escribe un lugar, barrio o calle.\nSe busca con internet. Para buscar sin internet instala el archivo .g593 en Ajustes › Mapas."
                q.length < 2 -> "Escribe un lugar, barrio o calle."
                origen == "error" -> "Sin resultados para «$q». Revisa tu conexión a internet."
                else -> "Sin resultados para «$q»."
            }
            val t = txt(14f, "sans-serif", pal.sub)
            t.text = msg
            t.setLineSpacing(0f, 1.2f)
            t.setPadding(dp(20), dp(14), dp(20), dp(18))
            listaResultados.addView(t)
            return
        }
        for (r in res) {
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(11), dp(18), dp(11))
                isClickable = true
                setOnClickListener { seleccionarResultado(r) }
            }
            val guardado = r.detalle.isNotEmpty()
            val icRes = if (guardado && r.nombre == "Casa") R.drawable.ic_home
            else if (guardado && r.nombre == "Trabajo") R.drawable.ic_work
            else if (guardado) R.drawable.ic_star_fill else R.drawable.ic_place
            val ico = icono(icRes, 26, if (guardado && icRes == R.drawable.ic_star_fill) Color.parseColor("#F59E0B") else pal.acento)
            (ico.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14)
            fila.addView(ico)
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val nombre = txt(15f, "sans-serif-medium", pal.texto).apply {
                text = r.nombre
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val sub = txt(12f, "sans-serif", pal.sub).apply {
                text = if (r.detalle.isNotEmpty()) "Guardado · " + r.detalle else {
                    val dist = if (!refLat.isNaN() && !refLon.isNaN()) " · " + Geo.dist(Geo.dm(refLat, refLon, r.lat, r.lon)) else ""
                    r.tipoTexto() + dist + " · " + (if (r.origen.isNotEmpty()) r.origen else origen)
                }
                setPadding(0, dp(2), 0, 0)
            }
            col.addView(nombre)
            col.addView(sub)
            fila.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            listaResultados.addView(fila)
        }
    }

    private fun seleccionarResultado(r: Resultado) {
        val dfn = definiendo
        if (dfn != null) {
            // Se está definiendo casa o trabajo: el lugar elegido se guarda
            definiendo = null
            guardarLugar(dfn, Resultado(etiquetaLugar(dfn), r.lat, r.lon, r.tipo, if (r.detalle.isNotEmpty()) r.detalle else r.nombre))
            aviso("${etiquetaLugar(dfn)} guardada: " + (if (r.detalle.isNotEmpty()) r.detalle else r.nombre))
            if (panelBusqueda.visibility == View.VISIBLE) cerrarBusqueda()
            return
        }
        registrarReciente(r)
        if (modoRuta != 0) cancelarRuta()
        if (panelBusqueda.visibility == View.VISIBLE) cerrarBusqueda()
        if (panelAjustes.visibility == View.VISIBLE) cerrarAjustes()
        destino = r
        ponerDestino()
        follow = false
        actualizarBotonCentrar()
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(r.lat, r.lon), 15.5))
        tvLugarTitulo.text = r.nombre
        tvLugarSub.text = if (r.detalle.isNotEmpty()) r.detalle
        else r.tipoTexto() + String.format(Locale.US, " · %.5f, %.5f", r.lat, r.lon)
        tarjetaLugar.visibility = View.VISIBLE
        actualizarEstrella()
        ajustarColumnas()
    }

    private fun ocultarLugar() {
        if (modoRuta != 0) cancelarRuta()
        destino = null
        ponerDestino()
        tarjetaLugar.visibility = View.GONE
        ajustarColumnas()
    }

    private fun ponerDestino() {
        val d = destino
        val json = if (d == null) "{\"type\":\"FeatureCollection\",\"features\":[]}"
        else "{\"type\":\"Point\",\"coordinates\":[${d.lon},${d.lat}]}"
        estilo?.getSourceAs<GeoJsonSource>("destino")?.setGeoJson(json)
    }

    // ───────────────────────── lugares guardados: listas, casa/trabajo, recientes y rutas frecuentes ─────────────────────────

    private fun etiquetaLugar(clave: String): String = if (clave == "casa") "Casa" else "Trabajo"

    private fun aJson(r: Resultado): JSONObject =
        JSONObject().put("n", r.nombre).put("la", r.lat).put("lo", r.lon).put("t", r.tipo)
            .put("d", r.detalle).put("c", r.categoria).put("l", r.lista)

    private fun deJson(o: JSONObject): Resultado =
        Resultado(o.getString("n"), o.getDouble("la"), o.getDouble("lo"), o.optInt("t", 6), o.optString("d", ""), o.optString("c", ""), "", o.optString("l", ""))

    private fun leerArray(clave: String): MutableList<Resultado> {
        val out = ArrayList<Resultado>()
        try {
            val a = JSONArray(prefs.getString(clave, "[]"))
            for (i in 0 until a.length()) out.add(deJson(a.getJSONObject(i)))
        } catch (e: Exception) { /* lista vacía */ }
        return out
    }

    private fun guardarArray(clave: String, l: List<Resultado>) {
        val a = JSONArray()
        for (r in l) a.put(aJson(r))
        prefs.edit().putString(clave, a.toString()).apply()
    }

    private fun leerFavs(): MutableList<Resultado> = leerArray("favs")
    private fun guardarFavs(l: List<Resultado>) = guardarArray("favs", l)

    /** Casa o trabajo ("casa" / "trabajo"), o null si no está definido. */
    private fun leerLugar(clave: String): Resultado? {
        val s = prefs.getString("lug_$clave", null) ?: return null
        return try { deJson(JSONObject(s)) } catch (e: Exception) { null }
    }

    private fun guardarLugar(clave: String, r: Resultado?) {
        val e = prefs.edit()
        if (r == null) e.remove("lug_$clave") else e.putString("lug_$clave", aJson(r).toString())
        e.apply()
    }

    private fun mismoLugar(a: Resultado, b: Resultado): Boolean =
        Math.abs(a.lat - b.lat) < 1e-5 && Math.abs(a.lon - b.lon) < 1e-5

    private fun esGuardado(d: Resultado): Boolean {
        if (leerFavs().any { mismoLugar(it, d) }) return true
        val casa = leerLugar("casa")
        if (casa != null && mismoLugar(casa, d)) return true
        val trabajo = leerLugar("trabajo")
        return trabajo != null && mismoLugar(trabajo, d)
    }

    // ── listas (carpetas) de favoritos ──

    private fun listaDe(r: Resultado): String = r.lista.ifEmpty { "Favoritos" }

    private fun leerListas(): MutableList<String> {
        val out = ArrayList<String>()
        out.add("Favoritos")
        try {
            val a = JSONArray(prefs.getString("listas", "[]"))
            for (i in 0 until a.length()) {
                val n = a.getString(i)
                if (n.isNotEmpty() && !out.contains(n)) out.add(n)
            }
        } catch (e: Exception) { /* solo la lista por defecto */ }
        for (f in leerFavs()) {
            val n = listaDe(f)
            if (!out.contains(n)) out.add(n)
        }
        return out
    }

    private fun guardarListas(l: List<String>) {
        val a = JSONArray()
        for (n in l) if (n != "Favoritos") a.put(n)
        prefs.edit().putString("listas", a.toString()).apply()
    }

    private fun asegurarLista(n: String) {
        val l = leerListas()
        if (!l.contains(n)) {
            l.add(n)
            guardarListas(l)
        }
    }

    private fun crearLista(n: String) {
        if (n.isEmpty()) return
        asegurarLista(n)
        aviso("Lista «$n» creada")
        refrescarAjustes()
    }

    private fun renombrarLista(viejo: String, nuevo: String) {
        if (nuevo.isEmpty() || nuevo == viejo || viejo == "Favoritos") return
        val l = leerListas()
        val i = l.indexOf(viejo)
        if (i >= 0) l[i] = nuevo
        guardarListas(l)
        val favs = leerFavs()
        for (k in favs.indices) {
            val f = favs[k]
            if (listaDe(f) == viejo) favs[k] = Resultado(f.nombre, f.lat, f.lon, f.tipo, f.detalle, f.categoria, "", nuevo)
        }
        guardarFavs(favs)
        refrescarAjustes()
    }

    /** Borra la lista; sus lugares pasan a "Favoritos" (no se pierde ninguno). */
    private fun borrarLista(n: String) {
        if (n == "Favoritos") return
        val favs = leerFavs()
        for (k in favs.indices) {
            val f = favs[k]
            if (listaDe(f) == n) favs[k] = Resultado(f.nombre, f.lat, f.lon, f.tipo, f.detalle, f.categoria, "", "Favoritos")
        }
        guardarFavs(favs)
        val l = leerListas()
        l.remove(n)
        guardarListas(l)
        refrescarAjustes()
    }

    // ── búsquedas recientes ──

    private fun registrarReciente(r: Resultado) {
        if (r.tipo == 99 && r.nombre.startsWith("Punto")) return
        val l = leerArray("hist_busq")
        l.removeAll { mismoLugar(it, r) }
        l.add(0, Resultado(r.nombre, r.lat, r.lon, r.tipo, r.detalle, r.categoria))
        while (l.size > 15) l.removeAt(l.size - 1)
        guardarArray("hist_busq", l)
    }

    private fun borrarRecientes() {
        prefs.edit().remove("hist_busq").apply()
        refrescarAjustes()
        if (panelBusqueda.visibility == View.VISIBLE) mostrarResultados(emptyList(), "", "")
    }

    // ── rutas frecuentes ──

    private class RutaHist(val dest: Resultado, var veces: Int, var ultima: Long, var dist: Double, var dur: Double)

    private fun leerRutasHist(): MutableList<RutaHist> {
        val out = ArrayList<RutaHist>()
        try {
            val a = JSONArray(prefs.getString("hist_rutas", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(RutaHist(deJson(o.getJSONObject("r")), o.optInt("v", 1), o.optLong("u", 0L), o.optDouble("d", 0.0), o.optDouble("t", 0.0)))
            }
        } catch (e: Exception) { /* sin historial */ }
        return out
    }

    private fun guardarRutasHist(l: List<RutaHist>) {
        val a = JSONArray()
        for (h in l) {
            a.put(JSONObject().put("r", aJson(h.dest)).put("v", h.veces).put("u", h.ultima).put("d", h.dist).put("t", h.dur))
        }
        prefs.edit().putString("hist_rutas", a.toString()).apply()
    }

    /** Anota que empezaste una ruta a este destino (suma una vez más si ya la habías usado). */
    private fun registrarRuta(dest: Resultado, r: Ruta) {
        val l = leerRutasHist()
        val ya = l.firstOrNull { Geo.dm(it.dest.lat, it.dest.lon, dest.lat, dest.lon) < 100.0 }
        if (ya != null) {
            ya.veces += 1
            ya.ultima = System.currentTimeMillis()
            ya.dist = r.distancia
            ya.dur = r.duracion
        } else {
            l.add(RutaHist(Resultado(dest.nombre, dest.lat, dest.lon, dest.tipo, dest.detalle, dest.categoria), 1, System.currentTimeMillis(), r.distancia, r.duracion))
        }
        l.sortWith(compareByDescending<RutaHist> { it.ultima })
        while (l.size > 30) l.removeAt(l.size - 1)
        guardarRutasHist(l)
    }

    private fun rutasFrecuentes(max: Int): List<RutaHist> {
        val l = leerRutasHist()
        l.sortWith(compareByDescending<RutaHist> { it.veces }.thenByDescending { it.ultima })
        return l.take(max)
    }

    private fun borrarRutasHist() {
        prefs.edit().remove("hist_rutas").apply()
        refrescarAjustes()
        if (panelBusqueda.visibility == View.VISIBLE) mostrarResultados(emptyList(), "", "")
    }

    private fun fechaCorta(ms: Long): String {
        if (ms <= 0L) return ""
        val dia = 86_400_000L
        val hoy = System.currentTimeMillis() / dia
        val d = ms / dia
        return when {
            d == hoy -> "hoy"
            d == hoy - 1 -> "ayer"
            else -> java.text.SimpleDateFormat("d MMM", Locale("es")).format(java.util.Date(ms))
        }
    }

    /** Calcula la ruta desde donde estás hasta `r` y empieza a navegar sin pasar por la vista previa. */
    private fun irDirecto(r: Resultado) {
        if (panelAjustes.visibility == View.VISIBLE) {
            panelAjustes.visibility = View.GONE
            tvDiagAcerca = null
            barraTab = null
            tvTabInstal = null
        }
        if (panelBusqueda.visibility == View.VISIBLE) cerrarBusqueda()
        seleccionarResultado(r)
        autoIniciar = true
        calcularRuta(r)
    }

    private fun actualizarEstrella() {
        val d = destino
        val guardado = d != null && esGuardado(d)
        btnFav.res = if (guardado) R.drawable.ic_star_fill else R.drawable.ic_star_line
        btnFav.tinte = if (guardado) Color.parseColor("#F59E0B") else 0
        btnFav.invalidate()
    }

    /** ☆: si el lugar no está guardado abre el diálogo para nombrarlo y elegir lista; si ya lo está, lo quita. */
    private fun alternarFavorito() {
        val d = destino ?: return
        if (esGuardado(d)) {
            val l = leerFavs()
            l.removeAll { mismoLugar(it, d) }
            guardarFavs(l)
            for (clave in arrayOf("casa", "trabajo")) {
                val p = leerLugar(clave)
                if (p != null && mismoLugar(p, d)) guardarLugar(clave, null)
            }
            aviso("Quitado de tus lugares")
            actualizarEstrella()
            if (panelAjustes.visibility == View.VISIBLE) refrescarAjustes()
        } else {
            val base = if (d.detalle.isNotEmpty()) d.detalle else d.nombre
            mostrarDialogo("Guardar lugar", "", base, listOf("Favorito", "Casa", "Trabajo"), "Guardar", true, "Favoritos") { nombre, cat ->
                guardarComo(d, cat, nombre)
            }
        }
    }

    private fun guardarComo(d: Resultado, cat: Int, nombre: String) {
        val original = if (d.detalle.isNotEmpty()) d.detalle else d.nombre
        when (cat) {
            1, 2 -> {
                val clave = if (cat == 1) "casa" else "trabajo"
                guardarLugar(clave, Resultado(etiquetaLugar(clave), d.lat, d.lon, d.tipo, original))
                aviso("Guardado como ${etiquetaLugar(clave)}")
            }
            else -> {
                val lista = dlgListaTexto.ifEmpty { "Favoritos" }
                asegurarLista(lista)
                val l = leerFavs()
                l.removeAll { mismoLugar(it, d) }
                val nom = if (nombre.isNotEmpty()) nombre else original
                l.add(0, Resultado(nom, d.lat, d.lon, d.tipo, original, d.categoria, "", lista))
                guardarFavs(l)
                aviso("Guardado en «$lista» como «$nom»")
            }
        }
        actualizarEstrella()
        if (panelAjustes.visibility == View.VISIBLE) refrescarAjustes()
    }

    private fun renombrarFav(f: Resultado, nuevo: String, lista: String) {
        val l = leerFavs()
        val i = l.indexOfFirst { mismoLugar(it, f) }
        if (i >= 0) {
            val lst = lista.ifEmpty { "Favoritos" }
            asegurarLista(lst)
            l[i] = Resultado(if (nuevo.isNotEmpty()) nuevo else f.nombre, f.lat, f.lon, f.tipo, f.detalle, f.categoria, "", lst)
            guardarFavs(l)
        }
        refrescarAjustes()
    }

    /** Lugares guardados cuyo nombre, dirección o lista contienen lo que se escribe en la búsqueda. */
    private fun coincidenciasGuardadas(q: String): List<Resultado> {
        val nq = Texto.norm(q).trim()
        val out = ArrayList<Resultado>()
        if (nq.length < 2) return out
        for (clave in arrayOf("casa", "trabajo")) {
            val p = leerLugar(clave) ?: continue
            if (Texto.norm(p.nombre).contains(nq) || Texto.norm(p.detalle).contains(nq)) out.add(p)
        }
        for (f in leerFavs()) {
            if (Texto.norm(f.nombre).contains(nq) || Texto.norm(f.detalle).contains(nq) || Texto.norm(listaDe(f)).contains(nq)) out.add(f)
        }
        return out
    }

    /** Empieza a definir casa o trabajo: abre la búsqueda y el próximo lugar que elijas se guarda. */
    private fun definirLugar(clave: String) {
        definiendo = clave
        if (panelAjustes.visibility == View.VISIBLE) {
            panelAjustes.visibility = View.GONE
            tvDiagAcerca = null
            barraTab = null
            tvTabInstal = null
        }
        abrirBusqueda()
    }

    private fun usarUbicacionActual() {
        val clave = definiendo ?: return
        val l = lastLoc
        if (l == null) {
            aviso("Aún no tengo tu posición.")
            return
        }
        definiendo = null
        guardarLugar(clave, Resultado(etiquetaLugar(clave), l.latitude, l.longitude, 99, "Mi ubicación actual"))
        aviso("${etiquetaLugar(clave)} guardada con tu ubicación actual")
        cerrarBusqueda()
    }

    private fun filaAtajo(res: Int, titulo: String, sub: String, tinte: Int, accion: () -> Unit): LinearLayout {
        val pal = paleta()
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(11), dp(18), dp(11))
            isClickable = true
            setOnClickListener { accion() }
        }
        val ico = icono(res, 26, tinte)
        (ico.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14)
        fila.addView(ico)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(txt(15f, "sans-serif-medium", pal.texto).apply {
            text = titulo
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(txt(12f, "sans-serif", pal.sub).apply {
            text = sub
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })
        fila.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return fila
    }

    private fun encabezado(titulo: String, accion: String?, onAccion: () -> Unit): LinearLayout {
        val pal = paleta()
        val f = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(2))
        }
        f.addView(txt(11.5f, "sans-serif-medium", pal.sub).apply {
            text = titulo
            letterSpacing = 0.1f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (accion != null) {
            f.addView(txt(12.5f, "sans-serif-medium", pal.acento).apply {
                text = accion
                isClickable = true
                setPadding(dp(8), dp(4), 0, dp(4))
                setOnClickListener { onAccion() }
            })
        }
        return f
    }

    /** Atajos al abrir la búsqueda: casa, trabajo, rutas frecuentes, recientes y favoritos. */
    private fun agregarAtajos(pal: Paleta) {
        val dfn = definiendo
        if (dfn != null) {
            val t = txt(14f, "sans-serif", pal.sub)
            t.text = "Elige dónde queda tu ${etiquetaLugar(dfn).lowercase(Locale.ROOT)}: busca una dirección, " +
                "mantén pulsado el mapa o usa tu ubicación actual."
            t.setLineSpacing(0f, 1.15f)
            t.setPadding(dp(20), dp(14), dp(20), dp(8))
            listaResultados.addView(t)
            if (lastLoc != null) {
                listaResultados.addView(
                    filaAtajo(R.drawable.ic_my_location, "Mi ubicación actual", "Usar donde estoy ahora", pal.acento) { usarUbicacionActual() }
                )
            }
            return
        }
        for (clave in arrayOf("casa", "trabajo")) {
            val p = leerLugar(clave)
            val ic = if (clave == "casa") R.drawable.ic_home else R.drawable.ic_work
            listaResultados.addView(
                filaAtajo(ic, etiquetaLugar(clave), if (p != null) p.detalle.ifEmpty { "Guardado" } else "Toca para definir", pal.acento) {
                    if (p != null) seleccionarResultado(p) else definirLugar(clave)
                }
            )
        }
        val rutas = rutasFrecuentes(3)
        if (rutas.isNotEmpty()) {
            listaResultados.addView(encabezado("RUTAS FRECUENTES", "Borrar") { borrarRutasHist() })
            for (h in rutas) {
                val sub = (if (h.veces > 1) "${h.veces} veces" else "1 vez") + " · " + Geo.dist(h.dist) + " · " + fechaCorta(h.ultima)
                listaResultados.addView(filaAtajo(R.drawable.ic_directions, h.dest.nombre, sub, pal.acento) { irDirecto(h.dest) })
            }
        }
        val rec = leerArray("hist_busq")
        if (rec.isNotEmpty()) {
            listaResultados.addView(encabezado("BÚSQUEDAS RECIENTES", "Borrar") { borrarRecientes() })
            for (r in rec.take(6)) {
                listaResultados.addView(filaAtajo(R.drawable.ic_history, r.nombre, r.tipoTexto(), pal.sub) { seleccionarResultado(r) })
            }
        }
        val favs = leerFavs()
        if (favs.isNotEmpty()) {
            listaResultados.addView(encabezado("FAVORITOS", null) { })
            for (f in favs.take(4)) {
                val sub = listaDe(f) + (if (f.detalle.isNotEmpty() && f.detalle != f.nombre) " · " + f.detalle else "")
                listaResultados.addView(filaAtajo(R.drawable.ic_star_fill, f.nombre, sub, Color.parseColor("#F59E0B")) { seleccionarResultado(f) })
            }
        }
    }

    // ───────────────────────── ajustes ─────────────────────────

    private fun abrirAjustes(tab: Int) {
        if (panelBusqueda.visibility == View.VISIBLE) return
        tabActual = tab
        panelAjustes.visibility = View.VISIBLE
        panelAjustes.alpha = 0f
        hojaAjustes.translationX = dp(60).toFloat()
        panelAjustes.animate().alpha(1f).setDuration(180L).start()
        hojaAjustes.animate().translationX(0f).setDuration(220L).start()
        refrescarAjustes()
        actualizarTarjetas()
    }

    private fun cerrarAjustes() {
        panelAjustes.animate().alpha(0f).setDuration(150L).withEndAction {
            panelAjustes.visibility = View.GONE
            tvDiagAcerca = null
            barraTab = null
            tvTabInstal = null
            actualizarTarjetas()
        }.start()
    }

    private fun refrescarAjustes() {
        if (!::panelAjustes.isInitialized || panelAjustes.visibility != View.VISIBLE) return
        val nombres = arrayOf("Mapas", "Lugares", "Preferencias", "Acerca de")
        filaTabs.removeAllViews()
        for (i in nombres.indices) {
            val c = chip(nombres[i], i == tabActual) {
                tabActual = i
                refrescarAjustes()
            }
            filaTabs.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
        }
        contenidoAjustes.removeAllViews()
        tvDiagAcerca = null
        barraTab = null
        tvTabInstal = null
        when (tabActual) {
            0 -> tabMapas()
            1 -> tabLugares()
            2 -> tabPreferencias()
            else -> tabAcerca()
        }
    }

    private fun seccion(titulo: String): TextView {
        val t = txt(12f, "sans-serif-medium", paleta().sub)
        t.text = titulo.uppercase(Locale.ROOT)
        t.letterSpacing = 0.1f
        t.setPadding(dp(4), dp(18), 0, dp(8))
        return t
    }

    private fun mbDe(ruta: String?): Long = if (ruta == null) 0L else File(ruta).length() / 1048576

    private fun filaEstado(res: Int, titulo: String, sub: String, ok: Boolean?): LinearLayout {
        val pal = paleta()
        val f = caja(18, 14, 12)
        f.orientation = LinearLayout.HORIZONTAL
        f.gravity = Gravity.CENTER_VERTICAL
        val ico = icono(res, 28, pal.acento)
        (ico.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14)
        f.addView(ico)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(txt(15f, "sans-serif-medium", pal.texto).apply { text = titulo })
        col.addView(txt(12.5f, "sans-serif", pal.sub).apply {
            text = sub
            setPadding(0, dp(3), 0, 0)
            setLineSpacing(0f, 1.1f)
        })
        f.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (ok != null) {
            f.addView(
                icono(
                    if (ok) R.drawable.ic_check_circle else R.drawable.ic_error, 26,
                    Color.parseColor(if (ok) "#22C55E" else "#F59E0B")
                )
            )
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        f.layoutParams = lp
        return f
    }

    private fun tabMapas() {
        val pal = paleta()
        val c = contenidoAjustes
        c.addView(seccion("Archivos del mapa"))
        val tj = caja(20, 16, 14)
        tj.addView(txt(14f, "sans-serif", pal.sub).apply {
            text = "Elige ecuador.pmtiles (mapa) y ecuador.g593 (rutas y búsqueda). Puedes escoger los dos a la vez; " +
                "la app reconoce cuál es cada uno y los copia una sola vez."
            setLineSpacing(0f, 1.15f)
        })
        if (Config.MANIFIESTO_URL.isNotEmpty() && manifiesto == null && !consultandoManifiesto && !instalando) consultarManifiesto()
        if (instalando) {
            val b = Barra(this)
            b.progreso = progreso
            barraTab = b
            tj.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(14) })
            val t = txt(13f, "sans-serif-medium", pal.sub)
            t.text = textoInstal
            t.setPadding(0, dp(8), 0, 0)
            tvTabInstal = t
            tj.addView(t)
            if (bajando) {
                tj.addView(
                    crearBotonTexto("Cancelar descarga", false) {
                        cancelarBajada = true
                        aviso("Cancelando…")
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
                )
            }
        } else {
            if (Config.MANIFIESTO_URL.isNotEmpty()) {
                val man = manifiesto
                val mb = if (man != null) man.totalBytes() / 1048576 else 0L
                val actualiza = hayActualizacion() && mapPath != null
                tj.addView(txt(13f, "sans-serif-medium", if (actualiza) pal.acento else pal.sub).apply {
                    text = when {
                        consultandoManifiesto -> "Consultando la última versión…"
                        errorManifiesto.isNotEmpty() -> errorManifiesto
                        man == null -> ""
                        actualiza -> "Hay una versión nueva de los mapas (" + man.version + ")" + (if (mb > 0L) " · $mb MB" else "")
                        mapPath != null -> "Tus mapas están al día (versión " + versionInstalada().ifEmpty { man.version } + ")"
                        else -> "Disponible: versión " + man.version + (if (mb > 0L) " · $mb MB" else "")
                    }
                    setPadding(0, dp(12), 0, 0)
                })
                tj.addView(
                    crearBotonTexto(if (actualiza) "Actualizar mapas" else if (mapPath != null) "Volver a descargar" else "Descargar mapas", !(mapPath != null && !actualiza)) { descargarMapas() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
                )
            }
            val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fila.addView(
                crearBotonTexto("Elegir archivos", Config.MANIFIESTO_URL.isEmpty()) { elegirArchivos() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) }
            )
            fila.addView(crearBotonTexto("Buscar en el pendrive", false) { buscarEnPendrive() })
            tj.addView(fila, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
        }
        if (errorInstal.isNotEmpty()) {
            tj.addView(txt(13f, "sans-serif-medium", Color.parseColor("#DC2626")).apply {
                text = errorInstal
                setPadding(0, dp(10), 0, 0)
            })
        }
        c.addView(tj)

        c.addView(seccion("Estado"))
        c.addView(
            filaEstado(
                R.drawable.ic_map, "Mapa de Ecuador",
                if (mapPath != null) "Instalado · ${mbDe(mapPath)} MB" else "No instalado", mapPath != null
            )
        )
        val ind = indice
        val rutasSub: String
        val rutasOk: Boolean?
        when {
            rutasPath == null -> { rutasSub = "No instalado · la búsqueda usará internet"; rutasOk = false }
            indexando -> { rutasSub = "Preparando la búsqueda… (una sola vez, puede tardar un poco)"; rutasOk = null }
            ind != null -> {
                val motor = if (grafo != null) "rutas listas" else if (cargandoGrafo) "preparando rutas…"
                else if (errorGrafo.isNotEmpty()) "rutas: $errorGrafo" else "rutas no listas"
                rutasSub = "Instalado · ${mbDe(rutasPath)} MB · ${ind.total} lugares · $motor"
                rutasOk = grafo != null
            }
            errorIndice.isNotEmpty() -> { rutasSub = "Instalado, pero no pude preparar la búsqueda: $errorIndice"; rutasOk = false }
            else -> { rutasSub = "Instalado · ${mbDe(rutasPath)} MB"; rutasOk = null }
        }
        c.addView(filaEstado(R.drawable.ic_route, "Rutas y búsqueda", rutasSub, rutasOk))
        c.addView(txt(12.5f, "sans-serif", pal.sub).apply {
            text = "Las rutas giro a giro usarán este mismo archivo en la próxima versión."
            setPadding(dp(4), dp(6), dp(4), 0)
        })
    }

    /** Fila de tarjeta con ícono, título, subtítulo y, opcionalmente, botones a la derecha. */
    private fun filaCaja(res: Int, tinte: Int, titulo: String, sub: String, accion: () -> Unit, vararg extra: Icono): LinearLayout {
        val pal = paleta()
        val f = caja(18, 14, 10)
        f.orientation = LinearLayout.HORIZONTAL
        f.gravity = Gravity.CENTER_VERTICAL
        f.isClickable = true
        f.setOnClickListener { accion() }
        val ico = icono(res, 26, tinte)
        (ico.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(12)
        f.addView(ico)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(txt(15f, "sans-serif-medium", pal.texto).apply {
            text = titulo
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(txt(12f, "sans-serif", pal.sub).apply {
            text = sub
            setPadding(0, dp(2), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        f.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for (e in extra) f.addView(e)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        f.layoutParams = lp
        return f
    }

    private fun cajaTexto(t: String): LinearLayout {
        val pal = paleta()
        return caja(18, 16, 14).apply {
            addView(txt(14f, "sans-serif", pal.sub).apply {
                text = t
                setLineSpacing(0f, 1.15f)
            })
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }
    }

    private fun filaLugar(r: Resultado): LinearLayout {
        val pal = paleta()
        val editar = icono(R.drawable.ic_edit, 32, pal.sub)
        editar.setOnClickListener {
            mostrarDialogo("Editar favorito", "", r.nombre, null, "Guardar", false, listaDe(r)) { t, _ ->
                renombrarFav(r, t, dlgListaTexto)
            }
        }
        val borrar = icono(R.drawable.ic_delete, 34, pal.sub)
        borrar.setOnClickListener {
            val l = leerFavs()
            l.removeAll { mismoLugar(it, r) }
            guardarFavs(l)
            actualizarEstrella()
            refrescarAjustes()
        }
        val sub = if (r.detalle.isNotEmpty() && r.detalle != r.nombre) r.detalle else r.tipoTexto()
        return filaCaja(R.drawable.ic_star_fill, Color.parseColor("#F59E0B"), r.nombre, sub, { seleccionarResultado(r) }, editar, borrar)
    }

    private fun filaCasaTrabajo(clave: String): LinearLayout {
        val pal = paleta()
        val p = leerLugar(clave)
        val extras = ArrayList<Icono>()
        if (p != null) {
            val cambiar = icono(R.drawable.ic_edit, 32, pal.sub)
            cambiar.setOnClickListener { definirLugar(clave) }
            val quitar = icono(R.drawable.ic_delete, 34, pal.sub)
            quitar.setOnClickListener {
                guardarLugar(clave, null)
                actualizarEstrella()
                refrescarAjustes()
            }
            extras.add(cambiar)
            extras.add(quitar)
        }
        return filaCaja(
            if (clave == "casa") R.drawable.ic_home else R.drawable.ic_work, pal.acento, etiquetaLugar(clave),
            if (p != null) p.detalle.ifEmpty { "Guardado" } else "Toca para definir",
            { if (p != null) seleccionarResultado(p) else definirLugar(clave) },
            *extras.toTypedArray()
        )
    }

    private fun filaRutaHist(h: RutaHist): LinearLayout {
        val pal = paleta()
        val ir = icono(R.drawable.ic_play_arrow, 38, pal.acento)
        ir.setOnClickListener { irDirecto(h.dest) }
        val sub = (if (h.veces > 1) "${h.veces} veces" else "1 vez") + " · " + Geo.dist(h.dist) + " · " + fechaCorta(h.ultima)
        return filaCaja(R.drawable.ic_directions, pal.acento, h.dest.nombre, sub, { seleccionarResultado(h.dest) }, ir)
    }

    private fun cabeceraLista(nombre: String, n: Int): LinearLayout {
        val pal = paleta()
        val f = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), 0, dp(6))
        }
        val ico = icono(R.drawable.ic_folder, 24, pal.acento)
        (ico.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(10)
        f.addView(ico)
        f.addView(txt(16f, "sans-serif-medium", pal.texto).apply { text = nombre })
        f.addView(txt(12.5f, "sans-serif", pal.sub).apply {
            text = "  ·  $n"
        })
        f.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (nombre != "Favoritos") {
            val editar = icono(R.drawable.ic_edit, 30, pal.sub)
            editar.setOnClickListener {
                mostrarDialogo("Renombrar lista", "", nombre, null, "Guardar") { t, _ -> renombrarLista(nombre, t) }
            }
            val borrar = icono(R.drawable.ic_delete, 32, pal.sub)
            borrar.setOnClickListener {
                mostrarDialogo("Borrar lista", "Sus lugares pasarán a «Favoritos»; no se pierde ninguno.", null, null, "Borrar") { _, _ ->
                    borrarLista(nombre)
                }
            }
            f.addView(editar)
            f.addView(borrar)
        }
        return f
    }

    private fun tabLugares() {
        val c = contenidoAjustes
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        c.addView(seccion("Casa y trabajo"))
        for (clave in arrayOf("casa", "trabajo")) c.addView(filaCasaTrabajo(clave))

        c.addView(seccion("Rutas frecuentes"))
        val rutas = rutasFrecuentes(8)
        if (rutas.isEmpty()) {
            c.addView(cajaTexto("Aquí aparecerán las rutas que uses. Toca ▶ para volver a ir sin buscar de nuevo."))
        } else {
            for (h in rutas) c.addView(filaRutaHist(h))
            c.addView(
                crearBotonTexto("Borrar rutas recientes", false) { borrarRutasHist() },
                LinearLayout.LayoutParams(wc, wc).apply { bottomMargin = dp(8) }
            )
        }

        c.addView(seccion("Búsquedas recientes"))
        val rec = leerArray("hist_busq")
        if (rec.isEmpty()) {
            c.addView(cajaTexto("Aquí aparecerán los lugares que busques."))
        } else {
            for (r in rec.take(8)) c.addView(filaCaja(R.drawable.ic_history, paleta().sub, r.nombre, r.tipoTexto(), { seleccionarResultado(r) }))
            c.addView(
                crearBotonTexto("Borrar búsquedas recientes", false) { borrarRecientes() },
                LinearLayout.LayoutParams(wc, wc).apply { bottomMargin = dp(8) }
            )
        }

        c.addView(seccion("Listas de lugares"))
        c.addView(
            crearBotonTexto("＋ Nueva lista", false) {
                mostrarDialogo("Nueva lista", "Por ejemplo: Restaurantes, Familia o Viajes.", "", null, "Crear") { t, _ -> crearLista(t) }
            },
            LinearLayout.LayoutParams(wc, wc).apply { bottomMargin = dp(4) }
        )
        val favs = leerFavs()
        for (nombre in leerListas()) {
            val items = favs.filter { listaDe(it) == nombre }
            c.addView(cabeceraLista(nombre, items.size))
            if (items.isEmpty()) {
                c.addView(
                    cajaTexto(
                        if (nombre == "Favoritos") "Aún no tienes favoritos. Busca un lugar y toca la estrella para guardarlo."
                        else "Lista vacía. Guarda un lugar y elige esta lista."
                    )
                )
            } else {
                for (f in items) c.addView(filaLugar(f))
            }
        }
    }

    private fun filaInterruptor(titulo: String, sub: String, valor: Boolean, cambio: (Boolean) -> Unit): LinearLayout {
        val pal = paleta()
        val f = caja(18, 16, 12)
        f.orientation = LinearLayout.HORIZONTAL
        f.gravity = Gravity.CENTER_VERTICAL
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(txt(15f, "sans-serif-medium", pal.texto).apply { text = titulo })
        col.addView(txt(12.5f, "sans-serif", pal.sub).apply {
            text = sub
            setPadding(0, dp(3), 0, 0)
        })
        f.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Interruptor(this, valor)
        sw.cambio = cambio
        f.addView(sw, LinearLayout.LayoutParams(dp(52), dp(30)).apply { leftMargin = dp(12) })
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        f.layoutParams = lp
        return f
    }

    private fun tabPreferencias() {
        val pal = paleta()
        val c = contenidoAjustes
        c.addView(seccion("Apariencia"))
        val tj = caja(20, 16, 14)
        tj.addView(txt(15f, "sans-serif-medium", pal.texto).apply { text = "Modo del mapa y la pantalla" })
        tj.addView(txt(12.5f, "sans-serif", pal.sub).apply {
            text = "Automático cambia a noche entre las 18:00 y las 06:00."
            setPadding(0, dp(3), 0, dp(12))
        })
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ops = arrayOf("Automático", "Día", "Noche")
        for (i in ops.indices) {
            fila.addView(
                chip(ops[i], modoTema == i) {
                    modoTema = i
                    prefs.edit().putInt("tema", i).apply()
                    aplicarModo()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
            )
        }
        tj.addView(fila)
        c.addView(tj)

        c.addView(seccion("Pantalla"))
        c.addView(filaInterruptor("Mantener la pantalla encendida", "Recomendado mientras conduces", pantallaOn) { v ->
            pantallaOn = v
            prefs.edit().putBoolean("pantalla", v).apply()
            if (v) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        })

        c.addView(seccion("Tu posición en el mapa"))
        val cm = caja(18, 14, 14)
        cm.addView(txt(12.5f, "sans-serif", pal.sub).apply {
            text = "Elige cómo se ve tu posición. La flecha, el triángulo y los vehículos giran hacia donde te mueves."
            setPadding(0, 0, 0, dp(10))
            setLineSpacing(0f, 1.15f)
        })
        val scrollMarc = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val filaMarc = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val nombresMarc = arrayOf("Punto", "Flecha", "Triángulo", "Auto", "Taxi")
        for (i in nombresMarc.indices) {
            val elegido = marcadorTipo == i
            val tarj = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(12), dp(10), dp(12), dp(8))
                isClickable = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (elegido) Color.argb(28, Color.red(pal.acento), Color.green(pal.acento), Color.blue(pal.acento)) else Color.TRANSPARENT)
                    setStroke(dp(if (elegido) 2 else 1), if (elegido) pal.acento else pal.borde)
                }
                setOnClickListener {
                    marcadorTipo = i
                    prefs.edit().putInt("marcador", i).apply()
                    estilo?.let { st -> aplicarMarcador(st) }
                    ponerPosicion(lastLoc)
                    refrescarAjustes()
                }
            }
            val vista = ImageView(this).apply {
                setImageBitmap(crearMarcador(i))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            tarj.addView(vista, LinearLayout.LayoutParams(dp(52), dp(52)))
            tarj.addView(txt(12f, "sans-serif-medium", if (elegido) pal.acento else pal.sub).apply {
                text = nombresMarc[i]
                setPadding(0, dp(4), 0, 0)
            })
            filaMarc.addView(tarj, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
        }
        scrollMarc.addView(filaMarc)
        cm.addView(scrollMarc)
        c.addView(cm)

        c.addView(seccion("Navegación"))
        c.addView(filaInterruptor("Mapa inclinado (3D)", "Más atractivo; desactívalo si tu equipo va lento", inclinado) { v ->
            inclinado = v
            prefs.edit().putBoolean("inclinado", v).apply()
        })

        c.addView(seccion("Voz"))
        c.addView(filaInterruptor("Instrucciones por voz", "Se usarán durante la navegación", vozOn) { v ->
            vozOn = v
            prefs.edit().putBoolean("voz", v).apply()
        })
        if (tts == null && !ttsFallo) iniciarTts { refrescarAjustes() }
        val cv = caja(18, 16, 14)
        val filaV = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filaV.addView(
            crearBotonTexto("Probar voz", true) { probarVoz() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) }
        )
        filaV.addView(crearBotonTexto("Voces del sistema", false) { abrirAjustesVoz() })
        cv.addView(filaV)
        cv.addView(txt(13f, "sans-serif-medium", pal.sub).apply {
            text = "Velocidad de la voz"
            setPadding(0, dp(16), 0, dp(8))
        })
        val filaVel = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val velocidades = arrayOf(Pair("Lenta", 0.8f), Pair("Normal", 0.95f), Pair("Rápida", 1.15f))
        for ((nombre, v) in velocidades) {
            filaVel.addView(
                chip(nombre, Math.abs(velVoz - v) < 0.03f) {
                    velVoz = v
                    prefs.edit().putFloat("voz_vel", v).apply()
                    tts?.setSpeechRate(v)
                    refrescarAjustes()
                    probarVoz()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
            )
        }
        cv.addView(filaVel)
        if (vocesEs.size > 1) {
            cv.addView(txt(13f, "sans-serif-medium", pal.sub).apply {
                text = "Voz (prueba cuál se entiende mejor)"
                setPadding(0, dp(16), 0, dp(8))
            })
            val actualVoz = try { tts?.voice?.name } catch (e: Exception) { null }
            val filaVoces = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val scrollVoces = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            for ((i, v) in vocesEs.take(8).withIndex()) {
                val pais = v.locale.displayCountry
                val nombre = "Voz ${i + 1}" + (if (pais.isNotEmpty()) " · $pais" else "") + (if (v.isNetworkConnectionRequired) " ☁" else "")
                filaVoces.addView(
                    chip(nombre, v.name == actualVoz) {
                        vozNombre = v.name
                        prefs.edit().putString("voz_nombre", v.name).apply()
                        tts?.setVoice(v)
                        refrescarAjustes()
                        probarVoz()
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
                )
            }
            scrollVoces.addView(filaVoces)
            cv.addView(scrollVoces)
        }
        cv.addView(txt(12f, "sans-serif", pal.sub).apply {
            text = "Si la voz suena en otro idioma o poco clara, instala o elige una voz en español en «Voces del sistema»."
            setPadding(0, dp(14), 0, 0)
            setLineSpacing(0f, 1.15f)
        })
        c.addView(cv)
    }

    // ───────────────────────── voz ─────────────────────────

    private val colaTts = ArrayList<() -> Unit>()
    private var ttsFallo = false

    private val abreviaturasVoz = arrayOf(
        Pair("\\bAv\\.? ", "Avenida "), Pair("\\bCdla\\.? ", "Ciudadela "), Pair("\\bPje\\.? ", "Pasaje "),
        Pair("\\bUrb\\.? ", "Urbanización "), Pair("\\bGral\\.? ", "General "), Pair("\\bDr\\.? ", "Doctor "),
        Pair("\\bS/N\\b", "sin número")
    )

    /** Expande abreviaturas para que la voz las lea bien ("Av. 30" → "Avenida 30"). */
    private fun textoParaVoz(t: String): String {
        var s = t
        for ((patron, reemplazo) in abreviaturasVoz) s = s.replace(Regex(patron, RegexOption.IGNORE_CASE), reemplazo)
        return s
    }

    /** Inicia el motor de voz (una sola vez) y ejecuta `cuando` en cuanto esté listo. */
    private fun iniciarTts(cuando: () -> Unit) {
        if (tts != null && ttsListo) {
            cuando()
            return
        }
        colaTts.add(cuando)
        if (tts == null) {
            tts = TextToSpeech(this) { estado -> ui.post { alIniciarTts(estado) } }
        }
    }

    private fun alIniciarTts(estado: Int) {
        val m = tts
        if (m == null || estado != TextToSpeech.SUCCESS) {
            tts = null
            ttsFallo = true
            colaTts.clear()
            aviso("Este equipo no tiene un motor de voz. En los ajustes del sistema instala «Servicios de voz de Google» u otro motor.")
            return
        }
        // Idioma: español (Ecuador si existe; si no, otro español disponible)
        var elegido: Locale? = null
        for (loc in arrayOf(Locale("es", "EC"), Locale("es", "US"), Locale("es", "MX"), Locale("es", "ES"), Locale("es"))) {
            if (m.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) {
                elegido = loc
                break
            }
        }
        if (elegido == null) {
            ttsFallo = true
            colaTts.clear()
            aviso("No hay una voz en español instalada. Descárgala en los ajustes de voz del sistema.")
            try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (e: Exception) { /* sin instalador */ }
            return
        }
        m.setLanguage(elegido)
        // Voces en español disponibles (para elegir la que mejor se entienda)
        try {
            val todas = m.voices
            val es = ArrayList<Voice>()
            if (todas != null) for (v in todas) if (v.locale.language == "es") es.add(v)
            es.sortWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
            vocesEs = es
        } catch (e: Exception) {
            vocesEs = emptyList()
        }
        val guardada = vocesEs.firstOrNull { it.name == vozNombre }
        if (guardada != null) m.setVoice(guardada)
        m.setSpeechRate(velVoz)
        m.setPitch(1.0f)
        try {
            m.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (e: Exception) { /* se usa el canal por defecto */ }
        ttsListo = true
        val pendientes = ArrayList(colaTts)
        colaTts.clear()
        for (f in pendientes) f()
        if (panelAjustes.visibility == View.VISIBLE) refrescarAjustes()
    }

    /** Habla un texto (si la voz está activada o se fuerza, p. ej. en "Probar voz"). */
    private fun hablar(t: String, forzar: Boolean = false) {
        if (!vozOn && !forzar) return
        val texto = textoParaVoz(t)
        iniciarTts { tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "nav") }
    }

    private fun probarVoz() {
        hablar("En doscientos metros, gire a la derecha por la Avenida Amazonas.", true)
    }

    private fun abrirAjustesVoz() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e2: Exception) { aviso("No pude abrir los ajustes del sistema.") }
        }
    }

    private fun filaDato(clave: String, valor: String): LinearLayout {
        val pal = paleta()
        val f = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        f.addView(txt(13f, "sans-serif", pal.sub).apply { text = clave }, LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT))
        f.addView(txt(15f, "sans-serif-medium", pal.texto).apply { text = valor })
        return f
    }

    private fun tabAcerca() {
        val pal = paleta()
        val c = contenidoAjustes
        val cab = caja(22, 18, 16)
        cab.orientation = LinearLayout.HORIZONTAL
        cab.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        cab.addView(logo, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(16) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(txt(20f, "sans-serif-medium", pal.texto).apply { text = "GPS 593" })
        col.addView(txt(13f, "sans-serif", pal.sub).apply {
            text = "Versión nativa $VERSION"
            setPadding(0, dp(3), 0, 0)
        })
        col.addView(txt(12.5f, "sans-serif", pal.sub).apply {
            text = "Sin anuncios ni rastreo en segundo plano"
            setPadding(0, dp(3), 0, 0)
        })
        cab.addView(col)
        c.addView(cab)
        c.addView(seccion("Créditos"))
        val cr = caja(18, 16, 14)
        cr.addView(filaDato("Creador", Config.CREADOR))
        cr.addView(filaDato("Propietario", Config.PROPIETARIO))
        if (versionInstalada().isNotEmpty()) cr.addView(filaDato("Datos de mapas", "versión " + versionInstalada()))
        cr.addView(txt(12f, "sans-serif", pal.sub).apply {
            text = "© 2026 ${Config.PROPIETARIO} Todos los derechos reservados."
            setPadding(0, dp(10), 0, 0)
        })
        c.addView(cr)
        c.addView(seccion("Mapas y datos"))
        c.addView(caja(18, 16, 14).apply {
            addView(txt(13f, "sans-serif", pal.sub).apply {
                text = "Mapas y lugares © OpenStreetMap contributors · lugares adicionales © Overture Maps Foundation (si el archivo de mapas los incluye) · teselas Protomaps · motor MapLibre Native · íconos Material Symbols."
                setLineSpacing(0f, 1.2f)
            })
        })
        c.addView(seccion("Diagnóstico del dispositivo"))
        val dcaja = caja(18, 14, 12)
        val d = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * escala)
            setTextColor(pal.texto)
        }
        tvDiagAcerca = d
        dcaja.addView(d)
        c.addView(dcaja)
        actualizarDiagnostico()
    }

    // ───────────────────────── GPS ─────────────────────────

    private fun edadSeg(l: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000_000L

    /** Posición de prueba (fijada a mano) o simulada: no depende del GPS. */
    private fun esSintetica(l: Location): Boolean = l.provider == "prueba" || l.provider == "simulado"

    private fun permisoTexto(): String = when {
        tienePermisoGps() -> "precisa"
        tieneAlgunPermiso() -> "solo aproximada"
        else -> "sin permiso"
    }

    private fun listaProveedores(): List<String> {
        val l = ArrayList<String>()
        l.add(LocationManager.GPS_PROVIDER)
        l.add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= 31) l.add(PROV_FUSED)
        return l
    }

    private fun nombreProveedor(p: String): String = when (p) {
        LocationManager.GPS_PROVIDER -> "GPS"
        LocationManager.NETWORK_PROVIDER -> "red"
        LocationManager.PASSIVE_PROVIDER -> "pasivo"
        else -> "fusionado"
    }

    private fun proveedoresTexto(): String {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val sb = StringBuilder()
        for (p in listaProveedores()) {
            val on = try { lm.isProviderEnabled(p) } catch (e: Exception) { false }
            if (sb.isNotEmpty()) sb.append("  ")
            sb.append(nombreProveedor(p)).append(if (on) " ✓" else " ✗")
        }
        return sb.toString()
    }

    /**
     * Pide posiciones al GPS, a la red (wifi/celular), al proveedor fusionado (Android 12+) y al pasivo
     * (las que obtienen otras apps): en interiores la red o el fusionado localizan al instante, de forma aproximada.
     */
    private fun iniciarGps() {
        if (gpsActivo || !tieneAlgunPermiso()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var alguno = false
        val pedidos = ArrayList<Pair<String, Long>>()
        pedidos.add(Pair(LocationManager.GPS_PROVIDER, 1000L))
        pedidos.add(Pair(LocationManager.NETWORK_PROVIDER, 3000L))
        if (Build.VERSION.SDK_INT >= 31) pedidos.add(Pair(PROV_FUSED, 2000L))
        pedidos.add(Pair(LocationManager.PASSIVE_PROVIDER, 0L))
        for ((prov, cada) in pedidos) {
            try {
                if (lm.isProviderEnabled(prov)) {
                    lm.requestLocationUpdates(prov, cada, 0f, this)
                    alguno = true
                }
            } catch (e: SecurityException) { /* sin permiso para este proveedor */ } catch (e: Exception) { /* no disponible */ }
        }
        gpsActivo = alguno
        if (alguno && tBusquedaGps == 0L) tBusquedaGps = SystemClock.elapsedRealtime()
        if (lastLoc == null) {
            for (prov in listaProveedores() + LocationManager.PASSIVE_PROVIDER) {
                try {
                    val k = lm.getLastKnownLocation(prov)
                    // Solo si es reciente: una posición vieja no sirve para centrar el mapa
                    if (k != null && edadSeg(k) < 120L) {
                        procesarUbicacion(k)
                        break
                    }
                } catch (e: Exception) { /* nada */ }
            }
        }
    }

    private fun detenerGps() {
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        } catch (e: Exception) { /* nada */ }
        gpsActivo = false
    }

    /** Dibuja el marcador elegido (mira hacia arriba = norte; el mapa lo gira con el rumbo). */
    private fun crearMarcador(tipo: Int): Bitmap {
        val s = Math.max(48, dp(56))
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        bmp.density = resources.displayMetrics.densityDpi
        val c = Canvas(bmp)
        val f = s.toFloat()
        val azul = Color.parseColor(if (night) "#7DB2FF" else "#2563EB")
        val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = f * 0.055f
            strokeJoin = Paint.Join.ROUND
        }
        val sombra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#44000000")
        }
        when (tipo) {
            0 -> {                                              // punto con halo
                relleno.color = Color.argb(70, Color.red(azul), Color.green(azul), Color.blue(azul))
                c.drawCircle(f / 2, f / 2, f * 0.46f, relleno)
                relleno.color = azul
                c.drawCircle(f / 2, f / 2, f * 0.24f, relleno)
                c.drawCircle(f / 2, f / 2, f * 0.24f, borde)
            }
            1 -> {                                              // flecha de navegación
                val p = Path()
                p.moveTo(f * 0.5f, f * 0.07f)
                p.lineTo(f * 0.86f, f * 0.90f)
                p.lineTo(f * 0.5f, f * 0.68f)
                p.lineTo(f * 0.14f, f * 0.90f)
                p.close()
                c.save()
                c.translate(f * 0.02f, f * 0.03f)
                c.drawPath(p, sombra)
                c.restore()
                relleno.color = azul
                c.drawPath(p, relleno)
                c.drawPath(p, borde)
            }
            2 -> {                                              // triángulo
                val p = Path()
                p.moveTo(f * 0.5f, f * 0.10f)
                p.lineTo(f * 0.86f, f * 0.86f)
                p.lineTo(f * 0.14f, f * 0.86f)
                p.close()
                c.save()
                c.translate(f * 0.02f, f * 0.03f)
                c.drawPath(p, sombra)
                c.restore()
                relleno.color = azul
                c.drawPath(p, relleno)
                c.drawPath(p, borde)
            }
            else -> {                                           // auto visto desde arriba (4 = taxi amarillo)
                val color = if (tipo == 4) Color.parseColor("#FBBF24") else azul
                val oscuro = Color.parseColor("#1E293B")
                c.drawOval(RectF(f * 0.18f, f * 0.12f, f * 0.84f, f * 0.98f), sombra)
                relleno.color = oscuro                          // ruedas
                for (y in floatArrayOf(0.20f, 0.66f)) {
                    c.drawRoundRect(RectF(f * 0.22f, f * y, f * 0.30f, f * (y + 0.16f)), f * 0.03f, f * 0.03f, relleno)
                    c.drawRoundRect(RectF(f * 0.70f, f * y, f * 0.78f, f * (y + 0.16f)), f * 0.03f, f * 0.03f, relleno)
                }
                val cuerpo = RectF(f * 0.28f, f * 0.05f, f * 0.72f, f * 0.95f)
                relleno.color = color
                c.drawRoundRect(cuerpo, f * 0.14f, f * 0.14f, relleno)
                c.drawRoundRect(cuerpo, f * 0.14f, f * 0.14f, borde)
                relleno.color = oscuro                          // parabrisas y luneta
                val pb = Path()
                pb.moveTo(f * 0.33f, f * 0.31f)
                pb.lineTo(f * 0.67f, f * 0.31f)
                pb.lineTo(f * 0.63f, f * 0.43f)
                pb.lineTo(f * 0.37f, f * 0.43f)
                pb.close()
                c.drawPath(pb, relleno)
                val lu = Path()
                lu.moveTo(f * 0.36f, f * 0.70f)
                lu.lineTo(f * 0.64f, f * 0.70f)
                lu.lineTo(f * 0.67f, f * 0.80f)
                lu.lineTo(f * 0.33f, f * 0.80f)
                lu.close()
                c.drawPath(lu, relleno)
                relleno.color = Color.argb(60, 0, 0, 0)         // techo
                c.drawRoundRect(RectF(f * 0.34f, f * 0.44f, f * 0.66f, f * 0.69f), f * 0.04f, f * 0.04f, relleno)
                if (tipo == 4) {                                // letrero de taxi
                    relleno.color = oscuro
                    c.drawRoundRect(RectF(f * 0.42f, f * 0.52f, f * 0.58f, f * 0.58f), f * 0.02f, f * 0.02f, relleno)
                }
                relleno.color = Color.parseColor("#FFF7CC")     // faros
                c.drawCircle(f * 0.36f, f * 0.10f, f * 0.032f, relleno)
                c.drawCircle(f * 0.64f, f * 0.10f, f * 0.032f, relleno)
                relleno.color = Color.parseColor("#EF4444")     // luces traseras
                c.drawCircle(f * 0.36f, f * 0.91f, f * 0.028f, relleno)
                c.drawCircle(f * 0.64f, f * 0.91f, f * 0.028f, relleno)
            }
        }
        return bmp
    }

    /** Registra en el mapa la imagen del marcador elegido (se vuelve a llamar al cambiar de estilo o de opción). */
    private fun aplicarMarcador(style: Style) {
        style.addImage("marcador-$marcadorTipo", crearMarcador(marcadorTipo))
    }

    private fun ponerPosicion(l: Location?) {
        val p = posMostrada
        if (p != null) pintarMarcador(p[0], p[1], rumboMostrado)
        else if (l != null) pintarMarcador(l.latitude, l.longitude, ultimoRumbo)
    }

    override fun onLocationChanged(location: Location) {
        if (simulando) return                       // durante la simulación se ignora el GPS real
        val prov = location.provider ?: "?"
        contFix[prov] = (contFix[prov] ?: 0) + 1
        if (!aceptarFix(location)) return
        procesarUbicacion(location)
    }

    /**
     * Elige la mejor posición: el GPS manda. La red, el proveedor fusionado y el pasivo solo se aceptan cuando no hay un GPS
     * reciente (si no, sus posiciones sin velocidad ni rumbo hacían que el cursor se trabara y el velocímetro quedara en 0).
     */
    private fun aceptarFix(l: Location): Boolean {
        val prev = lastLoc ?: return true
        if (esSintetica(prev)) return true
        val esGps = l.provider == LocationManager.GPS_PROVIDER
        val prevGps = prev.provider == LocationManager.GPS_PROVIDER
        val edadPrev = edadSeg(prev)
        if (!esGps && prevGps && edadPrev < 8L) return false                         // hay GPS reciente
        if (l.elapsedRealtimeNanos < prev.elapsedRealtimeNanos) return false          // llegó desordenada
        if (!esGps && l.hasAccuracy() && prev.hasAccuracy() && l.accuracy > prev.accuracy * 1.5f && edadPrev < 10L) return false
        return true
    }

    /** Punto único por donde pasa toda posición (GPS real, de prueba o simulada). */
    private fun procesarUbicacion(location: Location) {
        val prev = ultimaFix
        val sint = esSintetica(location)
        var v = -1f
        var dtFix = 1000L
        var rumbo = ultimoRumbo
        if (prev != null && !esSintetica(prev) && !sint) {
            val dt = (location.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
            if (dt > 0.0) dtFix = (dt * 1000.0).toLong()
            if (dt > 0.4 && dt < 8.0) {
                val d = Geo.dm(prev.latitude, prev.longitude, location.latitude, location.longitude)
                val ruido = if (location.hasAccuracy()) Math.max(3f, location.accuracy) else 10f
                // Velocidad: la del GPS si la trae; si no, se calcula con lo que avanzaste
                v = if (location.provider == LocationManager.GPS_PROVIDER && location.hasSpeed()) location.speed
                else if (d > ruido * 0.5) (d / dt).toFloat() else 0f
                if (d > 6.0 && d > ruido * 0.6) {
                    rumbo = Geo.rumbo(prev.latitude, prev.longitude, location.latitude, location.longitude).toFloat()
                }
            }
        }
        if (v < 0f) v = if (location.hasSpeed()) location.speed else 0f
        if (location.hasBearing() && v > 0.8f && (location.provider == LocationManager.GPS_PROVIDER || sint)) rumbo = location.bearing
        if (sint && location.hasBearing()) rumbo = location.bearing
        velMs = if (v < 0.3f) 0f else if (sint) v else velMs * 0.3f + v * 0.7f
        if (v >= 0.8f || sint) ultimoRumbo = rumbo
        ultimaFix = location
        lastLoc = location
        actualizarVelocidad()
        if (modoRuta == 2) {
            val kmh = velMs * 3.6f
            zoomObjetivo = if (kmh < 25f) 17.4 else if (kmh < 60f) 16.6 else 15.8
            actualizarNavegacion(location)
        }
        programarAnimacion(location.latitude, location.longitude, ultimoRumbo, dtFix)
    }

    /** Mueve el marcador (y la cámara si la vista te sigue) con una transición suave hacia la nueva posición. */
    private fun programarAnimacion(lat: Double, lon: Double, rumbo: Float, dtFix: Long) {
        val actual = posMostrada
        if (actual == null || Geo.dm(actual[0], actual[1], lat, lon) > 300.0) {
            // Primera posición o salto grande: sin animar
            posMostrada = doubleArrayOf(lat, lon)
            rumboMostrado = rumbo
            val m = map
            if (!primerFix && follow && m != null) {
                primerFix = true
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 15.0))
            }
            dibujar(lat, lon, rumbo)
            return
        }
        animDesde = doubleArrayOf(actual[0], actual[1])
        animHasta = doubleArrayOf(lat, lon)
        animRumboDesde = rumboMostrado
        animRumboHasta = rumbo
        animT0 = SystemClock.uptimeMillis()
        animDur = Math.max(450L, Math.min(1300L, dtFix))
        if (!animActiva) {
            animActiva = true
            ui.post(animTick)
        }
    }

    private val animTick = object : Runnable {
        override fun run() {
            val t = Math.min(1.0, (SystemClock.uptimeMillis() - animT0).toDouble() / animDur.toDouble())
            val lat = animDesde[0] + (animHasta[0] - animDesde[0]) * t
            val lon = animDesde[1] + (animHasta[1] - animDesde[1]) * t
            val dr = ((animRumboHasta - animRumboDesde + 540f) % 360f) - 180f
            val rumbo = (animRumboDesde + dr * t.toFloat() + 360f) % 360f
            posMostrada = doubleArrayOf(lat, lon)
            rumboMostrado = rumbo
            dibujar(lat, lon, rumbo)
            if (t < 1.0) ui.postDelayed(this, 50L) else animActiva = false
        }
    }

    private fun pintarMarcador(lat: Double, lon: Double, rumbo: Float) {
        val json = "{\"type\":\"Feature\",\"properties\":{\"rumbo\":$rumbo,\"icono\":\"marcador-$marcadorTipo\"}," +
            "\"geometry\":{\"type\":\"Point\",\"coordinates\":[$lon,$lat]}}"
        estilo?.getSourceAs<GeoJsonSource>("yo")?.setGeoJson(json)
    }

    /** Dibuja el marcador y, si la vista te sigue, centra la cámara (con el rumbo y la inclinación si corresponde). */
    private fun dibujar(lat: Double, lon: Double, rumbo: Float) {
        pintarMarcador(lat, lon, rumbo)
        val m = map ?: return
        if (!follow) return
        var z = m.cameraPosition?.zoom ?: 15.0
        if (modoRuta == 2) {
            zoomActual += (zoomObjetivo - zoomActual) * 0.12
            z = zoomActual
        }
        val b = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(z)
        if (rumboArriba) {
            b.bearing(rumbo.toDouble())
            b.tilt(if (inclinado) 45.0 else 0.0)
        } else {
            b.bearing(0.0)
            b.tilt(0.0)
        }
        m.moveCamera(CameraUpdateFactory.newCameraPosition(b.build()))
    }

    // Se declaran todos para que funcione también en Android antiguos (métodos abstractos antes de API 30)
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) { iniciarGps() }
    override fun onProviderDisabled(provider: String) { gpsActivo = false }

    // ───────────────────────── navegación ─────────────────────────

    private fun hora(seg: Double): String =
        java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
            .format(java.util.Date(System.currentTimeMillis() + (seg * 1000.0).toLong()))

    private fun tiempo(seg: Double): String {
        val m = Math.max(1L, Math.round(seg / 60.0))
        return if (m < 60L) "$m min" else "${m / 60} h ${String.format(Locale.US, "%02d", m % 60)} min"
    }

    private fun minuscula(t: String): String = t.replaceFirstChar { it.lowercase() }

    private fun redondeo(d: Double): Long =
        if (d > 100.0) Math.round(d / 50.0) * 50 else Math.round(d / 10.0) * 10

    /** Posición desde la que sale la ruta: el GPS reciente o la posición de prueba. */
    private fun origenActual(): Location? {
        val l = lastLoc ?: return null
        return if (esSintetica(l) || edadSeg(l) <= 60L) l else null
    }

    private fun accionPrimaria() {
        val d = destino ?: return
        if (modoRuta == 1) iniciarNavegacion() else calcularRuta(d)
    }

    private fun calcularRuta(d: Resultado) {
        if (calculando) return
        if (rutasPath == null) {
            aviso("Para calcular rutas instala el archivo ecuador.g593 en Ajustes › Mapas.")
            abrirAjustes(0)
            return
        }
        val g = grafo
        if (g == null) {
            aviso(
                if (indexando || cargandoGrafo) "Preparando el motor de rutas… intenta de nuevo en unos segundos."
                else "El motor de rutas no está listo" + (if (errorGrafo.isNotEmpty()) ": $errorGrafo" else ".")
            )
            autoIniciar = false
            return
        }
        val o = origenActual()
        if (o == null) {
            aviso("Aún no tengo tu posición. Espera el GPS, o mantén pulsado el mapa donde estés y toca «Estoy aquí».")
            autoIniciar = false
            return
        }
        calculando = true
        btnPrimario.text = "Calculando…"
        val oLat = o.latitude
        val oLon = o.longitude
        Thread {
            var rs: List<Ruta> = emptyList()
            var err = ""
            try {
                rs = g.alternativas(oLat, oLon, d.lat, d.lon)
            } catch (e: Throwable) {
                err = if (e is OutOfMemoryError) "No hay memoria suficiente para calcular esta ruta." else (e.message ?: "No pude calcular la ruta.")
            }
            val lista = rs
            ui.post {
                calculando = false
                if (lista.isEmpty() || destino !== d) {
                    btnPrimario.text = "Cómo llegar"
                    autoIniciar = false
                    if (lista.isEmpty()) aviso(err)
                } else {
                    vistaPrevia(lista)
                    if (autoIniciar) {
                        autoIniciar = false
                        iniciarNavegacion()
                    }
                }
            }
        }.start()
    }

    /** Vista previa: muestra las opciones de ruta (más rápida, más corta, alternativas) para elegir antes de iniciar. */
    private fun vistaPrevia(lista: List<Ruta>) {
        rutasAlt = lista
        idxSel = 0
        ruta = lista[0]
        modoRuta = 1
        ponerRuta()
        btnPrimario.text = "Iniciar"
        actualizarResumenPrevio()
        reconstruirAlternativas()
        try {
            val b = org.maplibre.android.geometry.LatLngBounds.Builder()
            for (r in lista) {
                val paso = Math.max(1, r.lat.size / 200)
                var i = 0
                while (i < r.lat.size) {
                    b.include(LatLng(r.lat[i], r.lon[i]))
                    i += paso
                }
                b.include(LatLng(r.lat[r.lat.size - 1], r.lon[r.lon.size - 1]))
            }
            map?.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), dp(80), dp(90), dp(80), dp(230)))
        } catch (e: Exception) { /* ruta de un solo punto */ }
        ajustarColumnas()
    }

    private fun actualizarResumenPrevio() {
        val r = ruta ?: return
        tvLugarSub.text = "Llegas ${hora(r.duracion)} · ${Geo.dist(r.distancia)} · ${tiempo(r.duracion)}"
    }

    /** Tarjetas de opciones de ruta (solo en la vista previa y si hay más de una). */
    private fun reconstruirAlternativas() {
        filaAlt.removeAllViews()
        if (modoRuta != 1 || rutasAlt.size < 2) {
            filaAlt.visibility = View.GONE
            return
        }
        filaAlt.visibility = View.VISIBLE
        val pal = paleta()
        val base = rutasAlt[0]
        for (i in rutasAlt.indices) {
            val r = rutasAlt[i]
            val sel = i == idxSel
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (sel) Color.argb(28, Color.red(pal.acento), Color.green(pal.acento), Color.blue(pal.acento)) else Color.TRANSPARENT)
                    setStroke(dp(if (sel) 2 else 1), if (sel) pal.acento else pal.borde)
                }
                setOnClickListener {
                    idxSel = i
                    ruta = rutasAlt[i]
                    ponerRuta()
                    actualizarResumenPrevio()
                    reconstruirAlternativas()
                }
            }
            c.addView(txt(11.5f, "sans-serif-medium", if (sel) pal.acento else pal.sub).apply {
                text = r.etiqueta
                maxLines = 1
            })
            c.addView(txt(17f, "sans-serif-medium", pal.texto).apply {
                text = tiempo(r.duracion)
                setPadding(0, dp(2), 0, 0)
            })
            val dif = r.duracion - base.duracion
            c.addView(txt(12f, "sans-serif", pal.sub).apply {
                text = Geo.dist(r.distancia) + (if (i > 0 && dif >= 30.0) " · +" + tiempo(dif) else "")
                maxLines = 1
            })
            filaAlt.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        }
    }

    private fun iniciarNavegacion() {
        val r = ruta ?: return
        modoRuta = 2
        val destinoRuta = destino
        if (destinoRuta != null) registrarRuta(destinoRuta, r)
        rutasAlt = emptyList()
        reconstruirAlternativas()
        ponerRuta()
        barraProgreso.progreso = 0f
        follow = true
        rumboArriba = true
        zoomActual = map?.cameraPosition?.zoom ?: 15.0
        zoomObjetivo = 17.0
        actualizarBotonCentrar()
        fueraCont = 0
        idxRuta = 0
        pasoAnunciado = IntArray(r.pasos.size)
        tarjetaLugar.visibility = View.GONE
        pillBusqueda.visibility = View.GONE
        bannerNav.visibility = View.VISIBLE
        barraNav.visibility = View.VISIBLE
        btnSimular.text = if (simulando) "Detener" else "Simular"
        ajustarColumnas()
        hablar(r.pasos[0].texto())
        val l = lastLoc
        if (l != null) {
            actualizarNavegacion(l)
        }
    }

    /** Calcula en qué punto de la ruta vas, la próxima maniobra, el tiempo restante y la voz. */
    private fun actualizarNavegacion(l: Location) {
        val r = ruta ?: return
        if (r.total <= 0.0) return
        var u = r.ubicar(l.latitude, l.longitude, idxRuta - 15, idxRuta + 250)
        if (u.d > 60.0) u = r.ubicar(l.latitude, l.longitude, 0, r.lat.size - 2)
        idxRuta = u.i
        if (u.d > 55.0 && !simulando) {
            fueraCont++
            if (fueraCont >= 4) {
                fueraCont = 0
                recalcularRuta(l)
                return
            }
        } else {
            fueraCont = 0
        }
        val restante = Math.max(0.0, r.total - u.along)
        val eta = r.duracion * (restante / r.total)
        var k = 0
        while (k + 1 < r.pasos.size && r.pasos[k + 1].inicio <= u.along) k++
        val sig = Math.min(k + 1, r.pasos.size - 1)
        val p = r.pasos[sig]
        val aSig = Math.max(0.0, p.inicio - u.along)
        icoNav.res = p.icono()
        icoNav.invalidate()
        tvNavDist.text = Geo.dist(aSig)
        tvNavTexto.text = p.texto()
        tvNavEta.text = hora(eta)
        tvNavDistR.text = Geo.dist(restante)
        tvNavTiempo.text = tiempo(eta)
        barraProgreso.progreso = (u.along / r.total).toFloat()
        barraProgreso.invalidate()
        anunciar(sig, aSig, p)
        if (restante < 25.0) finalizarNavegacion(true)
    }

    private fun anunciar(idx: Int, aSig: Double, p: Paso) {
        if (idx >= pasoAnunciado.size) return
        val a = pasoAnunciado[idx]
        if (p.tipo == "arrive") {
            if (aSig <= 150.0 && (a and 1) == 0) {
                pasoAnunciado[idx] = a or 1
                hablar("Su destino está a ${Math.round(aSig / 10.0) * 10} metros")
            }
            return
        }
        if (aSig <= 40.0 && (a and 4) == 0) {
            pasoAnunciado[idx] = a or 7
            hablar(p.texto())
        } else if (aSig > 40.0 && aSig <= 160.0 && (a and 2) == 0) {
            pasoAnunciado[idx] = a or 3
            hablar("En ${redondeo(aSig)} metros, " + minuscula(p.texto()))
        } else if (aSig > 160.0 && aSig <= 450.0 && (a and 1) == 0) {
            pasoAnunciado[idx] = a or 1
            hablar("En ${redondeo(aSig)} metros, " + minuscula(p.texto()))
        }
    }

    private fun recalcularRuta(l: Location) {
        val d = destino ?: return
        val g = grafo ?: return
        if (calculando) return
        calculando = true
        aviso("Recalculando ruta…")
        hablar("Recalculando")
        val la = l.latitude
        val lo = l.longitude
        Thread {
            var rr: Ruta? = null
            try {
                rr = g.ruta(la, lo, d.lat, d.lon)
            } catch (e: Throwable) { /* se intentará de nuevo en unos segundos */ }
            val nueva = rr
            ui.post {
                calculando = false
                if (nueva != null && modoRuta == 2) {
                    ruta = nueva
                    ponerRuta()
                    pasoAnunciado = IntArray(nueva.pasos.size)
                    idxRuta = 0
                    val ult = lastLoc
                    if (ult != null) actualizarNavegacion(ult)
                }
            }
        }.start()
    }

    private fun finalizarNavegacion(llegada: Boolean) {
        if (llegada) {
            hablar("Ha llegado a su destino")
            aviso("Ha llegado a su destino")
        }
        cancelarRuta()
        destino = null
        ponerDestino()
        tarjetaLugar.visibility = View.GONE
        ajustarColumnas()
        val m = map
        if (m != null) {
            m.animateCamera(
                CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(m.cameraPosition).bearing(0.0).tilt(0.0).build()),
                600
            )
        }
    }

    /** Quita la ruta y vuelve a la pantalla normal (sin tocar el destino). */
    private fun cancelarRuta() {
        rumboArriba = false
        simulando = false
        ui.removeCallbacks(simTick)
        ruta = null
        modoRuta = 0
        calculando = false
        rutasAlt = emptyList()
        idxSel = 0
        autoIniciar = false
        reconstruirAlternativas()
        ponerRuta()
        btnPrimario.text = "Cómo llegar"
        bannerNav.visibility = View.GONE
        barraNav.visibility = View.GONE
        pillBusqueda.visibility = View.VISIBLE
        ajustarColumnas()
    }

    private fun ponerRuta() {
        val r = ruta
        val vacio = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        estilo?.getSourceAs<GeoJsonSource>("ruta")?.setGeoJson(if (r == null) vacio else r.geojson())
        // En la vista previa, las otras opciones se dibujan más tenues
        val sb = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
        var n = 0
        if (modoRuta == 1) {
            for (o in rutasAlt) {
                if (o === r) continue
                if (n > 0) sb.append(',')
                sb.append("{\"type\":\"Feature\",\"properties\":{},\"geometry\":").append(o.geojson()).append('}')
                n++
            }
        }
        sb.append("]}")
        estilo?.getSourceAs<GeoJsonSource>("rutaAlt")?.setGeoJson(if (n == 0) vacio else sb.toString())
    }

    // ── Simulación del recorrido (para probar sin salir a la calle) ──

    private val simTick = object : Runnable {
        override fun run() {
            val r = ruta
            if (!simulando || r == null || modoRuta != 2) return
            simDist += 14.0                                   // ≈ 50 km/h
            val pe = r.puntoEn(simDist)
            val loc = Location("simulado")
            loc.latitude = pe[0]
            loc.longitude = pe[1]
            loc.bearing = pe[2].toFloat()
            loc.speed = 14f
            loc.accuracy = 5f
            loc.time = System.currentTimeMillis()
            loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            procesarUbicacion(loc)
            if (simulando && modoRuta == 2) ui.postDelayed(this, 1000L)
        }
    }

    private fun alternarSimulacion() {
        val r = ruta ?: return
        if (modoRuta != 2) return
        simulando = !simulando
        btnSimular.text = if (simulando) "Detener" else "Simular"
        if (simulando) {
            val l = lastLoc
            simDist = if (l != null) r.ubicar(l.latitude, l.longitude, 0, r.lat.size - 2).along else 0.0
            follow = true
            actualizarBotonCentrar()
            ui.post(simTick)
        } else {
            ui.removeCallbacks(simTick)
        }
    }

    /** Fija una posición de prueba (útil en interiores o para probar rutas desde cualquier punto). */
    private fun fijarPosicionPrueba() {
        val d = destino ?: return
        val loc = Location("prueba")
        loc.latitude = d.lat
        loc.longitude = d.lon
        loc.accuracy = 5f
        loc.time = System.currentTimeMillis()
        loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        ocultarLugar()
        follow = true
        actualizarBotonCentrar()
        procesarUbicacion(loc)
        aviso("Posición de prueba fijada. Ahora busca o elige un destino y toca «Cómo llegar».")
    }

    // ───────────────────────── velocímetro y diagnóstico (cada segundo) ─────────────────────────

    private val tick = object : Runnable {
        override fun run() {
            fps = frames.getAndSet(0)
            actualizarVelocidad()
            if (instalando) actualizarTarjetas()
            if (tvDiagAcerca != null && panelAjustes.visibility == View.VISIBLE) actualizarDiagnostico()
            ui.postDelayed(this, 1000L)
        }
    }

    private fun actualizarVelocidad() {
        val l = lastLoc
        val edad = if (l != null) edadSeg(l) else -1L
        val acc = if (l != null && l.hasAccuracy()) l.accuracy.toInt() else -1
        val buscandoMucho = tBusquedaGps != 0L && SystemClock.elapsedRealtime() - tBusquedaGps > 20000L
        val color: Int
        val msg: String
        when {
            !tieneAlgunPermiso() -> { color = Color.parseColor("#EF4444"); msg = "Sin permiso de ubicación" }
            l != null && l.provider == "simulado" -> { color = Color.parseColor("#A78BFA"); msg = "Simulando recorrido" }
            l != null && l.provider == "prueba" -> { color = Color.parseColor("#A78BFA"); msg = "Posición de prueba" }
            l == null -> {
                if (!gpsActivo) { color = Color.parseColor("#EF4444"); msg = "Ubicación apagada" }
                else if (buscandoMucho) { color = Color.parseColor("#F59E0B"); msg = "Sin señal · mantén pulsado el mapa" }
                else { color = Color.parseColor("#F59E0B"); msg = "Buscando señal GPS…" }
            }
            edad > 15 -> { color = Color.parseColor("#F59E0B"); msg = "Sin señal GPS reciente" }
            l?.provider == LocationManager.GPS_PROVIDER -> {
                color = Color.parseColor("#22C55E")
                msg = if (acc >= 0) "GPS ±$acc m" else "GPS"
            }
            else -> {
                color = Color.parseColor("#38BDF8")
                msg = if (acc >= 0) "Ubicación aprox. ±$acc m" else "Ubicación aproximada"
            }
        }
        val kmh = if (l != null && (edad <= 5 || esSintetica(l))) Math.round(velMs * 3.6f) else 0
        tvVelocidad.text = kmh.toString()
        tvGps.text = msg
        (puntoGps.background as? GradientDrawable)?.setColor(color)
    }

    private fun t(v: Long): String = if (v > 0L) "$v ms" else "…"

    private fun actualizarDiagnostico() {
        val d = tvDiagAcerca ?: return
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        val rt = Runtime.getRuntime()
        val javaMb = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val nativaMb = Debug.getNativeHeapAllocatedSize() / 1048576
        val sis = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(sis)

        val l = lastLoc
        val lineaGps = if (l == null) "GPS: sin posición" else String.format(
            Locale.US, "GPS: %.5f, %.5f  %.0f km/h  (%s)",
            l.latitude, l.longitude, if (l.hasSpeed()) l.speed * 3.6f else 0f, l.provider ?: "?"
        )
        val sb = StringBuilder()
        sb.append("GPS 593 nativo $VERSION\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ")
            .append(Build.SUPPORTED_ABIS.firstOrNull() ?: "?").append('\n')
        sb.append("Mapa: ").append(if (mapPath != null) "${mbDe(mapPath)} MB" else "no instalado")
            .append("  |  Rutas: ").append(if (rutasPath != null) "${mbDe(rutasPath)} MB" else "no instaladas").append('\n')
        sb.append("Inicio: pantalla ${t(tCreate)} · motor ${t(tMapReady)} · estilo ${t(tStyle)} · mapa completo ${t(tIdle)}\n")
        sb.append("FPS: $fps  |  Memoria de la app: ${mi.totalPss / 1024} MB (java $javaMb, nativa $nativaMb)\n")
        sb.append("RAM libre: ${sis.availMem / 1048576} MB de ${sis.totalMem / 1048576} MB\n")
        sb.append("Ubicación: permiso ${permisoTexto()}  |  ${proveedoresTexto()}\n")
        sb.append("Posiciones recibidas: ")
            .append(if (contFix.isEmpty()) "ninguna" else contFix.entries.joinToString("  ") { "${it.key} ${it.value}" })
            .append('\n')
        sb.append("Rutas: ")
            .append(if (grafo != null) "motor listo" else if (cargandoGrafo) "preparando…" else if (errorGrafo.isNotEmpty()) errorGrafo else "no listo")
            .append('\n')
        sb.append(lineaGps)
        if (errorMapa.isNotEmpty()) sb.append("\nERROR: ").append(errorMapa)
        d.text = sb.toString()
    }
}
