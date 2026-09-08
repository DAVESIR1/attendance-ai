/*
 * Attendance AI — offline-first, on-device attendance app.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later — see https://www.gnu.org/licenses/ for full text.
 */
package org.attendanceai.presentation.lockscreen

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.attendanceai.data.local.crypto.Bip39WordList
import org.attendanceai.data.local.crypto.KeyManager
import org.attendanceai.data.local.crypto.MnemonicGenerator
import org.attendanceai.data.local.crypto.MnemonicValidation
import org.attendanceai.data.local.crypto.MnemonicValidator
import org.attendanceai.data.local.crypto.PinRepository
import org.attendanceai.data.local.crypto.SetupStage
import org.attendanceai.data.local.crypto.VaultKeyBlob
import java.security.SecureRandom

/**
 * Abstraction over the system clipboard so the ViewModel never touches
 * Android framework types. Implementations MUST auto-clear the copied
 * secret after [CLIPBOARD_CLEAR_MS] (60 s) — the mnemonic is copied in
 * the clear for backup, so a lingering clipboard entry would defeat the
 * "shown once" property of the phrase.
 */
interface ClipboardController {

    /** Copies [text] and schedules automatic clearing after 60 seconds. */
    fun copySensitive(text: String)

    companion object {
        /** Plan requirement: clipboard auto-clears 60 seconds after copy. */
        const val CLIPBOARD_CLEAR_MS = 60_000L
    }
}

/** Sub-screens of the lock flow, driven as a state machine (no NavHost). */
enum class LockStep {
    Loading,
    MnemonicDisplay,
    RestoreFromPhrase,
    PinSetup,
    BiometricOptIn,
    VerifyPin,
    Done,
}

/**
 * Immutable UI state of the lock flow. Transient PIN entries live here
 * only in RAM; they are wiped on submit, on step change, and on process
 * death — never persisted, never logged.
 */
data class LockUiState(
    val step: LockStep = LockStep.Loading,
    val mnemonic: List<String> = emptyList(),
    val pin: String = "",
    val pinConfirm: String = "",
    val restoreText: String = "",
    val message: String? = null,
    val error: String? = null,
    val attempts: Int = 0,
    val lockoutDeadlineMs: Long = 0L,
    val showBiometricOption: Boolean = false,
    val biometricEnabled: Boolean = false,
) {
    /** Remaining lockout in ms, computed against the wall clock. */
    fun remainingLockoutMs(nowMillis: Long = System.currentTimeMillis()): Long =
        (lockoutDeadlineMs - nowMillis).coerceAtLeast(0L)
}

/**
 * Security role: orchestrates the Phase-1 vault lifecycle — generating
 * and displaying the recovery phrase exactly once, wrapping the derived
 * key via [KeyManager] before anything touches storage, persisting only
 * verifiers (never secrets), enforcing the escalating PIN lockout, and
 * hiding the biometric option when the device cannot support it.
 *
 * Invariants enforced here: the phrase binds nothing until confirmed;
 * PIN digits and mnemonic words are never logged or persisted; the setup
 * stage is persisted after every step (process death resumes correctly);
 * wrong PINs only escalate the lockout — no code path erases user data.
 */
