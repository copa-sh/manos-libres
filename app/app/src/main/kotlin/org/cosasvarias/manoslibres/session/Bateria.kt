package org.cosasvarias.manoslibres.session

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** ¿Está la app exenta de la optimización de batería (Doze)? */
fun tieneExencionBateria(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/**
 * Pide al usuario la exención de optimización de batería.
 *
 * No hay push por un tercero (docs/07-decisiones.md §3): si el sistema mata el proceso, nadie
 * lo despierta. Esta exención, la notificación persistente y `START_STICKY` son toda la
 * mitigación. Si ya está concedida no hace nada. Muestra el diálogo del sistema; si el
 * dispositivo no lo ofrece, abre la lista de ajustes de optimización.
 *
 * En fabricantes con matarratas propio (Xiaomi, Huawei, Samsung) esto no basta y hay que
 * añadir la excepción a mano en sus ajustes.
 */
@SuppressLint("BatteryLife") // Es el caso de uso que Play restringe y que aquí es el producto.
fun solicitarExencionBateria(context: Context) {
    if (tieneExencionBateria(context)) return
    val peticion = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(peticion)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            // Sin pantalla de ajustes que abrir: no hay nada más que hacer desde aquí.
        }
    }
}
