package org.cosasvarias.manoslibres.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cosasvarias.manoslibres.MainActivity
import org.cosasvarias.manoslibres.R
import org.cosasvarias.manoslibres.feedback.Haptics
import org.cosasvarias.manoslibres.input.BlackScreenActivity
import org.cosasvarias.manoslibres.input.MediaButtons
import org.cosasvarias.manoslibres.net.AgentClient
import org.cosasvarias.manoslibres.net.AlertPattern
import org.cosasvarias.manoslibres.net.ClientFrame
import org.cosasvarias.manoslibres.net.OptionTone
import org.cosasvarias.manoslibres.net.ServerFrame
import org.cosasvarias.manoslibres.net.SessionInfo
import org.cosasvarias.manoslibres.net.SessionState
import org.cosasvarias.manoslibres.pairing.Emparejamiento
import org.cosasvarias.manoslibres.pairing.PairingStore
import org.cosasvarias.manoslibres.speech.Ajustes
import org.cosasvarias.manoslibres.speech.Narrator
import org.cosasvarias.manoslibres.ui.DecisionActivity
import java.util.concurrent.atomic.AtomicInteger

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
 * permanente, `START_STICKY` y la exención de optimización de batería ([solicitarExencionBateria]).
 *
 * Reparte cada frame entrante a quien corresponde:
 *
 * ```
 *   message         → SessionStore.lineas + narrador.encolar(frases)   (solo role = assistant)
 *   text.delta      → SessionStore.parcial; NO se narra
 *   session.state   → SessionStore.estado + aviso permanente
 *   alert           → háptica + narrador.intercalar(spoken) + notificación de avisos
 *   task.done       → háptica `doble` + narrador.intercalar(spoken) + notificación de avisos
 *   decision.request→ cola de pendientes; la más antigua: háptica `largo`, narración
 *                     pausada, pregunta enumerada, full-screen intent a DecisionActivity y
 *                     notificación con una acción por opción; reinsiste a los 30 s y 5 min
 *   decision.resolved → la quita de la cola y dice quién contestó
 *   chat.message / hello.chatHistory → SessionStore.chat
 * ```
 *
 * **Ciclo de vida.** Cada `onStartCommand` vuelve a leer el emparejamiento: sin él, el estado
 * es `SIN_EMPAREJAR` y el servicio se para; con él (nuevo o cambiado) se (re)crea el
 * [AgentClient]. La UI relanza el servicio al terminar de emparejar.
 *
 * **Para otros componentes.** [instancia] da acceso al [narrador] y a [haptics] a la pantalla
 * negra; [decirEstado] es lo que responde al toque con dos dedos.
 */
class SessionService : LifecycleService() {

    lateinit var haptics: Haptics
        private set
    lateinit var narrador: Narrator
        private set
    private lateinit var botones: MediaButtons
    private lateinit var nm: NotificationManager

    private var cliente: AgentClient? = null
    private var ambito: CoroutineScope? = null
    private var emparejamiento: Emparejamiento? = null
    private var sessionId: String? = null
    private var vistoConectado = false

    // Lo que se publica en SessionStore (se guarda aquí y se publica una copia).
    private val lineas = LinkedHashMap<String, SessionStore.Linea>()
    private val parcial = StringBuilder()
    private var parcialId: String? = null
    private val chat = LinkedHashMap<Long, SessionStore.MensajeChat>()

    /** Decisiones sin contestar, en orden de llegada: la primera es la que se presenta. */
    private val pendientes = mutableListOf<ServerFrame.DecisionRequest>()
    private var reinsistir: Job? = null

    private var estadoAgente = SessionState.idle
    private var turnoInicio = 0L
    private var herramientas = 0

    private val idAviso = AtomicInteger(ID_AVISO_BASE)

