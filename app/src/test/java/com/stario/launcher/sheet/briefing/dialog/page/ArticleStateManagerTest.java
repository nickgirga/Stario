package com.stario.launcher.sheet.briefing.dialog.page;

import android.content.SharedPreferences;

import com.prof18.rssparser.model.RssItem;
import com.stario.launcher.themes.ThemedActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
public class ArticleStateManagerTest {

    @Mock
    private ThemedActivity mockActivity;

    @Mock
    private SharedPreferences mockPrefs;

    @Mock
    private RssItem mockRssItem;

    private ArticleStateManager manager;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockActivity.getApplicationContext()).thenReturn(mockActivity);
        when(mockActivity.getSharedPreferences(anyString())).thenReturn(mockPrefs);

        // Reset singleton
        ArticleStateManager.instance = null;
        manager = ArticleStateManager.from(mockActivity);
    }

    @Test
    public void testFrom_CreatesInstance() {
        assertNotNull(manager);
    }

    @Test
    public void testGetInstance_ReturnsInstance() {
        ArticleStateManager instance = ArticleStateManager.getInstance();
        assertNotNull(instance);
    }

    @Test(expected = RuntimeException.class)
    public void testGetInstance_ThrowsIfNotInitialized() {
        ArticleStateManager.instance = null;
        ArticleStateManager.getInstance();
    }

    @Test
    public void testMarkAsRead_AddsToReadSet() {
        when(mockRssItem.getLink()).thenReturn("http://example.com/article1");

        manager.markAsRead(mockRssItem);

        assertTrue(manager.isRead(mockRssItem));
    }

    @Test
    public void testMarkAsUnread_RemovesFromReadSet() {
        when(mockRssItem.getLink()).thenReturn("http://example.com/article1");

        manager.markAsRead(mockRssItem);
        assertTrue(manager.isRead(mockRssItem));

        manager.markAsUnread(mockRssItem);
        assertFalse(manager.isRead(mockRssItem));
    }

    @Test
    public void testToggleFavorite_AddsAndRemovesFavorite() {
        when(mockRssItem.getLink()).thenReturn("http://example.com/article1");

        // Initially not favorite
        assertFalse(manager.isFavorite(mockRssItem));

        // Toggle to favorite
        manager.toggleFavorite(mockRssItem, "Test Feed");
        assertTrue(manager.isFavorite(mockRssItem));

        // Toggle back
        manager.toggleFavorite(mockRssItem, "Test Feed");
        assertFalse(manager.isFavorite(mockRssItem));
    }

    @Test
    public void testGetFavorites_ReturnsCopy() {
        when(mockRssItem.getLink()).thenReturn("http://example.com/article1");

        manager.toggleFavorite(mockRssItem, "Test Feed");

        List<FavoriteArticle> favorites = manager.getFavorites();
        assertEquals(1, favorites.size());
        assertEquals("http://example.com/article1", favorites.get(0).articleId);
    }
}