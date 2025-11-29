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

package com.stario.launcher.sheet.briefing.configurator;

import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.stario.launcher.R;
import com.stario.launcher.sheet.briefing.dialog.page.feed.BriefingFeedList;
import com.stario.launcher.sheet.briefing.dialog.page.feed.Feed;
import com.stario.launcher.themes.ThemedActivity;
import com.stario.launcher.ui.dialogs.ActionDialog;

import carbon.view.SimpleTextWatcher;

public class FeedConfigurator extends ActionDialog {
    private static final String TAG = "FeedConfigurator";
    private final BriefingFeedList list;
    private final Feed feed;
    private EditText name;
    private EditText category;

    public FeedConfigurator(@NonNull ThemedActivity activity, @NonNull Feed feed) {
        super(activity);

        this.list = BriefingFeedList.from(activity);
        this.feed = feed;
    }

    @Override
    protected @NonNull View inflateContent(LayoutInflater inflater) {
        ViewGroup contentView = (ViewGroup) inflater.inflate(R.layout.feed_configurator, null);

        name = contentView.findViewById(R.id.name);
        category = contentView.findViewById(R.id.category);
        View warning = contentView.findViewById(R.id.warning);

        name.setText(feed.getTitle());
        
        // Hide category field for special feeds (CategoryFeed, UnifiedFeed, FavoritesFeed)
        if (feed instanceof com.stario.launcher.sheet.briefing.dialog.page.feed.CategoryFeed ||
            feed instanceof com.stario.launcher.sheet.briefing.dialog.page.feed.UnifiedFeed ||
            feed instanceof com.stario.launcher.sheet.briefing.dialog.page.feed.FavoritesFeed) {
            category.setVisibility(android.view.View.GONE);
        } else {
            if (feed.getCategory() != null) {
                category.setText(feed.getCategory());
            }
        }
        
        name.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(@NonNull Editable editable) {
                if (editable.length() == 0) {
                    warning.setVisibility(View.VISIBLE);
                } else {
                    warning.setVisibility(View.GONE);
                }
            }
        });

        return contentView;
    }

    @Override
    public void dismiss() {
        super.dismiss();

        android.util.Log.d(TAG, "dismiss() called for feed: " + feed.getTitle());
        
        boolean updated = false;
        
        if (name != null) {
            Editable editable = name.getText();
            String currentTitle = feed.getTitle();

            if (editable.length() > 0 &&
                    (currentTitle == null || !currentTitle.equals(editable.toString()))) {
                android.util.Log.d(TAG, "Updating name from '" + currentTitle + "' to '" + editable.toString() + "'");
                list.updateName(feed, editable.toString());
                updated = true;
            }
        }
        
        // Only allow category changes for regular feeds (not special feeds)
        if (category != null && 
            !(feed instanceof com.stario.launcher.sheet.briefing.dialog.page.feed.CategoryFeed) &&
            !(feed instanceof com.stario.launcher.sheet.briefing.dialog.page.feed.UnifiedFeed) &&
            !(feed instanceof com.stario.launcher.sheet.briefing.dialog.page.feed.FavoritesFeed)) {
            
            String categoryText = category.getText().toString().trim();
            String currentCategory = feed.getCategory();
            
            android.util.Log.d(TAG, "Category check: categoryText='" + categoryText + "', currentCategory='" + currentCategory + "'");
            
            // Update category if it changed (including null to empty or vice versa)
            if (!categoryText.equals(currentCategory == null ? "" : currentCategory)) {
                android.util.Log.d(TAG, "Category changed! Calling updateCategory with oldCategory='" + currentCategory + "'");
                // Save old category BEFORE changing it
                String oldCategory = currentCategory;
                feed.setCategory(categoryText.isEmpty() ? null : categoryText);
                // ALWAYS call updateCategory when category changes, regardless of name update
                list.updateCategory(feed, oldCategory);
            } else {
                android.util.Log.d(TAG, "Category NOT changed, skipping updateCategory");
            }
        } else {
            android.util.Log.d(TAG, "Category field is null or feed is special type, skipping category update");
        }
    }

    @Override
    protected boolean blurBehind() {
        return true;
    }

    @Override
    protected int getDesiredInitialState() {
        return BottomSheetBehavior.STATE_EXPANDED;
    }
}
