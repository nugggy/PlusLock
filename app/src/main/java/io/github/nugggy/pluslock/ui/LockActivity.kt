package io.github.nugggy.pluslock.ui

import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.widget.Toast
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import io.github.nugggy.pluslock.core.Apps
import io.github.nugggy.pluslock.service.AppGuard

/** Asks for a fingerprint (or the screen lock) before a locked app shows. */
class LockActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PKG = "pkg"
    }

    private var cancel: CancellationSignal? = null
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        prompt()
    }

    private fun prompt() {
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return finish()
        val label = Apps.label(this, pkg)
        val auth = Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
        if (getSystemService(BiometricManager::class.java).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            // Without a screen lock there is nothing to check against, so don't lock the user out.
            Toast.makeText(this, "Set a screen lock in Settings to use app lock", Toast.LENGTH_LONG).show()
            done = true
            AppGuard.instance?.unlock(pkg)
            finish(); return
        }
        val signal = CancellationSignal().also { cancel = it }
        BiometricPrompt.Builder(this)
            .setTitle("Unlock $label")
            .setSubtitle("Locked with PlusLock")
            .setAllowedAuthenticators(auth)
            .build()
            .authenticate(signal, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    done = true
                    AppGuard.instance?.unlock(pkg)
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = goHome()
            })
    }

    private fun goHome() {
        if (done) return
        done = true
        cancel?.cancel()
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onDestroy() {
        done = true
        cancel?.cancel()
        super.onDestroy()
    }
}
