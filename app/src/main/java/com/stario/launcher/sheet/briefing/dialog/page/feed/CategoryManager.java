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

import androidx.annotation.NonNull;

import com.stario.launcher.preferences.Entry;
import com.stario.launcher.themes.ThemedActivity;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages categories for organizing RSS feeds.
 * Implements Issue #2: "Briefing" categories.
 */
public class CategoryManager {
    private static final String CATEGORIES_KEY = "com.stario.CATEGORIES";
    private static final String UNCATEGORIZED = "Uncategorized";
    private static CategoryManager instance = null;

    private final SharedPreferences state;
    private final Set<String> categories;
    private final List<CategoryChangeListener> listeners;

    private CategoryManager(ThemedActivity activity) {
        this.state = activity.getApplicationContext()
                .getSharedPreferences(Entry.BRIEFING);
        this.categories = new HashSet<>();
        this.listeners = new ArrayList<>();

        loadCategories();
    }

    public static CategoryManager from(@NonNull ThemedActivity activity) {
        if (instance == null) {
            instance = new CategoryManager(activity);
        }
        return instance;
    }

    public static CategoryManager getInstance() {
        if (instance == null) {
            throw new RuntimeException("CategoryManager not initialized.");
        }
        return instance;
    }

    /**
     * Gets all categories including the default "Uncategorized".
     */
    public List<String> getAllCategories() {
        List<String> allCategories = new ArrayList<>();
        allCategories.add(UNCATEGORIZED);
        allCategories.addAll(new ArrayList<>(categories));
        return allCategories;
    }

    /**
     * Adds a new category.
     */
    public boolean addCategory(String category) {
        if (category == null || category.trim().isEmpty() || 
            category.equals(UNCATEGORIZED) || categories.contains(category)) {
            return false;
        }

        categories.add(category);
        saveCategories();
        notifyListeners();
        return true;
    }

    /**
     * Removes a category. Feeds in this category will be moved to "Uncategorized".
     */
    public boolean removeCategory(String category) {
        if (category == null || category.equals(UNCATEGORIZED)) {
            return false;
        }

        boolean removed = categories.remove(category);
        if (removed) {
            // Update all feeds in this category to be uncategorized
            BriefingFeedList feedList = BriefingFeedList.getInstance();
            for (int i = 0; i < feedList.size(); i++) {
                Feed feed = feedList.get(i);
                if (category.equals(feed.getCategory())) {
                    feed.setCategory(null);
                }
            }
            
            saveCategories();
            notifyListeners();
        }
        return removed;
    }

    /**
     * Renames a category.
     */
    public boolean renameCategory(String oldName, String newName) {
        if (oldName == null || newName == null || oldName.equals(UNCATEGORIZED) ||
            newName.trim().isEmpty() || oldName.equals(newName) || categories.contains(newName)) {
            return false;
        }

        if (categories.remove(oldName)) {
            categories.add(newName);
            
            // Update all feeds with this category
            BriefingFeedList feedList = BriefingFeedList.getInstance();
            for (int i = 0; i < feedList.size(); i++) {
                Feed feed = feedList.get(i);
                if (oldName.equals(feed.getCategory())) {
                    feed.setCategory(newName);
                }
            }
            
            saveCategories();
            notifyListeners();
            return true;
        }
        return false;
    }

    /**
     * Gets feeds organized by category.
     */
    public Map<String, List<Feed>> getFeedsByCategory(BriefingFeedList feedList) {
        Map<String, List<Feed>> feedsByCategory = new LinkedHashMap<>();
        
        // Initialize all categories
        for (String category : getAllCategories()) {
            feedsByCategory.put(category, new ArrayList<>());
        }

        // Organize feeds
        for (int i = 0; i < feedList.size(); i++) {
            Feed feed = feedList.get(i);
            if (feed instanceof UnifiedFeed) {
                continue; // Skip unified feed in category view
            }
            
            String category = feed.getCategory();
            if (category == null || category.trim().isEmpty() || !categories.contains(category)) {
                category = UNCATEGORIZED;
            }
            
            List<Feed> categoryFeeds = feedsByCategory.get(category);
            if (categoryFeeds != null) {
                categoryFeeds.add(feed);
            }
        }

        // Remove empty categories (except Uncategorized)
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, List<Feed>> entry : feedsByCategory.entrySet()) {
            if (!entry.getKey().equals(UNCATEGORIZED) && entry.getValue().isEmpty()) {
                toRemove.add(entry.getKey());
            }
        }
        for (String key : toRemove) {
            feedsByCategory.remove(key);
        }

        return feedsByCategory;
    }

    @SuppressLint("ApplySharedPref")
    private void saveCategories() {
        JSONArray array = new JSONArray(categories);
        state.edit().putString(CATEGORIES_KEY, array.toString()).commit();
    }

    private void loadCategories() {
        String data = state.getString(CATEGORIES_KEY, null);
        if (data != null) {
            try {
                JSONArray array = new JSONArray(data);
                for (int i = 0; i < array.length(); i++) {
                    categories.add(array.getString(i));
                }
            } catch (JSONException e) {
                // Ignore corrupted data
            }
        }
    }

    public void addCategoryChangeListener(CategoryChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeCategoryChangeListener(CategoryChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (CategoryChangeListener listener : listeners) {
            listener.onCategoriesChanged();
        }
    }

    public interface CategoryChangeListener {
        void onCategoriesChanged();
    }

    public static String getUncategorizedName() {
        return UNCATEGORIZED;
    }
}
