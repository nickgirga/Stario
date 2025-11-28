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
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.prof18.rssparser.model.RssItem;
import com.stario.launcher.R;
import com.stario.launcher.sheet.SheetType;
import com.stario.launcher.sheet.briefing.dialog.BriefingDialog;
import com.stario.launcher.themes.ThemedActivity;
import com.stario.launcher.ui.Measurements;
import com.stario.launcher.ui.recyclers.RecyclerItemAnimator;
import com.stario.launcher.ui.recyclers.managers.ScrollControlStaggeredGridLayoutManager;
import com.stario.launcher.ui.recyclers.overscroll.OverScrollEffect;
import com.stario.launcher.ui.recyclers.overscroll.OverScrollRecyclerView;
import com.stario.launcher.ui.utils.LayoutSizeObserver;
import com.stario.launcher.ui.utils.UiUtils;
import com.stario.launcher.ui.utils.animation.Animation;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays all favorited/saved articles.
 * Completes Issue #4: "Briefing" favorites/saved.
 */
public class FavoritesPage extends Fragment implements ArticleStateManager.StateChangeListener {
    public static final String PAGE_POSITION = "com.stario.FavoritesPage.PAGE_POSITION";

    private ScrollControlStaggeredGridLayoutManager manager;
    private OverScrollRecyclerView recyclerView;
    private ThemedActivity activity;
    private FeedPageAdapter adapter;
    private ArticleStateManager stateManager;
    private ViewGroup emptyView;
    private int position;
    private View title;
    private View tabs;

    public FavoritesPage() {
        // default
    }

    public FavoritesPage(int position) {
        this.position = position;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(PAGE_POSITION, position);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        if (!(context instanceof ThemedActivity)) {
            throw new RuntimeException("Parent activity is not of type ThemedActivity.");
        }

        this.activity = (ThemedActivity) context;
        super.onAttach(context);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            position = savedInstanceState.getInt(PAGE_POSITION, -1);
        }

        View root = inflater.inflate(R.layout.articles, container, false);

        assert container != null;
        View containerRoot = container.getRootView();
        title = containerRoot.findViewById(R.id.title_feeds);
        tabs = containerRoot.findViewById(R.id.tabs);

        recyclerView = root.findViewById(R.id.recycler_view);
        emptyView = root.findViewById(R.id.exception);
        
        // Hide the fetching view and refresh layout for favorites
        root.findViewById(R.id.fetching).setVisibility(View.GONE);
        root.findViewById(R.id.refresh).setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);

        recyclerView.setItemAnimator(new RecyclerItemAnimator(RecyclerItemAnimator.APPEARANCE, Animation.EXTENDED));

        SheetType type = SheetType.getSheetTypeForSheetDialogFragment(activity, BriefingDialog.class);
        if (type.getAxes() == View.SCROLL_AXIS_HORIZONTAL) {
            recyclerView.setOverscrollPullEdges(OverScrollEffect.PULL_EDGE_BOTTOM);
        }

        invalidateLayoutPadding();
        Measurements.addNavListener(value ->
                recyclerView.setPadding(recyclerView.getPaddingLeft(), recyclerView.getPaddingTop(),
                        recyclerView.getPaddingRight(), value));
        title.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop,
                                         oldRight, oldBottom) -> invalidateLayoutPadding());
        tabs.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop,
                                        oldRight, oldBottom) -> invalidateLayoutPadding());

        Measurements.addStatusBarListener(object ->
                recyclerView.setPadding(recyclerView.getPaddingLeft(), recyclerView.getPaddingTop(),
                        recyclerView.getPaddingRight(), Measurements.getNavHeight()));

        if (adapter == null) {
            adapter = new FeedPageAdapter(activity.getApplicationContext());
        }
        
        adapter.setFeedPosition(position);
        
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
                recyclerView.setPadding(recyclerView.getPaddingLeft(), recyclerView.getPaddingTop(),
                        recyclerView.getPaddingRight(), bottomInset));

        return root;
    }

    public void invalidateLayoutPadding() {
        int titleHeight = title.getMeasuredHeight();
        int tabsHeight = tabs.getMeasuredHeight();

        recyclerView.setPadding(recyclerView.getPaddingLeft(), Measurements.dpToPx(15) +
                titleHeight + tabsHeight, recyclerView.getPaddingRight(), Measurements.getNavHeight());
        emptyView.setPadding(0, (titleHeight + tabsHeight) / 2, 0, 0);
    }

    @Override
    public void onResume() {
        super.onResume();
        UiUtils.post(this::loadFavorites);
    }

    @Override
    public void onDestroy() {
        if (stateManager != null) {
            stateManager.removeStateChangeListener(this);
        }
        super.onDestroy();
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

    public RecyclerView getRecycler() {
        return recyclerView;
    }
}