class LockScreenViewModel(
    private val wordList: Bip39WordList,
    private val generator: MnemonicGenerator,
    private val pinRepository: PinRepository,
    private val keyManager: KeyManager,
    private val clipboard: ClipboardController?,
    biometricsAvailable: Boolean,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LockUiState(showBiometricOption = biometricsAvailable)
    )
    val state: StateFlow<LockUiState> = _state.asStateFlow()

    init {
        start()
    }

    /**
     * Resumes the flow at the correct sub-screen for the persisted setup
     * stage — this is what makes setup survive process death.
     */
    fun start() {
        val stage = pinRepository.setupStage()
        var freshPhrase: List<String> = emptyList()
        val step = when (stage) {
            SetupStage.NONE -> {
                // Nothing is bound yet: a fresh phrase is safe (and avoids
                // ever having to persist the words to survive death).
                freshPhrase = generator.generate(DEFAULT_STRENGTH_BITS)
                LockStep.MnemonicDisplay
            }
            SetupStage.MNEMONIC_CONFIRMED -> LockStep.PinSetup
            SetupStage.PIN_SET ->
                if (_state.value.showBiometricOption) {
                    LockStep.BiometricOptIn
                } else {
                    finalizeSetup()
                    LockStep.Done
                }
            SetupStage.COMPLETE -> LockStep.VerifyPin
        }
        _state.update {
            it.copy(
                step = step,
                mnemonic = freshPhrase,
                pin = "",
                pinConfirm = "",
                restoreText = "",
                error = null,
                message = null,
                attempts = pinRepository.failedAttempts(),
                lockoutDeadlineMs = System.currentTimeMillis() +
                    pinRepository.remainingLockoutMs(),
                biometricEnabled = pinRepository.isBiometricEnabled(),
            )
        }
    }

    /**
     * Copies the currently displayed phrase to the clipboard through
     * [ClipboardController]; the implementation clears it after 60 s.
     * The message never contains the copied content.
     */
    fun copyMnemonic() {
        val phrase = _state.value.mnemonic
        if (phrase.isEmpty() || clipboard == null) return
        clipboard.copySensitive(phrase.joinToString(" "))
        _state.update {
            it.copy(message = "Copied — clipboard will be cleared in 60 seconds", error = null)
        }
    }

    /** Text link: existing users restore instead of generating a phrase. */
    fun showRestore() {
        _state.update {
            it.copy(step = LockStep.RestoreFromPhrase, message = null, error = null)
        }
    }

    /** Back link from the restore screen to a fresh phrase display. */
    fun backToFreshPhrase() {
        _state.update {
            it.copy(
                step = LockStep.MnemonicDisplay,
                mnemonic = generator.generate(DEFAULT_STRENGTH_BITS),
                restoreText = "",
                message = null,
                error = null,
            )
        }
    }

    /**
     * User confirms they wrote the phrase down: derive the vault key from
     * it, wrap it in the Android Keystore and persist only the wrapped
     * blob + salt. The raw key never leaves this method alive.
     */
    fun onMnemonicConfirmed() {
        val phrase = _state.value.mnemonic
        if (phrase.isEmpty()) return
        installPhrase(phrase.joinToString(" ").toCharArray())
        _state.update {
            it.copy(step = LockStep.PinSetup, mnemonic = emptyList(), message = null, error = null)
        }
    }

    /** Called on every keystroke in the restore text field. */
    fun onRestoreChanged(text: String) {
        _state.update { it.copy(restoreText = text, error = null) }
    }

    /**
     * Restore path for existing users: the typed phrase must pass full
     * BIP-39 validation before anything is stored. On success the phrase
     * replaces the (never-created) generated one and setup continues at
     * the PIN step.
     */
    fun onRestoreSubmitted() {
        val words = _state.value.restoreText
            .split(WHITESPACE)
            .filter { it.isNotBlank() }
        when (val validation = MnemonicValidator.validate(words, wordList)) {
            is MnemonicValidation.Valid -> {
                installPhrase(words.joinToString(" ").toCharArray())
                _state.update {
                    it.copy(
                        step = LockStep.PinSetup,
                        restoreText = "",
                        message = "Recovery phrase accepted",
                        error = null,
                    )
                }
            }
            is MnemonicValidation.Invalid -> {
                _state.update {
                    it.copy(error = validation.reason, message = null)
                }
            }
        }
    }

    /**
     * Derives + wraps + stores the vault key for [phraseChars], then
     * advances the persisted stage so a process death resumes at the PIN
     * step instead of re-showing a phrase that is already bound.
     */
    private fun installPhrase(phraseChars: CharArray) {
        val salt = KeyManager.randomSalt()
        val key = KeyManager.deriveKey(phraseChars, salt)
        try {
            val wrapped = keyManager.wrap(key)
            pinRepository.storeWrappedKey(
                VaultKeyBlob(
                    wrapped = wrapped.encode(),
                    salt = Base64.encodeToString(salt, Base64.NO_WRAP),
                )
            )
            pinRepository.setSetupStage(SetupStage.MNEMONIC_CONFIRMED)
        } finally {
            key.fill(0)
        }
    }

    /** Called on every keystroke in the PIN field (digits only, max len). */
    fun onPinChanged(text: String) {
        _state.update { it.copy(pin = text.filter(Char::isDigit).take(MAX_PIN_LEN), error = null) }
    }

    /** Called on every keystroke in the confirm-PIN field. */
    fun onPinConfirmChanged(text: String) {
        _state.update {
            it.copy(pinConfirm = text.filter(Char::isDigit).take(MAX_PIN_LEN), error = null)
        }
    }

    /**
     * PIN setup: 4–12 digits, both entries equal. Stores only the
     * salted PBKDF2 verifier, clears the digits from state, and moves
     * on to biometric opt-in (skipped entirely when the device has no
     * usable biometric hardware/enrollment).
     */
    fun onPinSetupSubmitted() {
        val current = _state.value
        val pin = current.pin
        val error = when {
            pin.length < MIN_PIN_LEN -> "PIN must be at least $MIN_PIN_LEN digits"
            pin != current.pinConfirm -> "PINs do not match"
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(error = error) }
            return
        }
        pinRepository.setPin(pin.toCharArray())
        pinRepository.setSetupStage(SetupStage.PIN_SET)
        val next = if (current.showBiometricOption) LockStep.BiometricOptIn else {
            finalizeSetup()
            LockStep.Done
        }
        _state.update {
            it.copy(step = next, pin = "", pinConfirm = "", error = null)
        }
    }

    /**
     * Result of the biometric opt-in step. [enabled] is true only after
     * a successful BiometricPrompt authentication run by the activity.
     */
    fun onBiometricOptInResult(enabled: Boolean) {
        pinRepository.setBiometricEnabled(enabled)
        finalizeSetup()
        _state.update {
            it.copy(step = LockStep.Done, message = if (enabled) "Biometric unlock enabled" else null)
        }
    }

    /** PIN verify (unlock path) with escalating, persisted lockout. */
    fun onVerifySubmitted() {
        val current = _state.value
        val remaining = pinRepository.remainingLockoutMs()
        if (remaining > 0) {
            _state.update {
                it.copy(lockoutDeadlineMs = System.currentTimeMillis() + remaining)
            }
            return
        }
        if (current.pin.isEmpty()) return
        if (pinRepository.verifyPin(current.pin.toCharArray())) {
            pinRepository.resetFailedAttempts()
            SecurityGate.markUnlockedForSession()
            _state.update { it.copy(step = LockStep.Done, pin = "", error = null, message = null) }
        } else {
            val deadline = pinRepository.registerFailedAttempt()
            val attempts = pinRepository.failedAttempts()
            _state.update {
                it.copy(
                    pin = "",
                    attempts = attempts,
                    lockoutDeadlineMs = deadline,
                    error = "Wrong PIN (attempt $attempts)",
                )
            }
        }
    }

    /** Biometric unlock (system prompt already rate-limits attempts). */
    fun onBiometricUnlockSuccess() {
        pinRepository.resetFailedAttempts()
        SecurityGate.markUnlockedForSession()
        _state.update { it.copy(step = LockStep.Done, pin = "", error = null) }
    }

    /** Marks setup COMPLETE (persisted) and unlocks this session. */
    private fun finalizeSetup() {
        pinRepository.setSetupStage(SetupStage.COMPLETE)
        SecurityGate.markUnlockedForSession()
    }

    companion object {
        const val MIN_PIN_LEN = 4
        const val MAX_PIN_LEN = 12
        const val DEFAULT_STRENGTH_BITS = 128
        private val WHITESPACE = Regex("\\s+|,")

        /**
         * Factory wiring the Android-side collaborators. Fails closed:
         * any error loading the wordlist or opening the encrypted store
         * propagates to the activity, which must not proceed unlocked.
         */
        fun factory(
            context: android.content.Context,
            clipboard: ClipboardController?,
            biometricsAvailable: Boolean,
        ): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(LockScreenViewModel::class.java))
                    val wordList = Bip39WordList.fromAssets(appContext)
                    return LockScreenViewModel(
                        wordList = wordList,
                        generator = MnemonicGenerator(wordList, SecureRandom()),
                        pinRepository = PinRepository(appContext),
                        keyManager = KeyManager(),
                        clipboard = clipboard,
                        biometricsAvailable = biometricsAvailable,
                    ) as T
                }
            }
        }
    }
}