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

package com.stario.launcher.activities.settings.dialogs.favorites;

import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.prof18.rssparser.model.RssItem;
import com.stario.launcher.R;
import com.stario.launcher.sheet.briefing.dialog.page.ArticleStateManager;
import com.stario.launcher.sheet.briefing.dialog.page.FeedPageAdapter;
import com.stario.launcher.themes.ThemedActivity;
import com.stario.launcher.ui.Measurements;
import com.stario.launcher.ui.dialogs.ActionDialog;
import com.stario.launcher.ui.recyclers.RecyclerItemAnimator;
import com.stario.launcher.ui.recyclers.managers.ScrollControlStaggeredGridLayoutManager;
import com.stario.launcher.ui.recyclers.overscroll.OverScrollRecyclerView;
import com.stario.launcher.ui.utils.LayoutSizeObserver;
import com.stario.launcher.ui.utils.UiUtils;
import com.stario.launcher.ui.utils.animation.Animation;

import java.util.ArrayList;
import java.util.List;

/**
 * Dialog for viewing favorited articles from settings.
 * Allows users to access their favorites even when briefing is disabled.
 */
public class FavoritesDialog extends ActionDialog implements ArticleStateManager.StateChangeListener {
    private ScrollControlStaggeredGridLayoutManager manager;
    private OverScrollRecyclerView recyclerView;
    private FeedPageAdapter adapter;
    private ArticleStateManager stateManager;
    private ViewGroup emptyView;

    public FavoritesDialog(@NonNull ThemedActivity activity) {
        super(activity);
    }

    @NonNull
    @Override
    protected View inflateContent(LayoutInflater inflater) {
        View root = inflater.inflate(R.layout.favorites_dialog, null);

        recyclerView = root.findViewById(R.id.recycler_view);
        emptyView = root.findViewById(R.id.exception);

        recyclerView.setItemAnimator(new RecyclerItemAnimator(RecyclerItemAnimator.APPEARANCE, Animation.EXTENDED));

        adapter = new FeedPageAdapter(activity.getApplicationContext());
        adapter.setFeedPosition(-1); // Not part of any specific feed

        // Initialize ArticleStateManager
        stateManager = ArticleStateManager.from(activity);
        adapter.setStateManager(stateManager);
        stateManager.addStateChangeListener(this);

        manager = new ScrollControlStaggeredGridLayoutManager(0);
        LayoutSizeObserver.attach(root, LayoutSizeObserver.WIDTH, new LayoutSizeObserver.OnChange() {
            @Override
            public void onChange(View view, int watchFlags, Rect rect) {
                manager.setSpanCount(Math.max(1, rect.width() / Measurements.dpToPx(400)));
            }
        });
        manager.setItemPrefetchEnabled(true);

        recyclerView.setLayoutManager(manager);
        recyclerView.setAdapter(adapter);

        Measurements.addNavListener(bottomInset ->
                recyclerView.setPadding(recyclerView.getPaddingLeft(), Measurements.dpToPx(20),
                        recyclerView.getPaddingRight(), bottomInset));

        loadFavorites();

        return root;
    }

    @Override
    protected boolean blurBehind() {
        return true;
    }

    @Override
    protected int getDesiredInitialState() {
        return BottomSheetBehavior.STATE_EXPANDED;
    }

    @Override
    public void onStateChanged() {
        UiUtils.post(this::loadFavorites);
    }

    private void loadFavorites() {
        if (stateManager == null) {
            return;
        }

        List<ArticleStateManager.FavoriteArticle> favorites = stateManager.getFavorites();
        List<RssItem> items = new ArrayList<>();

        // Sort favorites by timestamp in descending order (most recent first)
        favorites.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

        // Convert favorites to RssItems
        for (ArticleStateManager.FavoriteArticle favorite : favorites) {
            items.add(favorite.toRssItem());
        }

        adapter.update(items);

        if (items.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void dismiss() {
        if (stateManager != null) {
            stateManager.removeStateChangeListener(this);
        }
        super.dismiss();
    }
}
