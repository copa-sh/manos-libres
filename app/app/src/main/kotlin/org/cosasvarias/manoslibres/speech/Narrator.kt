package org.cosasvarias.manoslibres.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * El narrador.
 *
 * Gestiona su propia cola en vez de dejársela a Android, y eso es lo que hace baratos el
 * retroceso y el salto: no hay que buscar dentro de un audio, solo mover un índice y volver
 * a encolar desde ahí.
 *
 * ```
 * cola  ──►  [ m13/0 ][ m13/1 ][ m13/2 ][ m14/0 ] ...
 *                        ▲
 *                     cursor
 * ```
 *
 * Cada frase se dice con `utteranceId = "<messageId>/<índice>#<n>"`: la parte antes de `#` es
 * la coordenada de narración del protocolo, y `#n` hace único cada intento para ignorar los
 * callbacks tardíos de una frase que ya se cortó. Solo hay **una** locución en vuelo a la vez.
 *
 * Hilos: todo el estado se toca en el hilo principal. Los callbacks de TTS saltan a él.
 *
 * Aparte de la cola hay una lista de **avisos** (`intercalar`/`interrumpirCon`): textos que no
 * son del agente y que no tienen coordenada. Se dicen antes de la siguiente frase, y no
 * cuentan para la posición ni para retroceder.
 *
 * **Reanudación.** La posición (`messageId`, `sentence`) se guarda por dispositivo en
 * `SharedPreferences` cada vez que empieza una frase. Al conectar, el nodo reenvía lo que
 * falta; durante [VENTANA_REPLAY_MS] los mensajes se retienen hasta ver aquel en el que se
 * quedó la voz, se descartan los anteriores (ya oídos) y se sigue **en esa frase**. Si no
 * aparece (hueco del buffer o nodo reiniciado) se narran solo los últimos [MAX_SIN_POSICION].
 */
