/*
 * Attendance AI — offline-first, on-device attendance app.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later — see https://www.gnu.org/licenses/ for full text.
 */
package org.attendanceai.presentation.lockscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val LockBackground = Color(0xFFF4F7FC)
private val LockInk = Color(0xFF1D2942)
private val LockMuted = Color(0xFF66738D)
private val LockPrimary = Color(0xFF6D5DF5)
private val LockSecondary = Color(0xFF28B8A6)
private val LockError = Color(0xFFC23B5A)
private val LockScheme = lightColorScheme(
    primary = LockPrimary,
    onPrimary = Color.White,
    secondary = LockSecondary,
    onSecondary = Color.White,
    background = LockBackground,
    onBackground = LockInk,
    surface = Color.White,
    onSurface = LockInk,
    error = LockError,
    onError = Color.White,
)

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
    MaterialTheme(colorScheme = LockScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = LockBackground) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFFE8E5FF),
                                LockBackground,
                                Color(0xFFE2F7F2),
                            )
                        )
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LockBrandHeader()
                    LockGlassPanel {
                        when (state.step) {
                            LockStep.Loading -> LoadingStep()
                            LockStep.MnemonicDisplay -> MnemonicDisplayStep(state, viewModel)
                            LockStep.RestoreFromPhrase -> RestoreFromPhraseStep(state, viewModel)
                            LockStep.PinSetup -> PinSetupStep(state, viewModel)
                            LockStep.BiometricOptIn -> BiometricOptInStep(state, viewModel, biometric)
                            LockStep.VerifyPin -> VerifyPinStep(state, viewModel, biometric)
                            LockStep.Done -> Spacer(Modifier.height(1.dp))
                        }
                    }
                    Text(
                        text = "Your face data stays on this device and is encrypted at rest.",
                        color = LockMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LockBrandHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    Brush.linearGradient(listOf(LockPrimary, Color(0xFFB66DFF))),
                    RoundedCornerShape(14.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("A", color = Color.White, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Attendance AI", color = LockInk, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            Text("Private • Offline • Encrypted", color = LockMuted,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LockGlassPanel(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.88f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun LoadingStep() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(color = LockPrimary)
        Text("Preparing your encrypted vault…", color = LockInk)
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rowWords.forEachIndexed { colIndex, word ->
                val number = rowIndex * 3 + colIndex + 1
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFF0EEFF),
                ) {
                    Text(
                        "$number  $word",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                        color = LockInk,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    StatusTexts(state)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = viewModel::copyMnemonic,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LockSecondary),
        ) {
            Text("Copy", color = Color.White)
        }
        Button(
            onClick = viewModel::onMnemonicConfirmed,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LockPrimary),
        ) {
            Text("I wrote it down", color = Color.White)
        }
    }
    TextButton(
        onClick = viewModel::showRestore,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.textButtonColors(contentColor = LockPrimary),
    ) {
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
    Text(
        text,
        color = LockInk,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun StatusTexts(state: LockUiState) {
    state.message?.let {
        Text(it, color = LockSecondary, style = MaterialTheme.typography.bodySmall)
    }
    state.error?.let {
        Text(it, color = LockError, style = MaterialTheme.typography.bodySmall)
    }
}