package org.cosasvarias.manoslibres.input

import org.junit.Assert.assertEquals
import org.junit.Test

class PressCounterTest {
    @Test fun unaPulsacionSeCierraTrasLaVentana() {
        val c = PressCounter(600)
        assertEquals(1, c.pulsar(1000))
        assertEquals(0, c.cerrar(1300)) // aún dentro de la ventana
        assertEquals(1, c.cerrar(1600))
        assertEquals(0, c.cerrar(2600)) // ya cerrada: no se repite
    }

    @Test fun pulsacionesDentroDeLaVentanaSeAcumulan() {
        val c = PressCounter(600)
        assertEquals(1, c.pulsar(1000))
        assertEquals(2, c.pulsar(1400))
        assertEquals(3, c.pulsar(1900))
        assertEquals(0, c.cerrar(2100))
        assertEquals(3, c.cerrar(2500))
    }

    @Test fun unaPulsacionTardiaEmpiezaRachaNueva() {
        val c = PressCounter(600)
        c.pulsar(1000)
        assertEquals(1, c.pulsar(2000))
        assertEquals(1, c.cerrar(2600))
    }

    @Test fun reiniciarDescartaLaRacha() {
        val c = PressCounter(600)
        c.pulsar(0)
        c.pulsar(100)
        c.reiniciar()
        assertEquals(0, c.cerrar(5000))
        assertEquals(1, c.pulsar(6000))
    }

    @Test fun cerrarSinRachaDevuelveCero() {
        assertEquals(0, PressCounter().cerrar(123456))
    }
}
