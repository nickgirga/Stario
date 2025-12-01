package com.stario.launcher.sheet.briefing.sync;

import android.content.SharedPreferences;

import com.stario.launcher.themes.ThemedActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
public class NextcloudSyncServiceTest {

    @Mock
    private ThemedActivity mockActivity;

    @Mock
    private SharedPreferences mockPrefs;

    @Mock
    private NextcloudSSOHelper mockSsoHelper;

    private NextcloudSyncService syncService;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockActivity.getApplicationContext()).thenReturn(mockActivity);
        when(mockActivity.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs);

        // Reset singleton for testing
        NextcloudSyncService.instance = null;
        syncService = NextcloudSyncService.from(mockActivity);
    }

    @Test
    public void testFrom_CreatesInstance() {
        assertNotNull(syncService);
    }

    @Test
    public void testGetInstance_ReturnsInstance() {
        NextcloudSyncService instance = NextcloudSyncService.getInstance();
        assertNotNull(instance);
    }

    @Test(expected = RuntimeException.class)
    public void testGetInstance_ThrowsIfNotInitialized() {
        NextcloudSyncService.instance = null;
        NextcloudSyncService.getInstance();
    }

    // More tests would require mocking HTTP calls, which is complex.
    // For demonstration, a simple test.
}