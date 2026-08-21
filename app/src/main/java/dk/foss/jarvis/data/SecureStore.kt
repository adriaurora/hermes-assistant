package dk.foss.jarvis.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts/decrypts short secrets with an AES-256-GCM key held in
 * [AndroidKeyStore]. The key never leaves the hardware-backed store; only a
 * base64(iv || ciphertext) blob is handed to the caller for storage.
 *
 * If the key is ever unrecoverable (e.g. a backup restored onto another
 * device, where Keystore keys do not follow), [decrypt] returns null and the
 * caller treats the secret as absent — the user simply re-enters it.
 */
interface AeadCipher {
    /** Returns a storable base64(iv || ciphertext) blob for [plainText]. */
    fun encrypt(plainText: String): String

    /** Returns the decrypted plaintext, or null if [blob] cannot be decrypted. */
    fun decrypt(blob: String): String?
}

/** Opaque per-alias blob storage (app-private by construction). */
interface SecretBlobStore {
    fun get(alias: String): String?
    fun put(alias: String, blob: String)
    fun remove(alias: String)
}

/** Real crypto: AES-256-GCM, key generated inside AndroidKeyStore. */
class KeystoreAeadCipher : AeadCipher {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    override fun decrypt(blob: String): String? = runCatching {
        val all = Base64.decode(blob, Base64.NO_WRAP)
        if (all.size <= IV_LEN) return null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, all, 0, IV_LEN))
        String(cipher.doFinal(all, IV_LEN, all.size - IV_LEN), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val KEY_ALIAS = "hermes_bearer_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_BITS = 128
    }
}

/** Stores blobs in an app-private SharedPreferences file. */
class PrefsBlobStore(context: Context) : SecretBlobStore {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun get(alias: String): String? = prefs.getString(alias, null)
    override fun put(alias: String, blob: String) {
        prefs.edit().putString(alias, blob).apply()
    }

    override fun remove(alias: String) {
        prefs.edit().remove(alias).apply()
    }

    private companion object {
        const val FILE = "hermes_secure"
    }
}

/**
 * Holds the Hermes bearer token encrypted-at-rest. Plaintext exists only in
 * memory long enough to build an HTTP header — never in DataStore, logs, or
 * BuildConfig. The process-wide instance is obtained via [get].
 */
class SecureStore internal constructor(
    private val cipher: AeadCipher,
    private val blobs: SecretBlobStore,
) {

    /** Current token, or null if none is stored / the blob is undecryptable. */
    fun loadToken(): String? =
        blobs.get(TOKEN_ALIAS)?.let { cipher.decrypt(it) }?.takeIf { it.isNotEmpty() }

    fun saveToken(plainText: String) {
        blobs.put(TOKEN_ALIAS, cipher.encrypt(plainText))
    }

    fun clearToken() {
        blobs.remove(TOKEN_ALIAS)
    }

    /**
     * One-shot migration from the legacy plaintext-in-DataStore era: if no
     * token is stored yet and [legacyPlaintext] is non-blank, encrypt and
     * store it. Idempotent and race-safe within the process; the caller is
     * responsible for purging [legacyPlaintext] from its own storage.
     */
    fun importOnce(legacyPlaintext: String?): String? = synchronized(this) {
        loadToken()?.let { return it }
        if (!legacyPlaintext.isNullOrBlank()) saveToken(legacyPlaintext.trim())
        loadToken()
    }

    companion object {
        internal const val TOKEN_ALIAS = "hermes_api_token"

        @Volatile
        private var instance: SecureStore? = null

        fun get(context: Context): SecureStore =
            instance ?: synchronized(this) {
                instance ?: SecureStore(KeystoreAeadCipher(), PrefsBlobStore(context))
                    .also { instance = it }
            }
    }
}
