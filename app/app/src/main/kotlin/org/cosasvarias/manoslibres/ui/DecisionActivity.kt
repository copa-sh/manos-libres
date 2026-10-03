package org.cosasvarias.manoslibres.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.cosasvarias.manoslibres.ui.theme.BohioTheme

/**
 * Contenedor de [DecisionScreen].
 *
 * Existe como Activity aparte por una razón concreta: `showWhenLocked` + `turnScreenOn` en
 * el manifiesto es lo que permite contestar **sin desbloquear el móvil**, y ése es el
 * camino crítico de toda la app. La lanza el [org.cosasvarias.manoslibres.session.SessionService]
 * con un full-screen intent al recibir un `decision.request`.
 *
 * TODO(H3): recibir el `decisionId` por intent, leer la decisión del servicio, vibrar con
 * `largo`, enunciar la pregunta y las opciones, y cerrarse si llega un `decision.resolved`
 * (contestada en otro dispositivo o cancelada por el agente).
 */
class DecisionActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            BohioTheme {
                // TODO(H3)
            }
        }
    }
}
