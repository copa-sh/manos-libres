package org.cosasvarias.manoslibres.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import org.cosasvarias.manoslibres.net.DecisionOption
import org.cosasvarias.manoslibres.session.SessionStore
import org.cosasvarias.manoslibres.ui.theme.Bohio
import org.cosasvarias.manoslibres.ui.theme.BohioTheme

/**
 * Contenedor de [DecisionScreen]: contestar sin desbloquear el móvil es el camino crítico.
 *
 * Pinta siempre la decisión pendiente actual de [SessionStore.decision]. Se cierra cuando
 * deja de haberla (contestada en otro dispositivo, cancelada) o tras contestar, salvo que
 * ya haya otra pendiente, que entonces se muestra a continuación.
 */
class DecisionActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        setContent {
            BohioTheme {
                val decision by SessionStore.decision.collectAsState()
                var contestada by remember { mutableStateOf<String?>(null) }
                val d = decision

                // Sin decisión (resuelta fuera, o aún no publicada): breve gracia y cierre.
                LaunchedEffect(d?.decisionId) {
                    if (d == null) {
                        delay(1500)
                        if (SessionStore.decision.value == null) finish()
                    }
                }
                // Tras contestar: si el servicio no publica otra distinta, cerrar.
                LaunchedEffect(contestada) {
                    val id = contestada ?: return@LaunchedEffect
                    delay(700)
                    val actual = SessionStore.decision.value
                    if (actual == null || actual.decisionId == id) finish()
                }

                if (d != null && d.decisionId != contestada) {
                    DecisionScreen(
                        decision = d,
                        onElegir = { opcion: DecisionOption ->
                            val acciones = SessionStore.acciones
                            if (acciones != null && contestada != d.decisionId) {
                                contestada = d.decisionId
                                acciones.contestar(d.decisionId, opcion.id)
                            }
                        },
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Bohio.bg1))
                }
            }
        }
    }
}
