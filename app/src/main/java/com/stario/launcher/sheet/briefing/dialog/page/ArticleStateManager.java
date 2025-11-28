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

package com.stario.launcher.sheet.briefing.dialog.page;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.prof18.rssparser.model.RssItem;
import com.stario.launcher.preferences.Entry;
import com.stario.launcher.themes.ThemedActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages the state of briefing articles including read/unread status and favorites.
 * Implements Issue #5 (mark as read) and Issue #4 (favorites/saved).
 */
public class ArticleStateManager {
    private static final String READ_ARTICLES_KEY = "com.stario.READ_ARTICLES";
    private static final String FAVORITE_ARTICLES_KEY = "com.stario.FAVORITE_ARTICLES";
    private static ArticleStateManager instance = null;

    private final SharedPreferences state;
    private final Set<String> readArticles;
    private final List<FavoriteArticle> favoriteArticles;
    private final List<StateChangeListener> listeners;

    private ArticleStateManager(ThemedActivity activity) {
        this.state = activity.getApplicationContext()
                .getSharedPreferences(Entry.BRIEFING);
        this.readArticles = new HashSet<>();
        this.favoriteArticles = new ArrayList<>();
        this.listeners = new ArrayList<>();

        loadReadArticles();
        loadFavoriteArticles();
    }

    public static ArticleStateManager from(@NonNull ThemedActivity activity) {
        if (instance == null) {
            instance = new ArticleStateManager(activity);
        }
        return instance;
    }

    public static ArticleStateManager getInstance() {
        if (instance == null) {
            throw new RuntimeException("ArticleStateManager not initialized.");
        }
        return instance;
    }

    /**
     * Generates a unique identifier for an article based on its link or GUID.
     */
    private String getArticleId(RssItem item) {
        String id = item.getLink();
        if (id == null || id.isEmpty()) {
            id = item.getGuid();
        }
        return id != null ? id : "";
    }

    /**
     * Marks an article as read.
     */
    public void markAsRead(RssItem item) {
        String id = getArticleId(item);
        if (!id.isEmpty() && readArticles.add(id)) {
            saveReadArticles();
            notifyStateChanged();
        }
    }

    /**
     * Checks if an article has been read.
     */
    public boolean isRead(RssItem item) {
        String id = getArticleId(item);
        return !id.isEmpty() && readArticles.contains(id);
    }

    /**
     * Marks an article as unread.
     */
    public void markAsUnread(RssItem item) {
        String id = getArticleId(item);
        if (!id.isEmpty() && readArticles.remove(id)) {
            saveReadArticles();
            notifyStateChanged();
        }
    }

    /**
     * Toggles the favorite status of an article.
     */
    public void toggleFavorite(RssItem item, String feedTitle) {
        String id = getArticleId(item);
        if (id.isEmpty()) {
            return;
        }

        FavoriteArticle favorite = findFavorite(id);
        if (favorite != null) {
            favoriteArticles.remove(favorite);
        } else {
            favoriteArticles.add(new FavoriteArticle(item, feedTitle));
        }

        saveFavoriteArticles();
        notifyStateChanged();
    }

    /**
     * Checks if an article is favorited.
     */
    public boolean isFavorite(RssItem item) {
        String id = getArticleId(item);
        return !id.isEmpty() && findFavorite(id) != null;
    }

    /**
     * Gets all favorite articles.
     */
    public List<FavoriteArticle> getFavorites() {
        return new ArrayList<>(favoriteArticles);
    }

    private FavoriteArticle findFavorite(String articleId) {
        for (FavoriteArticle favorite : favoriteArticles) {
            if (articleId.equals(favorite.articleId)) {
                return favorite;
            }
        }
        return null;
    }

    @SuppressLint("ApplySharedPref")
    private void saveReadArticles() {
        JSONArray array = new JSONArray(readArticles);
        state.edit().putString(READ_ARTICLES_KEY, array.toString()).commit();
    }

    private void loadReadArticles() {
        String data = state.getString(READ_ARTICLES_KEY, null);
        if (data != null) {
            try {
                JSONArray array = new JSONArray(data);
                for (int i = 0; i < array.length(); i++) {
                    readArticles.add(array.getString(i));
                }
            } catch (JSONException e) {
                // Ignore corrupted data
            }
        }
    }

