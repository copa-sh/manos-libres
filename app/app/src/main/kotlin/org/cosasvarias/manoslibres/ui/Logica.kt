package org.cosasvarias.manoslibres.ui

/** Un tramo de un mensaje: prosa o código (lo que va entre vallas ```). */
sealed interface Trozo {
    data class Texto(val texto: String) : Trozo
    data class Codigo(val texto: String) : Trozo
}

/**
 * Parte un mensaje en prosa y bloques de código delimitados por vallas ```.
 * Una valla sin cerrar (el texto aún llega en streaming) cuenta como código hasta el final.
 * Los tramos vacíos se descartan.
 */
fun segmentarCodigo(texto: String): List<Trozo> {
    val salida = ArrayList<Trozo>()
    val actual = ArrayList<String>()
    var enCodigo = false

    fun volcar() {
        val s = actual.joinToString("\n")
        if (s.isNotBlank()) salida.add(if (enCodigo) Trozo.Codigo(s) else Trozo.Texto(s.trim('\n')))
        actual.clear()
    }

    for (linea in texto.split("\n")) {
        if (linea.trimStart().startsWith("```")) {
            volcar()
            enCodigo = !enCodigo
        } else {
            actual.add(linea)
        }
    }
    volcar()
    return salida
}

/** ¿Es un mensaje de chat mío? Compara con el nombre de dispositivo emparejado. */
fun esPropio(from: String, dispositivo: String?): Boolean =
    dispositivo != null && dispositivo.isNotBlank() && from.trim().equals(dispositivo.trim(), ignoreCase = true)

/** El texto listo para enviar, o null si no hay nada que enviar. */
fun textoEnviable(entrada: String): String? = entrada.trim().ifEmpty { null }

/** ¿El rol de una línea es del usuario? */
fun esRolUsuario(rol: String): Boolean = rol.equals("user", ignoreCase = true)
