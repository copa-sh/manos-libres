package org.cosasvarias.manoslibres.pairing

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda el emparejamiento (URL del nodo, token, nombre del dispositivo) cifrado con una clave
 * AES-GCM de 256 bits que vive en el Android Keystore y no sale de él. En disco solo queda
 * `IV || texto cifrado` en Base64, dentro de unas SharedPreferences privadas.
 *
 * Si la clave desaparece (restauración de copia, borrado de credenciales) o el dato no
 * descifra, [cargar] devuelve null y limpia: hay que volver a emparejar.
 */
class PairingStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("pairing", Context.MODE_PRIVATE)

    fun cargar(): Emparejamiento? {
        val guardado = prefs.getString(CLAVE_PREFS, null) ?: return null
        return try {
            val bruto = Base64.decode(guardado, Base64.NO_WRAP)
            val iv = bruto.copyOfRange(0, IV_BYTES)
            val cifrado = bruto.copyOfRange(IV_BYTES, bruto.size)
            val c = Cipher.getInstance(TRANSFORMACION)
            c.init(Cipher.DECRYPT_MODE, clave(), GCMParameterSpec(TAG_BITS, iv))
            val json = String(c.doFinal(cifrado), Charsets.UTF_8)
            Json.decodeFromString(Emparejamiento.serializer(), json)
        } catch (_: Exception) {
            borrar()
            null
        }
    }

    fun guardar(e: Emparejamiento) {
        val c = Cipher.getInstance(TRANSFORMACION)
        c.init(Cipher.ENCRYPT_MODE, clave())   // el IV lo genera el Keystore
        val cifrado = c.doFinal(Json.encodeToString(Emparejamiento.serializer(), e).toByteArray(Charsets.UTF_8))
        val bruto = c.iv + cifrado
        prefs.edit().putString(CLAVE_PREFS, Base64.encodeToString(bruto, Base64.NO_WRAP)).apply()
    }

    fun borrar() {
        prefs.edit().remove(CLAVE_PREFS).apply()
    }

    /** Como [parseEmparejamiento], con el modelo del teléfono como nombre por defecto. */
    fun interpretar(texto: String): Emparejamiento? = parseEmparejamiento(texto, nombrePorDefecto())

    private fun clave(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "manoslibres.pairing"
        private const val CLAVE_PREFS = "emparejamiento"
        private const val TRANSFORMACION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** El modelo del teléfono (`Build.MODEL`), o «movil» si el sistema no lo da. */
        fun nombrePorDefecto(): String = Build.MODEL?.trim().takeUnless { it.isNullOrEmpty() } ?: "movil"
    }
}