    override fun onCreate() {
        super.onCreate()
        instancia = this
        nm = getSystemService(NotificationManager::class.java)
        crearCanales()

        haptics = Haptics(this)
        narrador = Narrator(this) { messageId, sentence ->
            // Decirle al nodo dónde va la voz: es lo que permite reanudar en la frase
            // exacta tras una reconexión.
            val s = sessionId
            if (s != null) {
                cliente?.enviar(ClientFrame.Narration(s, ClientFrame.Narration.At(messageId, sentence)))
            }
        }
        botones = MediaButtons(
            context = this,
            onPausarOSeguir = { narrador.pausarOSeguir() },
            onFrasePrevia = { narrador.frasePrevia() },
            onFraseSiguiente = { narrador.fraseSiguiente() },
            onAcelerar = { narrador.acelerar(it) },
            onElegirOpcion = ::contestarPorNumero,
            opcionesPendientes = {
                pendientes.firstOrNull()?.takeIf { !it.multiSelect }?.options?.count { !it.sticky } ?: 0
            },
            onConfirmar = { haptics.avisar(AlertPattern.corto) },
            capturarVolumen = Ajustes.teclasVolumen(this),
        )
        narrador.onEstado = { hablando, _, texto -> botones.actualizar(hablando, texto) }

        ServiceCompat.startForeground(
            this, ID_SESION, avisoPermanente(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        SessionStore.acciones = acciones
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Se relee en cada arranque: el emparejamiento puede haber aparecido o cambiado
        // desde la última vez (la UI relanza el servicio al terminar de emparejar).
        if (!verificarEmparejamiento()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (SessionStore.acciones === acciones) SessionStore.acciones = null
        if (instancia === this) instancia = null
        val estabaEmparejado = emparejamiento != null
        desconectar()
        if (estabaEmparejado) SessionStore.publicarConexion(SessionStore.Conexion.SIN_RED)
        nm.cancel(ID_DECISION)
        botones.liberar()
        narrador.liberar()
        super.onDestroy()
    }

    // ── Emparejamiento y conexión ──────────────────────────────────────────────

    /** @return false si no hay emparejamiento (y el servicio no tiene nada que hacer). */
    private fun verificarEmparejamiento(): Boolean {
        val p = PairingStore(this).cargar()
        if (p == null) {
            desconectar()
            SessionStore.publicarConexion(SessionStore.Conexion.SIN_EMPAREJAR)
            actualizarAviso()
            return false
        }
        if (p != emparejamiento || cliente == null) conectar(p)
        return true
    }

    private fun conectar(p: Emparejamiento) {
        desconectar()
        emparejamiento = p
        vistoConectado = false
        sessionId = null
        lineas.clear(); parcial.setLength(0); parcialId = null; chat.clear(); pendientes.clear()
        SessionStore.publicarLineas(emptyList())
        SessionStore.publicarParcial("")
        SessionStore.publicarChat(emptyList())
        SessionStore.publicarDecision(null)
        SessionStore.publicarEstado(SessionState.idle)
        estadoAgente = SessionState.idle
        SessionStore.publicarConexion(SessionStore.Conexion.CONECTANDO)
        narrador.cargarPosicion(p.device)

        // Un ámbito por conexión, hijo del ciclo de vida del servicio: cancelarlo corta a
        // la vez la reconexión del cliente, el latido y los avisos de reinsistencia.
        val sc = CoroutineScope(SupervisorJob(lifecycleScope.coroutineContext[Job]) + Dispatchers.Main)
        ambito = sc
        val c = AgentClient(url = p.url, token = p.token, device = p.device, scope = sc, context = applicationContext)
        cliente = c
        sc.launch(start = CoroutineStart.UNDISPATCHED) { c.frames.collect { manejar(it) } }
        sc.launch(start = CoroutineStart.UNDISPATCHED) { c.estado.collect { alCambiarConexion(it) } }
        sc.launch { bucleLatido() }
        c.conectar()
        actualizarAviso()
    }

    private fun desconectar() {
        cliente?.cerrar()
        cliente = null
        ambito?.cancel()
        ambito = null
        reinsistir = null
        emparejamiento = null
    }

    private fun alCambiarConexion(conectado: Boolean) {
        if (conectado) vistoConectado = true
        SessionStore.publicarConexion(
            when {
                conectado -> SessionStore.Conexion.CONECTADO
                vistoConectado -> SessionStore.Conexion.SIN_RED
                else -> SessionStore.Conexion.CONECTANDO
            }
        )
        actualizarAviso()
    }

    // ── Frames ─────────────────────────────────────────────────────────────────

    private fun manejar(frame: ServerFrame) {
        // Una sesión recién abierta (`abrirSesion`) la sigue el cliente por su cuenta: se
        // adopta al ver su primer frame, para que prompts y narración tengan a dónde ir.
        if (sessionId == null) sessionDe(frame)?.let { sessionId = it }

        when (frame) {
            is ServerFrame.Message -> {
                lineas[frame.messageId] = SessionStore.Linea(frame.messageId, frame.role, frame.text)
                SessionStore.publicarLineas(lineas.values.toList())
                if (parcialId == frame.messageId) {
                    parcial.setLength(0)
                    parcialId = null
                    SessionStore.publicarParcial("")
                }
                // Lo que dice el usuario no se narra: ya lo sabe.
                if (frame.role == "assistant") narrador.encolar(frame.messageId, frame.narratable.sentences)
            }

            // Los deltas alimentan la pantalla, no la voz.
            is ServerFrame.Delta -> {
                if (frame.messageId != parcialId) {
                    parcialId = frame.messageId
                    parcial.setLength(0)
                }
                parcial.append(frame.text)
                SessionStore.publicarParcial(parcial.toString())
            }

            is ServerFrame.State -> {
                estadoAgente = frame.state
                if (frame.state == SessionState.thinking || frame.state == SessionState.working) {
                    if (turnoInicio == 0L) {
                        turnoInicio = SystemClock.elapsedRealtime()
                        herramientas = 0
                    }
                } else if (frame.state == SessionState.idle) {
                    turnoInicio = 0L
                }
                SessionStore.publicarEstado(frame.state)
                actualizarAviso()
            }

            is ServerFrame.Tool -> if (frame.phase == "start") herramientas += 1

            is ServerFrame.Alert -> {
                haptics.avisar(frame.pattern)
                frame.spoken?.let(narrador::intercalar)
                val texto = frame.message ?: frame.spoken ?: getString(R.string.aviso_texto_defecto)
                notificarAviso(getString(R.string.aviso_titulo), texto)
            }

            is ServerFrame.TaskDone -> {
                haptics.avisar(AlertPattern.doble)
                narrador.intercalar(frame.spoken)
                notificarAviso(getString(R.string.tarea_terminada_titulo), frame.summary)
            }

            is ServerFrame.DecisionRequest -> {
                if (pendientes.any { it.decisionId == frame.decisionId }) return // reenvío tras reconectar
                val eraLaPrimera = pendientes.isEmpty()
                pendientes += frame
                if (eraLaPrimera) {
                    SessionStore.publicarDecision(frame)
                    presentar(frame)
                }
                actualizarAviso()
            }

            is ServerFrame.DecisionResolved -> resolver(frame)

            is ServerFrame.Hello -> {
                elegirSesion(frame.sessions)
                frame.chatHistory.forEach { chat[it.seq] = it.aMensaje() }
                publicarChat()
            }

            is ServerFrame.SessionClosed -> if (frame.sessionId == sessionId) {
                cliente?.dejarDeSeguir(frame.sessionId)
                sessionId = null
            }

            is ServerFrame.SessionList -> if (sessionId == null) elegirSesion(frame.sessions)

            is ServerFrame.Chat -> {
                val nuevo = !chat.containsKey(frame.seq)
                chat[frame.seq] = frame.aMensaje()
                publicarChat()
                // Un pulso, no voz: es texto entre tú y tú mismo, no del agente.
                if (nuevo && frame.from != emparejamiento?.device) haptics.avisar(AlertPattern.corto)
            }

            is ServerFrame.Error -> when (frame.code) {
                "auth_failed" -> {
                    // No reintentar: el token ya no vale. La UI vuelve a pedir emparejar.
                    desconectar()
                    // Sin borrarlo, la UI seguiría creyéndose emparejada y el siguiente
                    // arranque reconectaría con el mismo token inválido.
                    PairingStore(this).borrar()
                    SessionStore.publicarConexion(SessionStore.Conexion.SIN_EMPAREJAR)
                    narrador.intercalar(getString(R.string.voz_auth_fallida))
                    actualizarAviso()
                }
                "protocol_mismatch" -> narrador.intercalar(getString(R.string.voz_actualizar))
                "session_gone" -> if (frame.sessionId == null || frame.sessionId == sessionId) sessionId = null
                else -> Unit
            }

            else -> Unit
        }
    }

    private fun sessionDe(f: ServerFrame): String? = when (f) {
        is ServerFrame.State -> f.sessionId
        is ServerFrame.Delta -> f.sessionId
        is ServerFrame.Message -> f.sessionId
        is ServerFrame.Tool -> f.sessionId
        is ServerFrame.DecisionRequest -> f.sessionId
        is ServerFrame.Alert -> f.sessionId
        is ServerFrame.TaskDone -> f.sessionId
        else -> null
    }

    private fun ServerFrame.Chat.aMensaje() = SessionStore.MensajeChat(seq, from, text, sentAt)

    private fun publicarChat() {
        while (chat.size > MAX_CHAT) chat.remove(chat.keys.first())
        SessionStore.publicarChat(chat.values.sortedBy { it.seq })
    }

    /** Una sesión narra: la primera que haya, salvo que ya siguiéramos una que sigue viva. */
    private fun elegirSesion(sesiones: List<SessionInfo>) {
        val actual = sessionId
        if (actual != null && sesiones.any { it.sessionId == actual }) return
        val nueva = sesiones.firstOrNull()?.sessionId ?: return
        sessionId = nueva
        cliente?.seguir(nueva)
    }

    // ── Decisiones ─────────────────────────────────────────────────────────────

    /** Lo que ocurre al pasar a primer plano una decisión pendiente. */
    private fun presentar(d: ServerFrame.DecisionRequest) {
        haptics.avisar(AlertPattern.largo)
        // La pregunta interrumpe a la frase en curso y la narración queda parada mientras
        // se decide; `spoken` ya enumera las opciones («Opción uno, resumen…»).
        narrador.interrumpirCon(d.spoken, pausar = true)
        notificarDecision(d, reinsistencia = false)
        if (BlackScreenActivity.visible) {
            // Con la pantalla negra delante el full-screen intent solo saldría como heads-up
            // (el dispositivo está en uso), así que se lanza directamente.
            runCatching { startActivity(intentDecision(d)) }
        }
        armarReinsistencia(d)
    }

    /** A los 30 s y a los 5 min, si sigue pendiente: vibración, voz y notificación otra vez. */
    private fun armarReinsistencia(d: ServerFrame.DecisionRequest) {
        reinsistir?.cancel()
        reinsistir = ambito?.launch {
            delay(30_000)
            insistir(d)
            delay(270_000)
            insistir(d)
        }
    }

    private fun insistir(d: ServerFrame.DecisionRequest) {
        if (pendientes.firstOrNull()?.decisionId != d.decisionId) return
        haptics.avisar(AlertPattern.largo)
        narrador.interrumpirCon(getString(R.string.voz_decision_pendiente) + " " + d.spoken, pausar = true)
        notificarDecision(d, reinsistencia = true)
    }

    private fun resolver(r: ServerFrame.DecisionResolved) {
        val eraLaPrimera = pendientes.firstOrNull()?.decisionId == r.decisionId
        val quitada = pendientes.removeAll { it.decisionId == r.decisionId }
        if (!quitada) return

        val yo = emparejamiento?.device
        when {
            r.resolution == "cancelled" -> narrador.intercalar(getString(R.string.voz_cancelada))
            r.by != null && r.by != yo -> narrador.intercalar(getString(R.string.voz_resuelta_por, r.by))
        }

        if (!eraLaPrimera) {
            actualizarAviso()
            return
        }
        reinsistir?.cancel()
        reinsistir = null
        val siguiente = pendientes.firstOrNull()
        SessionStore.publicarDecision(siguiente)
        if (siguiente != null) {
            presentar(siguiente)
        } else {
            nm.cancel(ID_DECISION)
            narrador.reanudarTrasAviso()
        }
        actualizarAviso()
    }

    private fun contestarPorNumero(n: Int) {
        val d = pendientes.firstOrNull() ?: return
        val opcion = d.options.filter { !it.sticky }.getOrNull(n - 1) ?: return
        acciones.contestar(d.decisionId, opcion.id)
    }

    // ── Lo que la UI puede pedir ───────────────────────────────────────────────

    private val acciones = object : SessionStore.Acciones {
        override fun enviarPrompt(texto: String) {
            val s = sessionId ?: return
            cliente?.enviar(ClientFrame.Prompt(s, texto))
        }

        override fun enviarChat(texto: String) {
            cliente?.enviar(ClientFrame.Chat(texto))
        }

        override fun contestar(decisionId: String, optionId: String) {
            val d = pendientes.firstOrNull { it.decisionId == decisionId } ?: return
            val o = d.options.firstOrNull { it.id == optionId } ?: return
            // La confirmación va por el tono, no por la opción; «siempre» es como `danger`.
            haptics.confirmar(if (o.sticky) OptionTone.danger else o.tone)
            cliente?.enviar(
                ClientFrame.DecisionAnswer(d.sessionId, d.decisionId, listOf(o.id), always = o.sticky)
            )
            // La decisión sigue pendiente hasta que llegue `decision.resolved`: si el envío
            // falla (sin red), no debe desaparecer sin que el agente se haya enterado.
        }

        override fun interrumpir() {
            val s = sessionId ?: return
            cliente?.enviar(ClientFrame.Interrupt(s))
            haptics.avisar(AlertPattern.corto)
        }

        override fun entrarEnNarracion() {
            startActivity(
                Intent(this@SessionService, BlackScreenActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ── Estado hablado y latido ────────────────────────────────────────────────

    /** «¿En qué estado está el agente?» — el toque con dos dedos. Se dice ya, sin pausar la narración. */
    fun decirEstado() {
        val partes = mutableListOf<String>()
        when (SessionStore.conexion.value) {
            SessionStore.Conexion.SIN_EMPAREJAR -> partes += getString(R.string.voz_sin_emparejar)
            SessionStore.Conexion.CONECTANDO -> partes += getString(R.string.voz_conectando)
            SessionStore.Conexion.SIN_RED -> partes += getString(R.string.voz_sin_conexion)
            SessionStore.Conexion.CONECTADO -> partes += getString(
                when (estadoAgente) {
                    SessionState.idle -> R.string.voz_estado_inactivo
                    SessionState.thinking -> R.string.voz_estado_pensando
                    SessionState.working -> R.string.voz_estado_trabajando
                    SessionState.waiting -> R.string.voz_estado_esperando
                    SessionState.error -> R.string.voz_estado_error
                }
            )
        }
        if (pendientes.isNotEmpty()) {
            partes += resources.getQuantityString(R.plurals.voz_decisiones_pendientes, pendientes.size, pendientes.size)
        }
        narrador.interrumpirCon(partes.joinToString(" "), pausar = false)
    }

    /**
     * Latido hablado ([docs/07 §10](docs/07-decisiones.md)): si el agente trabaja y la voz
     * lleva N minutos callada, una frase corta de estado. N es [Ajustes.latidoMin] (por
     * defecto 3; 0 lo apaga) y se relee cada vez, así que cambiarlo surte efecto sin reiniciar.
     */
    private suspend fun bucleLatido() {
        val sc = ambito ?: return
        while (sc.isActive) {
            delay(15_000)
            comprobarLatido()
        }
    }

    private fun comprobarLatido() {
        val minutos = Ajustes.latidoMin(this)
        if (minutos <= 0) return
        if (SessionStore.conexion.value != SessionStore.Conexion.CONECTADO) return
        if (estadoAgente != SessionState.thinking && estadoAgente != SessionState.working) return
        if (narrador.pausado || narrador.ocupado || pendientes.isNotEmpty()) return
        val ahora = SystemClock.elapsedRealtime()
        if (ahora - narrador.ultimaActividad < minutos * 60_000L) return

        val llevo = if (turnoInicio > 0L) ((ahora - turnoInicio) / 60_000L).toInt() else 0
        val texto = when {
            llevo <= 0 -> getString(R.string.voz_latido_simple)
            herramientas > 0 -> getString(
                R.string.voz_latido_con_herramientas,
                resources.getQuantityString(R.plurals.minutos, llevo, llevo),
                resources.getQuantityString(R.plurals.herramientas, herramientas, herramientas),
            )
            else -> getString(R.string.voz_latido, resources.getQuantityString(R.plurals.minutos, llevo, llevo))
        }
        narrador.intercalar(texto)
    }

    // ── Notificaciones ─────────────────────────────────────────────────────────

    /**
     * Tres canales, porque el usuario tiene que poder silenciar el ruido sin silenciar lo
     * que le bloquea. `decisiones` es de importancia máxima y no respeta No Molestar: es lo
     * único que de verdad exige atención inmediata.
     */
    private fun crearCanales() {
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

    private fun abrirApp(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Lo que dice el aviso permanente: conexión y, si hay conexión, el estado del agente. */
    private fun textoSesion(): String {
        val base = when (SessionStore.conexion.value) {
            SessionStore.Conexion.SIN_EMPAREJAR -> getString(R.string.sesion_sin_emparejar)
            SessionStore.Conexion.CONECTANDO -> getString(R.string.sesion_conectando)
            SessionStore.Conexion.SIN_RED -> getString(R.string.sesion_sin_red)
            SessionStore.Conexion.CONECTADO -> getString(
                when (estadoAgente) {
                    SessionState.idle -> R.string.estado_inactivo
                    SessionState.thinking -> R.string.estado_pensando
                    SessionState.working -> R.string.estado_trabajando
                    SessionState.waiting -> R.string.estado_esperando
                    SessionState.error -> R.string.estado_error
                }
            )
        }
        return if (pendientes.isNotEmpty()) base + getString(R.string.sesion_decision_pendiente) else base
    }

    private fun avisoPermanente(): Notification {
        val b = NotificationCompat.Builder(this, CANAL_SESION)
            .setContentTitle(getString(R.string.sesion_activa))
            .setContentText(textoSesion())
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(abrirApp())
        if (::botones.isInitialized) {
            b.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(botones.session.sessionToken)
            )
        }
        return b.build()
    }

    private fun actualizarAviso() {
        nm.notify(ID_SESION, avisoPermanente())
    }

    /** Un aviso de `alert` o `task.done`: queda en la bandeja, no bloquea nada. */
    private fun notificarAviso(titulo: String, texto: String) {
        val n = NotificationCompat.Builder(this, CANAL_AVISOS)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(abrirApp())
            .build()
        nm.notify(idAviso.getAndIncrement(), n)
    }

    private fun intentDecision(d: ServerFrame.DecisionRequest): Intent =
        Intent(this, DecisionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_DECISION_ID, d.decisionId)

    /**
     * Alta prioridad, a pantalla completa con el móvil bloqueado y con **una acción por
     * opción** para contestar desde la pantalla de bloqueo sin abrir nada. Android muestra
     * como mucho tres acciones: las tres primeras opciones principales (la de «siempre»
     * no va aquí: es la única que no debe contestarse de un toque a ciegas).
     */
    private fun notificarDecision(d: ServerFrame.DecisionRequest, reinsistencia: Boolean) {
        val pantalla = PendingIntent.getActivity(
            this, 0, intentDecision(d),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CANAL_DECISIONES)
            .setContentTitle(getString(R.string.decision_titulo))
            .setContentText(d.prompt)
            .setStyle(NotificationCompat.BigTextStyle().bigText(d.prompt))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(!reinsistencia)
            .setFullScreenIntent(pantalla, true)
            .setContentIntent(pantalla)

        if (!d.multiSelect) {
            d.options.filter { !it.sticky }.take(3).forEachIndexed { i, o ->
                val intent = Intent(this, DecisionReceiver::class.java)
                    .setAction(ACCION_CONTESTAR)
                    .setData(Uri.parse("manoslibres://decision/${d.decisionId}/${o.id}"))
                    .putExtra(EXTRA_DECISION_ID, d.decisionId)
                    .putExtra(EXTRA_OPTION_ID, o.id)
                val pi = PendingIntent.getBroadcast(
                    this, ("${d.decisionId}/${o.id}").hashCode(), intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                b.addAction(0, "${i + 1} · ${o.label}", pi)
            }
        }
        nm.notify(ID_DECISION, b.build())
    }

    companion object {
        const val ACCION_CONTESTAR = "org.cosasvarias.manoslibres.CONTESTAR_DECISION"
        /** Extra de los intents hacia `DecisionActivity` y [DecisionReceiver]. */
        const val EXTRA_DECISION_ID = "decisionId"
        const val EXTRA_OPTION_ID = "optionId"

        /**
         * El servicio en marcha, o null. Es lo que usan la pantalla negra y el resto de
         * componentes que no tienen otro modo de llegar al narrador y a la háptica.
         */
        @Volatile
        var instancia: SessionService? = null
            private set

        private const val CANAL_SESION = "sesion"
        private const val CANAL_AVISOS = "avisos"
        private const val CANAL_DECISIONES = "decisiones"
        private const val ID_SESION = 1
        private const val ID_DECISION = 2
        private const val ID_AVISO_BASE = 100
        private const val MAX_CHAT = 200
    }
}
