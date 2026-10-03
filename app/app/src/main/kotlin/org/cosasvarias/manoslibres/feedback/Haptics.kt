package org.cosasvarias.manoslibres.feedback

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.cosasvarias.manoslibres.net.AlertPattern
import org.cosasvarias.manoslibres.net.OptionTone

/**
 * Los cuatro patrones hápticos.
 *
 * Son cuatro y no ocho a propósito: en el bolsillo, con abrigo, no se distinguen más. El
 * protocolo transporta el nombre del patrón; el mapeo a milisegundos vive aquí, en el
 * cliente, porque depende del hardware.
 *
 * | patrón    | onda de respaldo (`createWaveform`) | composición (API 31+, si hay primitivas) |
 * |-----------|-------------------------------------|------------------------------------------|
 * | `corto`   | 40 ms                               | CLICK                                    |
 * | `doble`   | 40-80-40 ms                         | CLICK, 80 ms, CLICK                      |
 * | `largo`   | 400 ms continuos                    | SLOW_RISE + THUD (se siente continuo)    |
 * | `urgente` | 4 × (100-60) ms                     | 4 × CLICK a 120 ms                       |
 *
 * Los tres informativos respetan No Molestar (uso NOTIFICATION); `urgente` no (uso ALARM).
 * La confirmación de gestos y de respuestas es uso TOUCH.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    private enum class Uso { NOTIFICACION, ALARMA, TOQUE }

    /** Un paso de una composición: primitiva, escala 0..1 y silencio previo. */
    private class Paso(val primitiva: Int, val escala: Float = 1f, val retrasoMs: Int = 0)

    // ── Avisos del nodo ────────────────────────────────────────────────────────

    fun avisar(pattern: AlertPattern) = when (pattern) {
        AlertPattern.corto -> emitir(
            Uso.NOTIFICACION,
            listOf(Paso(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f)),
            longArrayOf(0, 40),
        )
        AlertPattern.doble -> emitir(
            Uso.NOTIFICACION,
            listOf(
                Paso(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f),
                Paso(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f, 80),
            ),
            longArrayOf(0, 40, 80, 40),
        )
        AlertPattern.largo -> emitir(
            Uso.NOTIFICACION,
            listOf(
                Paso(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 1f),
                Paso(VibrationEffect.Composition.PRIMITIVE_THUD, 1f),
            ),
            longArrayOf(0, 400),
        )
        AlertPattern.urgente -> emitir(
            Uso.ALARMA,
            List(4) { i -> Paso(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, if (i == 0) 0 else 120) },
            longArrayOf(0, 100, 60, 100, 60, 100, 60, 100),
        )
    }

    /** La vibración de «te necesito: hay que decidir». Es el patrón `largo`. */
    fun largo() = avisar(AlertPattern.largo)

    // ── Confirmaciones ─────────────────────────────────────────────────────────

    /**
     * La confirmación de una decisión va por el **tono**, no por la opción elegida: así
     * aprobar algo destructivo se *siente* distinto de aprobar algo inocuo, sin necesidad
     * de leer nada. `danger` es el más pesado: dos golpes secos.
     */
    fun confirmar(tone: OptionTone) = when (tone) {
        OptionTone.go, OptionTone.neutral -> emitir(
            Uso.TOQUE,
            listOf(Paso(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f)),
            longArrayOf(0, 30),
        )
        OptionTone.stop -> emitir(
            Uso.TOQUE,
            listOf(
                Paso(VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f),
                Paso(VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f, 50),
            ),
            longArrayOf(0, 25, 50, 25),
        )
        OptionTone.danger -> emitir(
            Uso.TOQUE,
            listOf(
                Paso(VibrationEffect.Composition.PRIMITIVE_THUD, 1f),
                Paso(VibrationEffect.Composition.PRIMITIVE_THUD, 1f, 80),
            ),
            longArrayOf(0, 200, 80, 200),
        )
    }

    /** Al entrar y al salir de la aceleración: un pulso mínimo, solo para confirmar. */
    fun tic() = emitir(
        Uso.TOQUE,
        listOf(Paso(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f)),
        longArrayOf(0, 15),
    )

    fun cancelar() = vibrator.cancel()

    // ── Motor ──────────────────────────────────────────────────────────────────

    /**
     * En API 31+ y con *todas* las primitivas del patrón soportadas se compone; en cualquier
     * otro caso se cae a una onda cuadrada. `ms` alterna silencio/pulso empezando por silencio.
     */
    private fun emitir(uso: Uso, pasos: List<Paso>, ms: LongArray) {
        val efecto = composicion(pasos) ?: onda(ms)
        vibrar(efecto, uso)
    }

    private fun composicion(pasos: List<Paso>): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try {
            val primitivas = IntArray(pasos.size) { pasos[it].primitiva }
            if (!vibrator.areAllPrimitivesSupported(*primitivas)) return null
            val c = VibrationEffect.startComposition()
            pasos.forEach { c.addPrimitive(it.primitiva, it.escala, it.retrasoMs) }
            c.compose()
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun onda(ms: LongArray): VibrationEffect {
        val amplitudes = IntArray(ms.size) { i -> if (i % 2 == 0) 0 else 255 }
        return VibrationEffect.createWaveform(ms, amplitudes, -1)
    }

    private fun vibrar(efecto: VibrationEffect, uso: Uso) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val usage = when (uso) {
                Uso.NOTIFICACION -> VibrationAttributes.USAGE_NOTIFICATION
                Uso.ALARMA -> VibrationAttributes.USAGE_ALARM
                Uso.TOQUE -> VibrationAttributes.USAGE_TOUCH
            }
            vibrator.vibrate(efecto, VibrationAttributes.createForUsage(usage))
        } else {
            val usage = when (uso) {
                Uso.NOTIFICACION -> AudioAttributes.USAGE_NOTIFICATION
                Uso.ALARMA -> AudioAttributes.USAGE_ALARM
                Uso.TOQUE -> AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
            }
            @Suppress("DEPRECATION")
            vibrator.vibrate(efecto, AudioAttributes.Builder().setUsage(usage).build())
        }
    }
}