    @SuppressLint("ApplySharedPref")
    private void saveFavoriteArticles() {
        JSONArray array = new JSONArray();
        for (FavoriteArticle favorite : favoriteArticles) {
            try {
                array.put(favorite.toJSON());
            } catch (JSONException e) {
                // Skip this item
            }
        }
        state.edit().putString(FAVORITE_ARTICLES_KEY, array.toString()).commit();
    }

    private void loadFavoriteArticles() {
        String data = state.getString(FAVORITE_ARTICLES_KEY, null);
        if (data != null) {
            try {
                JSONArray array = new JSONArray(data);
                for (int i = 0; i < array.length(); i++) {
                    FavoriteArticle favorite = FavoriteArticle.fromJSON(array.getJSONObject(i));
                    if (favorite != null) {
                        favoriteArticles.add(favorite);
                    }
                }
            } catch (JSONException e) {
                // Ignore corrupted data
            }
        }
    }

    public void addStateChangeListener(StateChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeStateChangeListener(StateChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyStateChanged() {
        for (StateChangeListener listener : listeners) {
            listener.onStateChanged();
        }
    }

    public interface StateChangeListener {
        void onStateChanged();
    }

    /**
     * Represents a favorited article with its metadata.
     */
    public static class FavoriteArticle {
        public final String articleId;
        public final String feedTitle;
        public final String title;
        public final String description;
        public final String link;
        public final String image;
        public final String author;
        public final String pubDate;
        public final long timestamp;

        public FavoriteArticle(RssItem item, String feedTitle) {
            this.articleId = item.getLink() != null ? item.getLink() : 
                            (item.getGuid() != null ? item.getGuid() : "");
            this.feedTitle = feedTitle;
            this.title = item.getTitle();
            // Prefer description over content for display
            String desc = item.getDescription();
            if (desc == null || desc.isEmpty()) {
                desc = item.getContent();
            }
            this.description = desc;
            this.link = item.getLink();
            this.image = item.getImage();
            this.author = item.getAuthor();
            this.pubDate = item.getPubDate();
            this.timestamp = System.currentTimeMillis();
        }

        private FavoriteArticle(String articleId, String feedTitle, String title,
                               String description, String link, String image, 
                               String author, String pubDate, long timestamp) {
            this.articleId = articleId;
            this.feedTitle = feedTitle;
            this.title = title;
            this.description = description;
            this.link = link;
            this.image = image;
            this.author = author;
            this.pubDate = pubDate;
            this.timestamp = timestamp;
        }

        public JSONObject toJSON() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("id", articleId);
            obj.put("feedTitle", feedTitle);
            obj.put("title", title);
            obj.put("description", description);
            obj.put("link", link);
            obj.put("image", image);
            obj.put("author", author);
            obj.put("pubDate", pubDate);
            obj.put("timestamp", timestamp);
            return obj;
        }

        public static FavoriteArticle fromJSON(JSONObject obj) {
            try {
                return new FavoriteArticle(
                    obj.getString("id"),
                    obj.getString("feedTitle"),
                    obj.getString("title"),
                    obj.getString("description"),
                    obj.getString("link"),
                    obj.optString("image", null),
                    obj.optString("author", null),
                    obj.optString("pubDate", null),
                    obj.getLong("timestamp")
                );
            } catch (JSONException e) {
                return null;
            }
        }

        /**
         * Converts this favorite to an RssItem for display.
         */
        public RssItem toRssItem() {
            // Create RssItem with all necessary fields populated
            // Use description for both description and content to ensure proper display
            return new RssItem(
                    link != null ? link : articleId,  // guid
                    title != null ? title : "",        // title
                    link,                               // link
                    description,                        // description
                    author,                             // author
                    pubDate,                            // pubDate
                    image,                              // image
                    null,                               // audio
                    null,                               // video
                    feedTitle,                          // sourceName (show which feed it's from)
                    null,                               // sourceUrl
                    description,                        // content (same as description)
                    new ArrayList<>(),                  // categories
                    null,                               // itunesItemData
                    null,                               // commentsUrl
                    null,                               // youtubeItemData
                    null                                // rawEnclosure
            );
        }
    }
}
