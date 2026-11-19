package com.stone.sleepguard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.emailStore by preferencesDataStore("email_settings")

data class EmailSettings(
    val enabled: Boolean = false,
    val host: String = "smtp.qq.com",
    val port: Int = 465,
    val from: String = "",
    val partner: String = "",
    val authCode: String = "",
    val hasAuthCode: Boolean = false,
) {
    val configured get() = from.isNotBlank() && partner.isNotBlank() && host.isNotBlank() && authCode.isNotBlank()
}

class EmailPreferences(context: Context) {
    private val store = context.emailStore
    private val enabled = booleanPreferencesKey("enabled")
    private val host = stringPreferencesKey("host")
    private val port = intPreferencesKey("port")
    private val from = stringPreferencesKey("from")
    private val partner = stringPreferencesKey("partner")
    private val auth = stringPreferencesKey("authCipher")

    val flow = store.data.map { p ->
        val cipher = p[auth]
        EmailSettings(
            enabled = p[enabled] ?: false,
            host = p[host] ?: "smtp.qq.com",
            port = p[port] ?: 465,
            from = p[from].orEmpty(),
            partner = p[partner].orEmpty(),
            authCode = cipher?.let { EmailSecret.decrypt(it) }.orEmpty(),
            hasAuthCode = cipher != null,
        )
    }

    suspend fun save(value: EmailSettings) {
        store.edit { p ->
            p[enabled] = value.enabled
            p[host] = value.host.trim()
            p[port] = value.port
            p[from] = value.from.trim()
            p[partner] = value.partner.trim()
            if (value.authCode.isNotBlank()) {
                val encrypted = EmailSecret.encrypt(value.authCode) ?: error("无法保存授权码，请重试")
                p[auth] = encrypted
            }
        }
    }

    suspend fun setEnabled(value: Boolean) {
        store.edit { it[enabled] = value }
    }
}

/** 授权码用 Android Keystore 的 AES/GCM 加密，只存在本机；App 覆盖更新不会丢失。 */
private object EmailSecret {
    private const val ALIAS = "sleepguard_email_auth"

    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        Base64.encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }.getOrNull()

    fun decrypt(value: String): String? = runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        if (bytes.size <= 12) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
}
