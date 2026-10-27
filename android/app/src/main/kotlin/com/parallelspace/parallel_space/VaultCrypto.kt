package com.parallelspace.parallel_space

import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 仅用于把 1.3.4/1.3.5 的 `vault.enc` 迁到明文 SharedPreferences。
 * 新数据不再走 Keystore；解不开（未认证）时返回 null。
 */
object VaultCrypto {
    private const val ALIAS = "parallel_space_vault_v1"
    private const val STORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun tryDecrypt(blob: ByteArray): ByteArray? {
        if (blob.size <= IV_LEN) return null
        return try {
            val ks = KeyStore.getInstance(STORE).apply { load(null) }
            if (!ks.containsAlias(ALIAS)) return null
            val key = ks.getKey(ALIAS, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_LEN)),
            )
            cipher.doFinal(blob.copyOfRange(IV_LEN, blob.size))
        } catch (_: Throwable) {
            null
        }
    }

    @Deprecated("plaintext vault; kept for binary compat")
    fun encrypt(plain: ByteArray): ByteArray = plain

    @Deprecated("plaintext vault; kept for binary compat")
    fun decrypt(blob: ByteArray): ByteArray = tryDecrypt(blob) ?: blob
}
