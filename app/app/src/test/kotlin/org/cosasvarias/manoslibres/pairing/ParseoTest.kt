package org.cosasvarias.manoslibres.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseoTest {
    @Test fun uriCompleta() {
        val e = parseEmparejamiento("manoslibres://pair?url=https%3A%2F%2Fnodo.example.org&token=abc123&device=Pixel%207")
        assertEquals(Emparejamiento("https://nodo.example.org", "abc123", "Pixel 7"), e)
    }

    @Test fun uriSinDeviceUsaElPorDefecto() {
        val e = parseEmparejamiento("manoslibres://pair?url=https%3A%2F%2Fn.org%2F&token=t", "mi-movil")
        assertEquals(Emparejamiento("https://n.org", "t", "mi-movil"), e)
    }

    @Test fun uriConUrlSinCodificar() {
        val e = parseEmparejamiento("manoslibres://pair?url=https://n.org:8443&token=t")
        assertEquals("https://n.org:8443", e?.url)
    }

    @Test fun urlYTokenEnUnaLinea() {
        assertEquals(Emparejamiento("https://n.org", "tok"), parseEmparejamiento("  https://n.org/   tok \n"))
    }

    @Test fun urlYTokenEnDosLineas() {
        assertEquals("tok", parseEmparejamiento("https://n.org\ntok")?.token)
    }

    @Test fun tokenPrimeroYUrlDespues() {
        assertEquals(Emparejamiento("http://10.0.0.2:8080", "tok"), parseEmparejamiento("tok http://10.0.0.2:8080"))
    }

    @Test fun urlConTokenEnLaConsulta() {
        assertEquals(Emparejamiento("https://n.org", "tok"), parseEmparejamiento("https://n.org/?token=tok"))
    }

    @Test fun rechazaLoIncompleto() {
        assertNull(parseEmparejamiento(""))
        assertNull(parseEmparejamiento("https://n.org"))
        assertNull(parseEmparejamiento("manoslibres://pair?url=https%3A%2F%2Fn.org"))
        assertNull(parseEmparejamiento("manoslibres://pair?token=t"))
        assertNull(parseEmparejamiento("ftp://n.org tok"))
        assertNull(parseEmparejamiento("https:// tok"))
        assertNull(parseEmparejamiento("a b c"))
    }
}
