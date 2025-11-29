/*
 * Copyright (C) 2025 Răzvan Albu
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

package com.stario.launcher.sheet.briefing.dialog.page.feed;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.stario.launcher.activities.settings.Settings;
import com.stario.launcher.preferences.Entry;
import com.stario.launcher.sheet.briefing.dialog.page.ArticleStateManager;
import com.stario.launcher.themes.ThemedActivity;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public class BriefingFeedList implements ArticleStateManager.StateChangeListener {
    private static final String FEEDS_KEY = "com.stario.FEEDS";
    private static BriefingFeedList instance = null;

    private final List<FeedListener> listeners;
    private final SharedPreferences state;
    private final List<Feed> items;
    private final ArticleStateManager stateManager;

    private BriefingFeedList(ThemedActivity activity) {
        this.items = new ArrayList<>();
        this.listeners = new ArrayList<>();
        this.state = activity.getApplicationContext()
                .getSharedPreferences(Entry.BRIEFING);
        this.stateManager = ArticleStateManager.from(activity);

        load(state.getString(FEEDS_KEY, null));
        
        // Ensure unified feed respects the preference setting
        ensureUnifiedFeed();
        
        // Add favorites feed if there are favorites
        ensureFavoritesFeed();
        
        // Listen for state changes to update favorites feed
        stateManager.addStateChangeListener(this);
    }

    public static BriefingFeedList from(@NonNull ThemedActivity activity) {
        if (instance == null) {
            instance = new BriefingFeedList(activity);
        }

        return instance;
    }

    public static BriefingFeedList getInstance() {
        if (instance == null) {
            throw new RuntimeException("BriefingFeedList not initialized.");
        }

        return instance;
    }

    public Feed get(int position) {
        return items.get(position);
    }

    public int size() {
        return items.size();
    }
    
    /**
     * Get all feeds including those hidden in categories.
     * This is used by UnifiedFeed to aggregate all articles.
     * Returns the actual feed objects from our map to preserve modifications.
     */
    public List<Feed> getAllFeeds() {
        return new ArrayList<>(allFeedObjects.values());
    }

    private void load(String feedsSerial) {
        if (feedsSerial == null) {
            return;
        }

        try {
            JSONArray array = new JSONArray(feedsSerial);

            for (int index = 0; index < array.length(); index++) {
                Feed feed = Feed.deserialize((String) array.get(index));

                if (feed != null && !items.contains(feed)) {
                    items.add(feed);
                    // Add to allFeedObjects map
                    allFeedObjects.put(feed.getRSSLink(), feed);
                    for (FeedListener listener : listeners) {
                        listener.onInserted(size() - 1);
                    }
                }
            }
            
            // After loading all feeds, create category feeds and hide categorized feeds
            ensureCategoryFeeds();
        } catch (Exception exception) {
            Log.e("BriefingFeedList", "Error loading feeds.", exception);

            state.edit()
                    .remove(FEEDS_KEY)
                    .apply();
        }
    }

    public boolean add(Feed feed) {
        if (feed == null || items.contains(feed)) {
            return false;
        }

        items.add(feed);
        // Add to allFeedObjects map
        allFeedObjects.put(feed.getRSSLink(), feed);
        serialize();

        for (FeedListener listener : listeners) {
            listener.onInserted(size() - 1);
        }
        
        // Add unified feed if we now have multiple feeds and don't have one yet
        ensureUnifiedFeed();
        
        // Update category feeds
        ensureCategoryFeeds();
        
        // Notify CategoryFeed if this feed belongs to a category
        if (feed.getCategory() != null && !feed.getCategory().isEmpty()) {
            for (int i = 0; i < items.size(); i++) {
                Feed item = items.get(i);
                if (CategoryFeed.isCategoryFeed(item)) {
                    CategoryFeed categoryFeed = (CategoryFeed) item;
                    if (feed.getCategory().equals(categoryFeed.getCategoryName())) {
                        for (FeedListener listener : listeners) {
                            listener.onUpdated(i);
                        }
                        break;
                    }
                }
            }
        }

        return true;
    }
    
    private void ensureUnifiedFeed() {
        // Check if unified feed is enabled in settings
        boolean unifiedFeedEnabled = state.getBoolean(Settings.UNIFIED_FEED_ENABLED, true);
        
        boolean hasUnifiedFeed = false;
        int unifiedFeedIndex = -1;
        
        // Check if unified feed exists in items
        for (int i = 0; i < items.size(); i++) {
            Feed feed = items.get(i);
            if (UnifiedFeed.isUnifiedFeed(feed)) {
                hasUnifiedFeed = true;
                unifiedFeedIndex = i;
                break;
            }
        }
        
        // Count ALL regular feeds (including hidden ones in categories)
        int regularFeedsCount = 0;
        for (Feed feed : allFeedObjects.values()) {
            if (feed != null) {
                regularFeedsCount++;
            }
        }
        
        // Add unified feed if enabled, we have multiple feeds, and don't have one yet
        if (unifiedFeedEnabled && !hasUnifiedFeed && regularFeedsCount > 1) {
            items.add(0, new UnifiedFeed());
            for (FeedListener listener : listeners) {
                listener.onInserted(0);
            }
        } 
        // Remove unified feed if disabled or we only have one or zero regular feeds
        else if (hasUnifiedFeed && (!unifiedFeedEnabled || regularFeedsCount <= 1)) {
            items.remove(unifiedFeedIndex);
            for (FeedListener listener : listeners) {
                listener.onRemoved(unifiedFeedIndex);
            }
        }
    }
    
    private void ensureFavoritesFeed() {
        // Check if favorites feed is enabled in settings
        boolean favoritesFeedEnabled = state.getBoolean(Settings.FAVORITES_FEED_ENABLED, true);
        
        boolean hasFavoritesFeed = false;
        int favoritesIndex = -1;
        
        for (int i = 0; i < items.size(); i++) {
            if (FavoritesFeed.isFavoritesFeed(items.get(i))) {
                hasFavoritesFeed = true;
                favoritesIndex = i;
                break;
            }
        }
        
        boolean hasFavorites = stateManager != null && !stateManager.getFavorites().isEmpty();
        
        // Add favorites feed if enabled, we have favorites, and don't have one yet
        if (favoritesFeedEnabled && !hasFavoritesFeed && hasFavorites) {
            // Add favorites feed after unified feed (or at position 0 if no unified feed)
            int insertPosition = 0;
            for (int i = 0; i < items.size(); i++) {
                if (UnifiedFeed.isUnifiedFeed(items.get(i))) {
                    insertPosition = i + 1;
                    break;
                }
            }
            
            items.add(insertPosition, new FavoritesFeed());
            for (FeedListener listener : listeners) {
                listener.onInserted(insertPosition);
            }
        } 
        // Remove favorites feed if disabled or there are no favorites
        else if (hasFavoritesFeed && (!favoritesFeedEnabled || !hasFavorites)) {
            items.remove(favoritesIndex);
            for (FeedListener listener : listeners) {
                listener.onRemoved(favoritesIndex);
            }
        }
    }

    public void updateName(Feed feed, String name) {
        int position = items.indexOf(feed);
        if (position >= 0) {
            updateName(position, name);
        } else {
            // Feed is not in items (hidden in category), update it directly
            feed.title = name;
            // Make sure the modified object is in allFeedObjects
            allFeedObjects.put(feed.getRSSLink(), feed);
            serialize();
        }
    }

    public void updateName(int position, String name) {
        if (position < 0 || position >= items.size() || name == null) {
            return;
        }

        Feed feed = items.get(position);
        
        // Special handling for CategoryFeed - rename the category for all child feeds
        if (CategoryFeed.isCategoryFeed(feed)) {
            CategoryFeed categoryFeed = (CategoryFeed) feed;
            String oldCategoryName = categoryFeed.getCategoryName();
            
            // Update the category name for all feeds that belong to this category
            for (Feed childFeed : allFeedObjects.values()) {
                if (childFeed != null && oldCategoryName.equals(childFeed.getCategory())) {
                    childFeed.setCategory(name);
                }
            }
            
            // Update the CategoryFeed's title
            categoryFeed.title = name;
            serialize();
            
            // Refresh category feeds to reflect the new category name
            ensureCategoryFeeds();
        } else {
            // Regular feed name update
            feed.title = name;
            serialize();
        }

        for (FeedListener listener : listeners) {
            listener.onUpdated(position);
        }
    }
    
    public void updateCategory(Feed feed) {
        updateCategory(feed, null);
    }
    
    public void updateCategory(Feed feed, String oldCategory) {
        int position = items.indexOf(feed);
        if (position >= 0) {
            updateCategory(position, oldCategory);
        } else {
            // Feed is not in items (hidden in category)
            // The feed object has already been modified by FeedConfigurator
            // We need to update it in allFeedObjects and serialize
            
            Log.d("BriefingFeedList", "updateCategory: feed=" + feed.getTitle() + 
                  ", oldCategory=" + oldCategory + ", newCategory=" + feed.getCategory());
            
            // Make sure the modified object is in allFeedObjects
            allFeedObjects.put(feed.getRSSLink(), feed);
            serialize();
            
            // Refresh everything when a feed's category changes
            ensureUnifiedFeed();
            ensureCategoryFeeds();
            
            // Find and notify affected CategoryFeeds and UnifiedFeed
            String newCategory = feed.getCategory();
            for (int i = 0; i < items.size(); i++) {
                Feed item = items.get(i);
                // Notify UnifiedFeed, old CategoryFeed, and new CategoryFeed
                if (UnifiedFeed.isUnifiedFeed(item)) {
                    Log.d("BriefingFeedList", "Notifying UnifiedFeed at position " + i);
                    for (FeedListener listener : listeners) {
                        listener.onUpdated(i);
                    }
                } else if (CategoryFeed.isCategoryFeed(item)) {
                    CategoryFeed categoryFeed = (CategoryFeed) item;
                    String catName = categoryFeed.getCategoryName();
                    // Notify if this is the old category OR the new category
                    // Also notify if old category was null/empty (feed was uncategorized)
                    // or if new category is null/empty (feed is being uncategorized)
                    boolean isOldCategory = (oldCategory != null && !oldCategory.isEmpty() && catName.equals(oldCategory));
                    boolean isNewCategory = (newCategory != null && !newCategory.isEmpty() && catName.equals(newCategory));
                    
                    if (isOldCategory || isNewCategory) {
                        Log.d("BriefingFeedList", "Notifying CategoryFeed '" + catName + "' at position " + i);
                        for (FeedListener listener : listeners) {
                            listener.onUpdated(i);
                        }
                    }
                }
            }
        }
    }
    
    public void updateCategory(int position, String oldCategory) {
        if (position < 0 || position >= items.size()) {
            return;
        }
        
        Feed feed = items.get(position);
        
        serialize();
        
        // Refresh everything when a feed's category changes
        ensureUnifiedFeed();
        ensureCategoryFeeds();

        // Find and notify affected CategoryFeeds and UnifiedFeed
        String newCategory = feed.getCategory();
        for (int i = 0; i < items.size(); i++) {
            Feed item = items.get(i);
            // Notify UnifiedFeed, old CategoryFeed, and new CategoryFeed
            if (UnifiedFeed.isUnifiedFeed(item)) {
                for (FeedListener listener : listeners) {
                    listener.onUpdated(i);
                }
            } else if (CategoryFeed.isCategoryFeed(item)) {
                CategoryFeed categoryFeed = (CategoryFeed) item;
                String catName = categoryFeed.getCategoryName();
                // Notify if this is the old category OR the new category
                boolean isOldCategory = (oldCategory != null && !oldCategory.isEmpty() && catName.equals(oldCategory));
                boolean isNewCategory = (newCategory != null && !newCategory.isEmpty() && catName.equals(newCategory));
                
                if (isOldCategory || isNewCategory) {
                    for (FeedListener listener : listeners) {
                        listener.onUpdated(i);
                    }
                }
            }
        }
    }

    public void remove(Feed feed) {
        remove(items.indexOf(feed));
    }

    public void remove(int position) {
        if (position < 0 || position >= items.size()) {
            return;
        }
        
        // Prevent removal of special feeds directly
        Feed feed = items.get(position);
        if (UnifiedFeed.isUnifiedFeed(feed) || 
            FavoritesFeed.isFavoritesFeed(feed) ||
            CategoryFeed.isCategoryFeed(feed)) {
            return;
        }

        items.remove(position);
        serialize();

        for (FeedListener listener : listeners) {
            listener.onRemoved(position);
        }
        
        // Update special feeds status
        ensureUnifiedFeed();
        ensureCategoryFeeds();
    }
    
    /**
     * Remove a feed from storage by its Feed object.
     * This works for both visible and hidden (categorized) feeds.
     */
    public void removeFeedFromStorage(Feed feed) {
        if (feed == null || 
            UnifiedFeed.isUnifiedFeed(feed) || 
            FavoritesFeed.isFavoritesFeed(feed) ||
            CategoryFeed.isCategoryFeed(feed)) {
            return;
        }
        
        // Save the category before removing
        String category = feed.getCategory();
        
        // Remove from visible items if present
        int visibleIndex = items.indexOf(feed);
        if (visibleIndex >= 0) {
            items.remove(visibleIndex);
        }
        
        // Remove from allFeedObjects map
        allFeedObjects.remove(feed.getRSSLink());
        
        // Serialize to update storage
        serialize();
        
        // Notify listeners if it was visible
        if (visibleIndex >= 0) {
            for (FeedListener listener : listeners) {
                listener.onRemoved(visibleIndex);
            }
        }
        
        // Update special feeds status
        ensureUnifiedFeed();
        ensureCategoryFeeds();
        
        // Notify affected CategoryFeed and UnifiedFeed to refresh their articles
        for (int i = 0; i < items.size(); i++) {
            Feed item = items.get(i);
            if (UnifiedFeed.isUnifiedFeed(item)) {
                Log.d("BriefingFeedList", "Notifying UnifiedFeed after feed removal at position " + i);
                for (FeedListener listener : listeners) {
                    listener.onUpdated(i);
                }
            } else if (CategoryFeed.isCategoryFeed(item)) {
                CategoryFeed categoryFeed = (CategoryFeed) item;
                if (category != null && !category.isEmpty() && categoryFeed.getCategoryName().equals(category)) {
                    Log.d("BriefingFeedList", "Notifying CategoryFeed '" + category + "' after feed removal at position " + i);
                    for (FeedListener listener : listeners) {
                        listener.onUpdated(i);
                    }
                }
            }
        }
    }

    // Keep track of all feed objects (visible and hidden) to preserve modifications
    private final java.util.Map<String, Feed> allFeedObjects = new java.util.HashMap<>();
    
    @SuppressLint("ApplySharedPref")
    private void serialize() {
        // Update allFeedObjects with current items
        for (Feed item : items) {
            if (!UnifiedFeed.isUnifiedFeed(item) && 
                !FavoritesFeed.isFavoritesFeed(item) &&
                !CategoryFeed.isCategoryFeed(item)) {
                allFeedObjects.put(item.getRSSLink(), item);
            }
        }
        
        // Serialize all feeds from our object map
        ArrayList<String> serials = new ArrayList<>();
        for (Feed feed : allFeedObjects.values()) {
            if (feed != null) {
                serials.add(feed.serialize());
            }
        }

        state.edit().putString(FEEDS_KEY,
                new JSONArray(serials).toString()).commit();
    }

    public void addOnFeedUpdateListener(FeedListener listener) {
        if (listener != null) {
            this.listeners.add(listener);
        }
    }

    public void removeOnFeedUpdateListener(FeedListener listener) {
        if (listener != null) {
            this.listeners.remove(listener);
        }
    }
    
    private void ensureCategoryFeeds() {
        // Get all feeds from storage to count categories properly
        List<Feed> allFeeds = getAllFeeds();
        
        // Find all unique categories and count feeds per category
        List<String> categories = new ArrayList<>();
        for (Feed feed : allFeeds) {
            if (feed != null && 
                feed.getCategory() != null && 
                !feed.getCategory().isEmpty()) {
                
                if (!categories.contains(feed.getCategory())) {
                    categories.add(feed.getCategory());
                }
            }
        }
        
        // Determine which categories should have category feeds (1+ feeds)
        List<String> categoriesToShow = new ArrayList<>();
        for (String category : categories) {
            int feedsInCategory = 0;
            for (Feed feed : allFeeds) {
                if (category.equals(feed.getCategory())) {
                    feedsInCategory++;
                }
            }
            
            if (feedsInCategory >= 1) {
                categoriesToShow.add(category);
            }
        }
        
        // Remove category feeds that no longer have enough feeds
        List<Integer> toRemove = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Feed feed = items.get(i);
            if (CategoryFeed.isCategoryFeed(feed)) {
                CategoryFeed categoryFeed = (CategoryFeed) feed;
                if (!categoriesToShow.contains(categoryFeed.getCategoryName())) {
                    toRemove.add(i);
                }
            }
        }
        
        // Remove in reverse order to maintain indices
        for (int i = toRemove.size() - 1; i >= 0; i--) {
            int index = toRemove.get(i);
            items.remove(index);
            for (FeedListener listener : listeners) {
                listener.onRemoved(index);
            }
        }
        
        // Hide individual feeds that belong to categories with category feeds
        toRemove.clear();
        for (int i = 0; i < items.size(); i++) {
            Feed feed = items.get(i);
            if (feed != null &&
                feed.getCategory() != null &&
                !feed.getCategory().isEmpty() &&
                categoriesToShow.contains(feed.getCategory()) &&
                !(feed instanceof UnifiedFeed) &&
                !(feed instanceof FavoritesFeed) &&
                !(feed instanceof CategoryFeed)) {
                toRemove.add(i);
            }
        }
        
        // Remove categorized feeds in reverse order
        for (int i = toRemove.size() - 1; i >= 0; i--) {
            int index = toRemove.get(i);
            items.remove(index);
            for (FeedListener listener : listeners) {
                listener.onRemoved(index);
            }
        }
        
        // First, remove any duplicate CategoryFeeds (safety check)
        java.util.Set<String> seenCategories = new java.util.HashSet<>();
        toRemove.clear();
        for (int i = 0; i < items.size(); i++) {
            Feed feed = items.get(i);
            if (CategoryFeed.isCategoryFeed(feed)) {
                CategoryFeed categoryFeed = (CategoryFeed) feed;
                String catName = categoryFeed.getCategoryName();
                if (seenCategories.contains(catName)) {
                    // Duplicate CategoryFeed - remove it
                    toRemove.add(i);
                } else {
                    seenCategories.add(catName);
                }
            }
        }
        
        // Remove duplicates in reverse order
        for (int i = toRemove.size() - 1; i >= 0; i--) {
            int index = toRemove.get(i);
            items.remove(index);
            for (FeedListener listener : listeners) {
                listener.onRemoved(index);
            }
        }
        
        // Add category feeds for categories that don't have one yet
        for (String category : categoriesToShow) {
            boolean hasCategoryFeed = false;
            for (Feed feed : items) {
                if (CategoryFeed.isCategoryFeed(feed)) {
                    CategoryFeed categoryFeed = (CategoryFeed) feed;
                    if (category.equals(categoryFeed.getCategoryName())) {
                        hasCategoryFeed = true;
                        break;
                    }
                }
            }
            
            if (!hasCategoryFeed) {
                // Insert category feed after special feeds (unified, favorites)
                int insertPosition = 0;
                for (int i = 0; i < items.size(); i++) {
                    if (UnifiedFeed.isUnifiedFeed(items.get(i)) || 
                        FavoritesFeed.isFavoritesFeed(items.get(i))) {
                        insertPosition = i + 1;
                    } else {
                        break;
                    }
                }
                
                items.add(insertPosition, new CategoryFeed(category));
                for (FeedListener listener : listeners) {
                    listener.onInserted(insertPosition);
                }
            }
        }
        
        // Show feeds that should be visible (no category or category not in categoriesToShow)
        for (Feed feed : allFeedObjects.values()) {
            if (feed != null &&
                !(feed instanceof UnifiedFeed) &&
                !(feed instanceof FavoritesFeed) &&
                !(feed instanceof CategoryFeed)) {
                
                boolean shouldBeVisible = feed.getCategory() == null || 
                                         feed.getCategory().isEmpty() || 
                                         !categoriesToShow.contains(feed.getCategory());
                
                boolean isVisible = items.contains(feed);
                
                if (shouldBeVisible && !isVisible) {
                    // Feed should be visible but isn't - add it
                    items.add(feed);
                    for (FeedListener listener : listeners) {
                        listener.onInserted(items.size() - 1);
                    }
                }
            }
        }
        
        // Always refresh unified feed after category changes
        // This ensures "All Feeds" appears/disappears correctly
        ensureUnifiedFeed();
    }
    
    /**
     * Refresh feeds based on current preference settings.
     * This should be called when preferences change.
     */
    public void refreshFeeds() {
        ensureUnifiedFeed();
        ensureFavoritesFeed();
        ensureCategoryFeeds();
    }
    
    @Override
    public void onStateChanged() {
        // Update favorites feed when favorites change
        ensureFavoritesFeed();
    }

    public interface FeedListener {
        default void onInserted(int index) {
        }

        default void onUpdated(int index) {
        }

        default void onRemoved(int index) {
        }
    }
}
