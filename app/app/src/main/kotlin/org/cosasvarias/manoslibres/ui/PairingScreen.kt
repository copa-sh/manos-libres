package org.cosasvarias.manoslibres.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cosasvarias.manoslibres.pairing.Emparejamiento
import org.cosasvarias.manoslibres.pairing.parseEmparejamiento
import org.cosasvarias.manoslibres.ui.theme.Bohio

/**
 * Primer arranque: URL del nodo + token. [enlace] viene de un deep link `manoslibres://pair`
 * y rellena los campos (el usuario confirma con «Emparejar»).
 *
 * [onEmparejar] recibe la URL y el token ya recortados y devuelve false si no son válidos.
 */
@Composable
fun PairingScreen(
    enlace: Emparejamiento?,
    onEmparejar: (url: String, token: String) -> Boolean,
) {
    var url by remember(enlace) { mutableStateOf(enlace?.url.orEmpty()) }
    var token by remember(enlace) { mutableStateOf(enlace?.token.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val portapapeles = LocalClipboardManager.current

    Column(
        Modifier
            .fillMaxSize()
            .background(Bohio.bg1)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(Bohio.gutter),
        verticalArrangement = Arrangement.spacedBy(Bohio.spaceBetweenElements * 2),
    ) {
        Text(Textos.EMPAREJAR_TITULO, fontSize = 32.sp, color = Bohio.h1)
        Text(Textos.EMPAREJAR_AYUDA, fontSize = 16.sp, color = Bohio.icon)

        OutlinedTextField(
            value = url,
            onValueChange = { url = it; error = null },
            label = { Text(Textos.URL) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; error = null },
            label = { Text(Textos.TOKEN) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let { Text(it, fontSize = 16.sp, color = Bohio.dangerOnDark) }

        Row(horizontalArrangement = Arrangement.spacedBy(Bohio.spaceBetweenElements)) {
            OutlinedButton(
                onClick = {
                    val pegado = portapapeles.getText()?.text?.trim().orEmpty()
                    if (pegado.isEmpty()) {
                        error = Textos.PORTAPAPELES_VACIO
                    } else {
                        val e = parseEmparejamiento(pegado)
                        if (e != null) {
                            url = e.url
                            token = e.token
                        } else {
                            url = pegado
                        }
                        error = null
                    }
                },
            ) { Text(Textos.PEGAR) }

            Button(
                onClick = {
                    if (!onEmparejar(url.trim(), token.trim())) error = Textos.EMPAREJAR_INVALIDO
                },
                colors = ButtonDefaults.buttonColors(containerColor = Bohio.button1, contentColor = Bohio.bg1),
                modifier = Modifier.weight(1f),
            ) { Text(Textos.EMPAREJAR, fontSize = 18.sp) }
        }
    }
}
