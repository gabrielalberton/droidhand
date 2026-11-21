package work.alberton.droidhand.data

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
 * The device PIN, encrypted with an AES-GCM key that lives in the Android
 * Keystore (hardware-backed, never leaves the phone). Only the ciphertext is
 * stored in app preferences; the PIN is never sent to the relay or the agent.
 */
class SecretStore(context: Context) {
    private val sp = context.getSharedPreferences("droidhand_secrets", Context.MODE_PRIVATE)

    val hasPin: Boolean get() = sp.contains(KEY_CT)

    var pin: String?
        get() = runCatching {
            val ct = Base64.decode(sp.getString(KEY_CT, null) ?: return null, Base64.NO_WRAP)
            val iv = Base64.decode(sp.getString(KEY_IV, null) ?: return null, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            String(c.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
        set(v) {
            if (v.isNullOrEmpty()) { sp.edit().remove(KEY_CT).remove(KEY_IV).apply(); return }
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val ct = c.doFinal(v.toByteArray(Charsets.UTF_8))
            sp.edit()
                .putString(KEY_CT, Base64.encodeToString(ct, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(c.iv, Base64.NO_WRAP))
                .apply()
        }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
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
        private const val ALIAS = "droidhand_pin_key"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val KEY_CT = "pin_ct"
        private const val KEY_IV = "pin_iv"
    }
}
