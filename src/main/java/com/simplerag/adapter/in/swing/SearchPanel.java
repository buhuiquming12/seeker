package com.simplerag.adapter.in.swing;

import com.simplerag.application.dto.DocumentReference;
import com.simplerag.application.dto.SearchResultView;
import com.simplerag.model.SemanticHighlight;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Search page and sole owner of query, result and preview Swing state. */
public final class SearchPanel extends JPanel {
    private static final Pattern HIGHLIGHT_TERM = Pattern.compile("[\\p{L}\\p{N}_-]{2,}");
    /** Design size of the chunk preview, which follows the reading scale. */
    private static final float PREVIEW_SIZE = 13f;
    private final DefaultListModel<SearchResultView> results = new DefaultListModel<>();
    private final JList<SearchResultView> resultList = new JList<>(results);
    private final PromptTextField query = new PromptTextField("搜索笔记、代码和配置...");
    private final JComboBox<String> extension = new JComboBox<>();
    private final JLabel summary = new JLabel("输入内容开始检索");
    private final JTextArea preview = new JTextArea();
    private final JTextArea lineNumbers = new JTextArea();
    private final JLabel previewTitle = new JLabel("选择一个结果");
    private final JLabel previewMeta = new JLabel(" ");
    private final JButton open = new JButton("系统打开");
    private final JButton locate = new JButton("目录");
    private final JButton copy = new JButton("复制");
    private final JButton openInApp = new JButton("应用内打开");

