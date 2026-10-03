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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cosasvarias.manoslibres.net.SessionState
import org.cosasvarias.manoslibres.session.SessionStore
import org.cosasvarias.manoslibres.session.SessionStore.Conexion
import org.cosasvarias.manoslibres.ui.theme.Bohio

/**
 * La pantalla que casi nunca se mira (ver docs/04): transcripción completa, estado grande,
 * botón de narración siempre visible y, en otra pestaña, el chat entre dispositivos.
 * Lee de [SessionStore] y le pide cosas a través de [SessionStore.acciones].
 */
@Composable
fun TranscriptScreen(
    dispositivo: String?,
    bateriaPendiente: Boolean,
    onBateria: () -> Unit,
    onDesemparejar: () -> Unit,
) {
    val conexion by SessionStore.conexion.collectAsState()
    val estado by SessionStore.estado.collectAsState()
    var pestana by remember { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bohio.bg1)
            .systemBarsPadding()
            .imePadding(),
    ) {
        Cabecera(conexion, onDesemparejar)
        IndicadorEstado(estado, Modifier.padding(horizontal = Bohio.gutter))

        Button(
            onClick = { SessionStore.acciones?.entrarEnNarracion() },
            colors = ButtonDefaults.buttonColors(containerColor = Bohio.button1, contentColor = Bohio.bg1),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Bohio.gutter, vertical = Bohio.spaceBetweenElements)
                .heightIn(min = 64.dp),
        ) { Text(Textos.NARRACION, fontSize = 22.sp, fontWeight = FontWeight.Bold) }

        if (bateriaPendiente) FilaBateria(onBateria)

        Row(Modifier.fillMaxWidth().padding(horizontal = Bohio.gutter)) {
            Pestana(Textos.TAB_SESION, pestana == 0, Modifier.weight(1f)) { pestana = 0 }
            Pestana(Textos.TAB_CHAT, pestana == 1, Modifier.weight(1f)) { pestana = 1 }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (pestana == 0) SesionPanel(estado) else ChatScreen(dispositivo)
        }
    }
}

@Composable
private fun Cabecera(conexion: Conexion, onDesemparejar: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = Bohio.gutter, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = textoConexion(conexion),
            fontSize = 12.sp,
            color = colorConexion(conexion),
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮", fontSize = 22.sp, color = Bohio.icon) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(Textos.DESEMPAREJAR) },
                    onClick = { menu = false; onDesemparejar() },
                )
            }
        }
    }
}

@Composable
private fun IndicadorEstado(estado: SessionState, modifier: Modifier = Modifier) {
    val color = colorEstado(estado)
    Box(
        modifier
            .fillMaxWidth()
            .background(Bohio.bg2, RoundedCornerShape(4.dp))
            .border(3.dp, color, RoundedCornerShape(4.dp))
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(textoEstado(estado), fontSize = 28.sp, fontWeight = FontWeight.Black, color = color)
    }
}

@Composable
private fun FilaBateria(onBateria: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Bohio.gutter)
            .background(Bohio.bg3, RoundedCornerShape(4.dp))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Textos.BATERIA_AVISO, fontSize = 12.sp, color = Bohio.foreground, modifier = Modifier.weight(1f))
        TextButton(onClick = onBateria) { Text(Textos.BATERIA_ACCION, color = Bohio.accent2) }
    }
}

@Composable
private fun Pestana(texto: String, activa: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                texto,
                fontSize = 16.sp,
                fontWeight = if (activa) FontWeight.Bold else FontWeight.Normal,
                color = if (activa) Bohio.accent1 else Bohio.collapsibleHeader,
            )
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .heightIn(min = 2.dp, max = 2.dp)
                    .background(if (activa) Bohio.accent1 else Bohio.bg3),
            )
        }
    }
}

// ── Sesión ──────────────────────────────────────────────────────────────────────

@Composable
private fun SesionPanel(estado: SessionState) {
    val lineas by SessionStore.lineas.collectAsState()
    val parcial by SessionStore.parcial.collectAsState()
    val lista = rememberLazyListState()
    var entrada by remember { mutableStateOf("") }

    val total = lineas.size + (if (parcial.isNotEmpty()) 1 else 0)
    LaunchedEffect(total, parcial.length) {
        if (total > 0) lista.scrollToItem(total - 1)
    }

    Column(Modifier.fillMaxSize()) {
        if (total == 0) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(Textos.SESION_VACIA, fontSize = 16.sp, color = Bohio.collapsibleHeader)
            }
        } else {
            LazyColumn(
                state = lista,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Bohio.gutter),
                verticalArrangement = Arrangement.spacedBy(Bohio.gutter),
            ) {
                items(lineas.size) { i ->
                    val l = lineas[i]
                    Mensaje(rol = l.rol, texto = l.texto, parcial = false)
                }
                if (parcial.isNotEmpty()) {
                    items(1) { Mensaje(rol = "assistant", texto = parcial, parcial = true) }
                }
            }
        }

        val activo = estado == SessionState.thinking || estado == SessionState.working ||
            estado == SessionState.waiting
        if (activo) {
            Button(
                onClick = { SessionStore.acciones?.interrumpir() },
                colors = ButtonDefaults.buttonColors(containerColor = Bohio.danger, contentColor = Bohio.foreground),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Bohio.gutter),
            ) { Text(Textos.INTERRUMPIR) }
        }

        CampoEnvio(
            valor = entrada,
            onCambio = { entrada = it },
            pista = Textos.PROMPT_HINT,
            onEnviar = {
                val t = textoEnviable(entrada)
                val a = SessionStore.acciones
                if (t != null && a != null) {
                    a.enviarPrompt(t)
                    entrada = ""
                }
            },
        )
    }
}

