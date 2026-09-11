package com.simplerag.adapter.in.swing;

import com.formdev.flatlaf.ui.FlatUIUtils;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Insets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeTest {
    /**
     * The focus ring wraps the look-and-feel border instead of replacing it, because replacing it
     * costs the rounded corners and any later {@code setMargin}. Both are pinned here: the styled
     * button has to measure and round exactly like the unstyled one it is derived from.
     */
    @Test
    void theFocusRingKeepsButtonGeometryAndRoundedCorners() throws Exception {
        Theme.install();
        SwingUtilities.invokeAndWait(() -> {
            JButton styled = new JButton("获取模型");
            JButton reference = new JButton("获取模型");
            Theme.styleButton(styled, false);
            reference.setMargin(new Insets(9, 13, 9, 13));

            assertEquals(reference.getInsets(), styled.getInsets());
            assertEquals(reference.getPreferredSize(), styled.getPreferredSize());
            assertEquals(FlatUIUtils.getBorderArc(reference), FlatUIUtils.getBorderArc(styled),
                    "a border the look and feel cannot read falls back to square corners");
            assertTrue(FlatUIUtils.getBorderArc(styled) > 0);

            // Panels re-tighten padding after styling, so margin has to keep reaching the border.
            styled.setMargin(new Insets(5, 8, 5, 8));
            reference.setMargin(new Insets(5, 8, 5, 8));
            assertEquals(reference.getInsets(), styled.getInsets());
        });
    }

    @Test
    void contentScaleAppliesToReadingSizesAndStaysInRange() {
        try {
            Theme.contentScale(130);
            assertEquals(130, Theme.contentScale());
            assertEquals(13f * 1.3f, Theme.contentSize(13f), 0.001f);

            Theme.contentScale(500);
            assertEquals(200, Theme.contentScale(), "unbounded zoom would make the layout unusable");
            Theme.contentScale(10);
            assertEquals(80, Theme.contentScale());
        } finally {
            Theme.contentScale(100);
        }
    }
}
