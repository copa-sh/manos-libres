package org.cosasvarias.manoslibres.net

import java.io.ByteArrayOutputStream

/** Un evento SSE ya reensamblado. `id` es el del propio bloque (null si no traía `id:`). */
data class SseEvent(val id: String?, val event: String?, val data: String)

/**
 * Parser incremental de `text/event-stream`. Puro Kotlin, sin Android, para poder probarlo
 * en la JVM.
 *
 *  · acepta los tres finales de línea (`\n`, `\r`, `\r\n`), también partidos entre trozos;
 *  · decodifica UTF-8 por línea, así que un carácter partido entre dos trozos no se rompe;
 *  · varias líneas `data:` se unen con `\n`;
 *  · los comentarios (`: ping`, el keepalive del nodo) se descartan;
 *  · un bloque sin `data:` no genera evento.
 *
 * No es seguro entre hilos: un parser por conexión.
 */
class SseParser {
    private val linea = ByteArrayOutputStream()
    private var saltarLF = false

    private var id: String? = null
    private var evento: String? = null
    private val datos = StringBuilder()
    private var hayDatos = false

    /** Último `id:` visto en la conexión (el `lastEventId` de la especificación). */
    var ultimoId: String? = null
        private set

    /** El valor de `retry:` en milisegundos, si el servidor mandó alguno. */
    var reintentoMs: Long? = null
        private set

    fun feed(texto: String): List<SseEvent> = feed(texto.toByteArray(Charsets.UTF_8))

    fun feed(buf: ByteArray, len: Int = buf.size): List<SseEvent> {
        val salida = ArrayList<SseEvent>()
        for (i in 0 until len) {
            val b = buf[i].toInt()
            if (saltarLF) {
                saltarLF = false
                if (b == '\n'.code) continue
            }
            when (b) {
                '\n'.code -> procesarLinea(salida)
                '\r'.code -> {
                    saltarLF = true
                    procesarLinea(salida)
                }
                else -> linea.write(b)
            }
        }
        return salida
    }

    private fun procesarLinea(salida: MutableList<SseEvent>) {
        val texto = linea.toString(Charsets.UTF_8.name())
        linea.reset()

        if (texto.isEmpty()) {
            if (hayDatos) salida.add(SseEvent(id, evento, datos.toString()))
            id = null
            evento = null
            datos.setLength(0)
            hayDatos = false
            return
        }
        if (texto[0] == ':') return

        val dos = texto.indexOf(':')
        val campo: String
        var valor: String
        if (dos < 0) {
            campo = texto
            valor = ""
        } else {
            campo = texto.substring(0, dos)
            valor = texto.substring(dos + 1)
            if (valor.startsWith(" ")) valor = valor.substring(1)
        }

        when (campo) {
            "data" -> {
                if (hayDatos) datos.append('\n')
                datos.append(valor)
                hayDatos = true
            }
            "id" -> if ('\u0000' !in valor) {
                id = valor
                ultimoId = valor
            }
            "event" -> evento = valor
            "retry" -> if (valor.isNotEmpty() && valor.all { it in '0'..'9' }) {
                reintentoMs = valor.toLongOrNull()
            }
        }
    }
}