@Composable
private fun Mensaje(rol: String, texto: String, parcial: Boolean) {
    val usuario = esRolUsuario(rol)
    Column(verticalArrangement = Arrangement.spacedBy(Bohio.spaceBetweenElements)) {
        Text(
            text = (if (usuario) Textos.TU else Textos.AGENTE) + if (parcial) " …" else "",
            fontSize = 12.sp,
            color = if (usuario) Bohio.accent2 else Bohio.accent1,
        )
        for (trozo in segmentarCodigo(texto)) {
            when (trozo) {
                is Trozo.Texto -> Text(trozo.texto, fontSize = 16.sp, color = Bohio.foreground)
                is Trozo.Codigo -> Text(
                    text = trozo.texto,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Bohio.foreground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Bohio.bgDisplay, RoundedCornerShape(4.dp))
                        .padding(10.dp),
                )
            }
        }
    }
}

// ── Chat ────────────────────────────────────────────────────────────────────────

@Composable
fun ChatScreen(dispositivo: String?) {
    val chat by SessionStore.chat.collectAsState()
    val lista = rememberLazyListState()
    var entrada by remember { mutableStateOf("") }

    LaunchedEffect(chat.size) {
        if (chat.isNotEmpty()) lista.scrollToItem(chat.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        if (chat.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(Textos.CHAT_VACIO, fontSize = 16.sp, color = Bohio.collapsibleHeader)
            }
        } else {
            LazyColumn(
                state = lista,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Bohio.gutter),
                verticalArrangement = Arrangement.spacedBy(Bohio.spaceBetweenElements),
            ) {
                items(chat.size) { i ->
                    val m = chat[i]
                    val mio = esPropio(m.from, dispositivo)
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = if (mio) Alignment.End else Alignment.Start,
                    ) {
                        if (!mio) Text(m.from, fontSize = 12.sp, color = Bohio.accent4)
                        Text(
                            text = m.text,
                            fontSize = 16.sp,
                            color = Bohio.foreground,
                            modifier = Modifier
                                .background(if (mio) Bohio.bg4 else Bohio.bg2, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }

        CampoEnvio(
            valor = entrada,
            onCambio = { entrada = it },
            pista = Textos.CHAT_HINT,
            onEnviar = {
                val t = textoEnviable(entrada)
                val a = SessionStore.acciones
                if (t != null && a != null) {
                    a.enviarChat(t)
                    entrada = ""
                }
            },
        )
    }
}

// ── Piezas comunes ──────────────────────────────────────────────────────────────

@Composable
private fun CampoEnvio(valor: String, onCambio: (String) -> Unit, pista: String, onEnviar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(Bohio.gutter),
        horizontalArrangement = Arrangement.spacedBy(Bohio.spaceBetweenElements),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = valor,
            onValueChange = onCambio,
            placeholder = { Text(pista) },
            maxLines = 5,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onEnviar,
            enabled = textoEnviable(valor) != null,
            colors = ButtonDefaults.buttonColors(containerColor = Bohio.button1, contentColor = Bohio.bg1),
            shape = RoundedCornerShape(4.dp),
        ) { Text(Textos.ENVIAR) }
    }
}

private fun textoEstado(e: SessionState): String = when (e) {
    SessionState.idle -> Textos.EST_IDLE
    SessionState.thinking -> Textos.EST_THINKING
    SessionState.working -> Textos.EST_WORKING
    SessionState.waiting -> Textos.EST_WAITING
    SessionState.error -> Textos.EST_ERROR
}

private fun colorEstado(e: SessionState): Color = when (e) {
    SessionState.idle -> Bohio.collapsibleHeader
    SessionState.thinking -> Bohio.accent4
    SessionState.working -> Bohio.accent1
    SessionState.waiting -> Bohio.accent2
    SessionState.error -> Bohio.dangerOnDark
}

private fun textoConexion(c: Conexion): String = when (c) {
    Conexion.SIN_EMPAREJAR -> Textos.CON_SIN_EMPAREJAR
    Conexion.CONECTANDO -> Textos.CON_CONECTANDO
    Conexion.CONECTADO -> Textos.CON_CONECTADO
    Conexion.SIN_RED -> Textos.CON_SIN_RED
}

private fun colorConexion(c: Conexion): Color = when (c) {
    Conexion.CONECTADO -> Bohio.accent1
    Conexion.CONECTANDO -> Bohio.accent2
    Conexion.SIN_RED -> Bohio.dangerOnDark
    Conexion.SIN_EMPAREJAR -> Bohio.collapsibleHeader
}
