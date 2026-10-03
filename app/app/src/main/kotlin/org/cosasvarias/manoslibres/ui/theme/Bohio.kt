package org.cosasvarias.manoslibres.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Los tokens de diseño de rama-io (https://github.com/rama-io/bohio), traducidos a Compose.
 *
 * Bohio es una librería de Views XML y esta app es Compose, así que no se enlaza: se copian
 * los valores de `colors.xml` y `dimens.xml`. Si bohio cambia, se actualiza aquí.
 * Es oscuro, plano y mínimo: fondos verdeazulados en cuatro escalones, acentos turquesa y
 * dorado, tipografía del sistema, sin sombras ni degradados.
 */
object Bohio {
    val h1 = Color(0xFFEAE6DE)
    val foreground = Color(0xFFEAE6DF)
    val icon = Color(0xFFC8C2B8)

    val bg1 = Color(0xFF0E181A)
    val bg2 = Color(0xFF162A2E)
    val bg3 = Color(0xFF1E383D)
    val bg4 = Color(0xFF28484D)
    val bgDisplay = Color(0xFF081012)

    val accent1 = Color(0xFF4DA8AC)
    val accent2 = Color(0xFFD4AF37)
    val accent3 = Color(0xFFC77D4D)
    val accent4 = Color(0xFF6A9FCF)

    val disabled = Color(0xFF5D7A7C)
    val button1 = Color(0xFF3A9DA0)
    val button1Selected = Color(0xFF6BC1C4)
    val button2 = Color(0xFFC49B2E)
    val danger = Color(0xFFB83A2D)
    val collapsibleHeader = Color(0xFF7A9495)

    /** Aclarado de [danger] para que el rojo se lea sobre fondo oscuro (no está en bohio). */
    val dangerOnDark = Color(0xFFE5736A)

    val spaceBetweenElements = 8.dp
    val spaceBetweenElementsSmall = 2.dp
    val gutter = 16.dp
}

private val esquema = darkColorScheme(
    primary = Bohio.button1,
    onPrimary = Bohio.bg1,
    secondary = Bohio.button2,
    onSecondary = Bohio.bg1,
    background = Bohio.bg1,
    onBackground = Bohio.foreground,
    surface = Bohio.bg2,
    onSurface = Bohio.foreground,
    surfaceVariant = Bohio.bg3,
    onSurfaceVariant = Bohio.icon,
    outline = Bohio.collapsibleHeader,
    error = Bohio.danger,
)

// Escala de bohio: h1 72, h2 32, h3 18, text 16, small 12 (sp).
private val tipografia = Typography(
    displayLarge = TextStyle(fontSize = 72.sp, color = Bohio.h1),
    headlineMedium = TextStyle(fontSize = 32.sp, color = Bohio.foreground),
    titleMedium = TextStyle(fontSize = 18.sp, color = Bohio.foreground),
    bodyLarge = TextStyle(fontSize = 16.sp, color = Bohio.foreground),
    bodySmall = TextStyle(fontSize = 12.sp, color = Bohio.foreground),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
)

@Composable
fun BohioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = esquema, typography = tipografia, content = content)
}
