package org.cosasvarias.manoslibres.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureMathTest {
    // Un móvil 1080×2400: 15 % son 162 px de ancho y 360 px de alto.
    private val w = 1080
    private val h = 2400

    @Test fun cuatroDirecciones() {
        assertEquals(Direccion.DERECHA, GestureMath.clasificarDeslizamiento(300f, 10f, w, h))
        assertEquals(Direccion.IZQUIERDA, GestureMath.clasificarDeslizamiento(-300f, -10f, w, h))
        assertEquals(Direccion.ABAJO, GestureMath.clasificarDeslizamiento(5f, 500f, w, h))
        assertEquals(Direccion.ARRIBA, GestureMath.clasificarDeslizamiento(-5f, -500f, w, h))
    }

    @Test fun elUmbralEsElQuinceporCientoDeCadaDimension() {
        assertNull(GestureMath.clasificarDeslizamiento(161f, 0f, w, h))
        assertEquals(Direccion.DERECHA, GestureMath.clasificarDeslizamiento(162f, 0f, w, h))
        assertNull(GestureMath.clasificarDeslizamiento(0f, 359f, w, h))
        assertEquals(Direccion.ABAJO, GestureMath.clasificarDeslizamiento(0f, 360f, w, h))
    }

    @Test fun enUnaDiagonalGanaElEjeQueMasAvanzaEnProporcion() {
        // 200 px en horizontal son el 18 % del ancho; 300 en vertical, el 12,5 % del alto.
        assertEquals(Direccion.DERECHA, GestureMath.clasificarDeslizamiento(200f, 300f, w, h))
        // 170 horizontal (15,7 %) frente a 600 vertical (25 %).
        assertEquals(Direccion.ABAJO, GestureMath.clasificarDeslizamiento(170f, 600f, w, h))
    }

    @Test fun dimensionesInvalidasNoClasifican() {
        assertNull(GestureMath.clasificarDeslizamiento(500f, 500f, 0, h))
        assertNull(GestureMath.clasificarDeslizamiento(500f, 500f, w, 0))
    }

    @Test fun toqueEsPocoMovimientoYCorto() {
        assertTrue(GestureMath.esToque(3f, 4f, 120, 8f))
        assertFalse(GestureMath.esToque(30f, 0f, 120, 8f))
        assertFalse(GestureMath.esToque(0f, 0f, GestureMath.MANTENER_MS, 8f))
    }

    @Test fun toqueConDosDedos() {
        assertTrue(GestureMath.esToqueDosDedos(movido = false, duracionMs = 200))
        assertFalse(GestureMath.esToqueDosDedos(movido = true, duracionMs = 200))
        assertFalse(GestureMath.esToqueDosDedos(movido = false, duracionMs = 900))
    }
}
