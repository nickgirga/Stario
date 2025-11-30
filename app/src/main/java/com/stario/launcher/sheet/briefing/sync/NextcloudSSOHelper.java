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

package com.stario.launcher.sheet.briefing.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.GsonBuilder;
import com.nextcloud.android.sso.api.NextcloudAPI;
import com.nextcloud.android.sso.model.SingleSignOnAccount;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Helper class for Nextcloud Single Sign-On authentication and API requests.
 * Simplified version compatible with SSO library 1.3.0.
 */
public class NextcloudSSOHelper {
    private static final String TAG = "NextcloudSSO";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    
    private final Context context;
    private SingleSignOnAccount account;
    private NextcloudAPI nextcloudAPI;
    
    // Direct credentials (for WebView login)
    private String serverUrl;
    private String username;
    private String password;
    
    public NextcloudSSOHelper(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }
    
    /**
     * Sets credentials directly (for WebView-based login).
     */
    public void setCredentials(@NonNull String serverUrl, @NonNull String username, @NonNull String password) {
        this.serverUrl = serverUrl;
        this.username = username;
        this.password = password;
        this.account = null;
        this.nextcloudAPI = null;
        
        Log.d(TAG, "Credentials set for user: " + username + " at " + serverUrl);
    }
    
    /**
     * Sets the SSO account to use for API requests.
     */
    public void setAccount(@Nullable SingleSignOnAccount account) {
        // Clean up existing API if changing accounts
        if (this.nextcloudAPI != null && this.account != null && 
            (account == null || !this.account.name.equals(account.name))) {
            this.nextcloudAPI = null;
        }
        
        this.account = account;
        
        if (account != null) {
            try {
                // Initialize Nextcloud API with the account
                this.nextcloudAPI = new NextcloudAPI(
                    context, 
                    account, 
                    new GsonBuilder().create()
                );
                
                Log.d(TAG, "Nextcloud API initialized for account: " + account.name + " at " + account.url);
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize Nextcloud API", e);
                this.nextcloudAPI = null;
            }
        }
    }
    
    /**
     * Gets the current SSO account.
     */
    @Nullable
    public SingleSignOnAccount getAccount() {
        return account;
    }
    
    /**
     * Performs a GET API request using SSO authentication.
     */
    @NonNull
    public JSONObject performGetRequest(@NonNull String endpoint) throws Exception {
        return performApiRequest(endpoint, "GET", null, null);
    }
    
    /**
     * Performs a POST API request using SSO authentication.
     */
    @NonNull
    public JSONObject performPostRequest(@NonNull String endpoint, @Nullable JSONObject body) throws Exception {
        return performApiRequest(endpoint, "POST", body, null);
    }
    
    /**
     * Performs a PUT API request using SSO authentication.
     */
    @NonNull
    public JSONObject performPutRequest(@NonNull String endpoint, @Nullable JSONObject body) throws Exception {
        return performApiRequest(endpoint, "PUT", body, null);
    }
    
    /**
     * Performs a DELETE API request using SSO authentication.
     */
    @NonNull
    public JSONObject performDeleteRequest(@NonNull String endpoint) throws Exception {
        return performApiRequest(endpoint, "DELETE", null, null);
    }
    
