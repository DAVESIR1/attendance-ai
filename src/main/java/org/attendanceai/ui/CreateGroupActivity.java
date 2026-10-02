/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.attendanceai.data.local.db.AttendanceDatabase;
import org.attendanceai.data.local.db.RoomAttendanceStore;
import org.attendanceai.data.local.db.VaultSession;
import org.attendanceai.presentation.lockscreen.SecurityGate;
import org.attendanceai.store.AttendanceStore;

/**
 * Create Group screen (item 3), reachable from the Home screen.
 *
 * A name field, a checkbox per enrolled person (the roster Home already shows)
 * and a Create button that stays disabled until a name is typed **and** at
 * least one person is checked. Creation goes through {@link RoomAttendanceStore}
 * so the group and its membership rows are written inside one encrypted-Room
 * transaction with the existing foreign-key rules — no new storage path.
 */
public final class CreateGroupActivity extends AppCompatActivity {

    /** Intent factory; callers never need to know the extras. */
    public static Intent createIntent(Context context) {
        return new Intent(context, CreateGroupActivity.class);
    }

    private EditText nameField;
    private Button createButton;
    private TextView status;
    private RoomAttendanceStore store;
    private final List<CheckBox> memberBoxes = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AttendanceDatabase database = VaultSession.database();
        // Fail closed outside an unlocked vault (same rule as the other screens).
        if (SecurityGate.lockRequired() || database == null) {
            finish();
            return;
        }
        store = new RoomAttendanceStore(database);

        LinearLayout root = NavChrome.page(this);
        root.addView(NavChrome.title(this, "Create group"));

        nameField = new EditText(this);
        nameField.setHint("Group name");
        nameField.setTextSize(16f);
        nameField.setSingleLine(true);
        nameField.setTextColor(NavChrome.INK);
        nameField.setHintTextColor(NavChrome.MUTED);
        nameField.setPadding(NavChrome.dp(this, 14), NavChrome.dp(this, 12),
                NavChrome.dp(this, 14), NavChrome.dp(this, 12));
        nameField.setBackground(NavChrome.roundBackground(this, 0xDFFFFFFF, 18));
        nameField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateCreateState();
            }
        });
        root.addView(nameField);

        TextView instruction = NavChrome.cardText(this, 13f, NavChrome.MUTED);
        instruction.setText("Pick the people who belong to this group. Create turns on "
                + "when a name is typed and at least one person is checked.");
        root.addView(instruction);

        addRosterCheckboxes(root);

        createButton = NavChrome.actionButton(this, "Create group", NavChrome.SECONDARY);
        createButton.setEnabled(false);
        createButton.setOnClickListener(v -> createGroup());
        root.addView(createButton);

        status = NavChrome.cardText(this, 14f, NavChrome.INK);
        status.setText("\u00A0");
        root.addView(status);

        Button back = NavChrome.actionButton(this, "Back", NavChrome.TERTIARY);
        back.setOnClickListener(v -> finish());
        root.addView(back);

        setContentView(root);
        updateCreateState();
    }

    /** One checkbox per enrolled person, in the roster's own name order. */
    private void addRosterCheckboxes(LinearLayout root) {
        Map<String, AttendanceStore.Person> roster = store.loadRoster();
        if (roster.isEmpty()) {
            TextView empty = NavChrome.cardText(this, 14f, NavChrome.MUTED);
            empty.setText("No one is enrolled yet — enrol people on the Home screen first.");
            root.addView(empty);
            return;
        }
        ScrollView scroller = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (Map.Entry<String, AttendanceStore.Person> entry : roster.entrySet()) {
            CheckBox box = new CheckBox(this);
            box.setText(entry.getValue().name);
            box.setTag(entry.getKey()); // legacy roster id, e.g. "person-12"
            box.setTextSize(16f);
            box.setTextColor(NavChrome.INK);
            box.setPadding(NavChrome.dp(this, 8), NavChrome.dp(this, 6),
                    NavChrome.dp(this, 8), NavChrome.dp(this, 6));
            box.setOnCheckedChangeListener((buttonView, isChecked) -> updateCreateState());
            memberBoxes.add(box);
            list.addView(box);
        }
        scroller.addView(list);
        root.addView(scroller);
    }

    /** Create stays disabled until a name exists and at least one box is ticked. */
    private void updateCreateState() {
        if (createButton == null) {
            return;
        }
        boolean hasName = nameField != null
                && nameField.getText().toString().trim().length() > 0;
        boolean hasMember = false;
        for (CheckBox box : memberBoxes) {
            if (box.isChecked()) {
                hasMember = true;
                break;
            }
        }
        createButton.setEnabled(hasName && hasMember);
    }

    /** Writes the group + membership rows, then returns to Home (cards refresh). */
    private void createGroup() {
        List<String> memberIds = new ArrayList<>();
        for (CheckBox box : memberBoxes) {
            if (box.isChecked()) {
                memberIds.add((String) box.getTag());
            }
        }
        String name = nameField.getText().toString().trim();
        try {
            store.createGroup(name, memberIds);
            setResult(RESULT_OK);
            finish();
        } catch (IllegalArgumentException invalid) {
            status.setTextColor(NavChrome.ERROR);
            status.setText("Could not create group: " + invalid.getMessage());
        } catch (IOException failure) {
            status.setTextColor(NavChrome.ERROR);
            status.setText("Could not create group (" + failure.getClass().getSimpleName() + ")");
        }
    }
}
