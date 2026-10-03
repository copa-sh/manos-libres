package org.cosasvarias.manoslibres.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.cosasvarias.manoslibres.net.ServerFrame
import org.cosasvarias.manoslibres.net.SessionState

/**
 * El estado de la sesión visible para la UI, y el único punto de contacto entre ella y el
 * [SessionService].
 *
 * Contrato:
 *  · El **servicio escribe** (`publicar…`, `actions`) y la **UI solo lee** los `StateFlow` y
 *    llama a [actions]. Ninguna pantalla habla con la red directamente.
 *  · Vive en memoria: lo que sobrevive a un reinicio del proceso lo reconstruye el nodo
 *    con `Last-Event-ID` y el historial del `hello`.
 */
object SessionStore {

    enum class Conexion { SIN_EMPAREJAR, CONECTANDO, CONECTADO, SIN_RED }

    data class Linea(val id: String, val rol: String, val texto: String)
    data class MensajeChat(val seq: Long, val from: String, val text: String, val sentAt: Long)

    private val _conexion = MutableStateFlow(Conexion.SIN_EMPAREJAR)
    val conexion: StateFlow<Conexion> = _conexion

    private val _lineas = MutableStateFlow<List<Linea>>(emptyList())
    /** Mensajes completos del agente y del usuario, en orden. */
    val lineas: StateFlow<List<Linea>> = _lineas

    private val _parcial = MutableStateFlow("")
    /** Texto que el agente está escribiendo ahora (acumulado de `text.delta`). */
    val parcial: StateFlow<String> = _parcial

    private val _estado = MutableStateFlow(SessionState.idle)
    val estado: StateFlow<SessionState> = _estado

    private val _chat = MutableStateFlow<List<MensajeChat>>(emptyList())
    val chat: StateFlow<List<MensajeChat>> = _chat

    private val _decision = MutableStateFlow<ServerFrame.DecisionRequest?>(null)
    /** La decisión pendiente más antigua, o null. La botonera se pinta con esto. */
    val decision: StateFlow<ServerFrame.DecisionRequest?> = _decision

    /** Lo que la UI puede pedir. Lo instala el servicio al arrancar y lo retira al morir. */
    interface Acciones {
        fun enviarPrompt(texto: String)
        fun enviarChat(texto: String)
        fun contestar(decisionId: String, optionId: String)
        fun interrumpir()
        fun entrarEnNarracion()
    }

    @Volatile var acciones: Acciones? = null

    // ── Escritura: solo el servicio ─────────────────────────────────────────────
    fun publicarConexion(c: Conexion) { _conexion.value = c }
    fun publicarLineas(l: List<Linea>) { _lineas.value = l }
    fun publicarParcial(t: String) { _parcial.value = t }
    fun publicarEstado(s: SessionState) { _estado.value = s }
    fun publicarChat(c: List<MensajeChat>) { _chat.value = c }
    fun publicarDecision(d: ServerFrame.DecisionRequest?) { _decision.value = d }
}
