package com.simplerag.adapter.in.swing;

import com.simplerag.application.dto.WorkspaceLayout;
import com.simplerag.application.port.in.ManageWorkspaceLayout;

import javax.swing.JFrame;
import javax.swing.JSplitPane;
import javax.swing.Timer;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.function.Consumer;

/**
 * Where the window sits, how wide the sidebar is and how large the reading text is.
 *
 * <p>Split out of the workspace controller because none of it is about the knowledge base: it watches
 * one frame and one divider and writes a single settings row.
 */
final class WorkspaceLayoutCoordinator {
    private final ManageWorkspaceLayout layout;
    private final Runnable applyContentScale;
    private final Consumer<String> flashStatus;
    private final Timer saveTimer;
    private JFrame window;
    private JSplitPane sidebarSplit;
    /** Bounds to restore to. A maximized window reports the screen, which is not worth saving. */
    private Rectangle normalBounds;

    WorkspaceLayoutCoordinator(ManageWorkspaceLayout layout, Runnable applyContentScale,
                               Consumer<String> flashStatus) {
        this.layout = layout;
        this.applyContentScale = applyContentScale;
        this.flashStatus = flashStatus;
        // Dragging a window fires hundreds of events; only the arrangement it settles on is worth a write.
        this.saveTimer = new Timer(800, event -> save());
        saveTimer.setRepeats(false);
    }

    /**
     * Puts the window back where it was last closed and keeps watching it.
     *
     * <p>Saved bounds are only trusted when they still land on a screen that exists: a window restored
     * onto a monitor that has since been unplugged is one the user cannot reach or move.
     */
    void restore(JFrame frame, JSplitPane split) {
        this.window = frame;
        this.sidebarSplit = split;
        WorkspaceLayout saved = layout.workspaceLayout();
        Theme.contentScale(saved.contentScale());
        applyContentScale.run();
        split.setDividerLocation(Math.max(200, Math.min(640, saved.sidebarWidth())));
        Rectangle bounds = new Rectangle(saved.x(), saved.y(), saved.width(), saved.height());
        if (saved.placed() && reachable(bounds)) {
            frame.setBounds(bounds);
        } else {
            frame.setSize(Math.max(1120, saved.width()), Math.max(680, saved.height()));
            frame.setLocationRelativeTo(null);
        }
        normalBounds = frame.getBounds();
        if (saved.maximized()) frame.setExtendedState(Frame.MAXIMIZED_BOTH);
        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { windowChanged(); }
            @Override public void componentMoved(ComponentEvent event) { windowChanged(); }
        });
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,
                event -> saveTimer.restart());
    }

    /**
     * Reading size for the answer transcript, the chunk preview and the file page.
     *
     * @param deltaPercent step to apply, or {@code 0} to go back to the design size
     */
    void zoomContent(int deltaPercent) {
        int previous = Theme.contentScale();
        Theme.contentScale(deltaPercent == 0
                ? WorkspaceLayout.DEFAULT_CONTENT_SCALE : previous + deltaPercent);
        if (Theme.contentScale() == previous) {
            if (deltaPercent != 0) flashStatus.accept("正文字号已到上限或下限：" + previous + "%");
            return;
        }
        applyContentScale.run();
        flashStatus.accept("正文字号 " + Theme.contentScale() + "%  ·  Ctrl+0 恢复默认");
        saveTimer.restart();
    }

    void close() {
        saveTimer.stop();
        save();
    }

    private void windowChanged() {
        if (window != null && !maximized()) normalBounds = window.getBounds();
        saveTimer.restart();
    }

    private boolean maximized() {
        return window != null
                && (window.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
    }

    private void save() {
        if (window == null) return;
        Rectangle bounds = normalBounds == null ? window.getBounds() : normalBounds;
        int divider = sidebarSplit == null
                ? WorkspaceLayout.DEFAULT_SIDEBAR_WIDTH : sidebarSplit.getDividerLocation();
        try {
            layout.saveWorkspaceLayout(new WorkspaceLayout(bounds.x, bounds.y, bounds.width,
                    bounds.height, maximized(), divider, Theme.contentScale()));
        } catch (RuntimeException unwritable) {
            // Losing the arrangement is not worth interrupting a shutdown or a window drag over.
        }
    }

    private static boolean reachable(Rectangle bounds) {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle visible = device.getDefaultConfiguration().getBounds().intersection(bounds);
            if (visible.width >= 240 && visible.height >= 120) return true;
        }
        return false;
    }
}
