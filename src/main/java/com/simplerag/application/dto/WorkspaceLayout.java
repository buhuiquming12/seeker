package com.simplerag.application.dto;

/**
 * Window geometry and reading preferences the workspace restores on the next launch.
 *
 * @param x left edge, or {@link #UNPLACED} when the window has never been positioned
 * @param y top edge, or {@link #UNPLACED}
 * @param width restored width; ignored when {@link #placed()} is false
 * @param height restored height; ignored when {@link #placed()} is false
 * @param maximized whether the window was maximized when it was last closed
 * @param sidebarWidth divider position between the knowledge sidebar and the current page
 * @param contentScale reading font size in percent of the design size
 */
public record WorkspaceLayout(int x, int y, int width, int height, boolean maximized,
                              int sidebarWidth, int contentScale) {
    public static final int UNPLACED = Integer.MIN_VALUE;
    public static final int DEFAULT_SIDEBAR_WIDTH = 300;
    public static final int DEFAULT_CONTENT_SCALE = 100;
    public static final int MIN_CONTENT_SCALE = 80;
    public static final int MAX_CONTENT_SCALE = 200;

    public WorkspaceLayout {
        contentScale = Math.max(MIN_CONTENT_SCALE, Math.min(MAX_CONTENT_SCALE, contentScale));
    }

    /** First launch: centred at the design size, sidebar at its design width, no zoom. */
    public static WorkspaceLayout defaults() {
        return new WorkspaceLayout(UNPLACED, UNPLACED, 1440, 860, false, DEFAULT_SIDEBAR_WIDTH,
                DEFAULT_CONTENT_SCALE);
    }

    /** Whether a saved position exists at all; the caller still has to check it against a screen. */
    public boolean placed() {
        return x != UNPLACED && y != UNPLACED && width > 0 && height > 0;
    }

    public WorkspaceLayout withContentScale(int scale) {
        return new WorkspaceLayout(x, y, width, height, maximized, sidebarWidth, scale);
    }
}
