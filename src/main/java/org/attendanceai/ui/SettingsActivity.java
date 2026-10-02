/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.util.LinkedHashMap;

import org.attendanceai.data.local.db.AttendanceDatabase;
import org.attendanceai.data.local.db.RoomAttendanceStore;
import org.attendanceai.data.local.db.VaultSession;
import org.attendanceai.presentation.lockscreen.LockScreenActivity;
import org.attendanceai.presentation.lockscreen.SecurityGate;
import org.attendanceai.store.AttendanceStore;

/**
 * Settings destination for the navigation shell (item 2).
 *
 * Currently holds two working actions — the roster clear (moved here from the
 * Home screen, because wiping enrolled people is a settings-class operation)
 * and "View recovery phrase" — plus a note that the rest of the settings
 * surface is still to come. Every storage action goes through the existing
 * {@link RoomAttendanceStore}, so the encrypted-Room and foreign-key behaviour
 * is identical to the pipeline's own writes; no new storage path is added.
 */
public final class SettingsActivity extends AppCompatActivity {

    /**
     * Result action sent back to the Home screen after the roster changed, so
     * it can reload its face templates instead of matching against people that
     * no longer exist.
     */
    public static final String RESULT_RELOAD_TEMPLATES = "org.attendanceai.ui.RELOAD_TEMPLATES";

    private TextView status;
    /** True once the roster was cleared, so Back reports it to the Home screen. */
    private boolean rosterCleared;

    /** Intent factory; callers never need to know the extras. */
    public static Intent createIntent(Context context) {
        return new Intent(context, SettingsActivity.class);
    }

    /**
     * "View recovery phrase" re-authenticates through the existing Phase-1
     * lock screen — no new crypto, no new secret store — and only then opens
     * the explanation screen stating, honestly, that the phrase exists exactly
     * once (at setup) and is never stored.
     */
    private final ActivityResultLauncher<Intent> reauthLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    startActivity(RecoveryPhraseActivity.createIntent(this));
                } else {
                    status.setText("Recovery phrase view cancelled — nothing was shown.");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SecurityGate.lockRequired() || VaultSession.database() == null) {
            finish();
            return;
        }

        LinearLayout root = NavChrome.page(this);
        root.addView(NavChrome.title(this, "Settings"));

        TextView intro = NavChrome.cardText(this, 14f, NavChrome.MUTED);
        intro.setText("Two settings work today. Theme, backup and the remaining "
                + "options are planned for later stages.");
        root.addView(intro);

        // Phase-1 re-auth (biometric/PIN) before anything secret-adjacent.
        TextView recovery = NavChrome.rowCard(this, "View recovery phrase  \u203A");
        recovery.setOnClickListener(v ->
                reauthLauncher.launch(LockScreenActivity.createIntent(this)));
        root.addView(recovery);

        Button clear = NavChrome.actionButton(this, "Clear roster", NavChrome.TERTIARY);
        clear.setOnClickListener(v -> clearRoster());
        root.addView(clear);

        status = NavChrome.cardText(this, 14f, NavChrome.INK);
        status.setText("Settings - more coming later");
        root.addView(status);

        Button back = NavChrome.actionButton(this, "Back", NavChrome.PRIMARY);
        back.setOnClickListener(v -> close());
        root.addView(back);

        setContentView(root);
    }

    /**
     * Deletes every enrolled person through the encrypted store. The empty-map
     * path hard-deletes people, and the Room foreign keys cascade to group
     * memberships and attendance records — the same behaviour the old Home
     * button had.
     */
    private void clearRoster() {
        AttendanceDatabase database = VaultSession.database();
        if (SecurityGate.lockRequired() || database == null) {
            status.setTextColor(NavChrome.ERROR);
            status.setText("Clear failed: the encrypted session is not available.");
            return;
        }
        try {
            new RoomAttendanceStore(database)
                    .saveRoster(new LinkedHashMap<String, AttendanceStore.Person>());
            rosterCleared = true;
            status.setTextColor(NavChrome.INK);
            status.setText("Roster cleared — people, group memberships and records removed.");
        } catch (IOException | RuntimeException failure) {
            status.setTextColor(NavChrome.ERROR);
            status.setText("Clear failed: " + failure.getClass().getSimpleName());
        }
    }

    /** Reports back to the Home screen whether the roster changed. */
    private void close() {
        Intent data = new Intent();
        if (rosterCleared) {
            data.setAction(RESULT_RELOAD_TEMPLATES);
            setResult(RESULT_OK, data);
        } else {
            setResult(RESULT_CANCELED, data);
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        // Hardware back must report the same result as the Back button,
        // otherwise a cleared roster would leave Home matching stale people.
        close();
    }
}