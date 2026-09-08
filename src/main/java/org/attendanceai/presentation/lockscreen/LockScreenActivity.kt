/*
 * Attendance AI — offline-first, on-device attendance app.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later — see https://www.gnu.org/licenses/ for full text.
 */
package org.attendanceai.presentation.lockscreen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import org.attendanceai.ui.AttendanceActivity

/**
 * Security role: thin Android host for the lock screen. It owns the three
 * framework touch-points the ViewModel must stay free of — the
 * [androidx.biometric.BiometricPrompt] (strong biometrics only), the
 * clipboard (copied phrases auto-clear after 60 s), and the biometric
 * availability probe that hides the opt-in step on devices without
 * hardware or enrolled biometrics. It relays a single RESULT_OK to the
 * caller on successful unlock; the activity itself never sees or stores
 * secret material.
 */
class LockScreenActivity : FragmentActivity() {

    private lateinit var viewModel: LockScreenViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = try {
            ViewModelProvider(this, buildViewModelFactory())[LockScreenViewModel::class.java]
        } catch (t: Throwable) {
            // Fail closed: no vault, no app. The screen closes itself.
            finish()
            return
        }
        setContent {
            LockScreenScreen(
                viewModel = viewModel,
                biometric = BiometricAuthenticator(this),
                onUnlocked = {
                    startActivity(Intent(this, AttendanceActivity::class.java))
                    finish()
                },
            )
        }
    }

    private fun buildViewModelFactory(): ViewModelProvider.Factory {
        val biometricsAvailable = biometricsUsable()
        return LockScreenViewModel.factory(
            context = applicationContext,
            clipboard = AutoClearingClipboard(this),
            biometricsAvailable = biometricsAvailable,
        )
    }

    /**
     * Plan requirement: the biometric option exists only when
     * `BiometricManager.canAuthenticate` reports usable STRONG biometrics
     * (hardware present *and* something enrolled). Any error code or
     * pre-Android-6 device hides the feature.
     */
    private fun biometricsUsable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val manager = BiometricManager.from(this)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    companion object {
        @JvmStatic
        fun createIntent(context: Context): Intent =
            Intent(context, LockScreenActivity::class.java)
    }
}

/**
 * Security role: runs the BiometricPrompt with
 * [BiometricPrompt.Authenticators.BIOMETRIC_STRONG] semantics and hands
 * only the *outcome* (not any biometric data) to the caller. Requires a
 * [FragmentActivity] host, which [LockScreenActivity] provides.
 */
class BiometricAuthenticator(private val activity: FragmentActivity) {

    fun authenticate(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User cancel / negative button are not errors for us.
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        onError(errString.toString())
                    }
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(info)
    }
}

/**
 * Security role: [ClipboardController] implementation that clears the
 * system clipboard 60 seconds after copying a recovery phrase — the
 * plan's auto-clear requirement. The delayed clear survives recomposition
 * (Handler on the main looper) and uses `clearPrimaryClip` on API 28+,
 * falling back to an empty clip on older devices.
 */
private class AutoClearingClipboard(context: Context) : ClipboardController {

    private val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val handler = Handler(Looper.getMainLooper())

    private val clearTask = Runnable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboardManager.clearPrimaryClip()
        } else {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    override fun copySensitive(text: String) {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("recovery phrase", text))
        handler.removeCallbacks(clearTask)
        handler.postDelayed(clearTask, ClipboardController.CLIPBOARD_CLEAR_MS)
    }
}