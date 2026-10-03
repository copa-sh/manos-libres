package org.cosasvarias.manoslibres.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cosasvarias.manoslibres.net.DecisionOption
import org.cosasvarias.manoslibres.net.OptionTone
import org.cosasvarias.manoslibres.net.ServerFrame
import org.cosasvarias.manoslibres.ui.theme.Bohio

/**
 * La botonera.
 *
 * La pantalla entera dividida en tantas franjas iguales como opciones. En un móvil de 6,1"
 * una franja de un caso de dos opciones mide unos 7 cm de alto: imposible de fallar con el
 * pulgar sin mirar. Eso es toda la idea.
 *
 * Reglas que el código respeta y conviene no romper al retocarlo:
 *
 *  · **El número va delante.** Es la referencia compartida con la voz («opción dos») y con
 *    el auricular (dos pulsaciones). Sin número no hay forma de contestar sin mirar.
 *  · **El color depende del tono, nunca de la posición.** «Denegar» es del mismo color esté
 *    arriba o abajo. Y el tono también cambia el grosor del borde, porque el color no puede
 *    ser el único canal.
 *  · **`label` grande, `description` pequeña.** La etiqueta es para decidir; la descripción,
 *    para confirmar.
 *  · **Nada más en pantalla.** Ni coste, ni transcripción, ni «ver detalles».
 *  · **«Siempre» nunca es una franja principal.** Va abajo, estrecha, y con la háptica de
 *    `danger`.
 */
@Composable
fun DecisionScreen(
    decision: ServerFrame.DecisionRequest,
    onElegir: (DecisionOption) -> Unit,
) {
    val principales = decision.options.filter { !it.sticky }
    val fijas = decision.options.filter { it.sticky }

    Column(Modifier.fillMaxSize().background(Bohio.bg1)) {
        principales.forEachIndexed { i, opcion ->
            Franja(
                numero = i + 1,
                opcion = opcion,
                modifier = Modifier.weight(1f),
                onClick = { onElegir(opcion) },
            )
        }
        fijas.forEach { opcion ->
            Franja(
                numero = null,
                opcion = opcion,
                // Estrecha a propósito: es una opción que no queremos que se elija por
                // error con el pulgar.
                modifier = Modifier.fillMaxWidth().weight(0.28f),
                onClick = { onElegir(opcion) },
            )
        }
    }
}

@Composable
private fun Franja(
    numero: Int?,
    opcion: DecisionOption,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val (fondo, borde) = colores(opcion.tone)

    Box(
        modifier
            .fillMaxWidth()
            .background(fondo)
            .border(grosor(opcion.tone), borde)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (numero != null) {
                Text(
                    text = "$numero",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Black,
                    color = borde,
                    modifier = Modifier.padding(end = 20.dp),
                )
            }
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = opcion.label.uppercase(),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Bohio.foreground,
                )
                opcion.description?.let {
                    Text(
                        text = it,
                        fontSize = 14.sp,
                        color = Bohio.icon,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun colores(tone: OptionTone): Pair<Color, Color> = when (tone) {
    // (fondo, borde). Siguen distinguiéndose entre sí: turquesa, rojo, dorado y gris.
    OptionTone.go -> Bohio.bg2 to Bohio.accent1
    OptionTone.stop -> Color(0xFF2A1A1A) to Bohio.dangerOnDark
    OptionTone.danger -> Color(0xFF2A2410) to Bohio.accent2
    OptionTone.neutral -> Bohio.bg2 to Bohio.collapsibleHeader
}

/** El grosor duplica la señal del color, que no puede ser el único canal. */
private fun grosor(tone: OptionTone) = when (tone) {
    OptionTone.danger -> 4.dp
    OptionTone.stop -> 3.dp
    else -> 2.dp
}
