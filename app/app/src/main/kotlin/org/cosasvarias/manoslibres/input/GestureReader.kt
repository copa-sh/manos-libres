package org.cosasvarias.manoslibres.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

enum class Direccion { ARRIBA, ABAJO, IZQUIERDA, DERECHA }

/** Los umbrales y la geometría de los gestos, sin Android: se prueba con JUnit pelado. */
object GestureMath {
    /** Dos toques separados por menos de esto son un doble toque. */
    const val DOBLE_TOQUE_MS = 300L
    /** Un dedo quieto durante esto es «mantener». */
    const val MANTENER_MS = 400L
    /** Un deslizamiento recorre al menos esta fracción de la dimensión de la pantalla. */
    const val UMBRAL_DESLIZAR = 0.15f
    /** Un toque con dos dedos dura como mucho esto. */
    const val DOS_DEDOS_MAX_MS = 500L

    /**
     * Dirección de un desplazamiento de ([dx], [dy]) píxeles en una pantalla de [ancho]×[alto],
     * o null si no llega al 15 % en ninguno de los dos ejes. Gana el eje que más ha avanzado
     * *en proporción a su dimensión*, así que en una pantalla alta un trazo diagonal no se
     * decide por tener más píxeles verticales.
     */
    fun clasificarDeslizamiento(dx: Float, dy: Float, ancho: Int, alto: Int): Direccion? {
        if (ancho <= 0 || alto <= 0) return null
        val nx = abs(dx) / ancho
        val ny = abs(dy) / alto
        if (nx < UMBRAL_DESLIZAR && ny < UMBRAL_DESLIZAR) return null
        return if (nx >= ny) {
            if (dx > 0) Direccion.DERECHA else Direccion.IZQUIERDA
        } else {
            if (dy > 0) Direccion.ABAJO else Direccion.ARRIBA
        }
    }

    /** ¿Fue un toque? Poco movimiento (dentro del *slop*) y más corto que «mantener». */
    fun esToque(dx: Float, dy: Float, duracionMs: Long, slopPx: Float): Boolean =
        hypot(dx, dy) <= slopPx && duracionMs < MANTENER_MS

    /** ¿Fue un toque con dos dedos? Rápido y sin que el primero se haya movido. */
    fun esToqueDosDedos(movido: Boolean, duracionMs: Long): Boolean =
        !movido && duracionMs <= DOS_DEDOS_MAX_MS
}

/**
 * Reconocedor de los siete gestos: toque, doble toque, mantener, cuatro deslizamientos y
 * toque con dos dedos.
 *
 * No se usa `GestureDetector` del sistema porque su «long press» es un evento puntual y aquí
 * hace falta saber cuándo se *suelta*: la aceleración dura lo que dura la pulsación.
 *
 * Detalles que conviene saber:
 *  · El **toque simple** se retrasa [GestureMath.DOBLE_TOQUE_MS] para saber si es el primero
 *    de un doble; es el precio de que el doble toque exista.
 *  · **Mantener** se dispara a los 400 ms con el dedo quieto: `onMantener(true)`; al soltar,
 *    `onMantener(false)`. Si el dedo se mueve antes, el temporizador se cancela y es un
 *    deslizamiento o nada.
 *  · Un **deslizamiento** se decide al soltar, por el desplazamiento total.
 *  · Un segundo dedo convierte el gesto en «dos dedos», salvo que ya se estuviera
 *    manteniendo.
 *  · Todo ocurre en el hilo principal.
 */
class GestureReader(
    context: Context,
    private val onToque: () -> Unit,
    private val onDobleToque: () -> Unit,
    private val onMantener: (Boolean) -> Unit,
    private val onDeslizar: (Direccion) -> Unit,
    private val onDosDedos: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val metrics = context.resources.displayMetrics
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var x0 = 0f
    private var y0 = 0f
    private var xUlt = 0f
    private var yUlt = 0f
    private var t0 = 0L
    private var movido = false
    private var variosDedos = false
    private var manteniendo = false

    private var toquePendiente = false
    private var tUltimoToque = 0L

    private val alMantener = Runnable {
        manteniendo = true
        onMantener(true)
    }
    private val alToqueSimple = Runnable {
        toquePendiente = false
        onToque()
    }

    fun procesar(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                main.removeCallbacks(alMantener)
                x0 = e.x; y0 = e.y; xUlt = e.x; yUlt = e.y
                t0 = e.eventTime
                movido = false
                variosDedos = false
                manteniendo = false
                main.postDelayed(alMantener, GestureMath.MANTENER_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!manteniendo) {
                    variosDedos = true
                    main.removeCallbacks(alMantener)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                xUlt = e.x; yUlt = e.y
                if (!movido && hypot(xUlt - x0, yUlt - y0) > slop) {
                    movido = true
                    if (!manteniendo) main.removeCallbacks(alMantener)
                }
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(alMantener)
                xUlt = e.x; yUlt = e.y
                terminar(e.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(alMantener)
                if (manteniendo) onMantener(false)
                manteniendo = false
            }
        }
        return true
    }

    private fun terminar(t: Long) {
        val duracion = t - t0
        when {
            manteniendo -> {
                manteniendo = false
                onMantener(false)
            }
            variosDedos -> {
                if (GestureMath.esToqueDosDedos(movido, duracion)) onDosDedos()
            }
            else -> {
                val dx = xUlt - x0
                val dy = yUlt - y0
                val dir = GestureMath.clasificarDeslizamiento(dx, dy, metrics.widthPixels, metrics.heightPixels)
                if (dir != null) {
                    onDeslizar(dir)
                } else if (GestureMath.esToque(dx, dy, duracion, slop)) {
                    toque(t)
                }
            }
        }
    }

    private fun toque(t: Long) {
        if (toquePendiente && t - tUltimoToque <= GestureMath.DOBLE_TOQUE_MS) {
            main.removeCallbacks(alToqueSimple)
            toquePendiente = false
            onDobleToque()
        } else {
            toquePendiente = true
            tUltimoToque = t
            main.postDelayed(alToqueSimple, GestureMath.DOBLE_TOQUE_MS)
        }
    }

    /** Cancela lo pendiente (toque simple a medias, mantener) al salir de la pantalla. */
    fun liberar() {
        main.removeCallbacks(alMantener)
        main.removeCallbacks(alToqueSimple)
        if (manteniendo) onMantener(false)
        manteniendo = false
        toquePendiente = false
    }
}
