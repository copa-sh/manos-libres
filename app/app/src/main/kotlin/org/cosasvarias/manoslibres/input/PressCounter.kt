package org.cosasvarias.manoslibres.input

/**
 * Cuenta pulsaciones sucesivas del botón del auricular: N pulsaciones separadas por menos de
 * [ventanaMs] cuentan como una sola acción de N.
 *
 * Es lógica pura, sin reloj ni `Handler`: quien la usa le pasa la hora de cada evento y
 * programa él mismo el aviso de «se cerró la ventana». Así se prueba sin Android.
 */
class PressCounter(private val ventanaMs: Long = VENTANA_MS) {
    private var n = 0
    private var ultimo = 0L

    /** Registra una pulsación en el instante [t] y devuelve cuántas van en la racha. */
    fun pulsar(t: Long): Int {
        if (n > 0 && t - ultimo > ventanaMs) n = 0
        n += 1
        ultimo = t
        return n
    }

    /**
     * Si en el instante [t] ya pasó la ventana desde la última pulsación, cierra la racha y
     * devuelve su tamaño; si no (o no hay racha), 0 y no cambia nada.
     */
    fun cerrar(t: Long): Int {
        if (n == 0 || t - ultimo < ventanaMs) return 0
        val r = n
        n = 0
        return r
    }

    fun reiniciar() {
        n = 0
    }

    companion object {
        const val VENTANA_MS = 600L
    }
}
