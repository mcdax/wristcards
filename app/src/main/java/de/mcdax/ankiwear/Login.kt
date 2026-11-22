package de.mcdax.ankiwear

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import anki.sync.SyncAuth
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Was die Kontoseite anzeigt. */
sealed interface Account {
    data class SignedIn(val user: String, val endpoint: String) : Account
    // Noch nie angemeldet: die beim Bauen eingetragenen Zugangsdaten gelten weiter.
    data object BuiltIn : Account
    data object SignedOut : Account
}

/**
 * Hält nach der Anmeldung nur den Host-Key (SyncAuth.hkey) und den Endpoint, nie das Passwort.
 * Der Host-Key liegt mit einem Schlüssel aus dem Android Keystore verschlüsselt (AES-GCM) in
 * eigenen SharedPreferences; Benutzername und Endpoint sind nicht geheim und stehen im Klartext.
 */
class SyncCredentials(context: Context) {
    private val prefs = context.getSharedPreferences("sync_auth", Context.MODE_PRIVATE)

    val account: Account
        get() = when {
            prefs.contains("hkey") -> Account.SignedIn(prefs.getString("user", "") ?: "", prefs.getString("endpoint", "") ?: "")
            prefs.getBoolean("signedOut", false) -> Account.SignedOut
            else -> Account.BuiltIn
        }

    /** Gespeicherter Zugang; null, wenn keiner da ist oder sich nicht mehr entschlüsseln lässt. */
    fun load(): SyncAuth? {
        val stored = prefs.getString("hkey", null) ?: return null
        val hkey = try {
            decrypt(stored)
        } catch (_: Exception) {
            // Keystore-Schlüssel weg (z. B. nach Zurücksetzen der Displaysperre): neu anmelden.
            clear()
            return null
        }
        return SyncAuth.newBuilder().setHkey(hkey).apply {
            prefs.getString("endpoint", null)?.takeIf { it.isNotEmpty() }?.let { setEndpoint(it) }
        }.build()
    }

    fun save(auth: SyncAuth, user: String, endpoint: String) {
        prefs.edit()
            .putString("hkey", encrypt(auth.hkey))
            .putString("user", user)
            // Der Server kann beim Login einen anderen Endpoint zurückgeben; der gilt dann.
            .putString("endpoint", if (auth.hasEndpoint() && auth.endpoint.isNotEmpty()) auth.endpoint else endpoint)
            .remove("signedOut")
            .apply()
    }

    /** Abmelden: ab jetzt auch nicht mehr auf die eingebauten Zugangsdaten zurückfallen. */
    fun clear() {
        prefs.edit().clear().putBoolean("signedOut", true).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(plain.toByteArray())
        return b64(cipher.iv) + ":" + b64(data)
    }

    private fun decrypt(stored: String): String {
        val (iv, data) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return String(cipher.doFinal(data))
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val KEY_ALIAS = "sync_hkey"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** „anki.example.org“ → „https://anki.example.org/“; der Kern hängt Pfade an, dafür braucht es den Schrägstrich. */
fun normalizeEndpoint(input: String): String {
    val s = input.trim()
    if (s.isEmpty()) return ""
    val withScheme = if ("://" in s) s else "https://$s"
    return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
}
