/*
 * Attendance AI — offline-first, on-device attendance app.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later — see https://www.gnu.org/licenses/ for full text.
 */
package org.attendanceai.presentation.lockscreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Security role: the Compose rendering of the vault state machine. This
 * layer holds **no** secrets of its own — words, PIN entries and errors
 * flow through [LockScreenViewModel], which wipes them as they are
 * consumed. The biometric toggle and unlock button are rendered only
 * when the ViewModel reports the device can actually authenticate
 * (hardware present + biometrics enrolled).
 */
@Composable
fun LockScreenScreen(
    viewModel: LockScreenViewModel,
    biometric: BiometricAuthenticator,
    onUnlocked: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.step) {
        if (state.step == LockStep.Done) onUnlocked()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state.step) {
            LockStep.Loading -> CircularProgressIndicator()
            LockStep.MnemonicDisplay -> MnemonicDisplayStep(state, viewModel)
            LockStep.RestoreFromPhrase -> RestoreFromPhraseStep(state, viewModel)
            LockStep.PinSetup -> PinSetupStep(state, viewModel)
            LockStep.BiometricOptIn -> BiometricOptInStep(state, viewModel, biometric)
            LockStep.VerifyPin -> VerifyPinStep(state, viewModel, biometric)
            LockStep.Done -> {}
        }
    }
}

/** Step 1 of setup: show the phrase once, offer copy + restore link. */
@Composable
private fun MnemonicDisplayStep(state: LockUiState, viewModel: LockScreenViewModel) {
    StepTitle("Your recovery phrase")
    Text(
        "Write these ${state.mnemonic.size} words down in order and keep them " +
            "offline. They are the only way to restore your key. This screen " +
            "will not show them again.",
        style = MaterialTheme.typography.bodyMedium,
    )
    state.mnemonic.chunked(3).forEachIndexed { rowIndex, rowWords ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowWords.forEachIndexed { colIndex, word ->
                val number = rowIndex * 3 + colIndex + 1
                Text(
                    "$number. $word",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
    StatusTexts(state)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = viewModel::copyMnemonic, modifier = Modifier.weight(1f)) {
            Text("Copy")
        }
        Button(onClick = viewModel::onMnemonicConfirmed, modifier = Modifier.weight(1f)) {
            Text("I wrote it down")
        }
    }
    TextButton(onClick = viewModel::showRestore, modifier = Modifier.fillMaxWidth()) {
        Text("I already have a recovery phrase")
    }
}

/** Restore path reachable from a text link for existing users. */
@Composable
private fun RestoreFromPhraseStep(state: LockUiState, viewModel: LockScreenViewModel) {
    StepTitle("Restore from recovery phrase")
    Text(
        "Enter your 12–24 word phrase, separated by spaces or commas.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = state.restoreText,
        onValueChange = viewModel::onRestoreChanged,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Recovery phrase") },
        minLines = 3,
    )
    StatusTexts(state)
    Button(
        onClick = viewModel::onRestoreSubmitted,
        modifier = Modifier.fillMaxWidth(),
        enabled = state.restoreText.isNotBlank(),
    ) {
        Text("Restore")
    }
    TextButton(onClick = viewModel::backToFreshPhrase, modifier = Modifier.fillMaxWidth()) {
        Text("Back to a new phrase")
    }
}

/** Step 2 of setup: choose the PIN (digits only, stored as a verifier). */
@Composable
private fun PinSetupStep(state: LockUiState, viewModel: LockScreenViewModel) {
    StepTitle("Set your PIN")
    Text(
        "Used to unlock the app. ${LockScreenViewModel.MIN_PIN_LEN}–" +
            "${LockScreenViewModel.MAX_PIN_LEN} digits. It is stored only as a " +
            "salted PBKDF2 hash — never in the clear.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = state.pin,
        onValueChange = viewModel::onPinChanged,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("PIN") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
    OutlinedTextField(
        value = state.pinConfirm,
        onValueChange = viewModel::onPinConfirmChanged,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Confirm PIN") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
    StatusTexts(state)
    Button(
        onClick = viewModel::onPinSetupSubmitted,
        modifier = Modifier.fillMaxWidth(),
        enabled = state.pin.isNotEmpty() && state.pinConfirm.isNotEmpty(),
    ) {
        Text("Set PIN")
    }
}

/**
 * Step 3 of setup: biometric opt-in. Only reached when the device
 * reports usable biometrics; otherwise the ViewModel skips the step
 * entirely (the "hide the toggle" requirement).
 */
@Composable
private fun BiometricOptInStep(
    state: LockUiState,
    viewModel: LockScreenViewModel,
    biometric: BiometricAuthenticator,
) {
    StepTitle("Biometric unlock")
    Text(
        "Optionally allow unlocking with your fingerprint or face instead " +
            "of the PIN. Your biometrics never leave the device; the app " +
            "only learns whether authentication succeeded.",
        style = MaterialTheme.typography.bodyMedium,
    )
    StatusTexts(state)
    Button(
        onClick = {
            biometric.authenticate(
                title = "Enable biometric unlock",
                subtitle = "Verify to finish setup",
                onSuccess = { viewModel.onBiometricOptInResult(true) },
                onError = { viewModel.onBiometricOptInResult(false) },
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Enable biometric unlock")
    }
    OutlinedButton(
        onClick = { viewModel.onBiometricOptInResult(false) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Skip for now")
    }
}

/** Unlock path: PIN entry with escalating lockout + biometric button. */
@Composable
private fun VerifyPinStep(
    state: LockUiState,
    viewModel: LockScreenViewModel,
    biometric: BiometricAuthenticator,
) {
    StepTitle("Unlock")
    var remainingMs by remember { mutableStateOf(0L) }
    LaunchedEffect(state.lockoutDeadlineMs) {
        while (true) {
            remainingMs = state.remainingLockoutMs()
            if (remainingMs <= 0L) break
            delay(500)
        }
    }
    OutlinedTextField(
        value = state.pin,
        onValueChange = viewModel::onPinChanged,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("PIN") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
    if (remainingMs > 0L) {
        val seconds = (remainingMs + 999) / 1000
        Text(
            "Too many wrong attempts — try again in ${seconds}s. " +
                "Your data is safe.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    StatusTexts(state)
    Button(
        onClick = viewModel::onVerifySubmitted,
        modifier = Modifier.fillMaxWidth(),
        enabled = remainingMs <= 0L && state.pin.isNotEmpty(),
    ) {
        Text("Unlock")
    }
    if (state.showBiometricOption && state.biometricEnabled) {
        OutlinedButton(
            onClick = {
                biometric.authenticate(
                    title = "Unlock Attendance AI",
                    subtitle = "Verify to continue",
                    onSuccess = viewModel::onBiometricUnlockSuccess,
                    onError = { /* prompt already surfaces the error */ },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Unlock with biometrics")
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun StatusTexts(state: LockUiState) {
    state.message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}