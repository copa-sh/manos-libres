package org.cosasvarias.manoslibres.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.cosasvarias.manoslibres.R
import org.cosasvarias.manoslibres.feedback.Haptics
import org.cosasvarias.manoslibres.net.AgentClient
import org.cosasvarias.manoslibres.net.ClientFrame
import org.cosasvarias.manoslibres.net.ServerFrame
import org.cosasvarias.manoslibres.speech.Narrator

/**
 * El corazón de la app en ejecución.
 *
 * Es un foreground service y no un `ViewModel` porque tiene que sobrevivir a que la app no
 * esté en pantalla: el canal con el nodo, la narración y los avisos siguen mientras el móvil
 * está bloqueado en el bolsillo. Se declara como `mediaPlayback` porque narrar *es* reproducir
 * audio, y así el sistema lo respeta en vez de matarlo por inactividad.
 *
 * Que este servicio siga vivo es **la única garantía de entrega que hay**: no existe push por
 * un tercero que pueda resucitar el proceso (docs/07-decisiones.md §3). De ahí la notificación
 * permanente, `START_STICKY` y la exención de optimización de batería que se pide al usuario.
 *
 * Reparte cada frame entrante a quien corresponde:
 *
 * ```
 *   message         → narrador.encolar(frases)
 *   alert           → háptica + narrador.intercalar(spoken)
 *   task.done       → háptica `doble` + narrador.intercalar(spoken)
 *   decision.request→ háptica `largo` + narrador.interrumpirCon + DecisionActivity
 *   text.delta      → solo estado de pantalla; NO se narra
 * ```
 */
class SessionService : LifecycleService() {

    private lateinit var cliente: AgentClient
    private lateinit var narrador: Narrator
    private lateinit var haptics: Haptics

    private var sessionId: String? = null

    override fun onCreate() {
        super.onCreate()
        crearCanales()
        startForeground(1, avisoPermanente(getString(R.string.sesion_conectando)))

        haptics = Haptics(this)
        narrador = Narrator(this) { messageId, sentence ->
            // Decirle al nodo dónde va la voz: es lo que permite reanudar en la frase
            // exacta tras una reconexión.
            sessionId?.let {
                cliente.enviar(ClientFrame.Narration(it, ClientFrame.Narration.At(messageId, sentence)))
            }
        }

        // TODO(H1): url, token y device desde el almacenamiento de emparejamiento.
        cliente = AgentClient(url = "", token = "", device = "movil", scope = lifecycleScope)

        lifecycleScope.launch {
            cliente.frames.collect { manejar(it) }
        }
        cliente.conectar()
    }

    private fun manejar(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.Message -> narrador.encolar(frame.messageId, frame.narratable.sentences)

            is ServerFrame.Alert -> {
                haptics.avisar(frame.pattern)
                frame.spoken?.let(narrador::intercalar)
                // TODO(H2): notificación en el canal de avisos con `message`.
            }

            is ServerFrame.TaskDone -> {
                haptics.avisar(org.cosasvarias.manoslibres.net.AlertPattern.doble)
                narrador.intercalar(frame.spoken)
            }

            is ServerFrame.DecisionRequest -> {
                haptics.avisar(org.cosasvarias.manoslibres.net.AlertPattern.largo)
                narrador.interrumpirCon(frame.spoken)
                // TODO(H3): full-screen intent a DecisionActivity + notificación con las
                // opciones como acciones, para contestar desde la pantalla de bloqueo.
            }

            is ServerFrame.DecisionResolved -> {
                // TODO(H3): cerrar DecisionActivity si estaba abierta y decir quién contestó.
            }

            is ServerFrame.State -> {
                // TODO(H1): actualizar el aviso permanente. Es el único indicador de
                // «está pasando algo» que se consulta de un vistazo.
            }

            is ServerFrame.Hello -> {
                sessionId = frame.sessions.firstOrNull()?.sessionId
                // TODO(chat): pintar frame.chatHistory en la pantalla de chat al reconectar.
            }

            is ServerFrame.Chat -> {
                // TODO(chat): encolar en la pantalla de chat entre dispositivos. No se narra:
                // es texto entre tú y tú mismo, no del agente.
            }

            // Los deltas alimentan la pantalla, no la voz.
            is ServerFrame.Delta -> Unit

            else -> Unit
        }
    }

    override fun onDestroy() {
        cliente.cerrar()
        narrador.liberar()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    // ── Notificaciones ─────────────────────────────────────────────────────────

    /**
     * Tres canales, porque el usuario tiene que poder silenciar el ruido sin silenciar lo
     * que le bloquea. `decisiones` es de importancia máxima y no respeta No Molestar: es lo
     * único que de verdad exige atención inmediata.
     */
    private fun crearCanales() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL_SESION, getString(R.string.canal_sesion), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.canal_sesion_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CANAL_AVISOS, getString(R.string.canal_avisos), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = getString(R.string.canal_avisos_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CANAL_DECISIONES, getString(R.string.canal_decisiones), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = getString(R.string.canal_decisiones_desc)
                    setBypassDnd(true)
                }
        )
    }

    private fun avisoPermanente(texto: String): Notification =
        Notification.Builder(this, CANAL_SESION)
            .setContentTitle(getString(R.string.sesion_activa))
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    private companion object {
        const val CANAL_SESION = "sesion"
        const val CANAL_AVISOS = "avisos"
        const val CANAL_DECISIONES = "decisiones"
    }
}
