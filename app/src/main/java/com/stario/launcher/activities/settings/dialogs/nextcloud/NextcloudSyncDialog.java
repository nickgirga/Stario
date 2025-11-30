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

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.stario.launcher.R;
import com.stario.launcher.preferences.Entry;
import com.stario.launcher.sheet.briefing.sync.NextcloudSSOHelper;
import com.stario.launcher.sheet.briefing.sync.NextcloudSyncService;
import com.stario.launcher.themes.ThemedActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Dialog for configuring Nextcloud News sync using WebView-based login.
 */
public class NextcloudSyncDialog extends Dialog {
    private static final int REQUEST_WEB_LOGIN = 1;
    private static final String PREF_SERVER_URL = "nc_server_url";
    private static final String PREF_USERNAME = "nc_username";
    private static final String PREF_PASSWORD = "nc_password";
    
    private final ThemedActivity activity;
    private final NextcloudSyncService syncService;
    private final NextcloudSSOHelper ssoHelper;
    private final SharedPreferences prefs;
    
    private EditText serverUrlInput;
    private Button loginButton;
    private TextView accountInfo;
    private MaterialSwitch syncEnabled;
    private TextView lastSync;
    private TextView status;
    private Button syncNow;
    private Button saveButton;
    
    private String serverUrl;
    private String username;
    private String password;
    
    public NextcloudSyncDialog(@NonNull ThemedActivity activity) {
        super(activity);
        this.activity = activity;
        this.syncService = NextcloudSyncService.from(activity);
        this.ssoHelper = new NextcloudSSOHelper(activity);
        this.prefs = activity.getApplicationContext().getSharedPreferences("nextcloud_sync", Context.MODE_PRIVATE);
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.pop_up_nextcloud_sync);
        
        // Set rounded background
        if (getWindow() != null) {
            getWindow().setBackgroundDrawableResource(R.drawable.dialog_background);
        }
        
        // Initialize views
        serverUrlInput = findViewById(R.id.server_url_input);
        loginButton = findViewById(R.id.login_button);
        accountInfo = findViewById(R.id.account_info);
        syncEnabled = findViewById(R.id.sync_enabled);
        lastSync = findViewById(R.id.last_sync);
        status = findViewById(R.id.status);
        syncNow = findViewById(R.id.sync_now);
        saveButton = findViewById(R.id.save_button);
        
        // Load current settings
        loadSettings();
        
        // Set up listeners
        loginButton.setOnClickListener(v -> launchWebLogin());
        syncNow.setOnClickListener(v -> performSync());
        saveButton.setOnClickListener(v -> saveSettings());
        
