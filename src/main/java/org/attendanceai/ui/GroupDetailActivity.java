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

import java.util.List;

import org.attendanceai.data.local.db.AttendanceDatabase;
import org.attendanceai.data.local.db.GroupWithMembers;
import org.attendanceai.data.local.db.RoomAttendanceStore;
import org.attendanceai.data.local.db.VaultSession;
import org.attendanceai.presentation.lockscreen.SecurityGate;

/**
 * Group Detail screen (item 3): the group's members and a "Get attendance"
 * button.
 *
 * Limitation, stated on screen: the button starts the plan's **existing**
 * capture flow, which still matches against the full roster — restricting
 * matching to only this group's members is Stage 4-C work. The button asks the
 * Home screen to auto-start that flow (it is the activity that owns the camera
 * and the pipeline) and then closes.
 */
public final class GroupDetailActivity extends AppCompatActivity {

    /** The group's row id; without it the screen cannot render anything. */
    public static final String EXTRA_GROUP_ID = "org.attendanceai.ui.GROUP_ID";

    /** Intent factory; callers never need to know the extras. */
    public static Intent createIntent(Context context, long groupId) {
        return new Intent(context, GroupDetailActivity.class).putExtra(EXTRA_GROUP_ID, groupId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AttendanceDatabase database = VaultSession.database();
        long groupId = getIntent().getLongExtra(EXTRA_GROUP_ID, -1L);
        if (SecurityGate.lockRequired() || database == null || groupId < 0L) {
            finish();
            return;
        }

        GroupWithMembers group = new RoomAttendanceStore(database).loadGroup(groupId);
        if (group == null) {
            // Deleted (e.g. its people were cleared) — nothing to show.
            finish();
            return;
        }

        LinearLayout root = NavChrome.page(this);
        root.addView(NavChrome.title(this, group.getName()));

        TextView members = NavChrome.cardText(this, 16f, NavChrome.INK);
        members.setText(memberSummary(group.getMemberNames()));
        root.addView(members);

        TextView limitation = NavChrome.cardText(this, 12f, NavChrome.MUTED);
        limitation.setText("Get attendance starts the normal camera capture and matches "
                + "against the full roster for now — restricting matching to this group's "
                + "members is Stage 4-C work.");
        root.addView(limitation);

        Button getAttendance = NavChrome.actionButton(this, "Get attendance", NavChrome.PRIMARY);
        getAttendance.setOnClickListener(v -> {
            AttendanceActivity.requestAutoStartCamera();
            finish();
        });
        root.addView(getAttendance);

        Button back = NavChrome.actionButton(this, "Back", NavChrome.TERTIARY);
        back.setOnClickListener(v -> finish());
        root.addView(back);

        setContentView(root);
    }

    /** A simple bulleted member list (or an explicit "nobody left" note). */
    private String memberSummary(List<String> names) {
        if (names.isEmpty()) {
            return "No members — everyone in this group has been removed from the roster.";
        }
        StringBuilder summary = new StringBuilder();
        summary.append(names.size()).append(names.size() == 1 ? " member:" : " members:");
        for (String name : names) {
            summary.append("\n\n\u2022 ").append(name);
        }
        return summary.toString();
    }
}