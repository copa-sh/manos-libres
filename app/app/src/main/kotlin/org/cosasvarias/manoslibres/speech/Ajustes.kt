package org.cosasvarias.manoslibres.speech

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferencias de narración y entrada que el usuario puede cambiar. Es un envoltorio fino
 * sobre `SharedPreferences` para que la pantalla de ajustes (de la UI) y el servicio lean
 * y escriban las mismas claves sin saber cómo se llaman.
 */
object Ajustes {
    private const val PREFS = "ajustes"

    private fun p(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Minutos de silencio tras los que el latido hablado dice algo. 0 lo desactiva. */
    const val LATIDO_POR_DEFECTO_MIN = 3
    fun latidoMin(c: Context): Int = p(c).getInt("latido_min", LATIDO_POR_DEFECTO_MIN)
    fun latidoMin(c: Context, minutos: Int) = p(c).edit().putInt("latido_min", minutos.coerceAtLeast(0)).apply()

    /** Velocidad de reposo de la voz (1.0 = normal). Se recuerda entre sesiones. */
    fun velocidadReposo(c: Context): Float = p(c).getFloat("velocidad", 1.0f)
    fun velocidadReposo(c: Context, v: Float) = p(c).edit().putFloat("velocidad", v.coerceIn(0.5f, 2.5f)).apply()

    /** Nombre de la voz del sistema elegida por el usuario; null = la primera en español. */
    fun voz(c: Context): String? = p(c).getString("voz", null)
    fun voz(c: Context, nombre: String?) = p(c).edit().putString("voz", nombre).apply()

    /** Capturar las teclas de volumen con la sesión de medios (volumen largo = atrás/acelerar). */
    fun teclasVolumen(c: Context): Boolean = p(c).getBoolean("teclas_volumen", true)
    fun teclasVolumen(c: Context, activo: Boolean) = p(c).edit().putBoolean("teclas_volumen", activo).apply()
}