        // Add sync listener
        syncService.addSyncListener(new NextcloudSyncService.SyncListener() {
            @Override
            public void onSyncStarted() {
                activity.runOnUiThread(() -> {
                    status.setText(R.string.nextcloud_syncing);
                    status.setVisibility(View.VISIBLE);
                    syncNow.setEnabled(false);
                });
            }
            
            @Override
            public void onSyncCompleted(boolean success) {
                activity.runOnUiThread(() -> {
                    if (success) {
                        status.setText(R.string.nextcloud_sync_success);
                        updateLastSyncTime();
                    } else {
                        status.setText(R.string.nextcloud_sync_failed);
                    }
                    syncNow.setEnabled(true);
                });
            }
            
            @Override
            public void onSyncFailed(String error) {
                activity.runOnUiThread(() -> {
                    status.setText(activity.getString(R.string.nextcloud_sync_error, error));
                    status.setVisibility(View.VISIBLE);
                    syncNow.setEnabled(true);
                });
            }
        });
    }
    
    private void loadSettings() {
        // Load saved credentials
        serverUrl = prefs.getString(PREF_SERVER_URL, "");
        username = prefs.getString(PREF_USERNAME, "");
        password = prefs.getString(PREF_PASSWORD, "");
        
        // Update UI
        if (!serverUrl.isEmpty()) {
            serverUrlInput.setText(serverUrl);
        }
        
        if (!username.isEmpty() && !password.isEmpty()) {
            ssoHelper.setCredentials(serverUrl, username, password);
            // Also configure the sync service with these credentials
            syncService.configureSso(ssoHelper, syncService.isSyncEnabled());
            updateAccountInfo();
        } else {
            accountInfo.setVisibility(View.GONE);
        }
        
        // Load sync enabled state
        syncEnabled.setChecked(syncService.isSyncEnabled());
        
        // Update last sync time
        updateLastSyncTime();
    }
    
    private void launchWebLogin() {
        String url = serverUrlInput.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(activity, "Please enter your Nextcloud server URL", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Ensure URL has protocol
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        
        // Remove trailing slash
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        
        final String serverUrl = url;
        
        // Initialize Nextcloud Login Flow v2 in background
        new Thread(() -> {
            try {
                String initUrl = serverUrl + "/index.php/login/v2";
                java.net.URL urlObj = new java.net.URL(initUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) urlObj.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    org.json.JSONObject json = new org.json.JSONObject(response.toString());
                    
                    String loginUrl;
                    String pollToken;
                    String pollEndpoint;
                    
                    if (json.has("poll")) {
                        org.json.JSONObject pollObj = json.getJSONObject("poll");
                        pollToken = pollObj.getString("token");
                        pollEndpoint = pollObj.getString("endpoint");
                    } else {
                        throw new Exception("Missing poll information");
                    }
                    
                    if (json.has("login")) {
                        loginUrl = json.getString("login");
                    } else {
                        throw new Exception("Missing login URL");
                    }
                    
                    // Open Chrome Custom Tab on UI thread
                    activity.runOnUiThread(() -> {
                        androidx.browser.customtabs.CustomTabsIntent.Builder builder = 
                            new androidx.browser.customtabs.CustomTabsIntent.Builder();
                        androidx.browser.customtabs.CustomTabsIntent customTabsIntent = builder.build();
                        customTabsIntent.launchUrl(activity, android.net.Uri.parse(loginUrl));
                    });
                    
                    // Start polling for credentials
                    pollForCredentials(serverUrl, pollToken, pollEndpoint);
                } else {
                    activity.runOnUiThread(() -> {
                        Toast.makeText(activity, "Failed to initialize login flow", Toast.LENGTH_SHORT).show();
                    });
                }
                
                conn.disconnect();
            } catch (Exception e) {
                android.util.Log.e("NextcloudSync", "Error initializing login flow", e);
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
    
    private void pollForCredentials(String serverUrl, String pollToken, String pollEndpoint) {
        new Thread(() -> {
            int maxAttempts = 120;
            int attempt = 0;
            
            while (attempt < maxAttempts) {
                try {
                    Thread.sleep(1000);
                    
                    java.net.URL url = new java.net.URL(pollEndpoint);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    conn.setDoOutput(true);
                    
                    String postData = "token=" + pollToken;
                    java.io.OutputStream os = conn.getOutputStream();
                    os.write(postData.getBytes());
                    os.flush();
                    os.close();
                    
                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream()));
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        reader.close();
                        
                        org.json.JSONObject json = new org.json.JSONObject(response.toString());
                        String username = json.getString("loginName");
                        String appPassword = json.getString("appPassword");
                        
                        // Update credentials on UI thread
                        activity.runOnUiThread(() -> {
                            this.serverUrl = serverUrl;
                            this.username = username;
                            this.password = appPassword;
                            
                            serverUrlInput.setText(serverUrl);
                            ssoHelper.setCredentials(serverUrl, username, appPassword);
                            updateAccountInfo();
                            saveSettings();
                            
                            Toast.makeText(activity, "Login successful!", Toast.LENGTH_SHORT).show();
                        });
                        break;
                    }
                    
                    conn.disconnect();
                    attempt++;
                } catch (Exception e) {
                    android.util.Log.e("NextcloudSync", "Polling error", e);
                    attempt++;
                }
            }
            
            if (attempt >= maxAttempts) {
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity, "Login timeout", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
    
    private void updateAccountInfo() {
        if (username != null && !username.isEmpty()) {
            String info = activity.getString(R.string.nextcloud_account_selected,
                username, serverUrl);
            accountInfo.setText(info);
            accountInfo.setVisibility(View.VISIBLE);
        } else {
            accountInfo.setText(R.string.nextcloud_no_account);
            accountInfo.setVisibility(View.VISIBLE);
        }
    }
    
    private void updateLastSyncTime() {
        long lastSyncTime = syncService.getLastSyncTime();
        if (lastSyncTime > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());
            String timeStr = sdf.format(new Date(lastSyncTime));
            lastSync.setText(activity.getString(R.string.nextcloud_last_sync, timeStr));
            lastSync.setVisibility(View.VISIBLE);
        } else {
            lastSync.setVisibility(View.GONE);
        }
    }
    
    private void performSync() {
        if (!syncEnabled.isChecked()) {
            Toast.makeText(activity, R.string.nextcloud_sync_disabled, Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            Toast.makeText(activity, R.string.nextcloud_no_account, Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Check if sync is already in progress
        if (syncService.isSyncInProgress()) {
            Toast.makeText(activity, R.string.nextcloud_sync_in_progress, Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Ensure sync service has current credentials before syncing
        ssoHelper.setCredentials(serverUrl, username, password);
        syncService.configureSso(ssoHelper, true);
        
        syncService.performSync();
    }
    
    private void saveSettings() {
        if ((username == null || username.isEmpty()) && syncEnabled.isChecked()) {
            Toast.makeText(activity, R.string.nextcloud_no_account, Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Save credentials
        prefs.edit()
            .putString(PREF_SERVER_URL, serverUrl != null ? serverUrl : "")
            .putString(PREF_USERNAME, username != null ? username : "")
            .putString(PREF_PASSWORD, password != null ? password : "")
            .apply();
        
        // Configure sync service with credentials
        if (username != null && password != null) {
            ssoHelper.setCredentials(serverUrl, username, password);
            syncService.configureSso(ssoHelper, syncEnabled.isChecked());
        }
        
        Toast.makeText(activity, R.string.nextcloud_settings_saved, Toast.LENGTH_SHORT).show();
        dismiss();
    }
    
}
