package org.cosasvarias.manoslibres.input

import android.os.Bundle
import android.os.PowerManager
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.cosasvarias.manoslibres.net.AlertPattern
import org.cosasvarias.manoslibres.session.SessionService
import org.cosasvarias.manoslibres.session.SessionStore

/**
 * Modo A: la pantalla negra.
 *
 * El límite que hay que decir en voz alta: **con la pantalla físicamente apagada, Android
 * no entrega eventos táctiles a una app.** El digitalizador se apaga, y los pocos gestos
 * que sobreviven (el doble toque para despertar) son del sistema y no son interceptables.
 * No hay permiso ni API que lo cambie.
 *
 * Así que esto hace lo siguiente mejor: una superficie negra a pantalla completa con el
 * brillo a cero. La pantalla está encendida pero invisible — en un panel OLED consume
 * prácticamente lo mismo que apagada, y el usuario ve exactamente lo que vería con la
 * pantalla apagada, o sea nada. A cambio, los gestos son táctiles de verdad y ocupan toda
 * la superficie del móvil.
 *
 * | Gesto        | Acción                          | Confirmación        |
 * |--------------|---------------------------------|---------------------|
 * | toque        | pausa / reanuda                 | `corto`             |
 * | doble toque  | frase anterior                  | `corto`             |
 * | mantener     | ×2 mientras se mantiene         | pulso al entrar/salir |
 * | deslizar →   | frase siguiente                 | `corto`             |
 * | deslizar ←   | mensaje anterior completo       | `doble`             |
 * | deslizar ↑   | repetir el mensaje entero       | `doble`             |
 * | deslizar ↓   | salir del modo narración        | `largo`             |
 * | dos dedos    | dice el estado del agente       | —                   |
 *
 * Quien actúa es el [org.cosasvarias.manoslibres.speech.Narrator] del [SessionService], al
 * que se llega por [SessionService.instancia]: si el servicio no está vivo no hay nada que
 * controlar y la actividad se cierra.
 *
 * **Modo bolsillo.** Mientras la actividad está en primer plano se mantiene un
 * `PROXIMITY_SCREEN_OFF_WAKE_LOCK`: al meter el móvil en el bolsillo el sensor apaga la
 * pantalla de verdad y desactiva los toques fantasma; al sacarlo vuelven sin transición.
 *
 * **Decisiones.** Si llega una `decision.request` el brillo vuelve a automático (el servicio
 * lanza `DecisionActivity` encima); al resolverse se vuelve a negro.
 */
class BlackScreenActivity : ComponentActivity() {

    private lateinit var gestos: GestureReader
    private var proximidad: PowerManager.WakeLock? = null

    private val servicio: SessionService? get() = SessionService.instancia

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ponerBrillo(apagado = true)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if (servicio == null) {
            finish()
            return
        }

        gestos = GestureReader(
            context = this,
            onToque = {
                servicio?.let { s ->
                    s.narrador.pausarOSeguir()
                    s.haptics.avisar(AlertPattern.corto)
                }
            },
            onDobleToque = {
                servicio?.let { s ->
                    s.narrador.frasePrevia()
                    s.haptics.avisar(AlertPattern.corto)
                }
            },
            onMantener = { activo ->
                servicio?.let { s ->
                    s.haptics.tic()
                    s.narrador.acelerar(activo)
                }
            },
            onDeslizar = { dir ->
                val s = servicio
                when (dir) {
                    Direccion.DERECHA -> s?.let {
                        it.narrador.fraseSiguiente()
                        it.haptics.avisar(AlertPattern.corto)
                    }
                    Direccion.IZQUIERDA -> s?.let {
                        it.narrador.mensajeAnterior()
                        it.haptics.avisar(AlertPattern.doble)
                    }
                    Direccion.ARRIBA -> s?.let {
                        it.narrador.repetirMensaje()
                        it.haptics.avisar(AlertPattern.doble)
                    }
                    Direccion.ABAJO -> {
                        s?.haptics?.avisar(AlertPattern.largo)
                        finish()
                    }
                }
            },
            onDosDedos = { servicio?.decirEstado() },
        )

        // Brillo según haya o no una decisión pendiente.
        lifecycleScope.launch {
            SessionStore.decision.collect { d -> ponerBrillo(apagado = d == null) }
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        activarProximidad()
    }

    override fun onPause() {
        visible = false
        desactivarProximidad()
        if (::gestos.isInitialized) gestos.liberar()
        super.onPause()
    }

    override fun onDestroy() {
        desactivarProximidad()
        super.onDestroy()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (::gestos.isInitialized) gestos.procesar(event) else super.onTouchEvent(event)

    /** 0f, no BRIGHTNESS_OVERRIDE_OFF: pantalla encendida (para recibir toques) pero sin emitir luz. */
    private fun ponerBrillo(apagado: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (apagado) 0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun activarProximidad() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        val wl = proximidad ?: pm.newWakeLock(
            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
            "manoslibres:bolsillo",
        ).also {
            it.setReferenceCounted(false)
            proximidad = it
        }
        if (!wl.isHeld) wl.acquire()
    }

    private fun desactivarProximidad() {
        proximidad?.let { if (it.isHeld) it.release() }
    }

    companion object {
        /** ¿Está la pantalla negra delante? El servicio lo mira para lanzar la decisión encima. */
        @Volatile
        var visible: Boolean = false
            private set
    }
}
