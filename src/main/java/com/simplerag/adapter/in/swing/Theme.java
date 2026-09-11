package com.simplerag.adapter.in.swing;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

final class Theme {
    static final Color BACKGROUND = new Color(15, 18, 21);
    static final Color PANEL = new Color(21, 25, 29);
    static final Color PANEL_ALT = new Color(27, 32, 37);
    static final Color HOVER = new Color(38, 45, 50);
    static final Color BORDER = new Color(48, 56, 62);
    static final Color TEXT = new Color(235, 239, 241);
    static final Color MUTED = new Color(145, 156, 164);
    static final Color ACCENT = new Color(62, 201, 166);
    static final Color ACCENT_DARK = new Color(35, 134, 109);
    static final Color AMBER = new Color(238, 178, 85);
    static final Color RED = new Color(224, 105, 105);
    static final Font UI_FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
    static final Font MONO_FONT = new Font("JetBrains Mono", Font.PLAIN, 13);

    /** Reading size in percent of the design size; see {@link #contentSize(float)}. */
    private static int contentScale = 100;

    private Theme() {
    }

    static void install() {
        FlatDarkLaf.setup();
        UIManager.put("Label.font", UI_FONT);
        UIManager.put("Button.font", UI_FONT);
        UIManager.put("TextField.font", UI_FONT.deriveFont(14f));
        UIManager.put("ComboBox.font", UI_FONT);
        UIManager.put("List.font", UI_FONT);
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("OptionPane.background", PANEL);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("ToolTip.background", PANEL_ALT);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("Component.arc", 6);
        UIManager.put("Button.arc", 6);
        UIManager.put("TextComponent.arc", 6);
        UIManager.put("ScrollBar.width", 11);
        UIManager.put("Component.focusColor", ACCENT_DARK);
        UIManager.put("Component.focusedBorderColor", ACCENT);
        UIManager.put("Component.borderColor", BORDER);
        UIManager.put("TextField.background", BACKGROUND);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("ComboBox.background", PANEL_ALT);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("List.selectionBackground", HOVER);
        UIManager.put("List.selectionForeground", TEXT);
        // Keyboard focus has to be visible inside a list too, not only around it.
        UIManager.put("List.focusCellHighlightBorder", BorderFactory.createLineBorder(ACCENT, 1));
        UIManager.put("CheckBox.icon.focusedBorderColor", ACCENT);
    }

    /**
     * Reading font size for a design size of {@code base}. Only the surfaces that carry document and
     * answer text scale: chrome keeps its size so the layout stays predictable while zooming.
     */
    static float contentSize(float base) {
        return base * contentScale / 100f;
    }

    static Font contentFont(Font base, float size) {
        return base.deriveFont(contentSize(size));
    }

    static int contentScale() {
        return contentScale;
    }

    static void contentScale(int percent) {
        contentScale = Math.max(80, Math.min(200, percent));
    }

    static Border padding(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    static Border sectionBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, BORDER), padding(18, 18, 18, 18));
    }

    static void styleButton(JButton button, boolean primary) {
        button.setOpaque(true);
        button.setFocusPainted(false);
        button.setForeground(primary ? new Color(9, 30, 25) : TEXT);
        button.setBackground(primary ? ACCENT : PANEL_ALT);
        button.setMargin(new Insets(9, 13, 9, 13));
        button.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        // An accent ring on an accent button would be invisible, so a primary one is ringed in white.
        focusRing(button, primary ? TEXT : ACCENT);
    }

    /**
     * Makes keyboard focus visible on a button whose look-and-feel border is switched off.
     *
     * <p>These buttons paint their own flat background, so the focus indicator the look and feel draws
     * in its border never appears. The ring wraps that border rather than replacing it: the wrapped
     * border still supplies the insets, so {@link JButton#setMargin} keeps working and focusing a
     * button never resizes it, and the look and feel still reads the corner radius off it.
     */
    static void focusRing(JButton button, Color color) {
        Border existing = button.getBorder();
        if (existing == null) return;
        button.setBorder(new FocusRing(existing, color));
        button.setBorderPainted(true);
    }

    static void opaque(JComponent component, Color color) {
        component.setOpaque(true);
        component.setBackground(color);
    }

    /**
     * Draws a focus ring and nothing else.
     *
     * <p>A {@link CompoundBorder} rather than a bare {@link Border} on purpose: FlatLaf finds a
     * component's corner radius by walking to the outside border of a compound one, and a border it
     * cannot recognize makes it fall back to square corners. Painting is overridden instead of
     * delegated, so the wrapped border contributes its geometry without drawing its own outline.
     */
    private static final class FocusRing extends CompoundBorder {
        private final Color color;

        private FocusRing(Border laf, Color color) {
            super(laf, null);
            this.color = color;
        }

        @Override
        public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
            if (!component.isFocusOwner()) return;
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(2f));
            g2.drawRoundRect(x + 1, y + 1, width - 3, height - 3, 8, 8);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component component) {
            return outsideBorder.getBorderInsets(component);
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }
    }
}