    public SearchPanel(Runnable onQueryChanged, Consumer<SearchResultView> onSelection,
                       Runnable onOpen, Runnable onLocate, Runnable onCopy, Runnable onOpenInApp) {
        super(new BorderLayout());
        Theme.opaque(this, Theme.BACKGROUND);
        add(buildToolbar(), BorderLayout.NORTH);
        JSplitPane details = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildResults(), buildPreview());
        details.setDividerLocation(430); details.setDividerSize(1); details.setResizeWeight(0.38);
        details.setBorder(null); details.setBackground(Theme.BORDER); add(details, BorderLayout.CENTER);
        query.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { onQueryChanged.run(); }
            public void removeUpdate(DocumentEvent event) { onQueryChanged.run(); }
            public void changedUpdate(DocumentEvent event) { onQueryChanged.run(); }
        });
        extension.addActionListener(event -> onQueryChanged.run());
        query.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearSearch");
        query.getActionMap().put("clearSearch", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { if (!query.getText().isEmpty()) query.setText(""); }
        });
        resultList.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) onSelection.accept(resultList.getSelectedValue()); });
        // Same gesture as the explorer tree: double click stays inside the application.
        resultList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && resultList.getSelectedValue() != null) onOpenInApp.run();
            }
        });
        open.addActionListener(event -> onOpen.run()); locate.addActionListener(event -> onLocate.run()); copy.addActionListener(event -> onCopy.run());
        openInApp.addActionListener(event -> onOpenInApp.run());
    }

    public String query() { return query.getText().strip(); }
    public String extension() { return String.valueOf(extension.getSelectedItem()); }
    public SearchResultView selected() { return resultList.getSelectedValue(); }
    public void focusQuery() { query.requestFocusInWindow(); query.selectAll(); }

    /** Redraws the chunk preview at the current reading scale; the gutter has to match line for line. */
    public void applyContentScale() {
        preview.setFont(Theme.contentFont(Theme.MONO_FONT, PREVIEW_SIZE));
        lineNumbers.setFont(Theme.contentFont(Theme.MONO_FONT, PREVIEW_SIZE));
    }
    public void clear() { results.clear(); query.setText(""); preview(null); }
    public void searching() { results.clear(); summary.setText("检索中..."); }
    public void searchFailed() { summary.setText("检索失败"); }
    public void results(List<SearchResultView> values) {
        results.clear(); values.forEach(results::addElement); summary.setText(values.size() + " 个匹配");
        if (!values.isEmpty()) resultList.setSelectedIndex(0); else preview(null);
    }
    public void extensions(Set<String> values) {
        Object selected = extension.getSelectedItem(); DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        model.addElement("全部"); values.forEach(model::addElement); extension.setModel(model);
        if (selected != null) extension.setSelectedItem(selected);
    }

    public void preview(SearchResultView result) {
        boolean present = result != null; open.setEnabled(present); locate.setEnabled(present); copy.setEnabled(present);
        openInApp.setEnabled(present);
        preview.getHighlighter().removeAllHighlights();
        if (!present) { previewTitle.setText("选择一个结果"); previewMeta.setText(" "); preview.setText(""); lineNumbers.setText(""); return; }
        DocumentReference document = result.document(); previewTitle.setText(document.fileName()); previewTitle.setToolTipText(document.path().toString());
        previewMeta.setText(baseMeta(document)); previewMeta.setToolTipText(document.path().toString());
        preview.setText(document.content()); preview.setCaretPosition(0); StringBuilder numbers = new StringBuilder();
        for (int line = document.startLine(); line <= document.endLine(); line++) numbers.append(line).append('\n');
        lineNumbers.setText(numbers.toString()); highlightQuery();
    }
    public void semanticLoading(DocumentReference document) { previewMeta.setText(baseMeta(document) + "  ·  定位语义片段..."); }
    public void semanticReset(DocumentReference document) { previewMeta.setText(baseMeta(document)); }
    public void semanticHighlights(DocumentReference document, List<SemanticHighlight> highlights) {
        preview.getHighlighter().removeAllHighlights();
        Highlighter.HighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(new Color(38, 104, 83));
        for (SemanticHighlight highlight : highlights) {
            try { preview.getHighlighter().addHighlight(highlight.startOffset(), highlight.endOffset(), painter); }
            catch (javax.swing.text.BadLocationException ignored) { }
        }
        highlightQuery();
        if (highlights.isEmpty()) semanticReset(document);
        else { double best = highlights.stream().mapToDouble(SemanticHighlight::similarity).max().orElse(0);
            previewMeta.setText(baseMeta(document) + "  ·  语义片段 " + Math.round(best * 100) + "%"); preview.setCaretPosition(highlights.get(0).startOffset()); }
    }

    private void highlightQuery() {
        String queryText = query.getText().toLowerCase(Locale.ROOT); String content = preview.getText().toLowerCase(Locale.ROOT);
        Matcher matcher = HIGHLIGHT_TERM.matcher(queryText); Highlighter.HighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(new Color(89, 93, 52));
        int count = 0; while (matcher.find() && count < 80) { String term = matcher.group(); int offset = 0;
            while ((offset = content.indexOf(term, offset)) >= 0 && count < 80) { try { preview.getHighlighter().addHighlight(offset, offset + term.length(), painter); }
                catch (javax.swing.text.BadLocationException ignored) { break; } offset += term.length(); count++; } }
    }
    private static String baseMeta(DocumentReference document) { return document.path() + "  ·  " + document.sourceLocation(); }

    private JPanel buildToolbar() { JPanel panel = new JPanel(new BorderLayout(12, 0)); Theme.opaque(panel, Theme.PANEL_ALT);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), Theme.padding(13, 16, 13, 16)));
        query.setPreferredSize(new Dimension(560, 39)); query.setToolTipText("输入自然语言、关键词或代码标识符");
        extension.setModel(new DefaultComboBoxModel<>(new String[]{"全部"})); extension.setPreferredSize(new Dimension(115, 36));
        panel.add(query, BorderLayout.CENTER); panel.add(extension, BorderLayout.EAST); return panel; }
    private JPanel buildResults() { JPanel panel = new JPanel(new BorderLayout(0, 10)); Theme.opaque(panel, Theme.BACKGROUND);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER), Theme.padding(15, 14, 10, 14)));
        JPanel heading = new JPanel(new BorderLayout()); heading.setOpaque(false); JLabel title = new JLabel("检索结果"); title.setForeground(Theme.TEXT); title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 12f)); heading.add(title, BorderLayout.WEST);
        summary.setForeground(Theme.MUTED); summary.setFont(Theme.UI_FONT.deriveFont(11f)); heading.add(summary, BorderLayout.EAST);
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); resultList.setFixedCellHeight(108); resultList.setBackground(Theme.BACKGROUND); resultList.setCellRenderer(new ResultRenderer());
        panel.add(heading, BorderLayout.NORTH); panel.add(scroll(resultList), BorderLayout.CENTER); return panel; }
    private JPanel buildPreview() { JPanel panel = new JPanel(new BorderLayout(0, 12)); Theme.opaque(panel, Theme.PANEL_ALT); panel.setBorder(Theme.padding(15, 16, 12, 16));
        JPanel titlePanel = new JPanel(new BorderLayout(12, 4)); titlePanel.setOpaque(false); JPanel labels = new JPanel(); labels.setOpaque(false); labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        previewTitle.setForeground(Theme.TEXT); previewTitle.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 15f)); previewMeta.setForeground(Theme.MUTED); previewMeta.setFont(Theme.UI_FONT.deriveFont(10f)); labels.add(previewTitle); labels.add(Box.createVerticalStrut(3)); labels.add(previewMeta);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0)); actions.setOpaque(false); for (JButton button : List.of(copy, locate, open, openInApp)) { Theme.styleButton(button, button == openInApp); button.setMargin(new Insets(6, 10, 6, 10)); actions.add(button); }
        copy.setToolTipText("复制该片段的正文"); locate.setToolTipText("在文件资源管理器中打开所在目录");
        open.setToolTipText("用系统默认程序打开该文件");
        openInApp.setToolTipText("在应用内的文件页打开，并定位到这个片段（双击结果同样生效）");
        titlePanel.add(labels, BorderLayout.CENTER); titlePanel.add(actions, BorderLayout.EAST); preview.setEditable(false); preview.setFont(Theme.contentFont(Theme.MONO_FONT, PREVIEW_SIZE)); preview.setBackground(Theme.PANEL_ALT); preview.setForeground(new Color(218, 226, 230)); preview.setCaretColor(Theme.ACCENT); preview.setTabSize(4); preview.setBorder(Theme.padding(10, 10, 10, 10));
        lineNumbers.setEditable(false); lineNumbers.setFont(Theme.contentFont(Theme.MONO_FONT, PREVIEW_SIZE)); lineNumbers.setBackground(Theme.PANEL); lineNumbers.setForeground(new Color(104, 116, 124)); lineNumbers.setBorder(Theme.padding(10, 8, 10, 8)); lineNumbers.setFocusable(false);
        JScrollPane scroll = scroll(preview); scroll.setRowHeaderView(lineNumbers); scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER)); panel.add(titlePanel, BorderLayout.NORTH); panel.add(scroll, BorderLayout.CENTER); return panel; }
    private static JScrollPane scroll(Component component) { JScrollPane pane = new JScrollPane(component); pane.setBorder(null); pane.getViewport().setBackground(Theme.PANEL); return pane; }

    private static final class PromptTextField extends JTextField { private final String prompt; private PromptTextField(String prompt) { this.prompt = prompt; setOpaque(false); setForeground(Theme.TEXT); setCaretColor(Theme.ACCENT); outline(Theme.BORDER);
        // The look and feel draws its focus ring in a border this field replaces, so it draws its own.
        addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) { outline(Theme.ACCENT); }
            @Override public void focusLost(java.awt.event.FocusEvent event) { outline(Theme.BORDER); }
        }); }
        private void outline(Color color) { setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(color), Theme.padding(8, 12, 8, 12))); repaint(); }
        @Override protected void paintComponent(Graphics graphics) { graphics.setColor(Theme.PANEL); graphics.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8); super.paintComponent(graphics); if (getText().isEmpty() && !isFocusOwner()) { graphics.setColor(Theme.MUTED); graphics.setFont(getFont()); graphics.drawString(prompt, getInsets().left, getHeight() / 2 + graphics.getFontMetrics().getAscent() / 2 - 2); } } }
    private static final class ResultRenderer extends JPanel implements javax.swing.ListCellRenderer<SearchResultView> { private final JLabel title = new JLabel(); private final JLabel score = new JLabel(); private final JLabel meta = new JLabel(); private final JTextArea excerpt = new JTextArea();
        private ResultRenderer() { super(new BorderLayout(8, 4)); JPanel top = new JPanel(new BorderLayout()); top.setOpaque(false); title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 12f)); score.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 10f)); top.add(title, BorderLayout.CENTER); top.add(score, BorderLayout.EAST); excerpt.setEditable(false); excerpt.setLineWrap(true); excerpt.setWrapStyleWord(true); excerpt.setRows(2); excerpt.setFont(Theme.UI_FONT.deriveFont(10.5f)); excerpt.setBorder(null); meta.setFont(Theme.UI_FONT.deriveFont(9f)); add(top, BorderLayout.NORTH); add(excerpt, BorderLayout.CENTER); add(meta, BorderLayout.SOUTH); }
        @Override public Component getListCellRendererComponent(JList<? extends SearchResultView> list, SearchResultView value, int index, boolean selected, boolean focused) { Color background = selected ? Theme.PANEL_ALT : Theme.BACKGROUND; setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, selected ? 3 : 0, 1, 0, selected ? Theme.ACCENT : Theme.BORDER), Theme.padding(9, selected ? 8 : 11, 9, 7))); setBackground(background); excerpt.setBackground(background); title.setText(value.document().fileName()); title.setForeground(Theme.TEXT); score.setText(Math.round(value.score() * 100) + "%"); score.setForeground(value.score() >= 0.45 ? Theme.ACCENT : Theme.AMBER); excerpt.setText(snippet(value.document().content())); excerpt.setForeground(new Color(191, 200, 205)); meta.setText(value.document().sourceLocation() + "  ·  " + value.reason()); meta.setForeground(Theme.MUTED); return this; } }
    private static String snippet(String value) { String compact = value.replaceAll("\\s+", " ").strip(); return compact.length() <= 180 ? compact : compact.substring(0, 177) + "..."; }
}
