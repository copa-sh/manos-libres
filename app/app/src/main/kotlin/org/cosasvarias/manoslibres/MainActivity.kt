package org.cosasvarias.manoslibres

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.cosasvarias.manoslibres.session.SessionService
import org.cosasvarias.manoslibres.ui.theme.BohioTheme

/**
 * El punto de entrada, y la pantalla menos importante de la app.
 *
 * Su trabajo es arrancar el servicio, resolver el emparejamiento la primera vez y llevar al
 * usuario a modo narración cuanto antes. Todo lo que pasa después ocurre en el servicio, en
 * la pantalla negra o en la botonera de decisión.
 *
 * TODO(H1): emparejamiento (URL + token, luego QR en H6), lista de sesiones y
 * TranscriptScreen.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        startForegroundService(Intent(this, SessionService::class.java))
        setContent {
            BohioTheme {
                // TODO(H1)
            }
        }
    }
}
