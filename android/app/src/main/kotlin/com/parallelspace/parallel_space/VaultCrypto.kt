package com.parallelspace.parallel_space

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 账号密码本的密钥只放在 Android Keystore 里，数据用 AES-GCM 加密。
 * 密钥要求生物识别或设备锁，认证后 60 秒内可加解密。
 */
object VaultCrypto {
    private const val ALIAS = "parallel_space_vault_v1"
    private const val STORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private val lock = Any()

    fun encrypt(plain: ByteArray): ByteArray = synchronized(lock) {
        ensureKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val iv = cipher.iv ?: throw IllegalStateException("gcm iv")
        iv + cipher.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray = synchronized(lock) {
        ensureKey()
        require(blob.size > IV_LEN) { "bad blob" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secret(),
            GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_LEN)),
        )
        cipher.doFinal(blob.copyOfRange(IV_LEN, blob.size))
    }

    private fun ensureKey() {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        if (ks.containsAlias(ALIAS)) return
        var last: Throwable? = null
        for (spec in keySpecs()) {
            try {
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
                gen.init(spec)
                gen.generateKey()
                return
            } catch (t: Throwable) {
                last = t
                try {
                    val again = KeyStore.getInstance(STORE).apply { load(null) }
                    if (again.containsAlias(ALIAS)) again.deleteEntry(ALIAS)
                } catch (_: Throwable) {
                }
            }
        }
        throw last ?: IllegalStateException("keystore")
    }

    private fun keySpecs(): List<KeyGenParameterSpec> {
        val out = ArrayList<KeyGenParameterSpec>(2)
        if (Build.VERSION.SDK_INT >= 30) out += spec(useAuthParams = true)
        out += spec(useAuthParams = false)
        return out
    }

    private fun spec(useAuthParams: Boolean): KeyGenParameterSpec {
        val b = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 24) b.setInvalidatedByBiometricEnrollment(false)
        if (useAuthParams && Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(
                60,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            b.setUserAuthenticationValidityDurationSeconds(60)
        }
        return b.build()
    }

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        return ks.getKey(ALIAS, null) as SecretKey
    }
}
