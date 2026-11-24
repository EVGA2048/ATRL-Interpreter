package net.astrolab.interpreter

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ApiConfig(val key: String = "", val endpoint: String = "https://api.deepseek.com", val model: String = "deepseek-flash")

class ApiSettings(context: Context) {
    private val prefs = context.getSharedPreferences("api_settings", Context.MODE_PRIVATE)
    private val alias = "interpreter_deepseek_key"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): ApiConfig {
        val encrypted = prefs.getString("secret", null)
        val secret = if (encrypted == null) "" else {
            val parts = encrypted.split(":")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }
        val endpoint = prefs.getString("endpoint", "https://api.deepseek.com")!!
        val model = prefs.getString("model", "deepseek-flash")!!
        return ApiConfig(secret, endpoint, if (endpoint.trimEnd('/') == "https://api.deepseek.com" && model == "deepseek-chat") "deepseek-flash" else model)
    }
    fun save(config: ApiConfig) {
        val editor = prefs.edit().putString("endpoint", config.endpoint).putString("model", config.model)
        if (config.key.isBlank()) editor.remove("secret") else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val bytes = cipher.doFinal(config.key.toByteArray(Charsets.UTF_8))
            editor.putString("secret", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        check(editor.commit()) { "Could not save settings" }
    }
}
