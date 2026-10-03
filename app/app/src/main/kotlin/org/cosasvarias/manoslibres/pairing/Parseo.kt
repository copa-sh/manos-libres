package org.cosasvarias.manoslibres.pairing

import java.net.URI
import java.net.URLDecoder
import kotlinx.serialization.Serializable

/** Lo que la app necesita para hablar con un nodo. */
@Serializable
data class Emparejamiento(
    val url: String,
    val token: String,
    val device: String = "movil",
)

/**
 * Interpreta lo que el usuario pega o lo que lee un QR. Puro Kotlin, sin Android.
 *
 * Formas aceptadas:
 *  · `manoslibres://pair?url=https%3A%2F%2Fnodo.example&token=abc&device=movil`
 *    (también `manos-libres://`; `device` es opcional);
 *  · una URL y un token separados por espacios o saltos de línea: `https://nodo.example abc`;
 *  · una URL con el token en la consulta: `https://nodo.example/?token=abc`.
 *
 * La URL se devuelve sin la barra final. Devuelve null si falta la URL (http/https con
 * host) o el token.
 */
fun parseEmparejamiento(texto: String, deviceDefault: String = "movil"): Emparejamiento? {
    val t = texto.trim()
    if (t.isEmpty()) return null

    val esquema = t.substringBefore("://", "").lowercase()
    if (esquema == "manoslibres" || esquema == "manos-libres") {
        val consulta = t.substringAfter('?', "")
        val p = parseConsulta(consulta.substringBefore('#'))
        return construir(p["url"], p["token"], p["device"], deviceDefault)
    }

    val partes = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (partes.size == 1) {
        // una URL sola con `?token=` dentro
        val u = partes[0]
        if (!u.contains('?')) return null
        val p = parseConsulta(u.substringAfter('?').substringBefore('#'))
        val sinToken = u.substringBefore('?') +
            p.filterKeys { it != "token" && it != "device" }
                .entries.joinToString("&", prefix = if (p.keys.any { it != "token" && it != "device" }) "?" else "") {
                    "${it.key}=${it.value}"
                }
        return construir(sinToken, p["token"], p["device"], deviceDefault)
    }
    if (partes.size == 2) {
        val (a, b) = partes
        return if (esUrl(a)) construir(a, b, null, deviceDefault) else construir(b, a, null, deviceDefault)
    }
    return null
}

private fun construir(url: String?, token: String?, device: String?, deviceDefault: String): Emparejamiento? {
    val u = url?.trim()?.trimEnd('/') ?: return null
    val k = token?.trim()
    if (k.isNullOrEmpty() || !esUrl(u)) return null
    return Emparejamiento(u, k, device?.trim().takeUnless { it.isNullOrEmpty() } ?: deviceDefault)
}

private fun esUrl(s: String): Boolean {
    val e = s.substringBefore("://", "").lowercase()
    if (e != "http" && e != "https") return false
    return try {
        !URI(s).host.isNullOrEmpty()
    } catch (_: Exception) {
        false
    }
}

private fun parseConsulta(q: String): Map<String, String> {
    val m = LinkedHashMap<String, String>()
    for (par in q.split('&')) {
        if (par.isEmpty()) continue
        val k = par.substringBefore('=')
        val v = par.substringAfter('=', "")
        try {
            m.putIfAbsent(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"))
        } catch (_: IllegalArgumentException) {
            // percent-encoding roto: se ignora ese par
        }
    }
    return m
}
