package org.cosasvarias.manoslibres.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Los frames de `manos-libres/1`.
 *
 * Réplica en Kotlin de `server/protocolo.go`, que es la fuente de verdad. Si cambias
 * uno, cambia el otro.
 *
 * `ignoreUnknownKeys` y `classDiscriminator = "t"` implementan la regla del protocolo: un
 * cliente ignora lo que no conoce, para que añadir frames en el nodo no rompa una app
 * vieja. `ServerFrame.Unknown` recoge los tipos no reconocidos en vez de lanzar.
 */
const val PROTOCOL_VERSION = "manos-libres/1"

val protocolJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "t"
    encodeDefaults = true
    explicitNulls = false
}

enum class SessionState { idle, thinking, working, waiting, error }

/** Cuatro patrones y solo cuatro: en el bolsillo no se distinguen más. */
enum class AlertPattern { corto, doble, largo, urgente }

/** Determina color, grosor de borde, icono y háptica de confirmación. */
enum class OptionTone { neutral, go, stop, danger }

enum class DecisionSource { permission, ask_user_question, node }

@Serializable
data class SessionInfo(
    val sessionId: String,
    val title: String,
    val cwd: String,
    val engine: String,
    val state: SessionState,
    val seq: Long,
    val updatedAt: Long,
    val pendingDecisions: Int,
)

/**
 * Lo que se narra, frente a lo que se ve. El índice de una frase, junto al `messageId`, es
 * la coordenada de narración: es lo que decrementa el doble toque y lo que permite
 * reanudar exactamente donde se cortó.
 */
@Serializable
data class Narratable(
    val sentences: List<String>,
    val elided: Int = 0,
)

@Serializable
data class DecisionOption(
    val id: String,
    /** Lo que se pinta: una o dos palabras, legible de reojo. */
    val label: String,
    /** Lo que se pronuncia. */
    val spoken: String,
    val description: String? = null,
    val tone: OptionTone = OptionTone.neutral,
    /** «Permitir siempre»: franja estrecha aparte, nunca una de las principales. */
    val sticky: Boolean = false,
)

// ── App → nodo ───────────────────────────────────────────────────────────────

@Serializable
sealed interface ClientFrame {
    @Serializable @SerialName("auth")
    data class Auth(
        val token: String,
        val device: String,
        val protocol: String = PROTOCOL_VERSION,
        val signature: String? = null,
    ) : ClientFrame

    @Serializable @SerialName("sessions.list")
    data object SessionsList : ClientFrame

    @Serializable @SerialName("session.open")
    data class SessionOpen(val cwd: String, val engine: String? = null, val title: String? = null) : ClientFrame

    @Serializable @SerialName("session.attach")
    data class SessionAttach(val sessionId: String, val sinceSeq: Long) : ClientFrame

    @Serializable @SerialName("session.detach")
    data class SessionDetach(val sessionId: String) : ClientFrame

    @Serializable @SerialName("prompt")
    data class Prompt(val sessionId: String, val text: String) : ClientFrame

    @Serializable @SerialName("decision.answer")
    data class DecisionAnswer(
        val sessionId: String,
        val decisionId: String,
        val optionIds: List<String>,
        val always: Boolean = false,
    ) : ClientFrame

    @Serializable @SerialName("interrupt")
    data class Interrupt(val sessionId: String) : ClientFrame

    /** Dónde va la narración; permite reanudar en la frase exacta tras una reconexión. */
    @Serializable @SerialName("narration")
    data class Narration(val sessionId: String, val at: At) : ClientFrame {
        @Serializable data class At(val messageId: String, val sentence: Int)
    }

    @Serializable @SerialName("ping")
    data object Ping : ClientFrame

    /** Un mensaje directo a tus otros dispositivos, fuera de cualquier sesión de agente. */
    @Serializable @SerialName("chat")
    data class Chat(val text: String) : ClientFrame
}

// ── Nodo → app ───────────────────────────────────────────────────────────────

