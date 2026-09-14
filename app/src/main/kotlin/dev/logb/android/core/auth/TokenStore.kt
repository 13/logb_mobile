package dev.logb.android.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** The personal access token, and nothing else. */
interface TokenStore {
    suspend fun read(): String?
    suspend fun write(token: String)
    suspend fun clear()
}

private val Context.tokenDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

/**
 * The token at rest is AES-GCM ciphertext under a key that lives in the Android Keystore and
 * never leaves the device. A backup or a copied data directory therefore holds nothing usable.
 */
@Singleton
class KeystoreTokenStore @Inject constructor(@ApplicationContext private val context: Context) : TokenStore {
    private val key = stringPreferencesKey("token")

    override suspend fun read(): String? {
        val stored = context.tokenDataStore.data.first()[key] ?: return null
        // A key that vanished (a factory reset that kept app data, a broken keystore) means the
        // token is unrecoverable: treat it as signed out rather than crash on every start.
        return runCatching { KeystoreCipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)) }.getOrNull()
    }

    override suspend fun write(token: String) {
        val sealed = Base64.encodeToString(KeystoreCipher.encrypt(token), Base64.NO_WRAP)
        context.tokenDataStore.edit { it[key] = sealed }
    }

    override suspend fun clear() {
        context.tokenDataStore.edit { it.remove(key) }
    }
}

/** AES-256-GCM under a Keystore key; the 12-byte IV is prefixed to the ciphertext. */
object KeystoreCipher {
    private const val ALIAS = "logb-token"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(sealed: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    }
}
