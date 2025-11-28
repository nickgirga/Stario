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

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.Serializable;
import java.util.Objects;

public class Feed implements Serializable {
    private static final String TAG = "com.stario.FeedItem";
    private static final String FEED_TITLE = "com.stario.FEED_TITLE";
    private static final String FEED_RSS = "com.stario.FEED_RSS";
    private static final String FEED_CATEGORY = "com.stario.FEED_CATEGORY";
    private static final String FEED_IN_UNIFIED = "com.stario.FEED_IN_UNIFIED";

    private final String rss;

    String title;
    String category;
    boolean includeInUnified;

    public Feed(@NonNull String title, @NonNull String rss) {
        this(title, rss, null, true);
    }

    public Feed(@NonNull String title, @NonNull String rss, String category, boolean includeInUnified) {
        this.title = title;
        this.rss = rss;
        this.category = category;
        this.includeInUnified = includeInUnified;
    }

    public String getTitle() {
        return title;
    }

    public String getRSSLink() {
        return rss;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public boolean isIncludedInUnified() {
        return includeInUnified;
    }

    public void setIncludeInUnified(boolean include) {
        this.includeInUnified = include;
    }

    public static Feed deserialize(String data) {
        try {
            JSONObject jsonObject = new JSONObject(data);

            String title = jsonObject.getString(FEED_TITLE);
            String rss = jsonObject.getString(FEED_RSS);
            String category = jsonObject.optString(FEED_CATEGORY, null);
            boolean includeInUnified = jsonObject.optBoolean(FEED_IN_UNIFIED, true);

            return new Feed(title, rss, category, includeInUnified);
        } catch (Exception exception) {
            Log.e(TAG, "deserialize: Serialized object has corrupt data.");

            return null;
        }
    }

    public String serialize() {
        if (rss.isEmpty()) {
            return null;
        } else {
            try {
                JSONObject obj = new JSONObject();
                obj.put(FEED_TITLE, title);
                obj.put(FEED_RSS, rss);
                if (category != null) {
                    obj.put(FEED_CATEGORY, category);
                }
                obj.put(FEED_IN_UNIFIED, includeInUnified);
                return obj.toString();
            } catch (Exception e) {
                Log.e(TAG, "serialize: Error serializing feed.", e);
                return null;
            }
        }
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (object == null || getClass() != object.getClass()) {
            return false;
        }

        return Objects.equals(rss, ((Feed) object).rss);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, rss);
    }
}
