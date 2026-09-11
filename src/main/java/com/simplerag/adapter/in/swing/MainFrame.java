package com.simplerag.adapter.in.swing;

import com.simplerag.model.KnowledgeBase;
import com.simplerag.application.diagnostics.DiagnosticReportService;
import com.simplerag.application.port.in.ManageWorkspaceLayout;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;

/** Window composition, page navigation and application-close flow. */
public final class MainFrame extends JFrame {
    private static final String SEARCH_MODE = "search";
    private static final String ASK_MODE = "ask";
    private static final String FILE_MODE = "file";
    private static final String DIAGNOSTIC_MODE = "diagnostic";
    private static final String SETTINGS_MODE = "settings";
    private final CardLayout modeLayout = new CardLayout();
    private final JPanel modeCards = new JPanel(modeLayout);
    private final JLabel currentKnowledge = new JLabel();
    private final JButton searchMode = new JButton("语义检索");
    private final JButton askMode = new JButton("知识问答");
    private final JButton fileMode = new JButton("文件");
    private final JButton diagnosticMode = new JButton("诊断信息");
    private final JButton settingsMode = new JButton("设置");
    private final DesktopWorkspaceController workspace;
    /** Sidebar divider, kept as a field because its position is part of the restored arrangement. */
    private JSplitPane split;

    public MainFrame(KnowledgeController knowledge, SearchController search, AskController ask,
                     FileBrowserController browser, BackgroundTaskCoordinator tasks,
                     DesktopFileGateway files, ManageWorkspaceLayout layout,
                     DiagnosticReportService diagnostics) {
        super("SimpleRAG - 本地语义知识库");
        this.workspace = new DesktopWorkspaceController(knowledge, search, ask, browser, tasks, files,
                layout, this::showCurrentKnowledge, () -> showMode(FILE_MODE), diagnostics);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1120, 680));
        setContentPane(buildContent());
        installNavigation();
        workspace.restoreWindow(this, split);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { workspace.close(); }
        });
        showMode(SEARCH_MODE);
    }

    public void initializeKnowledge(Path demoRoot) { workspace.initializeKnowledge(demoRoot); }

    private JPanel buildContent() {
        JPanel content = new JPanel(new BorderLayout());
        Theme.opaque(content, Theme.BACKGROUND);
        content.add(buildHeader(), BorderLayout.NORTH);
        content.add(buildBody(), BorderLayout.CENTER);
        content.add(workspace.statusBar(), BorderLayout.SOUTH);
        return content;
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(24, 0));
        Theme.opaque(header, Theme.PANEL);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), Theme.padding(11, 18, 11, 18)));
        JPanel brand = new JPanel(); brand.setOpaque(false); brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("SimpleRAG"); title.setForeground(Theme.TEXT); title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 20f));
        JLabel subtitle = new JLabel("LOCAL KNOWLEDGE WORKSPACE"); subtitle.setForeground(Theme.MUTED); subtitle.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 9f));
        brand.add(title); brand.add(subtitle); brand.setPreferredSize(new Dimension(220, 42));
        JPanel modes = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2)); modes.setOpaque(false);
        styleModeButton(searchMode); styleModeButton(askMode); styleModeButton(fileMode);
        styleModeButton(diagnosticMode); styleModeButton(settingsMode);
        modes.add(searchMode); modes.add(askMode); modes.add(fileMode); modes.add(diagnosticMode); modes.add(settingsMode);
        currentKnowledge.setForeground(Theme.MUTED); currentKnowledge.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 12f));
        currentKnowledge.setBorder(Theme.padding(0, 8, 0, 4));
        header.add(brand, BorderLayout.WEST); header.add(modes, BorderLayout.CENTER); header.add(currentKnowledge, BorderLayout.EAST);
        return header;
    }

    private Component buildBody() {
        modeCards.setOpaque(true); modeCards.setBackground(Theme.BACKGROUND);
        modeCards.add(workspace.searchPanel(), SEARCH_MODE); modeCards.add(workspace.askPanel(), ASK_MODE);
        modeCards.add(workspace.fileViewerPanel(), FILE_MODE);
        modeCards.add(workspace.diagnosticPanel(), DIAGNOSTIC_MODE);
        modeCards.add(workspace.settingsPanel(), SETTINGS_MODE);
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, workspace.knowledgePanel(), modeCards);
        split.setDividerSize(1); split.setResizeWeight(0); split.setBorder(null); split.setBackground(Theme.BORDER);
        return split;
    }

    private void installNavigation() {
        searchMode.addActionListener(event -> showMode(SEARCH_MODE));
        askMode.addActionListener(event -> showMode(ASK_MODE));
        fileMode.addActionListener(event -> showMode(FILE_MODE));
        diagnosticMode.addActionListener(event -> { workspace.diagnosticPanel().refresh(); showMode(DIAGNOSTIC_MODE); });
        settingsMode.addActionListener(event -> showMode(SETTINGS_MODE));
        shortcut(KeyEvent.VK_K, "focusSearch", () -> { showMode(SEARCH_MODE); workspace.focusSearch(); });
        // Both rows of +/- keys, because which one a keyboard reports depends on the layout.
        for (int key : new int[]{KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD}) {
            shortcut(key, "zoomIn" + key, () -> workspace.zoomContent(10));
        }
        for (int key : new int[]{KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT}) {
            shortcut(key, "zoomOut" + key, () -> workspace.zoomContent(-10));
        }
        for (int key : new int[]{KeyEvent.VK_0, KeyEvent.VK_NUMPAD0}) {
            shortcut(key, "zoomReset" + key, () -> workspace.zoomContent(0));
        }
    }

    /**
     * Window-wide shortcut. Bound on the ancestor map rather than the root pane's own focus map, which
     * only fires while the root pane itself holds focus - something that effectively never happens.
     */
    private void shortcut(int keyCode, String name, Runnable action) {
        getRootPane().getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                KeyStroke.getKeyStroke(keyCode, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), name);
        getRootPane().getActionMap().put(name, new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }

    private void showMode(String mode) {
        modeLayout.show(modeCards, mode);
        for (JButton button : java.util.List.of(searchMode, askMode, fileMode, diagnosticMode, settingsMode)) {
            button.setForeground(Theme.TEXT);
        }
        searchMode.setBackground(SEARCH_MODE.equals(mode) ? Theme.ACCENT_DARK : Theme.PANEL_ALT);
        askMode.setBackground(ASK_MODE.equals(mode) ? Theme.ACCENT_DARK : Theme.PANEL_ALT);
        fileMode.setBackground(FILE_MODE.equals(mode) ? Theme.ACCENT_DARK : Theme.PANEL_ALT);
        diagnosticMode.setBackground(DIAGNOSTIC_MODE.equals(mode) ? Theme.ACCENT_DARK : Theme.PANEL_ALT);
        settingsMode.setBackground(SETTINGS_MODE.equals(mode) ? Theme.ACCENT_DARK : Theme.PANEL_ALT);
    }

    private void showCurrentKnowledge(KnowledgeBase knowledge) {
        currentKnowledge.setText(knowledge.name()); currentKnowledge.setToolTipText(knowledge.description());
    }

    private static void styleModeButton(JButton button) {
        Theme.styleButton(button, false); button.setMargin(new Insets(8, 16, 8, 16));
    }
}
