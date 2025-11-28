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

import android.util.Log;

import com.prof18.rssparser.model.RssItem;
import com.stario.launcher.sheet.briefing.rss.RSSHelper;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Represents a unified feed that aggregates articles from multiple RSS feeds.
 * Implements Issue #1: Unified "briefing" feed.
 */
public class UnifiedFeed extends Feed {
    private static final String TAG = "UnifiedFeed";
    private static final String UNIFIED_FEED_TITLE = "All Feeds";
    private static final String UNIFIED_FEED_RSS = "unified://all";

    public UnifiedFeed() {
        super(UNIFIED_FEED_TITLE, UNIFIED_FEED_RSS);
    }

    /**
     * Fetches and aggregates articles from all feeds in the provided list.
     * Articles are sorted by publication date (newest first).
     */
    public static List<RssItem> fetchUnifiedArticles(BriefingFeedList feedList) {
        List<RssItem> allArticles = new ArrayList<>();
        List<CompletableFuture<List<RssItem>>> futures = new ArrayList<>();

        for (int i = 0; i < feedList.size(); i++) {
            Feed feed = feedList.get(i);
            
            // Only include feeds that are marked for inclusion in unified view
            if (feed != null && feed.isIncludedInUnified() && !(feed instanceof UnifiedFeed)) {
                CompletableFuture<List<RssItem>> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return RSSHelper.parse(feed.getRSSLink());
                    } catch (Exception e) {
                        Log.e(TAG, "Error fetching feed: " + feed.getTitle(), e);
                        return null;
                    }
                });
                futures.add(future);
            }
        }

        // Wait for all feeds to complete
        for (CompletableFuture<List<RssItem>> future : futures) {
            try {
                List<RssItem> items = future.get();
                if (items != null && !items.isEmpty()) {
                    allArticles.addAll(items);
                }
            } catch (InterruptedException | ExecutionException e) {
                Log.e(TAG, "Error waiting for feed", e);
            }
        }

        // Sort by publication date (newest first)
        Collections.sort(allArticles, new Comparator<RssItem>() {
            // Common RSS date formats
            private final SimpleDateFormat[] dateFormats = {
                new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
                new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH),
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH),
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ENGLISH),
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH),
                new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
            };
            
            private Date parseDate(String dateStr) {
                if (dateStr == null) return null;
                
                for (SimpleDateFormat format : dateFormats) {
                    try {
                        return format.parse(dateStr);
                    } catch (ParseException e) {
                        // Try next format
                    }
                }
                
                Log.w(TAG, "Could not parse date: " + dateStr);
                return null;
            }
            
            @Override
            public int compare(RssItem item1, RssItem item2) {
                Date date1 = parseDate(item1.getPubDate());
                Date date2 = parseDate(item2.getPubDate());
                
                if (date1 == null && date2 == null) return 0;
                if (date1 == null) return 1;
                if (date2 == null) return -1;
                
                // Reverse order for newest first
                return date2.compareTo(date1);
            }
        });

        return allArticles;
    }

    /**
     * Checks if this is the unified feed.
     */
    public static boolean isUnifiedFeed(Feed feed) {
        return feed instanceof UnifiedFeed || 
               (feed != null && UNIFIED_FEED_RSS.equals(feed.getRSSLink()));
    }

    @Override
    public boolean isIncludedInUnified() {
        // The unified feed itself should not be included in its own aggregation
        return false;
    }
}
