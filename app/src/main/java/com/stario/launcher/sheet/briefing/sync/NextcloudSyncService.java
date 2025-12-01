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
import com.stario.launcher.sheet.briefing.dialog.page.feed.CategoryManager;
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
    private static final String LAST_SYNC_KEY = "nc_last_sync";

    private static NextcloudSyncService instance = null;

    private final SharedPreferences state;
    private final List<SyncListener> listeners;
    private ThemedActivity activity;
    private NextcloudSSOHelper ssoHelper;
    private boolean syncInProgress;

    private NextcloudSyncService(ThemedActivity activity) {
        this.activity = activity;
        this.state = activity.getApplicationContext()
                .getSharedPreferences(Entry.BRIEFING);
        this.listeners = new ArrayList<>();
        this.syncInProgress = false;

        // Load saved credentials on initialization
        loadSavedCredentials();
    }

    /**
     * Loads saved Nextcloud credentials from SharedPreferences.
     */
    private void loadSavedCredentials() {
        SharedPreferences prefs = activity.getApplicationContext()
                .getSharedPreferences("nextcloud_sync", android.content.Context.MODE_PRIVATE);
        String serverUrl = prefs.getString("nc_server_url", "");
        String username = prefs.getString("nc_username", "");
        String password = prefs.getString("nc_password", "");

        if (!serverUrl.isEmpty() && !username.isEmpty() && !password.isEmpty()) {
            this.ssoHelper = new NextcloudSSOHelper(activity);
            this.ssoHelper.setCredentials(serverUrl, username, password);
            Log.d(TAG, "Loaded saved credentials for: " + username);
        }
    }

    public static NextcloudSyncService from(@NonNull ThemedActivity activity) {
        if (instance == null) {
            instance = new NextcloudSyncService(activity);
        } else {
            // Update activity reference if it changed
            instance.activity = activity;
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
     * Configures Nextcloud sync with SSO.
     */
    @SuppressLint("ApplySharedPref")
    public void configureSso(NextcloudSSOHelper ssoHelper, boolean enabled) {
        this.ssoHelper = ssoHelper;
        state.edit()
                .putBoolean(SYNC_ENABLED_KEY, enabled)
                .commit();

        if (enabled && ssoHelper != null && ssoHelper.hasAccount()) {
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
     * Gets server URL from SSO helper.
     */
    public String getServerUrl() {
        return ssoHelper != null ? ssoHelper.getServerUrl() : "";
    }

    /**
     * Gets username from SSO helper.
     */
    public String getUsername() {
        return ssoHelper != null ? ssoHelper.getUsername() : "";
    }

    /**
     * Gets last sync timestamp.
     */
    public long getLastSyncTime() {
        return state.getLong(LAST_SYNC_KEY, 0);
    }

    /**
     * Checks if a sync is currently in progress.
     */
    public boolean isSyncInProgress() {
        return syncInProgress;
    }

    /**
     * Performs full sync with Nextcloud News.
     */
    public CompletableFuture<Boolean> performSync() {
        if (syncInProgress) {
            Log.d(TAG, "Sync already in progress, skipping");
            return CompletableFuture.completedFuture(false);
        }

        if (!isSyncEnabled()) {
            Log.d(TAG, "Sync not enabled, skipping");
            return CompletableFuture.completedFuture(false);
        }

        if (ssoHelper == null || !ssoHelper.isReady()) {
            Log.d(TAG, "SSO helper not ready, skipping sync");
            return CompletableFuture.completedFuture(false);
        }

        syncInProgress = true;
        notifySyncStarted();

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Run sync in background thread
        new Thread(() -> {
            boolean feedsSuccess = false;
            boolean readSuccess = false;
            boolean favoritesSuccess = false;
            boolean pushFavoritesSuccess = false;
            boolean pushReadStatusSuccess = false;
            
            try {
                Log.d(TAG, "Starting Nextcloud sync...");
                
                // Sync feeds (this will post UI updates to main thread)
                feedsSuccess = syncFeeds();
                Log.d(TAG, "Feed sync " + (feedsSuccess ? "succeeded" : "failed"));

                // Sync read/unread status
                readSuccess = syncReadStatus();
                Log.d(TAG, "Read status sync " + (readSuccess ? "succeeded" : "failed"));

                // Sync starred/favorites (pull from Nextcloud)
                favoritesSuccess = syncFavorites();
                Log.d(TAG, "Favorites sync " + (favoritesSuccess ? "succeeded" : "failed"));

                // Push local favorites to Nextcloud
                pushFavoritesSuccess = pushLocalFavorites();
                Log.d(TAG, "Push favorites " + (pushFavoritesSuccess ? "succeeded" : "failed"));
                
                // Push local read status to Nextcloud
                pushReadStatusSuccess = pushLocalReadStatus();
                Log.d(TAG, "Push read status " + (pushReadStatusSuccess ? "succeeded" : "failed"));

                boolean success = feedsSuccess && readSuccess && favoritesSuccess && pushFavoritesSuccess && pushReadStatusSuccess;
                if (success) {
                    state.edit().putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply();
                    Log.d(TAG, "Nextcloud sync completed successfully");
                } else {
                    Log.w(TAG, "Nextcloud sync completed with some failures");
                }

                syncInProgress = false;
                notifySyncCompleted(success);
                future.complete(success);
            } catch (Exception e) {
                Log.e(TAG, "Sync failed with exception", e);
                syncInProgress = false;
                String errorMsg = e.getMessage();
                if (errorMsg == null || errorMsg.isEmpty()) {
                    errorMsg = e.getClass().getSimpleName() + ": " + e.getCause();
                }
                Log.e(TAG, "Sync error details: " + errorMsg);
                notifySyncFailed(errorMsg);
                future.complete(false);
            }
        }).start();

        return future;
    }

    /**
     * Syncs feeds and folders/categories from Nextcloud News.
     */
    private boolean syncFeeds() {
        try {
            if (ssoHelper == null || !ssoHelper.isReady()) {
                throw new IllegalStateException("SSO account not configured or not ready");
            }

            String serverUrl = ssoHelper.getServerUrl();
            Log.d(TAG, "Starting feed sync from: " + serverUrl);

            // First, sync folders (categories)
            JSONObject foldersResponse = ssoHelper.performGetRequest(
                    "/index.php/apps/news/api/v1-2/folders");
            if (foldersResponse != null && foldersResponse.has("folders")) {
                JSONArray folders = foldersResponse.getJSONArray("folders");
                Log.d(TAG, "Found " + folders.length() + " folders");

                // Add categories from Nextcloud on UI thread
                for (int i = 0; i < folders.length(); i++) {
                    JSONObject folder = folders.getJSONObject(i);
                    String folderName = folder.getString("name");
                    Log.d(TAG, "Adding category: " + folderName);

                    // Post to UI thread
                    final String categoryName = folderName;
                    activity.runOnUiThread(() -> {
                        CategoryManager categoryManager = CategoryManager.from(activity);
                        categoryManager.addCategory(categoryName);
                    });
                }

                // Wait a bit for categories to be added
                Thread.sleep(500);
            } else {
                Log.d(TAG, "No folders found or folders response is null");
            }

            // Fetch feeds from Nextcloud
            JSONObject feedsResponse = ssoHelper.performGetRequest(
                    "/index.php/apps/news/api/v1-2/feeds");
            if (feedsResponse == null || !feedsResponse.has("feeds")) {
                Log.e(TAG, "Feeds response is null or has no feeds");
                return false;
            }

            JSONArray feeds = feedsResponse.getJSONArray("feeds");
            Log.d(TAG, "Found " + feeds.length() + " feeds");

            // Add feeds that don't exist locally
            for (int i = 0; i < feeds.length(); i++) {
                JSONObject feedObj = feeds.getJSONObject(i);
                String title = feedObj.getString("title");
                String url = feedObj.getString("url");
                int folderId = feedObj.optInt("folderId", 0);

                Log.d(TAG, "Processing feed: " + title + " (" + url + ")");

                // Map folder ID to category name
                String category = null;
                if (folderId > 0 && foldersResponse != null && foldersResponse.has("folders")) {
                    JSONArray folders = foldersResponse.getJSONArray("folders");
                    for (int j = 0; j < folders.length(); j++) {
                        JSONObject folder = folders.getJSONObject(j);
                        if (folder.getInt("id") == folderId) {
                            category = folder.getString("name");
                            Log.d(TAG, "Feed belongs to category: " + category);
                            break;
                        }
                    }
                }

                // Prepare feed data for UI thread
                final String feedTitle = title;
                final String feedUrl = url;
                final String feedCategory = category;

                // Post feed operations to UI thread
                activity.runOnUiThread(() -> {
                    BriefingFeedList feedList = BriefingFeedList.from(activity);

                    // Check if feed already exists
                    boolean exists = false;
                    Feed existingFeed = null;

                    // Check visible feeds
                    for (int j = 0; j < feedList.size(); j++) {
                        Feed feed = feedList.get(j);
                        if (feed.getRSSLink().equals(feedUrl)) {
                            exists = true;
                            existingFeed = feed;
                            break;
                        }
                    }

                    // Also check hidden feeds in allFeedObjects
                    if (!exists) {
                        for (Feed feed : feedList.getAllFeeds()) {
                            if (feed.getRSSLink().equals(feedUrl)) {
                                exists = true;
                                existingFeed = feed;
                                break;
                            }
                        }
                    }

                    if (exists && existingFeed != null) {
                        // Update category if it changed
                        String oldCategory = existingFeed.getCategory();
                        if ((feedCategory == null && oldCategory != null) ||
                                (feedCategory != null && !feedCategory.equals(oldCategory))) {
                            Log.d(TAG, "Updating feed category from '" + oldCategory + "' to '" + feedCategory + "'");
                            existingFeed.setCategory(feedCategory);
                            feedList.updateCategory(existingFeed, oldCategory);
                        }
                    } else {
                        Log.d(TAG, "Adding new feed: " + feedTitle);
                        feedList.add(new Feed(feedTitle, feedUrl, feedCategory, true));
                    }
                });
            }

            // Wait for UI operations to complete
            Thread.sleep(1000);

            Log.d(TAG, "Feed sync complete");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to sync feeds", e);
            throw new RuntimeException("Feed sync failed: " + e.getMessage(), e);
        }
    }

    /**
     * Syncs read/unread status with Nextcloud News.
     * Pulls read status from Nextcloud and applies it locally.
     * Pushes local read status changes to Nextcloud when sync is enabled.
     */
    private boolean syncReadStatus() {
        try {
            if (ssoHelper == null || !ssoHelper.isReady()) {
                return false;
            }
            
            // Get all items from Nextcloud (this includes read/unread status)
            // Using type=3 for all items, getRead=true to get all items with their read status
            JSONObject itemsResponse = ssoHelper.performGetRequest(
                    "/index.php/apps/news/api/v1-2/items?type=3&getRead=true&batchSize=200");
            if (itemsResponse != null && itemsResponse.has("items")) {
                JSONArray items = itemsResponse.getJSONArray("items");
                Log.d(TAG, "Processing " + items.length() + " items for read status sync");
                
                // Collect status changes to apply on UI thread
                List<StatusChange> statusChanges = new java.util.ArrayList<>();
                
                // Process each item from Nextcloud on background thread
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    String url = item.optString("url", "");
                    String guid = item.optString("guid", "");
                    boolean unread = item.optBoolean("unread", true); // Default to unread if not specified
                    
                    // Only process items with valid URLs or GUIDs
                    if ((url != null && !url.isEmpty()) || (guid != null && !guid.isEmpty())) {
                        String articleId = guid != null && !guid.isEmpty() ? guid : url;
                        statusChanges.add(new StatusChange(articleId, url, guid, unread));
                    }
                }
                
                // Apply status changes on UI thread
                if (!statusChanges.isEmpty()) {
                    activity.runOnUiThread(() -> {
                        try {
                            ArticleStateManager stateManager = ArticleStateManager.from(activity);
                            
                            for (StatusChange change : statusChanges) {
                                // Create a synthetic RssItem for status management
                                com.prof18.rssparser.model.RssItem rssItem = new com.prof18.rssparser.model.RssItem(
                                    change.guid != null && !change.guid.isEmpty() ? change.guid : change.url, // guid
                                    "", // title
                                    null, // author
                                    change.url, // link
                                    null, // pubDate
                                    null, // description
                                    null, // content
                                    null, // image
                                    null, // audio
                                    null, // video
                                    null, // sourceName
                                    null, // sourceUrl
                                    new java.util.ArrayList<>(), // categories
                                    null, // itunesItemData
                                    null, // commentsUrl
                                    null, // youtubeItemData
                                    null // rawEnclosure
                                );
                                
                                // Apply read status locally
                                if (change.unread && stateManager.isRead(rssItem)) {
                                    // Item is unread in Nextcloud but marked as read locally
                                    // Mark as unread locally to match Nextcloud
                                    stateManager.markAsUnread(rssItem);
                                    Log.d(TAG, "Marked article as unread to match Nextcloud: " + (change.url != null ? change.url : change.guid));
                                } else if (!change.unread && !stateManager.isRead(rssItem)) {
                                    // Item is read in Nextcloud but marked as unread locally
                                    // Mark as read locally to match Nextcloud
                                    stateManager.markAsRead(rssItem);
                                    Log.d(TAG, "Marked article as read to match Nextcloud: " + (change.url != null ? change.url : change.guid));
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to apply read status changes on UI thread", e);
                        }
                    });
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to sync read status", e);
            throw new RuntimeException("Read status sync failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Helper class to store status change information.
     */
    private static class StatusChange {
        final String articleId;
        final String url;
        final String guid;
        final boolean unread;
        
        StatusChange(String articleId, String url, String guid, boolean unread) {
            this.articleId = articleId;
            this.url = url;
            this.guid = guid;
            this.unread = unread;
        }
    }

/**
     * Pushes local read status changes to Nextcloud News.
     * This ensures that read/unread status changes made locally are synced to the server.
     */
    private boolean pushLocalReadStatus() {
        try {
            if (ssoHelper == null || !ssoHelper.isReady()) {
                Log.w(TAG, "SSO helper not ready for read status push");
                return false;
            }
            
            // Get all articles from Nextcloud to compare with local state
            JSONObject itemsResponse = ssoHelper.performGetRequest(
                    "/index.php/apps/news/api/v1-2/items?type=3&getRead=true&batchSize=500");
            if (itemsResponse == null || !itemsResponse.has("items")) {
                Log.w(TAG, "No items response from Nextcloud for read status comparison");
                return true; // Not a failure, just no items to compare
            }

            JSONArray items = itemsResponse.getJSONArray("items");
            Log.d(TAG, "Comparing read status for " + items.length() + " items");

            // Collect status comparisons to process on UI thread
            List<ReadStatusComparison> comparisons = new java.util.ArrayList<>();

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                int itemId = item.getInt("id");
                String url = item.optString("url", "");
                String guid = item.optString("guid", "");
                boolean nextcloudUnread = item.optBoolean("unread", true);

                String articleId = url != null && !url.isEmpty() ? url : guid;
                if (articleId == null || articleId.isEmpty()) {
                    continue; // Skip items without valid identifiers
                }

                comparisons.add(new ReadStatusComparison(itemId, url, guid, nextcloudUnread));
            }

            // Check local status on UI thread and push changes
            final int[] pushedChanges = {0};
            final int[] failedChanges = {0};

            if (!comparisons.isEmpty()) {
                activity.runOnUiThread(() -> {
                    try {
                        ArticleStateManager stateManager = ArticleStateManager.from(activity);
                        
                        for (ReadStatusComparison comparison : comparisons) {
                            // Create a synthetic RssItem to check local status
                            com.prof18.rssparser.model.RssItem rssItem = new com.prof18.rssparser.model.RssItem(
                                comparison.guid != null && !comparison.guid.isEmpty() ? comparison.guid : comparison.url, // guid
                                "", // title
                                null, // author
                                comparison.url, // link
                                null, // pubDate
                                null, // description
                                null, // content
                                null, // image
                                null, // audio
                                null, // video
                                null, // sourceName
                                null, // sourceUrl
                                new java.util.ArrayList<>(), // categories
                                null, // itunesItemData
                                null, // commentsUrl
                                null, // youtubeItemData
                                null // rawEnclosure
                            );

                            boolean localRead = stateManager.isRead(rssItem);

                            // If local state differs from Nextcloud, push change on background thread
                            if (localRead && comparison.nextcloudUnread) {
                                // Local is read, Nextcloud is unread -> mark as read on Nextcloud
                                pushReadChangeAsync(comparison.itemId, true, pushedChanges, failedChanges);
                            } else if (!localRead && !comparison.nextcloudUnread) {
                                // Local is unread, Nextcloud is read -> mark as unread on Nextcloud
                                pushReadChangeAsync(comparison.itemId, false, pushedChanges, failedChanges);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to compare read status on UI thread", e);
                        failedChanges[0]++;
                    }
                });
            }

            // Wait a bit for async operations to complete
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            Log.d(TAG, "Read status push completed: " + pushedChanges[0] + " pushed, " + failedChanges[0] + " failed");
            return failedChanges[0] == 0; // Return true only if all succeeded
        } catch (Exception e) {
            Log.e(TAG, "Failed to push local read status", e);
            return false;
        }
    }
    
    /**
     * Pushes a read status change asynchronously.
     */
    private void pushReadChangeAsync(int itemId, boolean isRead, int[] pushedChanges, int[] failedChanges) {
        new Thread(() -> {
            try {
                String endpoint = isRead ? 
                    "/index.php/apps/news/api/v1-2/items/" + itemId + "/read" :
                    "/index.php/apps/news/api/v1-2/items/" + itemId + "/unread";
                
                ssoHelper.performPutRequest(endpoint, null);
                synchronized (pushedChanges) {
                    pushedChanges[0]++;
                }
                Log.d(TAG, "Pushed read status for item " + itemId + ": " + (isRead ? "read" : "unread"));
            } catch (Exception e) {
                synchronized (failedChanges) {
                    failedChanges[0]++;
                }
                Log.e(TAG, "Failed to push read status for item " + itemId, e);
            }
        }).start();
    }
    
    /**
     * Helper class to store read status comparison information.
     */
    private static class ReadStatusComparison {
        final int itemId;
        final String url;
        final String guid;
        final boolean nextcloudUnread;
        
        ReadStatusComparison(int itemId, String url, String guid, boolean nextcloudUnread) {
            this.itemId = itemId;
            this.url = url;
            this.guid = guid;
            this.nextcloudUnread = nextcloudUnread;
        }
    }

            ArticleStateManager stateManager = ArticleStateManager.from(activity);
            
            // Get all articles from Nextcloud to compare with local state
            JSONObject itemsResponse = ssoHelper.performGetRequest(
                    "/index.php/apps/news/api/v1-2/items?type=3&getRead=true&batchSize=500");
            if (itemsResponse == null || !itemsResponse.has("items")) {
                Log.w(TAG, "No items response from Nextcloud for read status comparison");
                return true; // Not a failure, just no items to compare
            }

            JSONArray items = itemsResponse.getJSONArray("items");
            Log.d(TAG, "Comparing read status for " + items.length() + " items");

            int pushedChanges = 0;
            int failedChanges = 0;

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                int itemId = item.getInt("id");
                String url = item.optString("url", "");
                String guid = item.optString("guid", "");
                boolean nextcloudUnread = item.optBoolean("unread", true);

                // Create a synthetic RssItem to check local status
                String articleId = url != null && !url.isEmpty() ? url : guid;
                if (articleId == null || articleId.isEmpty()) {
                    continue; // Skip items without valid identifiers
                }

                com.prof18.rssparser.model.RssItem rssItem = new com.prof18.rssparser.model.RssItem(
                    guid != null && !guid.isEmpty() ? guid : url, // guid
                    "", // title
                    null, // author
                    url, // link
                    null, // pubDate
                    null, // description
                    null, // content
                    null, // image
                    null, // audio
                    null, // video
                    null, // sourceName
                    null, // sourceUrl
                    new java.util.ArrayList<>(), // categories
                    null, // itunesItemData
                    null, // commentsUrl
                    null, // youtubeItemData
                    null // rawEnclosure
                );

                boolean localRead = stateManager.isRead(rssItem);

                // If local state differs from Nextcloud, push the change
                if (localRead && nextcloudUnread) {
                    // Local is read, Nextcloud is unread -> mark as read on Nextcloud
                    try {
                        String endpoint = "/index.php/apps/news/api/v1-2/items/" + itemId + "/read";
                        ssoHelper.performPutRequest(endpoint, null);
                        pushedChanges++;
                        Log.d(TAG, "Pushed read status for item " + itemId + ": read");
                    } catch (Exception e) {
                        failedChanges++;
                        Log.e(TAG, "Failed to push read status for item " + itemId, e);
                    }
                } else if (!localRead && !nextcloudUnread) {
                    // Local is unread, Nextcloud is read -> mark as unread on Nextcloud
                    try {
                        String endpoint = "/index.php/apps/news/api/v1-2/items/" + itemId + "/unread";
                        ssoHelper.performPutRequest(endpoint, null);
                        pushedChanges++;
                        Log.d(TAG, "Pushed read status for item " + itemId + ": unread");
                    } catch (Exception e) {
                        failedChanges++;
                        Log.e(TAG, "Failed to push unread status for item " + itemId, e);
                    }
                }
            }

            Log.d(TAG, "Read status push completed: " + pushedChanges + " pushed, " + failedChanges + " failed");
            return failedChanges == 0; // Return true only if all succeeded
        } catch (Exception e) {
            Log.e(TAG, "Failed to push local read status", e);
            return false;
        }
    }

    /**
     * Syncs starred/favorited articles with Nextcloud News.
     * Pulls starred items from Nextcloud and adds them to local favorites.
     */
    private boolean syncFavorites() {
        try {
            if (ssoHelper == null || !ssoHelper.isReady()) {
                return false;
            }

            ArticleStateManager stateManager = ArticleStateManager.from(activity);

            // Get starred items from Nextcloud using pagination
            // Using type=2 for starred items
            JSONArray items = ssoHelper.performPaginatedRequest(
                    "/index.php/apps/news/api/v1-2/items?type=2&getRead=true",
                    "items",
                    100);
            if (items != null && items.length() > 0) {
                // Get feed information to map feed IDs to feed titles
                JSONObject feedsResponse = ssoHelper.performGetRequest(
                        "/index.php/apps/news/api/v1-2/feeds");

                Log.d(TAG, "Processing " + items.length() + " starred items");

                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);

                    // Extract article information
                    String title = item.optString("title", "");
                    String url = item.optString("url", "");
                    String guid = item.optString("guid", "");
                    String body = item.optString("body", "");
                    String author = item.optString("author", "");
                    String pubDate = item.optString("pubDate", "");
                    int feedId = item.optInt("feedId", 0);

                    // Find feed title
                    String feedTitle = "Unknown Feed";
                    if (feedsResponse != null && feedsResponse.has("feeds")) {
                        JSONArray feeds = feedsResponse.getJSONArray("feeds");
                        for (int j = 0; j < feeds.length(); j++) {
                            JSONObject feed = feeds.getJSONObject(j);
                            if (feed.getInt("id") == feedId) {
                                feedTitle = feed.getString("title");
                                break;
                            }
                        }
                    }

                    // Create a synthetic RssItem for the favorite
                    // Note: This is a simplified approach. A more robust solution would
                    // involve creating a proper RssItem with all fields populated
                    com.prof18.rssparser.model.RssItem rssItem = new com.prof18.rssparser.model.RssItem(
                            guid != null && !guid.isEmpty() ? guid : url, // guid
                            title, // title
                            author, // author
                            url, // link
                            pubDate, // pubDate
                            body, // description
                            body, // content
                            null, // image
                            null, // audio
                            null, // video
                            feedTitle, // sourceName
                            null, // sourceUrl
                            new java.util.ArrayList<>(), // categories (empty list, not null)
                            null, // itunesItemData
                            null, // commentsUrl
                            null, // youtubeItemData
                            null // rawEnclosure
                    );

                    // Add to favorites if not already favorited
                    if (!stateManager.isFavorite(rssItem)) {
                        stateManager.toggleFavorite(rssItem, feedTitle);
                    }
                }
            }

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to sync favorites", e);
            throw new RuntimeException("Favorites sync failed: " + e.getMessage(), e);
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

    /**
     * Pushes all local favorites to Nextcloud News during sync.
     * This ensures that favorites added locally are synced to the server.
     */
    private boolean pushLocalFavorites() {
        try {
            if (ssoHelper == null || !ssoHelper.isReady()) {
                return false;
            }

            ArticleStateManager stateManager = ArticleStateManager.from(activity);
            List<ArticleStateManager.FavoriteArticle> localFavorites = stateManager.getFavorites();

            if (localFavorites.isEmpty()) {
                Log.d(TAG, "No local favorites to push");
                return true;
            }

            Log.d(TAG, "Pushing " + localFavorites.size() + " local favorites to Nextcloud");

            int successCount = 0;
            int failCount = 0;

            for (ArticleStateManager.FavoriteArticle favorite : localFavorites) {
                try {
                    // Find the article details by URL
                    ArticleDetails details = findArticleDetailsByUrl(favorite.articleId);
                    if (details == null) {
                        Log.w(TAG, "Could not find Nextcloud article for favorite: " + favorite.title);
                        failCount++;
                        continue;
                    }

                    // Star the article (star endpoint still uses feedId/guidHash)
                    String endpoint = "/index.php/apps/news/api/v1-2/items/" + details.feedId + "/" + details.guidHash + "/star";
                    ssoHelper.performPutRequest(endpoint, null);

                    successCount++;
                    Log.d(TAG, "Successfully starred: " + favorite.title);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to push favorite: " + favorite.title, e);
                    failCount++;
                }
            }

            Log.d(TAG, "Pushed favorites: " + successCount + " succeeded, " + failCount + " failed");
            return failCount == 0; // Return true only if all succeeded
        } catch (Exception e) {
            Log.e(TAG, "Failed to push local favorites", e);
            return false;
        }
    }

    /**
     * Pushes a favorite/star change to Nextcloud News.
     * @param articleUrl The URL of the article
     * @param isFavorite True to star, false to unstar
     */
    public void pushFavoriteToNextcloud(String articleUrl, boolean isFavorite) {
        if (ssoHelper == null || !ssoHelper.isReady() || !isSyncEnabled()) {
            Log.d(TAG, "Sync not enabled or not ready, skipping favorite push");
            return;
        }

        new Thread(() -> {
            try {
                // First, try to find the article details by URL
                ArticleDetails details = findArticleDetailsByUrl(articleUrl);
                if (details == null) {
                    Log.w(TAG, "Could not find Nextcloud article for URL: " + articleUrl);
                    return;
                }

                // Star or unstar the article using the correct API endpoints
                // The Nextcloud News API v1-2 uses: PUT /items/{feedId}/{guidHash}/star or /unstar
                String endpoint;
                if (isFavorite) {
                    endpoint = "/index.php/apps/news/api/v1-2/items/" + details.feedId + "/" + details.guidHash + "/star";
                } else {
                    endpoint = "/index.php/apps/news/api/v1-2/items/" + details.feedId + "/" + details.guidHash + "/unstar";
                }

                Log.d(TAG, "Attempting to " + (isFavorite ? "star" : "unstar") + " article at: " + endpoint);

                // Use PUT request with null body (no body needed for star/unstar)
                ssoHelper.performPutRequest(endpoint, null);

                Log.d(TAG, "Successfully " + (isFavorite ? "starred" : "unstarred") + " article");
            } catch (Exception e) {
                Log.e(TAG, "Failed to push favorite to Nextcloud", e);
            }
        }).start();
    }

    /**
     * Pushes a read/unread status change to Nextcloud News.
     * @param articleUrl The URL of the article
     * @param isRead True to mark as read, false to mark as unread
     */
    public void pushReadStatusToNextcloud(String articleUrl, boolean isRead) {
        if (ssoHelper == null || !ssoHelper.isReady() || !isSyncEnabled()) {
            Log.d(TAG, "Sync not enabled or not ready, skipping read status push");
            return;
        }

        new Thread(() -> {
            try {
                // First, try to find the article details by URL to get the itemId
                ArticleDetails details = findArticleDetailsByUrl(articleUrl);
                if (details == null) {
                    Log.w(TAG, "Could not find Nextcloud article for URL: " + articleUrl);
                    return;
                }

                // Mark as read or unread using the correct API endpoints
                // The Nextcloud News API v1-2 uses: PUT /items/{itemId}/read or /unread
                String endpoint;
                if (isRead) {
                    endpoint = "/index.php/apps/news/api/v1-2/items/" + details.itemId + "/read";
                } else {
                    endpoint = "/index.php/apps/news/api/v1-2/items/" + details.itemId + "/unread";
                }

                Log.d(TAG, "Attempting to mark article as " + (isRead ? "read" : "unread") + " at: " + endpoint);

                // Use PUT request with null body (no body needed for read/unread)
                ssoHelper.performPutRequest(endpoint, null);

                Log.d(TAG, "Successfully marked article as " + (isRead ? "read" : "unread"));
            } catch (Exception e) {
                Log.e(TAG, "Failed to push read status to Nextcloud", e);
            }
        }).start();
    }

    /**
     * Helper class to store article details needed for API calls.
     */
    private static class ArticleDetails {
        int itemId;
        int feedId;
        String guidHash;

        ArticleDetails(int itemId, int feedId, String guidHash) {
            this.itemId = itemId;
            this.feedId = feedId;
            this.guidHash = guidHash;
        }
    }

    /**
     * Finds a Nextcloud article details by its URL.
     * @param articleUrl The article URL to search for
     * @return The article details (feedId and guidHash), or null if not found
     */
    private ArticleDetails findArticleDetailsByUrl(String articleUrl) {
        try {
            // Use pagination to search through more items
            // Start with a larger batch size and search up to 1000 items
            int batchSize = 500;
            int maxItems = 1000;
            int offset = 0;

            while (offset < maxItems) {
                String endpoint = "/index.php/apps/news/api/v1-2/items?type=3&getRead=true&batchSize=" + batchSize + "&offset=" + offset;
                JSONObject itemsResponse = ssoHelper.performGetRequest(endpoint);

                if (itemsResponse != null && itemsResponse.has("items")) {
                    JSONArray items = itemsResponse.getJSONArray("items");
                    if (items.length() == 0) {
                        // No more items to search
                        break;
                    }

                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        String itemUrl = item.optString("url", "");
                        String itemGuid = item.optString("guid", "");

                        // Match by URL or GUID
                        if (articleUrl.equals(itemUrl) || articleUrl.equals(itemGuid)) {
                            int itemId = item.getInt("id");
                            int feedId = item.getInt("feedId");
                            String guidHash = item.getString("guidHash");
                            Log.d(TAG, "Found article: itemId=" + itemId + ", feedId=" + feedId + ", guidHash=" + guidHash + " (searched " + (offset + i + 1) + " items)");
                            return new ArticleDetails(itemId, feedId, guidHash);
                        }
                    }

                    // Move to next batch
                    offset += items.length();

                    // If we got fewer items than requested, we've reached the end
                    if (items.length() < batchSize) {
                        break;
                    }
                } else {
                    break;
                }
            }

            Log.d(TAG, "Article not found in Nextcloud after searching " + offset + " items: " + articleUrl);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error finding article details by URL", e);
            return null;
        }
    }

    public interface SyncListener {
        default void onSyncStarted() {}
        default void onSyncCompleted(boolean success) {}
        default void onSyncFailed(String error) {}
    }
}
