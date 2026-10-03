package org.cosasvarias.manoslibres

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.cosasvarias.manoslibres.pairing.Emparejamiento
import org.cosasvarias.manoslibres.pairing.PairingStore
import org.cosasvarias.manoslibres.pairing.parseEmparejamiento
import org.cosasvarias.manoslibres.session.SessionService
import org.cosasvarias.manoslibres.session.SessionStore
import org.cosasvarias.manoslibres.session.solicitarExencionBateria
import org.cosasvarias.manoslibres.ui.PairingScreen
import org.cosasvarias.manoslibres.ui.TranscriptScreen
import org.cosasvarias.manoslibres.ui.theme.BohioTheme

/**
 * El punto de entrada: arranca el servicio, resuelve el emparejamiento y muestra la
 * transcripción. Todo lo demás ocurre en el servicio, en la pantalla negra o en la botonera.
 */
class MainActivity : ComponentActivity() {
    private lateinit var almacen: PairingStore

    private var emparejado by mutableStateOf(false)
    private var dispositivo by mutableStateOf<String?>(null)
    private var enlace by mutableStateOf<Emparejamiento?>(null)
    private var bateriaPendiente by mutableStateOf(false)

    private val pedirNotificaciones =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        almacen = PairingStore(applicationContext)
        refrescarEmparejamiento()
        leerEnlace(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pedirNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        arrancarServicio()

        setContent {
            BohioTheme {
                val conexion by SessionStore.conexion.collectAsState()
                if (conexion == SessionStore.Conexion.SIN_EMPAREJAR && !emparejado) {
                    PairingScreen(enlace = enlace, onEmparejar = ::emparejar)
                } else {
                    TranscriptScreen(
                        dispositivo = dispositivo,
                        bateriaPendiente = bateriaPendiente,
                        onBateria = { solicitarExencionBateria(this@MainActivity) },
                        onDesemparejar = ::desemparejar,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        leerEnlace(intent)
    }

    override fun onResume() {
        super.onResume()
        // El usuario vuelve de los ajustes de batería: reevaluar.
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        bateriaPendiente = !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun arrancarServicio() {
        ContextCompat.startForegroundService(this, Intent(this, SessionService::class.java))
    }

    private fun refrescarEmparejamiento() {
        val e = almacen.cargar()
        emparejado = e != null
        dispositivo = e?.device
    }

    /** Un `manoslibres://pair?url=&token=` rellena el formulario; el usuario confirma. */
    private fun leerEnlace(i: Intent?) {
        val dato = i?.dataString ?: return
        if (i.data?.scheme != "manoslibres") return
        parseEmparejamiento(dato)?.let { enlace = it }
    }

    private fun emparejar(url: String, token: String): Boolean {
        val okUrl = url.startsWith("http://") || url.startsWith("https://")
        if (!okUrl || token.isEmpty()) return false
        val nombre = enlace?.device.orEmpty().ifBlank { Build.MODEL }
        almacen.guardar(Emparejamiento(url, token, nombre))
        enlace = null
        refrescarEmparejamiento()
        arrancarServicio() // el servicio relee el emparejamiento en onStartCommand
        return true
    }

    private fun desemparejar() {
        almacen.borrar()
        refrescarEmparejamiento()
        arrancarServicio() // para que el servicio cierre la conexión actual
    }
}
