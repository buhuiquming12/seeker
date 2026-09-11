package com.simplerag.adapter.in.swing;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;

/**
 * What the user looks at while the application opens its database and restores an index.
 *
 * <p>That work takes seconds on a large knowledge base and used to happen before any window existed,
 * so launching looked like nothing happening. Stages are named rather than counted: the slow one is
 * restoring the index, and a percentage would sit at the same number for most of the wait.
 */
public final class StartupProgressWindow {
    private final JWindow window = new JWindow();
    private final JLabel stage = new JLabel("正在启动…");
    private final JProgressBar progress = new JProgressBar();
    private final JButton close = new JButton("关闭");

    /** Must be called on the event dispatch thread. */
    public StartupProgressWindow() {
        JPanel content = new JPanel(new BorderLayout(0, 14));
        Theme.opaque(content, Theme.PANEL);
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), Theme.padding(24, 28, 22, 28)));

        JPanel brand = new JPanel();
        brand.setOpaque(false);
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("SimpleRAG");
        title.setForeground(Theme.TEXT);
        title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 22f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel subtitle = new JLabel("LOCAL KNOWLEDGE WORKSPACE");
        subtitle.setForeground(Theme.MUTED);
        subtitle.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 9f));
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        brand.add(title);
        brand.add(Box.createVerticalStrut(3));
        brand.add(subtitle);
        content.add(brand, BorderLayout.NORTH);

        JPanel state = new JPanel(new BorderLayout(0, 10));
        state.setOpaque(false);
        stage.setForeground(Theme.MUTED);
        stage.setFont(Theme.UI_FONT.deriveFont(11f));
        progress.setIndeterminate(true);
        progress.setForeground(Theme.ACCENT);
        progress.setBackground(Theme.BORDER);
        progress.setPreferredSize(new Dimension(360, 5));
        state.add(stage, BorderLayout.NORTH);
        state.add(progress, BorderLayout.CENTER);
        close.setVisible(false);
        Theme.styleButton(close, false);
        close.addActionListener(event -> System.exit(1));
        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        actions.add(close, BorderLayout.EAST);
        state.add(actions, BorderLayout.SOUTH);
        content.add(state, BorderLayout.CENTER);

        window.setContentPane(content);
        window.pack();
        window.setLocationRelativeTo(null);
        window.setVisible(true);
    }

    /** Callable from the startup thread. */
    public void stage(String text) {
        SwingUtilities.invokeLater(() -> stage.setText(text));
    }

    /**
     * Startup failed. The window stays up and says so, because the alternative is a process that
     * exits without ever having drawn anything.
     */
    public void failed(Throwable failure) {
        SwingUtilities.invokeLater(() -> {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            String message = cause.getMessage();
            stage.setForeground(Theme.RED);
            stage.setText("<html>启动失败：" + (message == null ? cause.toString() : message) + "</html>");
            progress.setIndeterminate(false);
            progress.setVisible(false);
            close.setVisible(true);
            window.pack();
        });
    }

    public void close() {
        window.dispose();
    }
}
