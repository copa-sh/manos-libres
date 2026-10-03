package org.cosasvarias.manoslibres.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Recibe el toque en una de las acciones de la notificación de decisión: es lo que permite
 * contestar desde la pantalla de bloqueo sin abrir la app.
 *
 * Delega en [SessionStore.acciones], que instala el [SessionService]. Si el servicio no está
 * vivo (el sistema mató el proceso) no hay a quién contestar: se relanza el servicio para que
 * se reconecte y la decisión vuelva a presentarse desde el buffer de replay del nodo.
 */
class DecisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SessionService.ACCION_CONTESTAR) return
        val decisionId = intent.getStringExtra(SessionService.EXTRA_DECISION_ID) ?: return
        val optionId = intent.getStringExtra(SessionService.EXTRA_OPTION_ID) ?: return

        val acciones = SessionStore.acciones
        if (acciones == null) {
            ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
            return
        }
        acciones.contestar(decisionId, optionId)
    }
}
