/*
 * Copyright (C) 2025 Nicholas Girga
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>
 */

package com.stario.launcher.activities.settings.dialogs.nextcloud;

import android.annotation.SuppressLint;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.stario.launcher.R;
import com.stario.launcher.sheet.briefing.sync.NextcloudSyncService;
import com.stario.launcher.themes.ThemedActivity;
import com.stario.launcher.ui.dialogs.ActionDialog;

/**
 * Dialog for configuring Nextcloud News synchronization.
 */
public class NextcloudSyncDialog extends ActionDialog implements NextcloudSyncService.SyncListener {
    private NextcloudSyncService syncService;
    private EditText serverUrlInput;
    private EditText usernameInput;
    private EditText passwordInput;
    private MaterialSwitch enabledSwitch;
    private Button syncButton;
    private TextView lastSyncText;
    private TextView statusText;

    public NextcloudSyncDialog(@NonNull ThemedActivity activity) {
        super(activity);
        
        this.syncService = NextcloudSyncService.from(activity);
        this.syncService.addSyncListener(this);
    }

    @SuppressLint("ClickableViewAccessibility")
    @NonNull
    @Override
    protected View inflateContent(LayoutInflater inflater) {
        View root = inflater.inflate(R.layout.pop_up_nextcloud_sync, null);

        serverUrlInput = root.findViewById(R.id.server_url);
        usernameInput = root.findViewById(R.id.username);
        passwordInput = root.findViewById(R.id.password);
        enabledSwitch = root.findViewById(R.id.sync_enabled);
        syncButton = root.findViewById(R.id.sync_now);
        lastSyncText = root.findViewById(R.id.last_sync);
        statusText = root.findViewById(R.id.status);

        // Load current settings
        serverUrlInput.setText(syncService.getServerUrl());
        usernameInput.setText(syncService.getUsername());
        enabledSwitch.setChecked(syncService.isSyncEnabled());
        
        updateLastSyncTime();
        updateSyncButtonState();

        // Set up listeners
        enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateSyncButtonState();
        });

        syncButton.setOnClickListener(v -> {
            saveSettings();
            performSync();
        });

        root.findViewById(R.id.save_button).setOnClickListener(v -> {
            saveSettings();
            dismiss();
        });

        return root;
    }

    private void saveSettings() {
        String serverUrl = serverUrlInput.getText().toString().trim();
        String username = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        boolean enabled = enabledSwitch.isChecked();

        // Remove trailing slash from server URL if present
        if (serverUrl.endsWith("/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }

        syncService.configure(serverUrl, username, password, enabled);
        
        Toast.makeText(activity, R.string.nextcloud_settings_saved, Toast.LENGTH_SHORT).show();
    }

    private void performSync() {
        if (!syncService.isSyncEnabled()) {
            Toast.makeText(activity, R.string.nextcloud_sync_disabled, Toast.LENGTH_SHORT).show();
            return;
        }

        statusText.setText(R.string.nextcloud_syncing);
        statusText.setVisibility(View.VISIBLE);
        syncButton.setEnabled(false);

        syncService.performSync().thenAccept(success -> {
            activity.runOnUiThread(() -> {
                syncButton.setEnabled(true);
                updateLastSyncTime();
            });
        });
    }

    private void updateLastSyncTime() {
        long lastSync = syncService.getLastSyncTime();
        
        if (lastSync > 0) {
            CharSequence timeAgo = DateUtils.getRelativeTimeSpanString(
                lastSync,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            );
            lastSyncText.setText(activity.getString(R.string.nextcloud_last_sync, timeAgo));
            lastSyncText.setVisibility(View.VISIBLE);
        } else {
            lastSyncText.setVisibility(View.GONE);
        }
    }

    private void updateSyncButtonState() {
        syncButton.setEnabled(enabledSwitch.isChecked());
        syncButton.setAlpha(enabledSwitch.isChecked() ? 1.0f : 0.5f);
    }

    @Override
    public void onSyncStarted() {
        activity.runOnUiThread(() -> {
            statusText.setText(R.string.nextcloud_syncing);
            statusText.setVisibility(View.VISIBLE);
            syncButton.setEnabled(false);
        });
    }

    @Override
    public void onSyncCompleted(boolean success) {
        activity.runOnUiThread(() -> {
            if (success) {
                statusText.setText(R.string.nextcloud_sync_success);
                Toast.makeText(activity, R.string.nextcloud_sync_success, Toast.LENGTH_SHORT).show();
            } else {
                statusText.setText(R.string.nextcloud_sync_failed);
                Toast.makeText(activity, R.string.nextcloud_sync_failed, Toast.LENGTH_SHORT).show();
            }
            
            statusText.postDelayed(() -> statusText.setVisibility(View.GONE), 3000);
            syncButton.setEnabled(true);
            updateLastSyncTime();
        });
    }

    @Override
    public void onSyncFailed(String error) {
        activity.runOnUiThread(() -> {
            statusText.setText(activity.getString(R.string.nextcloud_sync_error, error));
            statusText.setVisibility(View.VISIBLE);
            statusText.postDelayed(() -> statusText.setVisibility(View.GONE), 5000);
            syncButton.setEnabled(true);
            
            Toast.makeText(activity, 
                activity.getString(R.string.nextcloud_sync_error, error), 
                Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected boolean blurBehind() {
        return true;
    }

    @Override
    public void dismiss() {
        syncService.removeSyncListener(this);
        super.dismiss();
    }
}
