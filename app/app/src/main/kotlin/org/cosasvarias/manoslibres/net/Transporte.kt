package org.cosasvarias.manoslibres.net

import android.content.Context
import android.net.http.HttpEngine
import android.net.http.HttpException
import android.net.http.UploadDataProvider
import android.net.http.UploadDataSink
import android.net.http.UrlRequest
import android.net.http.UrlResponseInfo
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/**
 * Una sola operación sirve para las dos formas de uso: un `POST` normal acumula los bytes de
 * la respuesta, y el `GET` en SSE los va entregando según llegan y no termina hasta que se
 * corta la conexión.
 *
 * Contrato de [solicitar]:
 *  · [onEstado] se llama una vez, con el código HTTP, antes de cualquier byte;
 *  · [onBytes] se llama desde un hilo de red, siempre en orden, con el cuerpo (también el de
 *    los errores 4xx/5xx); el array se reutiliza, así que hay que consumirlo ahí mismo;
 *  · vuelve cuando el cuerpo acaba; lanza [IOException] si la conexión falla;
 *  · cancelar la corrutina cancela la petición.
 */
internal interface Transporte {
    suspend fun solicitar(
        metodo: String,
        url: String,
        cabeceras: Map<String, String>,
        cuerpo: ByteArray?,
        onEstado: (Int) -> Unit,
        onBytes: (ByteArray, Int) -> Unit,
    )

    fun cerrar()

    companion object {
        /**
         * HttpEngine (con QUIC) desde Android 14 si tenemos `Context`; por debajo, o si el
         * motor no arranca, `HttpURLConnection`.
         */
        fun crear(context: Context?, baseUrl: String): Transporte {
            if (context != null && Build.VERSION.SDK_INT >= 34) {
                try {
                    return TransporteHttpEngine(context.applicationContext, baseUrl)
                } catch (_: Throwable) {
                    // cae a HttpURLConnection
                }
            }
            return TransporteUrlConnection()
        }
    }
}

// ── API 34+: HttpEngine (Cronet como módulo de plataforma), con HTTP/3 ─────────────────────

@RequiresApi(34)
internal class TransporteHttpEngine(context: Context, baseUrl: String) : Transporte {
    private val ejecutor: ExecutorService = Executors.newCachedThreadPool()

    private val motor: HttpEngine = HttpEngine.Builder(context)
        .setEnableHttp2(true)
        .setEnableQuic(true)
        .also { b ->
            // Sin pista, el primer intento va por TCP hasta descubrir `Alt-Svc`. Con ella, QUIC
            // desde la primera petición; si UDP no pasa, Cronet cae solo a HTTP/2.
            runCatching {
                val u = URI(baseUrl)
                if (u.scheme == "https" && u.host != null) {
                    val puerto = if (u.port > 0) u.port else 443
                    b.addQuicHint(u.host, puerto, puerto)
                }
            }
        }
        .build()

    override suspend fun solicitar(
        metodo: String,
        url: String,
        cabeceras: Map<String, String>,
        cuerpo: ByteArray?,
        onEstado: (Int) -> Unit,
        onBytes: (ByteArray, Int) -> Unit,
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val trozo = ByteArray(16 * 1024)

        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
                request.followRedirect()
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                onEstado(info.httpStatusCode)
                request.read(ByteBuffer.allocateDirect(16 * 1024))
            }

            override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
                byteBuffer.flip()
                while (byteBuffer.hasRemaining()) {
                    val n = min(byteBuffer.remaining(), trozo.size)
                    byteBuffer.get(trozo, 0, n)
                    onBytes(trozo, n)
                }
                byteBuffer.clear()
                request.read(byteBuffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: HttpException) {
                if (cont.isActive) cont.resumeWithException(IOException(error.message, error))
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                if (cont.isActive) cont.resumeWithException(IOException("petición cancelada"))
            }
        }

        try {
            val b = motor.newUrlRequestBuilder(url, ejecutor, callback)
                .setHttpMethod(metodo)
            cabeceras.forEach { (k, v) -> b.addHeader(k, v) }
            if (cuerpo != null) b.setUploadDataProvider(ProveedorBytes(cuerpo), ejecutor)
            val peticion = b.build()
            cont.invokeOnCancellation { peticion.cancel() }
            peticion.start()
        } catch (t: Throwable) {
            if (cont.isActive) cont.resumeWithException(IOException(t.message, t))
        }
    }

    override fun cerrar() {
        runCatching { motor.shutdown() }
        ejecutor.shutdown()
    }

    private class ProveedorBytes(private val datos: ByteArray) : UploadDataProvider() {
        private var pos = 0

        override fun getLength(): Long = datos.size.toLong()

        override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
            val n = min(byteBuffer.remaining(), datos.size - pos)
            byteBuffer.put(datos, pos, n)
            pos += n
            uploadDataSink.onReadSucceeded(false)
        }

        override fun rewind(uploadDataSink: UploadDataSink) {
            pos = 0
            uploadDataSink.onRewindSucceeded()
        }
    }
}

// ── Cualquier API: HttpURLConnection (HTTP/1.1 o HTTP/2 según el sistema) ───────────────────

internal class TransporteUrlConnection : Transporte {
    private val ejecutor: ExecutorService = Executors.newCachedThreadPool()

    override suspend fun solicitar(
        metodo: String,
        url: String,
        cabeceras: Map<String, String>,
        cuerpo: ByteArray?,
        onEstado: (Int) -> Unit,
        onBytes: (ByteArray, Int) -> Unit,
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val conn = URL(url).openConnection() as HttpURLConnection
        cont.invokeOnCancellation { runCatching { conn.disconnect() } }

        ejecutor.execute {
            try {
                conn.requestMethod = metodo
                conn.connectTimeout = 10_000
                // El nodo manda un keepalive cada 20 s por los flujos; un POST responde al momento.
                conn.readTimeout = 90_000
                conn.useCaches = false
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Accept-Encoding", "identity")
                cabeceras.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (cuerpo != null) {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(cuerpo.size)
                    conn.outputStream.use { it.write(cuerpo) }
                }

                val estado = conn.responseCode
                onEstado(estado)
                val entrada = if (estado >= 400) conn.errorStream else conn.inputStream
                entrada?.use { ins ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (n > 0) onBytes(buf, n)
                    }
                }
                if (cont.isActive) cont.resume(Unit)
            } catch (t: Throwable) {
                if (cont.isActive) {
                    cont.resumeWithException(if (t is IOException) t else IOException(t.message, t))
                }
            } finally {
                runCatching { conn.disconnect() }
            }
        }
    }

    override fun cerrar() {
        ejecutor.shutdown()
    }
}
