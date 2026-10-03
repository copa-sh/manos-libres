package org.cosasvarias.manoslibres.net

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * El canal con el nodo (docs/03-protocolo.md, docs/07-decisiones.md §3).
 *
 * No es un WebSocket. Hacia abajo, SSE sobre HTTP (HTTP/3 donde se pueda):
 *
 *  · `GET /v1/control` — el canal de control: `hello`, `session.list`, `chat.message`.
 *  · `GET /v1/sesiones/{id}/flujo` — uno por sesión seguida: los frames de esa sesión. El
 *    `id:` de cada evento es el `seq`; al reconectar se devuelve el último en `Last-Event-ID`.
 *
 * Hacia arriba, un `POST` suelto por acción ([enviar]).
 *
 * El móvil pierde la conexión constantemente, así que la desconexión es el estado normal:
 * cada flujo se reintenta solo, con retroceso exponencial (1 s, 2 s, 4 s… tope 30 s), y un
 * vigilante corta los flujos que llevan más de 75 s sin un solo byte (el nodo manda un
 * comentario de keepalive cada 20 s). El narrador no se para mientras tanto.
 *
 * Tras el `hello` (y tras cada `session.list`) se siguen automáticamente todas las sesiones
 * listadas.
 *
 * [context] es opcional: con él, en Android 14+ se usa `HttpEngine` con QUIC; sin él, o por
 * debajo de API 34, `HttpURLConnection`.
 */
