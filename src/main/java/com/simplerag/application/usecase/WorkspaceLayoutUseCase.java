package com.simplerag.application.usecase;

import com.simplerag.application.dto.WorkspaceLayout;
import com.simplerag.application.port.in.ManageWorkspaceLayout;
import com.simplerag.application.port.out.SettingsRepository;

/**
 * Stores the window arrangement as one settings row.
 *
 * <p>One row rather than seven: the fields are only ever read and written together, and a half-applied
 * update would place a window at a size it was never closed at. Anything unparseable falls back to the
 * defaults, so a hand-edited or truncated value costs the arrangement and nothing else.
 */
public final class WorkspaceLayoutUseCase implements ManageWorkspaceLayout {
    private static final String KEY = "ui.workspace.layout";
    private static final int FIELDS = 7;

    private final SettingsRepository settings;

    public WorkspaceLayoutUseCase(SettingsRepository settings) {
        this.settings = settings;
    }

    @Override
    public WorkspaceLayout workspaceLayout() {
        return settings.getSetting(KEY).map(WorkspaceLayoutUseCase::parse)
                .orElseGet(WorkspaceLayout::defaults);
    }

    @Override
    public void saveWorkspaceLayout(WorkspaceLayout layout) {
        settings.putSetting(KEY, layout.x() + "," + layout.y() + "," + layout.width() + ","
                + layout.height() + "," + layout.maximized() + "," + layout.sidebarWidth() + ","
                + layout.contentScale());
    }

    private static WorkspaceLayout parse(String value) {
        String[] parts = value.split(",", -1);
        if (parts.length != FIELDS) return WorkspaceLayout.defaults();
        try {
            return new WorkspaceLayout(
                    Integer.parseInt(parts[0].strip()), Integer.parseInt(parts[1].strip()),
                    Integer.parseInt(parts[2].strip()), Integer.parseInt(parts[3].strip()),
                    Boolean.parseBoolean(parts[4].strip()), Integer.parseInt(parts[5].strip()),
                    Integer.parseInt(parts[6].strip()));
        } catch (NumberFormatException unusable) {
            return WorkspaceLayout.defaults();
        }
    }
}
