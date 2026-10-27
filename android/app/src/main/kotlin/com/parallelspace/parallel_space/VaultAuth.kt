package com.parallelspace.parallel_space

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** 打开密码本前的生物识别或设备锁。认证成功后密钥在 60 秒内可用。 */
object VaultAuth {
    fun deviceSecure(ctx: Context): Boolean = try {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        km.isDeviceSecure
    } catch (_: Throwable) {
        false
    }

    fun prompt(activity: FragmentActivity, done: (String) -> Unit) {
        if (!deviceSecure(activity)) {
            done("need_lock")
            return
        }
        if (VaultSession.valid()) {
            done("ok")
            return
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                VaultSession.open()
                done("ok")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                done(if (cancelled) "cancelled" else "error")
            }
        }
        try {
            BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
                .authenticate(promptInfo())
        } catch (_: Throwable) {
            done("error")
        }
    }

    private fun promptInfo(): BiometricPrompt.PromptInfo {
        val b = BiometricPrompt.PromptInfo.Builder()
            .setTitle("验证身份")
            .setSubtitle("查看或填入账号密码前，需要生物识别或锁屏密码")
        return if (Build.VERSION.SDK_INT >= 30) {
            b.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            ).build()
        } else {
            @Suppress("DEPRECATION")
            b.setDeviceCredentialAllowed(true).build()
        }
    }
}
