package ec.gps593.nativo

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
import android.provider.Settings
import android.speech.tts.TextToSpeech
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

private const val VERSION = "0.5"
private const val REQ_ARCHIVOS = 77
private const val REQ_PERM_UBICACION = 1
private const val REQ_PERM_ALMACEN = 2

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
    private lateinit var izqAbajo: LinearLayout
    private lateinit var derAbajo: LinearLayout

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
            fondo = Color.parseColor("#F0111827"), fondoClaro = Color.parseColor("#1B2535"),
            borde = Color.parseColor("#26FFFFFF"), texto = Color.parseColor("#F1F5F9"),
            sub = Color.parseColor("#94A3B8"), acento = Color.parseColor("#3B82F6"),
            acento2 = Color.parseColor("#6366F1"), pantalla = Color.parseColor("#0B1220"),
            pantalla2 = Color.parseColor("#16233F"), hoja = Color.parseColor("#0E1626")
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
        derAbajo.addView(botonRedondo(R.drawable.ic_add, 50) { map?.animateCamera(CameraUpdateFactory.zoomIn()) })
        derAbajo.addView(botonRedondo(R.drawable.ic_remove, 50) { map?.animateCamera(CameraUpdateFactory.zoomOut()) })
        btnCentrar = botonRedondo(R.drawable.ic_near_me, 62) { centrar() }
        btnCentrar.destacado = true
        derAbajo.addView(btnCentrar)
        raiz.addView(derAbajo, FrameLayout.LayoutParams(wc, wc, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(14), dp(24))
        })

        crearTarjetaLugar()
        crearTarjetaMapa()
        crearPanelBusqueda()
        crearPanelAjustes()
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

        val abajo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        abajo.addView(botonTexto("Cómo llegar", true) {
            aviso("La navegación giro a giro llega en la próxima versión. Ya puedes buscar y guardar lugares.")
        })
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
        filaBotonesMapa = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filaBotonesMapa.addView(
            botonTexto("Elegir archivos", true) { elegirArchivos() },
            LinearLayout.LayoutParams(wc, wc).apply { rightMargin = dp(10) }
        )
        filaBotonesMapa.addView(botonTexto("Buscar en el pendrive", false) { buscarEnPendrive() })
        tarjetaMapa.addView(tvMapaTitulo)
        tarjetaMapa.addView(tvMapaTexto)
        tarjetaMapa.addView(tvMapaError)
        tarjetaMapa.addView(barraCopia, LinearLayout.LayoutParams(mw, dp(8)).apply { topMargin = dp(16) })
        tarjetaMapa.addView(tvPct)
        tarjetaMapa.addView(filaBotonesMapa, LinearLayout.LayoutParams(wc, wc).apply { topMargin = dp(18) })
        raiz.addView(tarjetaMapa, FrameLayout.LayoutParams(dp(560), wc, Gravity.CENTER))
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
        val barra = Barra(this).apply { indeterminada = true }
        centro.addView(splashTitulo)
        centro.addView(splashSub)
        centro.addView(barra, LinearLayout.LayoutParams(dp(170), dp(4)).apply { topMargin = dp(22) })
        splash.addView(centro, FrameLayout.LayoutParams(wc, wc, Gravity.CENTER))
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
        (tarjetaLugar.layoutParams as FrameLayout.LayoutParams).width =
            if (w < dp(760)) w - dp(28) else Math.min(dp(520), w - dp(180))
        tarjetaMapa.requestLayout()
        hojaAjustes.requestLayout()
        cardBusqueda.requestLayout()
        tarjetaLugar.requestLayout()
    }

    /** En pantallas estrechas, sube los botones laterales cuando la tarjeta del lugar ocupa el fondo. */
    private fun ajustarColumnas() {
        tarjetaLugar.post {
            val estrecho = anchoPx < dp(760)
            val sube = if (estrecho && tarjetaLugar.visibility == View.VISIBLE) -(tarjetaLugar.height + dp(10)).toFloat() else 0f
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
        etBusqueda.setTextColor(pal.texto)
        etBusqueda.setHintTextColor(pal.sub)
        splash.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(pal.pantalla, pal.pantalla2))
        splashTitulo.setTextColor(pal.texto)
        splashSub.setTextColor(pal.sub)
        btnNoche.res = if (night) R.drawable.ic_light_mode else R.drawable.ic_dark_mode
        btnCentrar.destacado = follow
        btnCentrar.res = if (follow) R.drawable.ic_near_me else R.drawable.ic_my_location
        pintarBoton(btnCentrar)
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

    private fun pedirPermisoUbicacion() {
        if (!tienePermisoGps()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_PERM_UBICACION)
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
            ponerDestino()
        }
    }

    private fun centrar() {
        follow = true
        actualizarBotonCentrar()
        if (!tienePermisoGps()) {
            aviso("Permite el acceso a la ubicación para centrar el mapa en tu posición.")
            pedirPermisoUbicacion()
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = lastLoc
        if (l != null && edadSeg(l) <= 120L) {
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
        aviso("Buscando tu posición… En interiores el GPS puede tardar; sal a cielo abierto.")
        gpsActivo = false
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
            ui.post {
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
                    prepararIndice()
                }
                if (errores.isEmpty() && (nuevoMapa || nuevasRutas)) aviso("Listo: los archivos quedaron instalados.")
                actualizarTarjetas()
                refrescarAjustes()
            }
        }.start()
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
                if (!idx.exists() || idx.lastModified() < g.lastModified()) IndiceLugares.construir(g, idx)
                val ind = IndiceLugares.cargar(idx) ?: throw RuntimeException("índice no válido")
                ui.post {
                    indice = ind
                    indexando = false
                    refrescarAjustes()
                    if (panelBusqueda.visibility == View.VISIBLE) mostrarResultados(ultimosResultados, ultimaConsulta, ultimoOrigen)
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
                tvMapaError.visibility = View.GONE
            } else {
                tvMapaTitulo.text = "Instala el mapa de Ecuador"
                tvMapaTexto.text = "Elige los archivos ecuador.pmtiles (mapa) y ecuador.g593 (rutas y búsqueda). " +
                    "Puedes escoger los dos a la vez; la app los reconoce sola."
                barraCopia.visibility = View.GONE
                tvPct.visibility = View.GONE
                filaBotonesMapa.visibility = View.VISIBLE
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
            Thread {
                var res: List<Resultado> = emptyList()
                var origen = ""
                try {
                    if (ind != null) {
                        res = ind.buscar(q)
                        origen = "sin internet"
                    }
                    if (res.isEmpty()) {
                        res = BusquedaEnLinea.buscar(q)
                        origen = "en línea"
                    }
                } catch (e: Throwable) {
                    origen = "error"
                }
                ui.post { if (id == busquedaId) mostrarResultados(res, q, origen) }
            }.start()
        }
    }

    private fun abrirBusqueda() {
        if (panelAjustes.visibility == View.VISIBLE) return
        panelBusqueda.visibility = View.VISIBLE
        panelBusqueda.alpha = 0f
        panelBusqueda.animate().alpha(1f).setDuration(160L).start()
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
            val ico = icono(R.drawable.ic_place, 26, pal.acento)
            (ico.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14)
            fila.addView(ico)
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val nombre = txt(15f, "sans-serif-medium", pal.texto).apply {
                text = r.nombre
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val sub = txt(12f, "sans-serif", pal.sub).apply {
                text = r.tipoTexto() + " · " + origen
                setPadding(0, dp(2), 0, 0)
            }
            col.addView(nombre)
            col.addView(sub)
            fila.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            listaResultados.addView(fila)
        }
    }

    private fun seleccionarResultado(r: Resultado) {
        if (panelBusqueda.visibility == View.VISIBLE) cerrarBusqueda()
        if (panelAjustes.visibility == View.VISIBLE) cerrarAjustes()
        destino = r
        ponerDestino()
        follow = false
        actualizarBotonCentrar()
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(r.lat, r.lon), 15.5))
        tvLugarTitulo.text = r.nombre
        tvLugarSub.text = r.tipoTexto() + String.format(Locale.US, " · %.5f, %.5f", r.lat, r.lon)
        tarjetaLugar.visibility = View.VISIBLE
        actualizarEstrella()
        ajustarColumnas()
    }

    private fun ocultarLugar() {
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

    // ───────────────────────── favoritos ─────────────────────────

    private fun leerFavs(): MutableList<Resultado> {
        val out = ArrayList<Resultado>()
        try {
            val a = JSONArray(prefs.getString("favs", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Resultado(o.getString("n"), o.getDouble("la"), o.getDouble("lo"), o.optInt("t", 6)))
            }
        } catch (e: Exception) { /* lista vacía */ }
        return out
    }

    private fun guardarFavs(l: List<Resultado>) {
        val a = JSONArray()
        for (r in l) a.put(JSONObject().put("n", r.nombre).put("la", r.lat).put("lo", r.lon).put("t", r.tipo))
        prefs.edit().putString("favs", a.toString()).apply()
    }

    private fun mismoLugar(a: Resultado, b: Resultado): Boolean =
        Math.abs(a.lat - b.lat) < 1e-5 && Math.abs(a.lon - b.lon) < 1e-5

    private fun actualizarEstrella() {
        val d = destino
        val fav = d != null && leerFavs().any { mismoLugar(it, d) }
        btnFav.res = if (fav) R.drawable.ic_star_fill else R.drawable.ic_star_line
        btnFav.tinte = if (fav) Color.parseColor("#F59E0B") else 0
        btnFav.invalidate()
    }

    private fun alternarFavorito() {
        val d = destino ?: return
        val l = leerFavs()
        val ya = l.firstOrNull { mismoLugar(it, d) }
        if (ya != null) {
            l.remove(ya)
            aviso("Quitado de favoritos")
        } else {
            l.add(0, d)
            aviso("Guardado en favoritos")
        }
        guardarFavs(l)
        actualizarEstrella()
        if (panelAjustes.visibility == View.VISIBLE) refrescarAjustes()
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
        } else {
            val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fila.addView(
                crearBotonTexto("Elegir archivos", true) { elegirArchivos() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) }
            )
            fila.addView(crearBotonTexto("Buscar en el pendrive", false) { buscarEnPendrive() })
            tj.addView(fila, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
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
            ind != null -> { rutasSub = "Instalado · ${mbDe(rutasPath)} MB · ${ind.total} lugares para buscar"; rutasOk = true }
            errorIndice.isNotEmpty() -> { rutasSub = "Instalado, pero no pude preparar la búsqueda: $errorIndice"; rutasOk = false }
            else -> { rutasSub = "Instalado · ${mbDe(rutasPath)} MB"; rutasOk = null }
        }
        c.addView(filaEstado(R.drawable.ic_route, "Rutas y búsqueda", rutasSub, rutasOk))
        c.addView(txt(12.5f, "sans-serif", pal.sub).apply {
            text = "Las rutas giro a giro usarán este mismo archivo en la próxima versión."
            setPadding(dp(4), dp(6), dp(4), 0)
        })
    }

    private fun filaLugar(r: Resultado): LinearLayout {
        val pal = paleta()
        val f = caja(18, 14, 10)
        f.orientation = LinearLayout.HORIZONTAL
        f.gravity = Gravity.CENTER_VERTICAL
        f.isClickable = true
        f.setOnClickListener { seleccionarResultado(r) }
        val ico = icono(R.drawable.ic_place, 26, pal.acento)
        (ico.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(12)
        f.addView(ico)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(txt(15f, "sans-serif-medium", pal.texto).apply {
            text = r.nombre
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(txt(12f, "sans-serif", pal.sub).apply {
            text = r.tipoTexto()
            setPadding(0, dp(2), 0, 0)
        })
        f.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val borrar = icono(R.drawable.ic_delete, 34, pal.sub)
        borrar.setOnClickListener {
            val l = leerFavs()
            l.removeAll { mismoLugar(it, r) }
            guardarFavs(l)
            actualizarEstrella()
            refrescarAjustes()
        }
        f.addView(borrar)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        f.layoutParams = lp
        return f
    }

    private fun tabLugares() {
        val pal = paleta()
        val c = contenidoAjustes
        c.addView(seccion("Favoritos"))
        val favs = leerFavs()
        if (favs.isEmpty()) {
            c.addView(caja(18, 16, 14).apply {
                addView(txt(14f, "sans-serif", pal.sub).apply {
                    text = "Aún no tienes favoritos. Busca un lugar y toca la estrella para guardarlo."
                    setLineSpacing(0f, 1.15f)
                })
            })
        } else {
            for (f in favs) c.addView(filaLugar(f))
        }
        c.addView(seccion("Rutas guardadas"))
        c.addView(caja(18, 16, 14).apply {
            addView(txt(14f, "sans-serif", pal.sub).apply {
                text = "Aquí aparecerán tus rutas cuando llegue la navegación."
                setLineSpacing(0f, 1.15f)
            })
        })
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

        c.addView(seccion("Voz"))
        c.addView(filaInterruptor("Instrucciones por voz", "Se usarán durante la navegación", vozOn) { v ->
            vozOn = v
            prefs.edit().putBoolean("voz", v).apply()
        })
        val probar = caja(18, 16, 14)
        probar.addView(crearBotonTexto("Probar voz", false) { probarVoz() })
        c.addView(probar)
    }

    private fun probarVoz() {
        val actual = tts
        if (actual == null) {
            tts = TextToSpeech(this) { estado ->
                if (estado == TextToSpeech.SUCCESS) {
                    tts?.setLanguage(Locale("es", "EC"))
                    hablarPrueba()
                } else {
                    aviso("Este equipo no tiene una voz instalada.")
                }
            }
        } else {
            hablarPrueba()
        }
    }

    private fun hablarPrueba() {
        tts?.speak("En doscientos metros, gire a la derecha.", TextToSpeech.QUEUE_FLUSH, null, "prueba")
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
        c.addView(seccion("Mapas y datos"))
        c.addView(caja(18, 16, 14).apply {
            addView(txt(13f, "sans-serif", pal.sub).apply {
                text = "Mapas © OpenStreetMap contributors · teselas Protomaps · motor MapLibre Native · íconos Material Symbols."
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

    /** Pide posiciones al GPS y también a la red (wifi/celular): en interiores la red localiza al instante, aproximado. */
    private fun iniciarGps() {
        if (gpsActivo || !tienePermisoGps()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var alguno = false
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
                alguno = true
            }
        } catch (e: SecurityException) { /* sin permiso preciso */ } catch (e: Exception) { /* proveedor no disponible */ }
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, this)
                alguno = true
            }
        } catch (e: SecurityException) { /* nada */ } catch (e: Exception) { /* nada */ }
        gpsActivo = alguno
        if (lastLoc == null) {
            for (prov in arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    val k = lm.getLastKnownLocation(prov)
                    // Solo si es reciente: una posición vieja no sirve para centrar el mapa
                    if (k != null && edadSeg(k) < 120L) { onLocationChanged(k); break }
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

    private fun puntoJson(l: Location): String =
        "{\"type\":\"Point\",\"coordinates\":[${l.longitude},${l.latitude}]}"

    private fun ponerPosicion(l: Location?) {
        if (l == null) return
        estilo?.getSourceAs<GeoJsonSource>("yo")?.setGeoJson(puntoJson(l))
    }

    override fun onLocationChanged(location: Location) {
        val prev = lastLoc
        // Si ya hay un GPS reciente, ignora la posición aproximada de la red
        if (prev != null && location.provider == LocationManager.NETWORK_PROVIDER &&
            prev.provider == LocationManager.GPS_PROVIDER && edadSeg(prev) < 15L
        ) return
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
            if (instalando) actualizarTarjetas()
            if (tvDiagAcerca != null && panelAjustes.visibility == View.VISIBLE) actualizarDiagnostico()
            ui.postDelayed(this, 1000L)
        }
    }

    private fun actualizarVelocidad() {
        val l = lastLoc
        val edad = if (l != null) edadSeg(l) else -1L
        val acc = if (l != null && l.hasAccuracy()) l.accuracy.toInt() else -1
        val color: Int
        val msg: String
        when {
            !tienePermisoGps() -> { color = Color.parseColor("#EF4444"); msg = "Sin permiso de ubicación" }
            l == null -> {
                if (gpsActivo) { color = Color.parseColor("#F59E0B"); msg = "Buscando señal GPS…" }
                else { color = Color.parseColor("#EF4444"); msg = "Ubicación apagada" }
            }
            edad > 15 -> { color = Color.parseColor("#F59E0B"); msg = "Sin señal GPS reciente" }
            l?.provider == LocationManager.NETWORK_PROVIDER -> {
                color = Color.parseColor("#38BDF8")
                msg = if (acc >= 0) "Ubicación aprox. ±$acc m" else "Ubicación aproximada"
            }
            else -> {
                color = Color.parseColor("#22C55E")
                msg = if (acc >= 0) "GPS ±$acc m" else "GPS"
            }
        }
        val kmh = if (l != null && edad <= 5 && l.hasSpeed()) Math.round(l.speed * 3.6f) else 0
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
        sb.append(lineaGps)
        if (errorMapa.isNotEmpty()) sb.append("\nERROR: ").append(errorMapa)
        d.text = sb.toString()
    }
}
