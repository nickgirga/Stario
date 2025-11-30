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

package com.stario.launcher.sheet.briefing.dialog.page.feed;

import com.prof18.rssparser.model.RssItem;
import com.stario.launcher.sheet.briefing.dialog.page.ArticleStateManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a feed that displays favorited/saved articles.
 * Implements Issue #4: "Briefing" favorites/saved.
 */
public class FavoritesFeed extends Feed {
    private static final String FAVORITES_FEED_TITLE = "Favorites";
    private static final String FAVORITES_FEED_RSS = "favorites://saved";

    public FavoritesFeed() {
        super(FAVORITES_FEED_TITLE, FAVORITES_FEED_RSS);
    }

    /**
     * Fetches all favorited articles from the ArticleStateManager.
     * Articles are sorted by timestamp in descending order (most recent first).
     */
    public static List<RssItem> fetchFavoriteArticles(ArticleStateManager stateManager) {
        List<RssItem> items = new ArrayList<>();
        
        if (stateManager == null) {
            return items;
        }

        List<ArticleStateManager.FavoriteArticle> favorites = stateManager.getFavorites();
        
        // Sort favorites by timestamp in descending order (most recent first)
        favorites.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        
        for (ArticleStateManager.FavoriteArticle favorite : favorites) {
            items.add(favorite.toRssItem());
        }

        return items;
    }

    /**
     * Checks if this is the favorites feed.
     */
    public static boolean isFavoritesFeed(Feed feed) {
        return feed instanceof FavoritesFeed || 
               (feed != null && FAVORITES_FEED_RSS.equals(feed.getRSSLink()));
    }

    @Override
    public boolean isIncludedInUnified() {
        // Favorites should not be included in the unified feed
        return false;
    }
}