    /**
     * Performs an API request using SSO authentication.
     */
    @NonNull
    public JSONObject performApiRequest(@NonNull String endpoint, @NonNull String method, 
                                       @Nullable JSONObject body, @Nullable Map<String, String> headers) throws Exception {
        // Check if we have direct credentials or SSO account
        String url;
        String authUsername;
        String authPassword;
        
        if (username != null && password != null && serverUrl != null) {
            // Use direct credentials from WebView login
            String normalizedEndpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
            url = serverUrl + normalizedEndpoint;
            authUsername = username;
            authPassword = password;
            
            Log.d(TAG, "Using direct credentials for user: " + username);
        } else if (account != null) {
            // Use SSO account
            if (nextcloudAPI == null) {
                throw new IllegalStateException("Nextcloud API not initialized");
            }
            
            String normalizedEndpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
            url = account.url + normalizedEndpoint;
            authUsername = account.name;
            authPassword = account.token;
            
            if (authPassword == null || authPassword.isEmpty()) {
                throw new IllegalStateException("No authentication token available. Please ensure the Nextcloud account is properly configured in the Nextcloud Files app.");
            }
            
            Log.d(TAG, "Using SSO account for user: " + account.name);
        } else {
            throw new IllegalStateException("No account or credentials configured");
        }
        
        Log.d(TAG, "Performing " + method + " request to: " + url);
        
        // Build the request
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("OCS-APIRequest", "true")
                .addHeader("Accept", "application/json");
        
        // Add custom headers if provided
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                requestBuilder.addHeader(header.getKey(), header.getValue());
            }
        }
        
        // Set the HTTP method and body
        RequestBody requestBody = null;
        if (body != null) {
            requestBody = RequestBody.create(body.toString(), JSON_MEDIA_TYPE);
        } else if ("POST".equals(method) || "PUT".equals(method)) {
            requestBody = RequestBody.create("", JSON_MEDIA_TYPE);
        }
        
        switch (method.toUpperCase()) {
            case "GET":
                requestBuilder.get();
                break;
            case "POST":
                requestBuilder.post(requestBody);
                break;
            case "PUT":
                requestBuilder.put(requestBody);
                break;
            case "DELETE":
                if (requestBody != null) {
                    requestBuilder.delete(requestBody);
                } else {
                    requestBuilder.delete();
                }
                break;
            default:
                throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        }
        
        Request request = requestBuilder.build();
        
        // Use HTTP Basic Authentication
        String credentials = authUsername + ":" + authPassword;
        String basicAuth = "Basic " + android.util.Base64.encodeToString(
            credentials.getBytes(StandardCharsets.UTF_8), 
            android.util.Base64.NO_WRAP
        );
        
        Log.d(TAG, "Using Basic Auth for user: " + authUsername);
        
        OkHttpClient client = new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                Request originalRequest = chain.request();
                Request.Builder builder = originalRequest.newBuilder()
                    .header("Authorization", basicAuth);
                return chain.proceed(builder.build());
            })
            .build();
        
        try (okhttp3.Response response = client.newCall(request).execute()) {
            // Read response body
            String responseBody = "";
            if (response.body() != null) {
                try (InputStream inputStream = response.body().byteStream();
                     BufferedReader reader = new BufferedReader(
                         new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    responseBody = sb.toString();
                }
            }
            
            // Check for HTTP errors
            if (!response.isSuccessful()) {
                String errorMsg = "HTTP " + response.code() + ": " + response.message();
                if (!responseBody.isEmpty()) {
                    try {
                        JSONObject errorJson = new JSONObject(responseBody);
                        if (errorJson.has("message")) {
                            errorMsg += " - " + errorJson.getString("message");
                        }
                    } catch (JSONException ex) {
                        errorMsg += " - " + responseBody;
                    }
                }
                Log.e(TAG, "Request failed: " + errorMsg);
                throw new Exception(errorMsg);
            }
            
            // Parse and return JSON response
            if (responseBody.isEmpty()) {
                return new JSONObject();
            }
            
            // Handle empty array response (some endpoints return [] on success)
            if (responseBody.trim().equals("[]")) {
                Log.d(TAG, "Received empty array response, treating as success");
                return new JSONObject();
            }
            
            try {
                return new JSONObject(responseBody);
            } catch (JSONException ex) {
                Log.e(TAG, "Failed to parse JSON response: " + responseBody);
                throw new Exception("Invalid JSON response: " + ex.getMessage(), ex);
            }
        } catch (Exception e) {
            Log.e(TAG, "Request execution failed", e);
            throw new Exception("Request failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Performs a paginated API request, automatically fetching all pages.
     */
    @NonNull
    public JSONArray performPaginatedRequest(@NonNull String endpoint, @NonNull String itemsKey, 
                                            int batchSize) throws Exception {
        JSONArray allItems = new JSONArray();
        int offset = 0;
        boolean hasMore = true;
        
        while (hasMore) {
            String separator = endpoint.contains("?") ? "&" : "?";
            String paginatedEndpoint = endpoint + separator + "batchSize=" + batchSize + "&offset=" + offset;
            
            JSONObject response = performGetRequest(paginatedEndpoint);
            
            if (response.has(itemsKey)) {
                JSONArray items = response.getJSONArray(itemsKey);
                
                for (int i = 0; i < items.length(); i++) {
                    allItems.put(items.get(i));
                }
                
                hasMore = items.length() == batchSize;
                offset += items.length();
                
                Log.d(TAG, "Fetched " + items.length() + " items, total: " + allItems.length());
            } else {
                hasMore = false;
            }
        }
        
        return allItems;
    }
    
    /**
     * Checks if an SSO account or direct credentials are configured.
     */
    public boolean hasAccount() {
        return account != null || (username != null && password != null && serverUrl != null);
    }
    
    /**
     * Checks if the API is properly initialized and ready for requests.
     */
    public boolean isReady() {
        return (account != null && nextcloudAPI != null) || 
               (username != null && password != null && serverUrl != null);
    }
    
    /**
     * Gets the server URL from the current account or direct credentials.
     */
    @NonNull
    public String getServerUrl() {
        if (serverUrl != null) {
            return serverUrl;
        }
        return account != null ? account.url : "";
    }
    
    /**
     * Gets the username from the current account or direct credentials.
     */
    @NonNull
    public String getUsername() {
        if (username != null) {
            return username;
        }
        return account != null ? account.name : "";
    }
    
    /**
     * Clears the current account and cleans up resources.
     */
    public void clearAccount() {
        this.account = null;
        this.nextcloudAPI = null;
        this.serverUrl = null;
        this.username = null;
        this.password = null;
    }
    
    /**
     * Tests the connection to the Nextcloud server.
     */
    public boolean testConnection() {
        if (!isReady()) {
            return false;
        }
        
        try {
            JSONObject response = performGetRequest("/ocs/v2.php/cloud/capabilities?format=json");
            return response.has("ocs");
        } catch (Exception e) {
            Log.e(TAG, "Connection test failed", e);
            return false;
        }
    }
    
    /**
     * Gets the Nextcloud API instance for advanced usage.
     */
    @Nullable
    public NextcloudAPI getNextcloudAPI() {
        return nextcloudAPI;
    }
}
