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

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.browser.customtabs.CustomTabsIntent;

/**
 * Activity for logging into Nextcloud via Chrome Custom Tabs.
 * Uses the Nextcloud Login Flow v2 for OAuth-based authentication.
 */
public class NextcloudWebLoginActivity extends Activity {
    private static final String TAG = "NextcloudWebLogin";
    public static final String EXTRA_SERVER_URL = "server_url";
    public static final String EXTRA_USERNAME = "username";
    public static final String EXTRA_PASSWORD = "password";
    
    private String serverUrl;
    private String loginUrl;
    private String pollToken;
    private String pollEndpoint;
    
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Get server URL from intent
        serverUrl = getIntent().getStringExtra(EXTRA_SERVER_URL);
        if (serverUrl == null || serverUrl.isEmpty()) {
            Toast.makeText(this, "Server URL is required", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        // Ensure URL has protocol
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "https://" + serverUrl;
        }
        
        // Remove trailing slash
        if (serverUrl.endsWith("/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }
        
        // Initialize Nextcloud Login Flow v2
        initializeLoginFlow();
    }
    
    /**
     * Initializes the Nextcloud Login Flow v2.
     * This creates a login session and opens Chrome Custom Tabs for authentication.
     */
    private void initializeLoginFlow() {
        new Thread(() -> {
            try {
                // Step 1: Initialize login flow
                String initUrl = serverUrl + "/index.php/login/v2";
                java.net.URL url = new java.net.URL(initUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    // Parse response
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    // Parse JSON response
                    String jsonString = response.toString();
                    Log.d(TAG, "API Response: " + jsonString);
                    
                    org.json.JSONObject json = new org.json.JSONObject(jsonString);
                    
                    // Parse poll information
                    if (json.has("poll")) {
                        org.json.JSONObject pollObj = json.getJSONObject("poll");
                        pollToken = pollObj.getString("token");
                        pollEndpoint = pollObj.getString("endpoint");
                    } else {
                        throw new Exception("Missing poll information in response");
                    }
                    
                    // Parse login URL
                    if (json.has("login")) {
                        loginUrl = json.getString("login");
                    } else {
                        throw new Exception("Missing login URL in response");
                    }
                    
                    Log.d(TAG, "Login URL: " + loginUrl);
                    Log.d(TAG, "Poll endpoint: " + pollEndpoint);
                    Log.d(TAG, "Poll token: " + pollToken);
                    Log.d(TAG, "Login flow initialized. Opening Chrome Custom Tab...");
                    
                    // Step 2: Open Chrome Custom Tab for login
                    runOnUiThread(this::openChromeCustomTab);
                    
                    // Step 3: Start polling for credentials
                    startPolling();
                } else {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Failed to initialize login flow", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }
                
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Error initializing login flow", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }).start();
    }
    
    /**
     * Opens Chrome Custom Tab for Nextcloud login.
     */
    private void openChromeCustomTab() {
        CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder();
        CustomTabsIntent customTabsIntent = builder.build();
        customTabsIntent.launchUrl(this, Uri.parse(loginUrl));
    }
    
    /**
     * Polls the Nextcloud server for authentication completion.
     */
    private void startPolling() {
        new Thread(() -> {
            int maxAttempts = 120; // Poll for up to 2 minutes
            int attempt = 0;
            
            while (attempt < maxAttempts) {
                try {
                    Thread.sleep(1000); // Poll every second
                    
                    java.net.URL url = new java.net.URL(pollEndpoint);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    conn.setDoOutput(true);
                    
                    // Send poll token
                    String postData = "token=" + pollToken;
                    java.io.OutputStream os = conn.getOutputStream();
                    os.write(postData.getBytes());
                    os.flush();
                    os.close();
                    
                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        // Authentication successful!
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream()));
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        reader.close();
                        
                        // Parse credentials
                        org.json.JSONObject json = new org.json.JSONObject(response.toString());
                        String username = json.getString("loginName");
                        String appPassword = json.getString("appPassword");
                        
                        Log.d(TAG, "Login successful for user: " + username);
                        
                        // Return credentials
                        runOnUiThread(() -> returnCredentials(username, appPassword));
                        break;
                    }
                    
                    conn.disconnect();
                    attempt++;
                } catch (Exception e) {
                    Log.e(TAG, "Polling error", e);
                    attempt++;
                }
            }
            
            if (attempt >= maxAttempts) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Login timeout", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }).start();
    }
    
    /**
     * Returns the captured credentials to the calling activity.
     */
    private void returnCredentials(String username, String password) {
        Intent result = new Intent();
        result.putExtra(EXTRA_SERVER_URL, serverUrl);
        result.putExtra(EXTRA_USERNAME, username);
        result.putExtra(EXTRA_PASSWORD, password);
        setResult(RESULT_OK, result);
        
        Toast.makeText(this, "Login successful!", Toast.LENGTH_SHORT).show();
        finish();
    }
}
