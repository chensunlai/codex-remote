package dev.codexremote.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dev.codexremote.app.model.GatewayConfig
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): GatewayConfig? = runCatching {
        val encoded = preferences.getString(CONFIG, null) ?: return null
        val envelope = JSONObject(encoded)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)),
        )
        val plaintext = cipher.doFinal(Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP))
        val value = JSONObject(String(plaintext, StandardCharsets.UTF_8))
        GatewayConfig(
            baseUrl = value.getString("baseUrl"),
            token = value.getString("token"),
        )
    }.getOrNull()

    fun save(config: GatewayConfig) {
        val plaintext = JSONObject()
            .put("baseUrl", config.baseUrl.trimEnd('/'))
            .put("token", config.token)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val envelope = JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP))
        preferences.edit { putString(CONFIG, envelope.toString()) }
    }

    fun clear() {
        preferences.edit { remove(CONFIG) }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
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

    private companion object {
        const val PREFERENCES = "gateway_secrets"
        const val CONFIG = "gateway_config_v1"
        const val KEY_ALIAS = "codex_remote_gateway_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
