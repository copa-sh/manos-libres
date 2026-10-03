package org.cosasvarias.manoslibres.input

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.media.VolumeProviderCompat

/**
 * Modo B: la pantalla apagada de verdad.
 *
 * Cuando el teléfono no se va a sacar del bolsillo, los gestos táctiles no existen
 * (ver [BlackScreenActivity]). Lo que sí llega es el botón del auricular y —mientras haya
 * una sesión de medios activa— las teclas de volumen.
 *
 * El mapeo mantiene la misma intención que el modo A: **doble = atrás, mantener =
 * acelerar**. Lo que cambia es la superficie, no el vocabulario.
 *
 * | Entrada                       | Sin decisión pendiente | Con decisión pendiente |
 * |-------------------------------|------------------------|------------------------|
 * | botón del auricular ×1        | pausa / reanuda        | opción 1               |
 * | ×2                            | frase anterior         | opción 2               |
 * | ×3                            | frase siguiente        | opción 3 (…)           |
 * | botón mantenido               | ×2 mientras se aprieta | —                      |
 * | volumen ↓ mantenido           | frase anterior         | —                      |
 * | volumen ↑ mantenido           | ×2 mientras se aprieta | —                      |
 *
 * **Por qué se intercepta `onMediaButtonEvent`.** El `Callback` del sistema ya convierte la
 * doble pulsación del botón en `onSkipToNext`, lo contrario de lo que queremos (doble =
 * atrás). Así que las pulsaciones se cuentan aquí, con [PressCounter] y su ventana de
 * 600 ms, y se encaminan según haya o no una decisión pendiente. Consecuencia asumida: una
 * pulsación simple tarda 600 ms en actuar, porque hay que esperar a ver si es la primera de
 * dos.
 *
 * **Teclas de volumen.** Android solo las entrega a una sesión que declara volumen remoto, y
 * entonces el volumen del sistema deja de moverse por sí solo. Un toque suelto (sin
 * repetición) se reenvía a `STREAM_MUSIC` tras 700 ms; una pulsación mantenida se reconoce
 * por la cadencia de autorrepetición (llamadas separadas < 200 ms) y dispara el gesto.
 *
 * Publicar una `MediaSession` tiene una ventaja que no es obvia: la narración pasa a
 * comportarse como cualquier audio del sistema. Se pausa con una llamada, baja de volumen
 * con un aviso, y aparece en los controles de la pantalla de bloqueo y del reloj.
 */
