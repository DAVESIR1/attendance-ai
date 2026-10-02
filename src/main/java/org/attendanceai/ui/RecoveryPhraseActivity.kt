/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import org.attendanceai.data.local.db.VaultSession
import org.attendanceai.presentation.lockscreen.SecurityGate

/**
 * Recovery-phrase screen reached from Settings **after** the existing
 * [org.attendanceai.presentation.lockscreen.LockScreenActivity] re-auth.
 *
 * It holds no secret and performs no crypto: Phase 1 never stores the phrase,
 * so the screen explains that design plainly instead of pretending to show it
 * (see [RecoveryPhraseText]). Compose is used only to match the lock screen's
 * look; the rest of the navigation shell stays classic-views Java.
 */
class RecoveryPhraseActivity : FragmentActivity() {

    companion object {
        /** Intent factory so callers never have to know the extras. */
        @JvmStatic
        fun createIntent(context: Context): Intent =
            Intent(context, RecoveryPhraseActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fail closed: this screen is only meaningful inside an unlocked vault.
        if (SecurityGate.lockRequired() || VaultSession.database() == null) {
            finish()
            return
        }
        setContent { RecoveryPhraseScreen(onClose = { finish() }) }
    }
}

/** The read-only explanation, rendered with the lock screen's palette. */
@Composable
fun RecoveryPhraseScreen(onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF4F7FC)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                RecoveryPhraseText.TITLE,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1D2942),
            )
            RecoveryPhraseText.paragraphs().forEach { paragraph ->
                Text(
                    paragraph,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF66738D),
                )
            }
            Button(onClick = onClose) { Text("Close") }
        }
    }
}