@Serializable
sealed interface ServerFrame {
    @Serializable @SerialName("hello")
    data class Hello(
        val protocol: String,
        val node: String,
        val engines: List<String>,
        val sessions: List<SessionInfo>,
        val challenge: String? = null,
        /** Los últimos mensajes de chat entre dispositivos. Buffer en anillo del nodo. */
        val chatHistory: List<Chat> = emptyList(),
    ) : ServerFrame

    @Serializable @SerialName("session.list")
    data class SessionList(val sessions: List<SessionInfo>) : ServerFrame

    @Serializable @SerialName("session.state")
    data class State(
        val sessionId: String, val seq: Long,
        val state: SessionState, val detail: String? = null,
    ) : ServerFrame

    /** Solo para la pantalla. Narrar deltas corta la entonación. */
    @Serializable @SerialName("text.delta")
    data class Delta(
        val sessionId: String, val seq: Long,
        val messageId: String, val text: String,
    ) : ServerFrame

    @Serializable @SerialName("message")
    data class Message(
        val sessionId: String, val seq: Long,
        val messageId: String, val role: String, val kind: String,
        val text: String, val narratable: Narratable,
    ) : ServerFrame

    @Serializable @SerialName("tool")
    data class Tool(
        val sessionId: String, val seq: Long,
        val toolUseId: String, val phase: String, val label: String, val ok: Boolean? = null,
    ) : ServerFrame

    @Serializable @SerialName("decision.request")
    data class DecisionRequest(
        val sessionId: String, val seq: Long,
        val decisionId: String,
        val source: DecisionSource,
        val multiSelect: Boolean,
        val prompt: String,
        val spoken: String,
        val options: List<DecisionOption>,
        val deadlineMs: Long? = null,
    ) : ServerFrame

    @Serializable @SerialName("decision.resolved")
    data class DecisionResolved(
        val sessionId: String, val seq: Long,
        val decisionId: String, val resolution: String, val by: String? = null,
    ) : ServerFrame

    @Serializable @SerialName("alert")
    data class Alert(
        val sessionId: String, val seq: Long,
        val pattern: AlertPattern,
        val source: String,
        val message: String? = null,
        val spoken: String? = null,
        val sound: String? = null,
    ) : ServerFrame

    @Serializable @SerialName("task.done")
    data class TaskDone(
        val sessionId: String, val seq: Long,
        val summary: String, val spoken: String,
        val costUsd: Double? = null, val durationMs: Long? = null,
    ) : ServerFrame

    @Serializable @SerialName("error")
    data class Error(
        val sessionId: String? = null, val seq: Long? = null,
        val code: String, val message: String,
    ) : ServerFrame

    @Serializable @SerialName("pong")
    data object Pong : ServerFrame

    /**
     * Un mensaje directo entre tus dispositivos, fuera de cualquier sesión de agente. Viaja
     * por el canal de control, no por el flujo de una sesión, así que no lleva `sessionId`.
     *
     * No hay cifrado extremo a extremo por encima de TLS: el nodo ve el texto en claro, igual
     * que ve el resto del protocolo (ver docs/07-decisiones.md §11).
     */
    @Serializable @SerialName("chat.message")
    data class Chat(
        val seq: Long,
        val chatId: String,
        /** El dispositivo que lo mandó (`X-Dispositivo`). */
        val from: String,
        val text: String,
        val sentAt: Long,
    ) : ServerFrame
}

/** El `seq` del frame, o 0 si no pertenece a una sesión. */
val ServerFrame.seqOrZero: Long
    get() = when (this) {
        is ServerFrame.State -> seq
        is ServerFrame.Delta -> seq
        is ServerFrame.Message -> seq
        is ServerFrame.Tool -> seq
        is ServerFrame.DecisionRequest -> seq
        is ServerFrame.DecisionResolved -> seq
        is ServerFrame.Alert -> seq
        is ServerFrame.TaskDone -> seq
        is ServerFrame.Error -> seq ?: 0
        is ServerFrame.Chat -> seq
        else -> 0
    }
