package com.simplerag.application.usecase;

import com.simplerag.application.dto.WorkspaceLayout;
import com.simplerag.application.port.out.SettingsRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceLayoutUseCaseTest {
    @Test
    void restoresTheArrangementItSaved() {
        MapSettings settings = new MapSettings();
        WorkspaceLayoutUseCase useCase = new WorkspaceLayoutUseCase(settings);
        useCase.saveWorkspaceLayout(new WorkspaceLayout(120, 40, 1600, 900, false, 340, 120));

        WorkspaceLayout restored = useCase.workspaceLayout();
        assertEquals(120, restored.x());
        assertEquals(1600, restored.width());
        assertEquals(340, restored.sidebarWidth());
        assertEquals(120, restored.contentScale());
        assertFalse(restored.maximized());
        assertTrue(restored.placed());
    }

    @Test
    void aMaximizedWindowIsRestoredMaximized() {
        MapSettings settings = new MapSettings();
        WorkspaceLayoutUseCase useCase = new WorkspaceLayoutUseCase(settings);
        useCase.saveWorkspaceLayout(new WorkspaceLayout(0, 0, 1440, 860, true, 300, 100));

        assertTrue(useCase.workspaceLayout().maximized());
    }

    @Test
    void theFirstLaunchHasNoSavedPosition() {
        assertFalse(new WorkspaceLayoutUseCase(new MapSettings()).workspaceLayout().placed());
    }

    /** A row written by an older build, or edited by hand, costs the arrangement and nothing else. */
    @Test
    void anUnusableRowFallsBackToDefaults() {
        MapSettings settings = new MapSettings();
        settings.putSetting("ui.workspace.layout", "120,40,1600");
        assertEquals(WorkspaceLayout.defaults(), new WorkspaceLayoutUseCase(settings).workspaceLayout());

        settings.putSetting("ui.workspace.layout", "120,40,wide,900,false,340,120");
        assertEquals(WorkspaceLayout.defaults(), new WorkspaceLayoutUseCase(settings).workspaceLayout());
    }

    /** Zoom is persisted, so a stored value out of range must not come back as an unreadable page. */
    @Test
    void anOutOfRangeContentScaleIsBroughtBackIntoRange() {
        MapSettings settings = new MapSettings();
        settings.putSetting("ui.workspace.layout", "0,0,1440,860,false,300,4000");
        assertEquals(WorkspaceLayout.MAX_CONTENT_SCALE,
                new WorkspaceLayoutUseCase(settings).workspaceLayout().contentScale());
    }

    private static final class MapSettings implements SettingsRepository {
        private final Map<String, String> values = new HashMap<>();
        @Override public Optional<String> getSetting(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public void putSetting(String key, String value) { values.put(key, value); }
    }
}