class Narrator(
    context: Context,
    private val onPosicion: (messageId: String, sentence: Int) -> Unit,
) {
    /** Una frase encolada, con su coordenada. */
    data class Frase(val messageId: String, val indice: Int, val texto: String) {
        val coordenada get() = "$messageId/$indice"
    }

    private class EnVuelo(val uid: String, val frase: Frase?)
    private data class Pos(val messageId: String, val sentence: Int)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("narracion", Context.MODE_PRIVATE)
    private val audio = app.getSystemService(AudioManager::class.java)

    private val cola = mutableListOf<Frase>()
    private var cursor = 0
    private val avisos = ArrayDeque<String>()
    private var enVuelo: EnVuelo? = null
    private var contador = 0

    private var listo = false
    private var velocidadReposo = Ajustes.velocidadReposo(app)
    private var acelerando = false

    /** El usuario (o una decisión) ha pausado. Se queda así hasta [seguir]. */
    var pausado = false
        private set
    private var pausadoPorAviso = false
    /** Pausa impuesta por otra app con el foco de audio (una llamada); se levanta sola. */
    private var pausaTransitoria = false

    /** Marca de [SystemClock.elapsedRealtime] de la última vez que pasó algo de voz. */
    var ultimaActividad = SystemClock.elapsedRealtime()
        private set

    /** (hablando, pausado, frase en curso) — para la `MediaSession` y el aviso permanente. */
    var onEstado: ((hablando: Boolean, pausado: Boolean, texto: String?) -> Unit)? = null

    val hablando: Boolean get() = enVuelo != null

    /** Hay algo dicho o por decir que no es el fin de la cola. */
    val ocupado: Boolean get() = enVuelo != null || avisos.isNotEmpty() || cursor < cola.size

    // Reanudación
    private var dispositivo = "movil"
    private var posGuardada: Pos? = null
    private var enReplay = true
    private var reanudado = false
    private var temporizadorReplay = false
    private val retenidos = mutableListOf<Pair<String, List<String>>>()

    private val atributos = AudioAttributes.Builder()
        // USAGE_ASSISTANT con ducking: la narración se mezcla por encima de la música en
        // vez de matarla. Es lo que la distingue de un reproductor.
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var peticionFoco: AudioFocusRequest? = null
    private val cambioFoco = AudioManager.OnAudioFocusChangeListener { cambio ->
        when (cambio) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausaTransitoria = true
                if (enVuelo?.frase != null) detenerActual()
                notificarEstado()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausar()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausaTransitoria) {
                    pausaTransitoria = false
                    avanzar()
                }
            }
            else -> Unit // LOSS_TRANSIENT_CAN_DUCK: el sistema baja el volumen; seguimos.
        }
    }

    private val tts: TextToSpeech = TextToSpeech(app) { status -> main.post { alIniciar(status) } }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post { alEmpezar(utteranceId) }
            }

            override fun onDone(utteranceId: String?) {
                main.post { terminar(utteranceId) }
            }

            @Deprecated("la firma sin errorCode está obsoleta pero es la que llama el sistema")
            override fun onError(utteranceId: String?) {
                main.post { terminar(utteranceId) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post { terminar(utteranceId) }
            }
        })
    }

    private fun alIniciar(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        listo = true
        configurarVoz()
        tts.setAudioAttributes(atributos)
        tts.setSpeechRate(velocidadActual())
        avanzar()
    }

    /**
     * Voz fija, sin autodetección (docs/07-decisiones.md §6):
     * la elegida por el usuario si existe, o la primera variante de español disponible. Si no
     * hay ninguna se deja en es-ES: equivocarse siempre igual suena mejor que cambiar de voz.
     */
    private fun configurarVoz() {
        val nombre = Ajustes.voz(app)
        if (nombre != null) {
            val v = tts.voices?.firstOrNull { it.name == nombre }
            if (v != null) {
                tts.voice = v
                return
            }
        }
        val candidatas = listOf(Locale("es", "ES"), Locale("es", "MX"), Locale("es", "US"), Locale("es"))
        for (loc in candidatas) {
            if (tts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = loc
                return
            }
        }
        tts.language = candidatas[0]
    }

    // ── Reanudación por dispositivo ────────────────────────────────────────────

    /**
     * Fija de quién es la posición guardada y empieza un ciclo de reanudación. Se llama al
     * conectar (y al re-emparejar): descarta la cola que hubiera.
     */
    fun cargarPosicion(device: String) {
        detenerActual()
        cola.clear()
        cursor = 0
        avisos.clear()
        retenidos.clear()
        dispositivo = device
        posGuardada = leerPosicion()
        enReplay = true
        reanudado = false
        notificarEstado()
    }

    private fun leerPosicion(): Pos? {
        val s = prefs.getString("pos_$dispositivo", null) ?: return null
        val i = s.lastIndexOf('|')
        if (i <= 0) return null
        return Pos(s.substring(0, i), s.substring(i + 1).toIntOrNull() ?: 0)
    }

    private fun guardarPosicion(f: Frase) {
        prefs.edit().putString("pos_$dispositivo", "${f.messageId}|${f.indice}").apply()
    }

    /** La coordenada de la frase en curso (o la siguiente por decir). */
    fun posicionActual(): Pair<String, Int>? =
        (enVuelo?.frase ?: cola.getOrNull(cursor))?.let { it.messageId to it.indice }

    // ── Alimentar la cola ──────────────────────────────────────────────────────

    /** Encola un mensaje ya segmentado por el nodo. */
    fun encolar(messageId: String, frases: List<String>) {
        if (frases.isEmpty()) return
        if (cola.any { it.messageId == messageId } || retenidos.any { it.first == messageId }) return
        ultimaActividad = SystemClock.elapsedRealtime()

        if (enReplay && !reanudado) {
            val pos = posGuardada
            if (pos != null && pos.messageId == messageId) {
                // Aquí se quedó la voz: lo anterior ya se oyó, y se sigue en esta frase.
                retenidos.clear()
                reanudado = true
                añadir(messageId, frases, desde = pos.sentence.coerceIn(0, frases.lastIndex))
                return
            }
            retenidos += messageId to frases
            if (!temporizadorReplay) {
                temporizadorReplay = true
                main.postDelayed({ cerrarReplay() }, VENTANA_REPLAY_MS)
            }
            return
        }
        añadir(messageId, frases, desde = 0)
    }

    private fun cerrarReplay() {
        enReplay = false
        temporizadorReplay = false
        posGuardada = null
        val ultimos = retenidos.takeLast(MAX_SIN_POSICION)
        retenidos.clear()
        ultimos.forEach { (id, frases) -> añadir(id, frases, 0) }
    }

    private fun añadir(messageId: String, frases: List<String>, desde: Int) {
        val inicio = cola.size
        frases.forEachIndexed { i, texto -> cola += Frase(messageId, i, texto) }
        if (desde > 0) cursor = inicio + desde
        recortar()
        avanzar()
    }

    /** Acota la memoria: conserva un centenar de frases ya dichas para poder retroceder. */
    private fun recortar() {
        if (cursor > 200) {
            val k = cursor - 100
            repeat(k) { cola.removeAt(0) }
            cursor -= k
        }
    }

    /**
     * Un aviso se dice **entre** frases, no encima de la actual: interrumpir a mitad de
     * frase para decir «tarea terminada» hace perder el hilo de las dos cosas.
     */
    fun intercalar(texto: String) {
        if (texto.isBlank()) return
        ultimaActividad = SystemClock.elapsedRealtime()
        avisos.addLast(texto)
        avanzar()
    }

    /**
     * Dice [texto] ya, cortando la frase en curso (que se repetirá desde su principio al
     * seguir). Una decisión sí interrumpe: bloquea al agente, es lo más importante del
     * momento. Con [pausar] la narración queda detenida tras el aviso hasta
     * [reanudarTrasAviso]; sin él continúa sola (es el caso de «¿en qué estado estás?»).
     */
    fun interrumpirCon(texto: String, pausar: Boolean = false) {
        ultimaActividad = SystemClock.elapsedRealtime()
        if (pausar && !pausado) {
            pausado = true
            pausadoPorAviso = true
        }
        if (texto.isNotBlank()) avisos.addFirst(texto)
        if (enVuelo?.frase != null) detenerActual()
        pausaTransitoria = false
        avanzar()
    }

    /** Levanta la pausa que puso [interrumpirCon], pero no una que eligió el usuario. */
    fun reanudarTrasAviso() {
        if (pausadoPorAviso) seguir()
    }

    // ── Los gestos ─────────────────────────────────────────────────────────────

    /** Pausa o reanuda. Devuelve true si ahora suena. */
    fun pausarOSeguir(): Boolean {
        return if (pausado || pausaTransitoria) {
            seguir()
            true
        } else {
            pausar()
            false
        }
    }

    fun pausar() {
        pausado = true
        pausadoPorAviso = false
        if (enVuelo?.frase != null) detenerActual()
        if (enVuelo == null) soltarFoco()
        notificarEstado()
    }

    /** Reanuda en la frase en la que se pausó (se repite desde su principio). */
    fun seguir() {
        pausado = false
        pausadoPorAviso = false
        pausaTransitoria = false
        avanzar()
        notificarEstado()
    }

    /** Doble toque, o doble pulsación en el auricular: `cursor -= 1`, y si estaba en la primera de un mensaje cae en la última del anterior. */
    fun frasePrevia() {
        irA(maxOf(0, minOf(cursor, cola.size - 1) - 1))
    }

    fun fraseSiguiente() {
        if (cursor + 1 >= cola.size) {
            detenerActual()
            cursor = cola.size
            avisos.addLast(app.getString(org.cosasvarias.manoslibres.R.string.voz_sin_mas))
            pausado = false
            avanzar()
            return
        }
        irA(cursor + 1)
    }

    /** Deslizar a la izquierda: el mensaje anterior, completo, desde su primera frase. */
    fun mensajeAnterior() {
        val actual = indiceActual()
        val inicio = inicioDeMensaje(actual)
        irA(if (inicio > 0) inicioDeMensaje(inicio - 1) else 0)
    }

    /** Deslizar arriba: repetir el mensaje entero desde su primera frase. */
    fun repetirMensaje() {
        irA(inicioDeMensaje(indiceActual()))
    }

    private fun indiceActual(): Int = minOf(cursor, cola.size - 1).coerceAtLeast(0)

    private fun inicioDeMensaje(i: Int): Int {
        if (cola.isEmpty()) return 0
        val id = cola[i].messageId
        var k = i
        while (k > 0 && cola[k - 1].messageId == id) k--
        return k
    }

    /** Mueve el cursor y vuelve a hablar desde ahí, saliendo de la pausa si la había. */
    private fun irA(nuevo: Int) {
        detenerActual()
        cursor = nuevo.coerceIn(0, cola.size)
        pausado = false
        pausadoPorAviso = false
        pausaTransitoria = false
        ultimaActividad = SystemClock.elapsedRealtime()
        avanzar()
    }

    /**
     * Mantener pulsado: ×2 mientras se mantiene, ×1 al soltar. `setSpeechRate` solo vale para
     * la siguiente locución, así que al entrar se rehabla la frase en curso a la nueva
     * velocidad; al soltar no se repite nada y el ×1 llega con la frase siguiente.
     */
    fun acelerar(activo: Boolean) {
        if (acelerando == activo) return
        acelerando = activo
        if (activo && enVuelo?.frase != null) {
            detenerActual()
            avanzar()
        }
    }

    fun velocidadReposo(v: Float) {
        velocidadReposo = v.coerceIn(0.5f, 2.5f)
        Ajustes.velocidadReposo(app, velocidadReposo)
    }

    fun velocidadReposo(): Float = velocidadReposo

    private fun velocidadActual(): Float = velocidadReposo * (if (acelerando) 2f else 1f)

    fun detener() = pausar()

    fun liberar() {
        main.removeCallbacksAndMessages(null)
        enVuelo = null
        tts.stop()
        tts.shutdown()
        soltarFoco()
    }

    // ── Motor ──────────────────────────────────────────────────────────────────

    /** Si no hay nada en vuelo, lo siguiente: un aviso, o la frase del cursor si no hay pausa. */
    private fun avanzar() {
        if (!listo || enVuelo != null || pausaTransitoria) return

        val aviso = avisos.removeFirstOrNull()
        if (aviso != null) {
            hablar(null, aviso)
            return
        }
        if (pausado) {
            soltarFoco()
            notificarEstado()
            return
        }
        while (cursor < cola.size && cola[cursor].texto.isBlank()) cursor++
        val f = cola.getOrNull(cursor)
        if (f == null) {
            soltarFoco()
            notificarEstado()
            return
        }
        hablar(f, f.texto)
    }

    private fun hablar(frase: Frase?, texto: String) {
        pedirFoco()
        val uid = "${frase?.coordenada ?: "aviso"}#${++contador}"
        enVuelo = EnVuelo(uid, frase)
        tts.setSpeechRate(velocidadActual())
        val r = tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, uid)
        if (r != TextToSpeech.SUCCESS) {
            // El motor rechazó la locución: no quedarse colgado esperando un callback.
            main.postDelayed({ terminar(uid) }, 500)
        }
        notificarEstado()
    }

    private fun alEmpezar(uid: String?) {
        val ev = enVuelo ?: return
        if (ev.uid != uid) return
        ultimaActividad = SystemClock.elapsedRealtime()
        val f = ev.frase ?: return
        guardarPosicion(f)
        onPosicion(f.messageId, f.indice)
    }

    private fun terminar(uid: String?) {
        val ev = enVuelo ?: return
        if (ev.uid != uid) return // un callback tardío de algo que ya cortamos
        enVuelo = null
        ultimaActividad = SystemClock.elapsedRealtime()
        ev.frase?.let { f ->
            val i = cola.indexOf(f)
            if (i >= 0) cursor = i + 1
        }
        avanzar()
    }

    private fun detenerActual() {
        enVuelo = null
        tts.stop()
    }

    private fun notificarEstado() {
        onEstado?.invoke(enVuelo != null, pausado, enVuelo?.frase?.texto)
    }

    // ── Foco de audio ──────────────────────────────────────────────────────────

    private fun pedirFoco() {
        if (peticionFoco != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(atributos)
            .setOnAudioFocusChangeListener(cambioFoco, main)
            .build()
        audio.requestAudioFocus(req)
        // Se hable o no con el foco concedido, se sigue: una llamada ya nos pausará.
        peticionFoco = req
    }

    private fun soltarFoco() {
        peticionFoco?.let { audio.abandonAudioFocusRequest(it) }
        peticionFoco = null
    }

    companion object {
        /** Cuánto se espera, tras el primer mensaje, a ver si llega aquel en que se quedó la voz. */
        const val VENTANA_REPLAY_MS = 1200L
        /** Si no se encuentra la posición guardada, solo se narran los últimos N del reenvío. */
        const val MAX_SIN_POSICION = 2
    }
}
