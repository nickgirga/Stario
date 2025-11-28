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

import com.stario.launcher.preferences.Entry;
import com.stario.launcher.themes.ThemedActivity;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public class BriefingFeedList {
    private static final String FEEDS_KEY = "com.stario.FEEDS";
    private static BriefingFeedList instance = null;

    private final List<FeedListener> listeners;
    private final SharedPreferences state;
    private final List<Feed> items;

    private BriefingFeedList(ThemedActivity activity) {
        this.items = new ArrayList<>();
        this.listeners = new ArrayList<>();
        this.state = activity.getApplicationContext()
                .getSharedPreferences(Entry.BRIEFING);

        load(state.getString(FEEDS_KEY, null));
        
        // Add unified feed as the first item if it doesn't exist
        boolean hasUnifiedFeed = false;
        for (Feed feed : items) {
            if (UnifiedFeed.isUnifiedFeed(feed)) {
                hasUnifiedFeed = true;
                break;
            }
        }
        
        if (!hasUnifiedFeed && items.size() > 1) {
            items.add(0, new UnifiedFeed());
            for (FeedListener listener : listeners) {
                listener.onInserted(0);
            }
        }
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
                    for (FeedListener listener : listeners) {
                        listener.onInserted(size() - 1);
                    }
                }
            }
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
        serialize();

        for (FeedListener listener : listeners) {
            listener.onInserted(size() - 1);
        }
        
        // Add unified feed if we now have multiple feeds and don't have one yet
        ensureUnifiedFeed();

        return true;
    }
    
    private void ensureUnifiedFeed() {
        boolean hasUnifiedFeed = false;
        int regularFeedsCount = 0;
        
        for (Feed feed : items) {
            if (UnifiedFeed.isUnifiedFeed(feed)) {
                hasUnifiedFeed = true;
            } else {
                regularFeedsCount++;
            }
        }
        
        if (!hasUnifiedFeed && regularFeedsCount > 1) {
            items.add(0, new UnifiedFeed());
            for (FeedListener listener : listeners) {
                listener.onInserted(0);
            }
        } else if (hasUnifiedFeed && regularFeedsCount <= 1) {
            // Remove unified feed if we only have one or zero regular feeds
            for (int i = 0; i < items.size(); i++) {
                if (UnifiedFeed.isUnifiedFeed(items.get(i))) {
                    items.remove(i);
                    for (FeedListener listener : listeners) {
                        listener.onRemoved(i);
                    }
                    break;
                }
            }
        }
    }

    public void updateName(Feed feed, String name) {
        updateName(items.indexOf(feed), name);
    }

    public void updateName(int position, String name) {
        if (position < 0 || position >= items.size() || name == null) {
            return;
        }

        items.get(position).title = name;
        serialize();

        for (FeedListener listener : listeners) {
            listener.onUpdated(position);
        }
    }

    public void remove(Feed feed) {
        remove(items.indexOf(feed));
    }

    public void remove(int position) {
        if (position < 0 || position >= items.size()) {
            return;
        }
        
        // Prevent removal of unified feed directly
        Feed feed = items.get(position);
        if (UnifiedFeed.isUnifiedFeed(feed)) {
            return;
        }

        items.remove(position);
        serialize();

        for (FeedListener listener : listeners) {
            listener.onRemoved(position);
        }
        
        // Update unified feed status
        ensureUnifiedFeed();
    }

    @SuppressLint("ApplySharedPref")
    private void serialize() {
        ArrayList<String> serials = new ArrayList<>();
        for (Feed item : items) {
            // Don't serialize the unified feed
            if (!UnifiedFeed.isUnifiedFeed(item)) {
                serials.add(item.serialize());
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

    public interface FeedListener {
        default void onInserted(int index) {
        }

        default void onUpdated(int index) {
        }

        default void onRemoved(int index) {
        }
    }
}
