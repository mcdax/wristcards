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

/** What the account page shows. */
sealed interface Account {
    data class SignedIn(val user: String, val endpoint: String) : Account
    // Never signed in: the credentials entered at build time still apply.
    data object BuiltIn : Account
    data object SignedOut : Account
}

/**
 * After sign-in keeps only the host key (SyncAuth.hkey) and the endpoint, never the password.
 * The host key is stored encrypted (AES-GCM) with a key from the Android Keystore in
 * its own SharedPreferences; username and endpoint are not secret and are stored in plain text.
 */
class SyncCredentials(context: Context) {
    private val prefs = context.getSharedPreferences("sync_auth", Context.MODE_PRIVATE)

    val account: Account
        get() = when {
            prefs.contains("hkey") -> Account.SignedIn(prefs.getString("user", "") ?: "", prefs.getString("endpoint", "") ?: "")
            prefs.getBoolean("signedOut", false) -> Account.SignedOut
            // The built-in test account exists only in the debug build (see build.gradle.kts).
            BuildConfig.SYNC_USER.isEmpty() -> Account.SignedOut
            else -> Account.BuiltIn
        }

    /** Stored credentials; null if there are none or they can no longer be decrypted. */
    fun load(): SyncAuth? {
        val stored = prefs.getString("hkey", null) ?: return null
        val hkey = try {
            decrypt(stored)
        } catch (_: Exception) {
            // Keystore key gone (e.g. after resetting the screen lock): sign in again.
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
            // The server may return a different endpoint at login; that one then applies.
            .putString("endpoint", if (auth.hasEndpoint() && auth.endpoint.isNotEmpty()) auth.endpoint else endpoint)
            .remove("signedOut")
            .apply()
    }

    /** AnkiWeb assigns a fixed sync host during sync (e.g. sync21); it applies to this account from then on. */
    fun updateEndpoint(endpoint: String) {
        if (prefs.contains("hkey")) prefs.edit().putString("endpoint", endpoint).apply()
    }

    /** Sign out: from now on don't fall back to the built-in credentials either. */
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

/** "anki.example.org" → "https://anki.example.org/"; the core appends paths, which needs the slash. */
fun normalizeEndpoint(input: String): String {
    val s = input.trim()
    if (s.isEmpty()) return ""
    val withScheme = if ("://" in s) s else "https://$s"
    return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
}
