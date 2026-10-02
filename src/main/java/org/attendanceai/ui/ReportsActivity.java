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

import androidx.appcompat.app.AppCompatActivity;

import org.attendanceai.BuildConfig;
import org.attendanceai.data.local.db.VaultSession;
import org.attendanceai.presentation.lockscreen.SecurityGate;

/**
 * Placeholder Reports destination for the navigation shell (item 2).
 *
 * Reports (PDF/Excel attendance exports, date ranges, per-group views) is
 * Stage 4-D work — this screen exists so the menu has a real destination and
 * the "not built yet" state is explicit on the device instead of an empty
 * menu item that looks broken.
 */
public final class ReportsActivity extends AppCompatActivity {

    /** Intent factory; callers never need to know the extras. */
    public static Intent createIntent(Context context) {
        return new Intent(context, ReportsActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Secondary screens stay fail-closed: never render app content in a
        // process whose vault was not opened through the lock screen.
        if (SecurityGate.lockRequired() || VaultSession.database() == null) {
            finish();
            return;
        }

        LinearLayout root = NavChrome.page(this);
        root.addView(NavChrome.title(this, "Reports"));

        TextView note = NavChrome.cardText(this, 16f, NavChrome.INK);
        note.setText("Reports - coming in Stage 4-D\n\n"
                + "Attendance reports (PDF and Excel export, date ranges, per-group views) "
                + "are planned for Stage 4-D. Nothing is generated or exported yet.");
        root.addView(note);

        TextView version = NavChrome.cardText(this, 12f, NavChrome.MUTED);
        version.setText("Attendance AI \u2022 " + BuildConfig.VERSION_NAME);
        root.addView(version);

        Button back = NavChrome.actionButton(this, "Back", NavChrome.TERTIARY);
        back.setOnClickListener(v -> finish());
        root.addView(back);

        setContentView(root);
    }
}