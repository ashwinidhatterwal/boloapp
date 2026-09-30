package com.offlinenarrator.benchmark.book

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class DirectorSettings(val enabled: Boolean = false, val endpoint: String = "", val model: String = "",
    val apiKey: String = "", val pronunciation: String = "") {
    val signature: String get() {
        val credentialRevision = java.security.MessageDigest.getInstance("SHA-256")
            .digest(apiKey.toByteArray()).joinToString("") { "%02x".format(it) }
        return "director-v2|$enabled|$endpoint|$model|$pronunciation|$credentialRevision"
    }
}

/** Provider use is explicit. API keys are encrypted with Android Keystore. */
class DirectorSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("narration_director", Context.MODE_PRIVATE)
    fun load(): DirectorSettings = DirectorSettings(prefs.getBoolean("enabled", false), prefs.getString("endpoint", "").orEmpty(),
        prefs.getString("model", "").orEmpty(), runCatching { decrypt(prefs.getString("key", "").orEmpty()) }.getOrDefault(""),
        prefs.getString("pronunciation", "").orEmpty())
    fun save(settings: DirectorSettings) {
        if (settings.enabled) {
            require(settings.endpoint.startsWith("https://")) { "Director endpoint must use HTTPS" }
            require(settings.model.isNotBlank()) { "Enter a model name" }
        }
        prefs.edit().putBoolean("enabled", settings.enabled).putString("endpoint", settings.endpoint.trim().trimEnd('/'))
            .putString("model", settings.model.trim()).putString("key", encrypt(settings.apiKey.trim()))
            .putString("pronunciation", settings.pronunciation).apply()
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(text: String): String {
        if (text.isBlank()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }
    private fun decrypt(encoded: String): String {
        if (encoded.isBlank()) return ""
        val bytes = Base64.decode(encoded, Base64.NO_WRAP); require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
    }
    companion object { private const val ALIAS = "bolo-narration-director-v1" }
}
