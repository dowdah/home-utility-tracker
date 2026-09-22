package com.dowdah.utilitytracker.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/** Stores the API token encrypted with a non-exportable Android Keystore key. */
@Singleton
class SecretStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("utility_tracker_secrets", Context.MODE_PRIVATE)

    fun token(): String? = preferences.getString(TOKEN, null)?.let(::decrypt)

    fun saveToken(token: String) {
        preferences.edit().putString(TOKEN, encrypt(token)).apply()
    }

    fun clearToken() {
        preferences.edit().remove(TOKEN).apply()
    }

    fun installationId(): String = preferences.getString(INSTALLATION_ID, null)
        ?: UUID.randomUUID().toString().also { preferences.edit().putString(INSTALLATION_ID, it).apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + bytes, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String? = runCatching {
        val all = Base64.decode(value, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, iv)) }
            .doFinal(all.copyOfRange(12, all.size)).toString(Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val TOKEN = "token"
        const val KEY_ALIAS = "utility_tracker_token_key"
        const val INSTALLATION_ID = "installation_id"
    }
}