class AgentClient(
    url: String,
    private val token: String,
    private val device: String,
    private val scope: CoroutineScope,
    context: Context? = null,
) {
    private val base = url.trim().trimEnd('/')
    private val transporte: Transporte = Transporte.crear(context, base)

    /** Último `seq` visto por sesión; es lo que se manda como `Last-Event-ID` al reconectar. */
    private val ultimoSeq = ConcurrentHashMap<String, Long>()

    private val seguidas = ConcurrentHashMap<String, Job>()
    private val colasEnvio = ConcurrentHashMap<String, Channel<ClientFrame>>()

    // Los frames se encolan en orden desde los hilos de red y los publica una sola corrutina.
    private val entrada = Channel<ServerFrame>(Channel.UNLIMITED)
    private val _frames = MutableSharedFlow<ServerFrame>(replay = 0, extraBufferCapacity = 256)
    val frames: SharedFlow<ServerFrame> = _frames

    private val _estado = MutableStateFlow(false)

    /** `true` mientras el flujo del canal de control está abierto. */
    val estado: StateFlow<Boolean> = _estado

    private var control: Job? = null
    private var publicador: Job? = null

    @Synchronized
    fun conectar() {
        if (control?.isActive == true) return
        publicador = scope.launch { for (f in entrada) _frames.emit(f) }
        control = lanzarFlujo(CLAVE_CONTROL, "/v1/control", null, CoroutineStart.DEFAULT)
    }

    @Synchronized
    fun cerrar() {
        control?.cancel()
        control = null
        seguidas.values.forEach { it.cancel() }
        seguidas.clear()
        colasEnvio.values.forEach { it.close() }
        colasEnvio.clear()
        publicador?.cancel()
        publicador = null
        _estado.value = false
        transporte.cerrar()
    }

    /**
     * Empieza a recibir el flujo de una sesión. [desde] es el último `seq` que la app ya
     * tiene (de una ejecución anterior); 0 pide todo lo que guarde el buffer del nodo.
     */
    fun seguir(sessionId: String, desde: Long = 0) {
        if (desde > 0) ultimoSeq.merge(sessionId, desde) { a, b -> maxOf(a, b) }
        synchronized(seguidas) {
            if (seguidas.containsKey(sessionId)) return
            val job = lanzarFlujo(sessionId, rutaFlujo(sessionId), sessionId, CoroutineStart.LAZY)
            seguidas[sessionId] = job
            job.start()
        }
    }

    /** Deja de recibir los frames de una sesión, sin cerrarla en el nodo. */
    fun dejarDeSeguir(sessionId: String) {
        seguidas.remove(sessionId)?.cancel()
    }

    /** `POST /v1/sesiones`; la sesión nueva se sigue sola al llegar la respuesta. */
    fun abrirSesion(cwd: String, title: String? = null) {
        enviar(ClientFrame.SessionOpen(cwd = cwd, title = title))
    }

    /**
     * Manda un frame de la app como el `POST` que le corresponde. No bloquea: se hace en un
     * hilo de fondo, y los frames de una misma sesión salen en orden. Un fallo llega a
     * [frames] como un `ServerFrame.Error` (`code = "network"` o el que devuelva el nodo).
     */
    fun enviar(frame: ClientFrame) {
        val clave = when (frame) {
            is ClientFrame.Prompt -> frame.sessionId
            is ClientFrame.DecisionAnswer -> frame.sessionId
            is ClientFrame.Interrupt -> frame.sessionId
            is ClientFrame.Narration -> frame.sessionId
            else -> ""
        }
        val cola = colasEnvio.computeIfAbsent(clave) {
            val c = Channel<ClientFrame>(Channel.UNLIMITED)
            scope.launch(Dispatchers.IO) { for (f in c) ejecutar(f) }
            c
        }
        cola.trySend(frame)
    }

    // ── Flujos descendentes ──────────────────────────────────────────────────────────────

    private fun lanzarFlujo(clave: String, ruta: String, sessionId: String?, start: CoroutineStart): Job =
        scope.launch(start = start) {
            val fallos = AtomicInteger(0)
            while (isActive) {
                val estado = AtomicInteger(0)
                val parser = SseParser()
                val ultimoByte = AtomicLong(System.nanoTime())

                val intento = launch(Dispatchers.IO) {
                    try {
                        transporte.solicitar(
                            metodo = "GET",
                            url = base + ruta,
                            cabeceras = cabeceras(sessionId, flujo = true),
                            cuerpo = null,
                            onEstado = { codigo ->
                                estado.set(codigo)
                                if (codigo in 200..299) {
                                    fallos.set(0)
                                    if (sessionId == null) _estado.value = true
                                }
                            },
                            onBytes = { buf, n ->
                                ultimoByte.set(System.nanoTime())
                                if (estado.get() in 200..299) {
                                    for (ev in parser.feed(buf, n)) alEvento(sessionId, ev)
                                }
                            },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // se reintenta abajo
                    }
                }
                val vigilante = launch {
                    while (isActive) {
                        delay(10_000)
                        if (System.nanoTime() - ultimoByte.get() > SILENCIO_MAX_NS) {
                            intento.cancel()
                            break
                        }
                    }
                }
                try {
                    intento.join()
                } finally {
                    vigilante.cancel()
                    if (sessionId == null) _estado.value = false
                }

                when (estado.get()) {
                    401 -> { emitir(error(sessionId, "auth_failed", "token inválido")); break }
                    400 -> { emitir(error(sessionId, "protocol_mismatch", "el nodo no habla $PROTOCOL_VERSION")); break }
                    404 -> if (sessionId != null) {
                        seguidas.remove(sessionId)
                        emitir(error(sessionId, "session_gone", "la sesión ya no existe"))
                        break
                    }
                }
                if (clave != CLAVE_CONTROL && !seguidas.containsKey(clave)) break

                val n = fallos.getAndIncrement()
                delay(min(30_000L, 1000L shl min(n, 5)))
            }
        }

    private fun alEvento(sessionId: String?, ev: SseEvent) {
        if (sessionId != null) {
            ev.id?.toLongOrNull()?.let { s -> ultimoSeq.merge(sessionId, s) { a, b -> maxOf(a, b) } }
        }
        val frame = runCatching { protocolJson.decodeFromString(ServerFrame.serializer(), ev.data) }
            .getOrNull() ?: return   // frame desconocido: se ignora, por diseño

        when (frame) {
            is ServerFrame.Hello -> frame.sessions.forEach { seguir(it.sessionId) }
            is ServerFrame.SessionList -> frame.sessions.forEach { seguir(it.sessionId) }
            is ServerFrame.SessionClosed -> {
                ultimoSeq.remove(frame.sessionId)
                seguidas.remove(frame.sessionId)?.cancel()
            }
            else -> Unit
        }
        emitir(frame)
    }

    private fun emitir(frame: ServerFrame) {
        entrada.trySend(frame)
    }

    // ── Peticiones ascendentes ───────────────────────────────────────────────────────────

    private class Respuesta(val estado: Int, val cuerpo: String) {
        val ok get() = estado in 200..299
    }

    private suspend fun ejecutar(frame: ClientFrame) {
        try {
            when (frame) {
                is ClientFrame.Auth, is ClientFrame.Ping -> Unit   // la cabecera ya autentica

                is ClientFrame.SessionsList -> llamar("GET", "/v1/sesiones", null, null)?.let { r ->
                    val lista = protocolJson.parseToJsonElement(r.cuerpo).jsonObject["sessions"]
                    if (lista != null) {
                        val sesiones = protocolJson.decodeFromJsonElement(
                            ListSerializer(SessionInfo.serializer()), lista,
                        )
                        emitir(ServerFrame.SessionList(sesiones))
                    }
                }

                is ClientFrame.SessionOpen -> {
                    // El nodo rechaza campos que no conoce: `engine` no se manda.
                    val cuerpo = buildJsonObject {
                        put("cwd", frame.cwd)
                        frame.title?.let { put("title", it) }
                    }
                    llamar("POST", "/v1/sesiones", cuerpo.toString(), null)?.let { r ->
                        val info = protocolJson.decodeFromString(SessionInfo.serializer(), r.cuerpo)
                        seguir(info.sessionId)
                    }
                }

                is ClientFrame.SessionAttach -> seguir(frame.sessionId, frame.sinceSeq)
                is ClientFrame.SessionDetach -> dejarDeSeguir(frame.sessionId)

                is ClientFrame.Prompt -> llamar(
                    "POST", "${rutaSesion(frame.sessionId)}/prompt",
                    buildJsonObject { put("text", frame.text) }.toString(), frame.sessionId,
                )

                is ClientFrame.DecisionAnswer -> llamar(
                    "POST", "${rutaSesion(frame.sessionId)}/decision",
                    buildJsonObject {
                        put("decisionId", frame.decisionId)
                        putJsonArray("optionIds") { frame.optionIds.forEach { add(it) } }
                        put("always", frame.always)
                    }.toString(),
                    frame.sessionId,
                )?.let { r ->
                    // 200 (en vez de 204): ya la había contestado otro dispositivo.
                    if (r.estado == 200) {
                        val o = runCatching { protocolJson.parseToJsonElement(r.cuerpo) as? JsonObject }.getOrNull()
                        emitir(
                            ServerFrame.DecisionResolved(
                                sessionId = frame.sessionId, seq = 0,
                                decisionId = (o?.get("decisionId") as? JsonPrimitive)?.content ?: frame.decisionId,
                                resolution = (o?.get("resolution") as? JsonPrimitive)?.content ?: "answered",
                            ),
                        )
                    }
                }

                is ClientFrame.Interrupt -> llamar(
                    "POST", "${rutaSesion(frame.sessionId)}/interrupcion", "{}", frame.sessionId,
                )

                is ClientFrame.Narration -> llamar(
                    "POST", "${rutaSesion(frame.sessionId)}/narracion",
                    buildJsonObject {
                        put("messageId", frame.at.messageId)
                        put("sentence", frame.at.sentence)
                    }.toString(),
                    frame.sessionId,
                )

                is ClientFrame.Chat -> llamar(
                    "POST", "/v1/chat", buildJsonObject { put("text", frame.text) }.toString(), null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emitir(error(null, "network", e.message ?: "fallo de red"))
        }
    }

    /** Hace la petición; si falla emite el `error` y devuelve null. */
    private suspend fun llamar(metodo: String, ruta: String, cuerpo: String?, sessionId: String?): Respuesta? {
        val acumulado = ByteArrayOutputStream()
        var estado = 0
        val cab = cabeceras(null).toMutableMap()
        if (cuerpo != null) cab["Content-Type"] = "application/json; charset=utf-8"
        cab["Accept"] = "application/json"
        try {
            transporte.solicitar(
                metodo, base + ruta, cab, cuerpo?.toByteArray(Charsets.UTF_8),
                onEstado = { estado = it },
                onBytes = { b, n -> acumulado.write(b, 0, n) },
            )
        } catch (e: IOException) {
            emitir(error(sessionId, "network", e.message ?: "sin conexión"))
            return null
        }
        val r = Respuesta(estado, acumulado.toString(Charsets.UTF_8.name()))
        if (r.ok) return r

        val del = runCatching { protocolJson.decodeFromString(ServerFrame.serializer(), r.cuerpo) }
            .getOrNull() as? ServerFrame.Error
        emitir(
            ServerFrame.Error(
                sessionId = sessionId, seq = null,
                code = del?.code ?: "http_$estado",
                message = del?.message ?: "el nodo respondió $estado",
            ),
        )
        return null
    }

    // ── Utilidades ───────────────────────────────────────────────────────────────────────

    private fun cabeceras(sessionId: String?, flujo: Boolean = false): Map<String, String> {
        val h = linkedMapOf(
            "Authorization" to "Bearer $token",
            "X-Dispositivo" to soloAscii(device),
            "X-Protocolo" to PROTOCOL_VERSION,
        )
        if (flujo) {
            h["Accept"] = "text/event-stream"
            h["Cache-Control"] = "no-cache"
            // El canal de control no tiene `id:`; solo los flujos de sesión se reanudan.
            if (sessionId != null) {
                ultimoSeq[sessionId]?.takeIf { it > 0 }?.let { h["Last-Event-ID"] = it.toString() }
            }
        }
        return h
    }

    private fun error(sessionId: String?, code: String, message: String) =
        ServerFrame.Error(sessionId = sessionId, seq = null, code = code, message = message)

    private fun rutaSesion(id: String) = "/v1/sesiones/" + codificar(id)
    private fun rutaFlujo(id: String) = rutaSesion(id) + "/flujo"

    private fun codificar(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Las cabeceras HTTP no admiten más que ASCII imprimible. */
    private fun soloAscii(s: String) =
        s.map { if (it.code in 0x20..0x7E) it else '?' }.joinToString("").ifBlank { "movil" }

    private companion object {
        const val CLAVE_CONTROL = "\u0000control"
        const val SILENCIO_MAX_NS = 75_000_000_000L
    }
}