class MediaButtons(
    private val context: Context,
    private val onPausarOSeguir: () -> Unit,
    private val onFrasePrevia: () -> Unit,
    private val onFraseSiguiente: () -> Unit,
    private val onAcelerar: (Boolean) -> Unit,
    /** N pulsaciones = opción N de la decisión pendiente (N empieza en 1). */
    private val onElegirOpcion: (Int) -> Unit,
    /** Cuántas opciones principales se pueden contestar por número; 0 si no hay decisión. */
    private val opcionesPendientes: () -> Int,
    /** Opcional: un pulso de confirmación (háptica) al reconocer una acción. */
    private val onConfirmar: () -> Unit = {},
    capturarVolumen: Boolean = true,
) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val contador = PressCounter()

    private var mantenidoAuricular = false

    private val alCerrarVentana = Runnable {
        val n = contador.cerrar(SystemClock.uptimeMillis())
        if (n > 0) encaminar(n)
    }

    val session = MediaSessionCompat(context, "manos-libres").apply {
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean =
                manejarBoton(mediaButtonEvent) || super.onMediaButtonEvent(mediaButtonEvent)

            // Controles de la pantalla de bloqueo y del reloj: no pasan por el contador.
            override fun onPlay() = onPausarOSeguir()
            override fun onPause() = onPausarOSeguir()
            override fun onSkipToPrevious() = onFrasePrevia()
            override fun onSkipToNext() = onFraseSiguiente()
            override fun onFastForward() = onAcelerar(true)
            override fun onRewind() = onAcelerar(false)
        }, main)

        setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Manos Libres")
                .build()
        )
        actualizarEstado(this, reproduciendo = false)
        isActive = true
    }

    // ── Estado hacia el sistema ────────────────────────────────────────────────

    /**
     * Refleja si la narración suena o está en pausa, y la frase en curso como título. Sin
     * `ACTION_SKIP_TO_PREVIOUS/NEXT` declaradas el sistema no encamina los botones hasta aquí.
     */
    fun actualizar(reproduciendo: Boolean, frase: String?) {
        actualizarEstado(session, reproduciendo)
        if (frase != null) {
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, frase)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Manos Libres")
                    .build()
            )
        }
    }

    private fun actualizarEstado(s: MediaSessionCompat, reproduciendo: Boolean) {
        s.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_REWIND
                )
                .setState(
                    if (reproduciendo) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build()
        )
    }

    // ── Botón del auricular ────────────────────────────────────────────────────

    private fun manejarBoton(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return false
        val ev = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            ?: return false

        when (ev.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> botonPrincipal(ev)

            KeyEvent.KEYCODE_MEDIA_NEXT -> if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                onFraseSiguiente()
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                onFrasePrevia()
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> when (ev.action) {
                KeyEvent.ACTION_DOWN -> onAcelerar(true)
                KeyEvent.ACTION_UP -> onAcelerar(false)
            }
            else -> return false
        }
        return true
    }

    private fun botonPrincipal(ev: KeyEvent) {
        when (ev.action) {
            KeyEvent.ACTION_DOWN -> {
                if (ev.repeatCount > 0 || ev.isLongPress) {
                    // Mantenido: acelera mientras dure, y no cuenta como pulsación.
                    if (!mantenidoAuricular) {
                        mantenidoAuricular = true
                        main.removeCallbacks(alCerrarVentana)
                        contador.reiniciar()
                        onConfirmar()
                        onAcelerar(true)
                    }
                } else {
                    contador.pulsar(SystemClock.uptimeMillis())
                    main.removeCallbacks(alCerrarVentana)
                    main.postDelayed(alCerrarVentana, PressCounter.VENTANA_MS)
                }
            }
            KeyEvent.ACTION_UP -> if (mantenidoAuricular) {
                mantenidoAuricular = false
                onConfirmar()
                onAcelerar(false)
            }
        }
    }

    /** Cierra una racha de [n] pulsaciones: contesta una decisión, o navega la narración. */
    internal fun encaminar(n: Int) {
        val opciones = opcionesPendientes()
        if (opciones > 0) {
            if (n in 1..opciones) {
                onConfirmar()
                onElegirOpcion(n)
            }
            return
        }
        when (n) {
            1 -> { onConfirmar(); onPausarOSeguir() }
            2 -> { onConfirmar(); onFrasePrevia() }
            3 -> { onConfirmar(); onFraseSiguiente() }
            else -> Unit
        }
    }

    // ── Teclas de volumen ──────────────────────────────────────────────────────

    private var volDireccion = 0
    private var volUltimo = 0L
    private var volMantenido = false

    private val alSoltarVolumen = Runnable {
        if (volMantenido) {
            volMantenido = false
            if (volDireccion > 0) onAcelerar(false)
            volDireccion = 0
        }
    }

    /** Un toque suelto: el volumen debería haberse movido, y no lo ha hecho nadie. */
    private val alToqueVolumen = Runnable {
        if (volMantenido || volDireccion == 0) return@Runnable
        val dir = if (volDireccion > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        volDireccion = 0
    }

    private fun capturarTeclasDeVolumen() {
        val proveedor = object : VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) {
                if (direction == 0) return
                val t = SystemClock.uptimeMillis()
                val repeticion = volDireccion == direction && t - volUltimo < CADENCIA_REPETICION_MS
                volUltimo = t
                if (repeticion) {
                    if (!volMantenido) {
                        volMantenido = true
                        main.removeCallbacks(alToqueVolumen)
                        onConfirmar()
                        if (direction < 0) onFrasePrevia() else onAcelerar(true)
                    }
                    main.removeCallbacks(alSoltarVolumen)
                    main.postDelayed(alSoltarVolumen, 250)
                } else if (!volMantenido) {
                    volDireccion = direction
                    main.removeCallbacks(alToqueVolumen)
                    main.postDelayed(alToqueVolumen, 700)
                }
            }
        }
        session.setPlaybackToRemote(proveedor)
    }

    init {
        // Al final a propósito: usa las propiedades de volumen de arriba, que deben estar ya inicializadas.
        if (capturarVolumen) capturarTeclasDeVolumen()
    }

    fun liberar() {
        main.removeCallbacksAndMessages(null)
        if (mantenidoAuricular) onAcelerar(false)
        if (volMantenido && volDireccion > 0) onAcelerar(false)
        session.isActive = false
        session.release()
    }

    private companion object {
        /** Autorrepetición de una tecla mantenida: calls cada ~50-100 ms. */
        const val CADENCIA_REPETICION_MS = 200L
    }
}
