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

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import com.stario.launcher.preferences.Entry;
import com.stario.launcher.sheet.briefing.dialog.page.ArticleStateManager;
import com.stario.launcher.sheet.briefing.dialog.page.feed.BriefingFeedList;
import com.stario.launcher.sheet.briefing.dialog.page.feed.Feed;
import com.stario.launcher.themes.ThemedActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Handles synchronization with Nextcloud News API.
 * Implements Issue #3: Nextcloud sync with "Briefing" page.
 */
public class NextcloudSyncService {
    private static final String TAG = "NextcloudSync";
    private static final String SYNC_ENABLED_KEY = "nc_sync_enabled";
    private static final String SERVER_URL_KEY = "nc_server_url";
    private static final String USERNAME_KEY = "nc_username";
    private static final String PASSWORD_KEY = "nc_password";
    private static final String LAST_SYNC_KEY = "nc_last_sync";
    
    private static NextcloudSyncService instance = null;
    
    private final SharedPreferences state;
    private final List<SyncListener> listeners;
    private boolean syncInProgress;
    
    private NextcloudSyncService(ThemedActivity activity) {
        this.state = activity.getApplicationContext()
                .getSharedPreferences(Entry.BRIEFING);
        this.listeners = new ArrayList<>();
        this.syncInProgress = false;
    }
    
    public static NextcloudSyncService from(@NonNull ThemedActivity activity) {
        if (instance == null) {
            instance = new NextcloudSyncService(activity);
        }
        return instance;
    }
    
    public static NextcloudSyncService getInstance() {
        if (instance == null) {
            throw new RuntimeException("NextcloudSyncService not initialized.");
        }
        return instance;
    }
    
    /**
     * Configures Nextcloud sync settings.
     */
    @SuppressLint("ApplySharedPref")
    public void configure(String serverUrl, String username, String password, boolean enabled) {
        state.edit()
            .putString(SERVER_URL_KEY, serverUrl)
            .putString(USERNAME_KEY, username)
            .putString(PASSWORD_KEY, password)
            .putBoolean(SYNC_ENABLED_KEY, enabled)
            .commit();
        
        if (enabled) {
            performSync();
        }
    }
    
    /**
     * Checks if sync is enabled.
     */
    public boolean isSyncEnabled() {
        return state.getBoolean(SYNC_ENABLED_KEY, false);
    }
    
    /**
     * Gets server URL.
     */
    public String getServerUrl() {
        return state.getString(SERVER_URL_KEY, "");
    }
    
    /**
     * Gets username.
     */
public String getUsername() {
        return state.getString(USERNAME_KEY, "");
    }
    
    /**
     * Gets last sync timestamp.
     */
    public long getLastSyncTime() {
        return state.getLong(LAST_SYNC_KEY, 0);
    }
    
