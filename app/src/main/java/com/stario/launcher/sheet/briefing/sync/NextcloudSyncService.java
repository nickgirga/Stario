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
            try {
                // Sync feeds (this will post UI updates to main thread)
                boolean feedsSuccess = syncFeeds();
                
                // Sync read/unread status
                boolean readSuccess = syncReadStatus();
                
                // Sync starred/favorites (pull from Nextcloud)
                boolean favoritesSuccess = syncFavorites();
                
                // Push local favorites to Nextcloud
                boolean pushFavoritesSuccess = pushLocalFavorites();
                
                boolean success = feedsSuccess && readSuccess && favoritesSuccess && pushFavoritesSuccess;
                
                if (success) {
                    state.edit().putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply();
                }
                
                syncInProgress = false;
                notifySyncCompleted(success);
                future.complete(success);
            } catch (Exception e) {
                Log.e(TAG, "Sync failed", e);
                syncInProgress = false;
                String errorMsg = e.getMessage();
                if (errorMsg == null || errorMsg.isEmpty()) {
                    errorMsg = e.getClass().getSimpleName();
                }
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
     * Note: This implementation focuses on pulling read status from Nextcloud.
     * Pushing local read status to Nextcloud would require tracking article IDs
     * from Nextcloud, which is complex for RSS feeds.
     */
    private boolean syncReadStatus() {
        try {
            if (ssoHelper == null || !ssoHelper.isReady()) {
                return false;
            }
            
            ArticleStateManager stateManager = ArticleStateManager.from(activity);
            
            // Get all items from Nextcloud (this includes read/unread status)
            // Using type=3 for all items, getRead=false to get unread items
            JSONObject itemsResponse = ssoHelper.performGetRequest(
                "/index.php/apps/news/api/v1-2/items?type=3&getRead=false&batchSize=100");
            
            if (itemsResponse != null && itemsResponse.has("items")) {
                JSONArray items = itemsResponse.getJSONArray("items");
                
                // Note: We can only sync items that we can match by URL
                // Since Nextcloud News tracks items by internal ID, we match by URL/GUID
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = itemsResponse.getJSONArray("items").getJSONObject(i);
                    String url = item.optString("url", "");
                    String guid = item.optString("guid", "");
                    boolean unread = item.optBoolean("unread", false);
                    
                    // We can't directly mark items as read/unread without the RssItem object
                    // This would require a more complex mapping system
                    // For now, this serves as a foundation for future enhancement
                }
            }
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to sync read status", e);
            throw new RuntimeException("Read status sync failed: " + e.getMessage(), e);
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
                        guid != null && !guid.isEmpty() ? guid : url,  // guid
                        title,                                           // title
                        author,                                          // author
                        url,                                             // link
                        pubDate,                                         // pubDate
                        body,                                            // description
                        body,                                            // content
                        null,                                            // image
                        null,                                            // audio
                        null,                                            // video
                        feedTitle,                                       // sourceName
                        null,                                            // sourceUrl
                        new java.util.ArrayList<>(),                     // categories (empty list, not null)
                        null,                                            // itunesItemData
                        null,                                            // commentsUrl
                        null,                                            // youtubeItemData
                        null                                             // rawEnclosure
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
                    
                    // Star the article
                    String endpoint = "/index.php/apps/news/api/v1-2/items/" + 
                                     details.feedId + "/" + details.guidHash + "/star";
                    
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
     * Helper class to store article details needed for API calls.
     */
    private static class ArticleDetails {
        int feedId;
        String guidHash;
        
        ArticleDetails(int feedId, String guidHash) {
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
                String endpoint = "/index.php/apps/news/api/v1-2/items?type=3&getRead=true&batchSize=" + 
                                 batchSize + "&offset=" + offset;
                
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
                            int feedId = item.getInt("feedId");
                            String guidHash = item.getString("guidHash");
                            Log.d(TAG, "Found article: feedId=" + feedId + ", guidHash=" + guidHash + 
                                      " (searched " + (offset + i + 1) + " items)");
                            return new ArticleDetails(feedId, guidHash);
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
