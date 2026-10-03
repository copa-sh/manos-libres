package org.cosasvarias.manoslibres.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    @Test fun eventoConIdYData() {
        val ev = SseParser().feed("id: 12\ndata: {\"t\":\"pong\"}\n\n")
        assertEquals(listOf(SseEvent("12", null, "{\"t\":\"pong\"}")), ev)
    }

    @Test fun eventoSinIdComoElHello() {
        val p = SseParser()
        val ev = p.feed("data: {\"t\":\"hello\"}\n\n")
        assertEquals(1, ev.size)
        assertNull(ev[0].id)
        assertNull(p.ultimoId)
    }

    @Test fun variasLineasDataSeUnenConSaltoDeLinea() {
        val ev = SseParser().feed("data: a\ndata: b\ndata:\ndata: c\n\n")
        assertEquals("a\nb\n\nc", ev[0].data)
    }

    @Test fun comentariosYKeepaliveSeIgnoran() {
        val ev = SseParser().feed(": ping\n\n: otro\ndata: x\n\n")
        assertEquals(listOf(SseEvent(null, null, "x")), ev)
    }

    @Test fun bloqueSinDataNoGeneraEvento() {
        assertTrue(SseParser().feed("id: 5\n\n").isEmpty())
    }

    @Test fun troceadoEnCualquierPunto() {
        val texto = "id: 3\ndata: uno\n\nid: 4\ndata: dos\n\n"
        for (corte in 0..texto.length) {
            val p = SseParser()
            val ev = p.feed(texto.substring(0, corte)) + p.feed(texto.substring(corte))
            assertEquals("corte $corte", listOf(SseEvent("3", null, "uno"), SseEvent("4", null, "dos")), ev)
        }
    }

    @Test fun finesDeLineaCRyCRLF() {
        assertEquals("a", SseParser().feed("data: a\r\n\r\n")[0].data)
        assertEquals("a", SseParser().feed("data: a\r\r")[0].data)
        // CRLF partido entre dos trozos
        val p = SseParser()
        val ev = p.feed("data: a\r") + p.feed("\n\r") + p.feed("\n")
        assertEquals(listOf(SseEvent(null, null, "a")), ev)
    }

    @Test fun utf8PartidoEntreTrozos() {
        val bytes = "data: ñandú 🙂\n\n".toByteArray(Charsets.UTF_8)
        for (corte in 0..bytes.size) {
            val p = SseParser()
            val ev = p.feed(bytes.copyOfRange(0, corte)) + p.feed(bytes.copyOfRange(corte, bytes.size))
            assertEquals("corte $corte", "ñandú 🙂", ev[0].data)
        }
    }

    @Test fun soloSeQuitaUnEspacioInicial() {
        assertEquals(" x", SseParser().feed("data:  x\n\n")[0].data)
        assertEquals("x", SseParser().feed("data:x\n\n")[0].data)
    }

    @Test fun eventYRetry() {
        val p = SseParser()
        val ev = p.feed("event: msg\nretry: 3000\ndata: z\n\n")
        assertEquals("msg", ev[0].event)
        assertEquals(3000L, p.reintentoMs)
    }

    @Test fun ultimoIdPersisteEntreEventos() {
        val p = SseParser()
        p.feed("id: 7\ndata: a\n\n")
        p.feed("data: b\n\n")
        assertEquals("7", p.ultimoId)
    }

    @Test fun idConNulSeIgnora() {
        assertNull(SseParser().feed("id: 1\u00002\ndata: a\n\n")[0].id)
    }
}