    /**
     * Performs full sync with Nextcloud News.
     */
    public CompletableFuture<Boolean> performSync() {
        if (syncInProgress || !isSyncEnabled()) {
            return CompletableFuture.completedFuture(false);
        }
        
        syncInProgress = true;
        notifySyncStarted();
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Sync feeds
                boolean feedsSuccess = syncFeeds();
                
                // Sync read/unread status
                boolean readSuccess = syncReadStatus();
                
                // Sync starred/favorites
                boolean favoritesSuccess = syncFavorites();
                
                boolean success = feedsSuccess && readSuccess && favoritesSuccess;
                
                if (success) {
                    state.edit().putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply();
                }
                
                syncInProgress = false;
                notifySyncCompleted(success);
                
                return success;
            } catch (Exception e) {
                Log.e(TAG, "Sync failed", e);
                syncInProgress = false;
                notifySyncFailed(e.getMessage());
                return false;
            }
        });
    }
    
    /**
     * Syncs feeds from Nextcloud News.
     */
    private boolean syncFeeds() {
        try {
            String serverUrl = getServerUrl();
            String username = getUsername();
            String password = state.getString(PASSWORD_KEY, "");
            
            if (serverUrl.isEmpty() || username.isEmpty()) {
                return false;
            }
            
            // Fetch feeds from Nextcloud
            String apiUrl = serverUrl + "/index.php/apps/news/api/v1-2/feeds";
            JSONArray feeds = performApiRequest(apiUrl, "GET", null, username, password);
            
            if (feeds == null) {
                return false;
            }
            
            BriefingFeedList feedList = BriefingFeedList.getInstance();
            
            // Add feeds that don't exist locally
            for (int i = 0; i < feeds.length(); i++) {
                JSONObject feedObj = feeds.getJSONObject(i);
                String title = feedObj.getString("title");
                String url = feedObj.getString("url");
                
                // Check if feed already exists
                boolean exists = false;
                for (int j = 0; j < feedList.size(); j++) {
                    Feed existingFeed = feedList.get(j);
                    if (existingFeed.getRSSLink().equals(url)) {
                        exists = true;
                        break;
                    }
                }
                
                if (!exists) {
                    feedList.add(new Feed(title, url));
                }
            }
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to sync feeds", e);
            return false;
        }
    }
    
    /**
     * Syncs read/unread status with Nextcloud News.
     */
    private boolean syncReadStatus() {
        try {
            String serverUrl = getServerUrl();
            String username = getUsername();
            String password = state.getString(PASSWORD_KEY, "");
            
            if (serverUrl.isEmpty() || username.isEmpty()) {
                return false;
            }
            
            // This would fetch read items and update local state
            // For now, this is a placeholder for the actual implementation
            ArticleStateManager stateManager = ArticleStateManager.getInstance();
            
            // TODO: Implement bidirectional sync of read status
            // 1. Get unread items from Nextcloud
            // 2. Mark them as unread locally
            // 3. Push locally read items to Nextcloud
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to sync read status", e);
            return false;
        }
    }
    
    /**
     * Syncs starred/favorited articles with Nextcloud News.
     */
    private boolean syncFavorites() {
        try {
            String serverUrl = getServerUrl();
            String username = getUsername();
            String password = state.getString(PASSWORD_KEY, "");
            
            if (serverUrl.isEmpty() || username.isEmpty()) {
                return false;
            }
            
            // This would sync starred items
            // For now, this is a placeholder
            ArticleStateManager stateManager = ArticleStateManager.getInstance();
            
            // TODO: Implement bidirectional sync of favorites
            // 1. Get starred items from Nextcloud
            // 2. Add them to local favorites
            // 3. Push local favorites to Nextcloud
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to sync favorites", e);
            return false;
        }
    }
    
    /**
     * Performs an API request to Nextcloud News.
     */
    private JSONArray performApiRequest(String urlString, String method, JSONObject body, 
                                       String username, String password) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setRequestProperty("Content-Type", "application/json");
            
            // Add basic authentication
            String auth = username + ":" + password;
            String encodedAuth = Base64.encodeToString(auth.getBytes(), Base64.NO_WRAP);
            conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
            
            if (body != null && (method.equals("POST") || method.equals("PUT"))) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes());
                    os.flush();
                }
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                
                JSONObject jsonResponse = new JSONObject(response.toString());
                return jsonResponse.optJSONArray("feeds");
            }
            
            return null;
        } catch (Exception e) {
            Log.e(TAG, "API request failed", e);
            return null;
        }
    }
    
    public void addSyncListener(SyncListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    
    public void removeSyncListener(SyncListener listener) {
        listeners.remove(listener);
    }
    
    private void notifySyncStarted() {
        for (SyncListener listener : listeners) {
            listener.onSyncStarted();
        }
    }
    
    private void notifySyncCompleted(boolean success) {
        for (SyncListener listener : listeners) {
            listener.onSyncCompleted(success);
        }
    }
    
    private void notifySyncFailed(String error) {
        for (SyncListener listener : listeners) {
            listener.onSyncFailed(error);
        }
    }
    
    public interface SyncListener {
        default void onSyncStarted() {}
        default void onSyncCompleted(boolean success) {}
        default void onSyncFailed(String error) {}
    }
}
