package org.cosasvarias.manoslibres.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicaTest {
    @Test fun sinCodigo() {
        assertEquals(listOf<Trozo>(Trozo.Texto("hola\nmundo")), segmentarCodigo("hola\nmundo"))
    }

    @Test fun codigoEnMedio() {
        val r = segmentarCodigo("antes\n```kotlin\nval x = 1\n```\ndespués")
        assertEquals(listOf<Trozo>(Trozo.Texto("antes"), Trozo.Codigo("val x = 1"), Trozo.Texto("después")), r)
    }

    @Test fun vallaSinCerrar() {
        val r = segmentarCodigo("mira\n```\nfun a()")
        assertEquals(listOf<Trozo>(Trozo.Texto("mira"), Trozo.Codigo("fun a()")), r)
    }

    @Test fun vacio() {
        assertTrue(segmentarCodigo("").isEmpty())
    }

    @Test fun propio() {
        assertTrue(esPropio("Pixel", " pixel "))
        assertFalse(esPropio("Pixel", "Otro"))
        assertFalse(esPropio("Pixel", null))
    }

    @Test fun enviable() {
        assertNull(textoEnviable("  \n"))
        assertEquals("hola", textoEnviable(" hola "))
    }
}
