package com.simplerag.adapter.in.swing;

import com.simplerag.application.dto.DocumentReference;
import com.simplerag.application.dto.FileContentView;
import com.simplerag.application.dto.FileIndexState;
import com.simplerag.application.dto.FileNodeView;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * File page: the extracted text a file contributes to the index, next to the exact state the tree
 * reports for it.
 *
 * <p>The body comes from the same readers that build the index, so a PDF, DOCX or XLSX shows what
 * retrieval can actually cite instead of raw bytes.
 */
public final class FileViewerPanel extends JPanel {
    /** Same green the search page paints semantic hits with, so "located" reads the same everywhere. */
    private static final Color FOCUS = new Color(38, 104, 83);
    /** Design size of the extracted text, which follows the reading scale. */
    private static final float BODY_SIZE = 13f;

    private final JLabel title = new JLabel("选择一个文件");
    private final JLabel location = new JLabel(" ");
    private final JLabel state = new JLabel(" ");
    private final JLabel meta = new JLabel(" ");
    private final JTextArea body = new JTextArea();
    private final JTextArea gutter = new JTextArea();
    private final JButton open = new JButton("打开");
    private final JButton reveal = new JButton("目录");
    private final JButton copy = new JButton("复制路径");
    private FileNodeView current;

    public FileViewerPanel(Consumer<Path> onOpenWithSystem, Consumer<Path> onReveal,
                           Consumer<Path> onCopyPath) {
        super(new BorderLayout(0, 12));
        Theme.opaque(this, Theme.PANEL_ALT);
        setBorder(Theme.padding(15, 16, 14, 16));
        add(buildHeader(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        open.addActionListener(event -> withCurrent(onOpenWithSystem, false));
        reveal.addActionListener(event -> withCurrent(onReveal, true));
        copy.addActionListener(event -> withCurrent(onCopyPath, false));
        empty();
    }

    public FileNodeView selected() { return current; }

    /** Redraws the extracted text at the current reading scale; the gutter has to match line for line. */
    public void applyContentScale() {
        body.setFont(Theme.contentFont(Theme.MONO_FONT, BODY_SIZE));
        gutter.setFont(Theme.contentFont(Theme.MONO_FONT, BODY_SIZE));
    }

    /**
     * Package-private views for panel tests; the workspace controller drives this page through its
     * public methods only.
     */
    String metaText() { return meta.getText(); }

    String bodyText() { return body.getText(); }

    /** Offsets of the located citation, or {@code null} when nothing is highlighted. */
    int[] focusRange() {
        Highlighter.Highlight[] highlights = body.getHighlighter().getHighlights();
        return highlights.length == 0 ? null
                : new int[]{highlights[0].getStartOffset(), highlights[0].getEndOffset()};
    }

    public void empty() {
        current = null;
        buttons(false);
        title.setText("选择一个文件");
        title.setToolTipText(null);
        location.setText("在左侧资源管理器中双击文件即可在此查看正文与索引状态");
        state.setText(" ");
        state.setIcon(null);
        meta.setText(" ");
        setBody("", List.of());
    }

    public void loading(FileNodeView node) {
        header(node);
        setBody("正在读取文件内容…", List.of());
    }

    /** Header for a file reached from a citation or search hit, before its index state is known. */
    public void loading(Path path) {
        header(path, "正在读取索引状态…");
        setBody("正在读取文件内容…", List.of());
    }

    public void show(FileNodeView node, FileContentView content) {
        show(node, content, null);
    }

    /**
     * @param focus the cited chunk to put on screen, or {@code null} for a plain preview. The chunk
     *              text is matched verbatim because the same readers feed both the index and this
     *              page, so a miss means the file changed after indexing — which the page reports
     *              instead of silently scrolling somewhere plausible.
     */
    public void show(FileNodeView node, FileContentView content, DocumentReference focus) {
        header(node);
        if (content.text().isEmpty()) {
            setBody(content.notice().isEmpty() ? "该文件没有可显示的文本" : content.notice(), List.of());
            return;
        }
        setBody(content.text(), content.lineLabels());
        if (!content.notice().isEmpty()) meta.setText(meta.getText() + "  ·  " + content.notice());
        if (focus != null) locate(content, focus);
    }

    public void failed(FileNodeView node, String message) {
        header(node);
        setBody("无法读取该文件：" + message, List.of());
    }

    public void failed(Path path, String message) {
        header(path, " ");
        setBody("无法读取该文件：" + message, List.of());
    }

    public void folder(FileNodeView node) {
        header(node);
        setBody(node.state() == FileIndexState.IGNORED
                ? "该目录按忽略或安全策略被排除，其中的文件不会进入索引。"
                : node.indexedDescendants() + " 个已索引文件（含子目录）\n\n"
                        + "在左侧展开该目录，可以逐个查看其中文件的索引状态。", List.of());
    }

    private void header(FileNodeView node) {
        current = node;
        buttons(true);
        title.setText(node.name());
        title.setToolTipText(node.path().toString());
        location.setText(node.path().toString());
        state.setIcon(FileStatusStyle.icon(node));
        state.setForeground(FileStatusStyle.color(node.state()));
        String label = FileStatusStyle.label(node.state());
        String explanation = FileStatusStyle.explanation(node);
        state.setText(label.isEmpty() ? explanation : label + "  ·  " + explanation);
        meta.setText(metaLine(node));
    }

    private void header(Path path, String metaText) {
        current = null;
        buttons(false);
        title.setText(fileName(path));
        title.setToolTipText(path.toString());
        location.setText(path.toString());
        state.setText(" ");
        state.setIcon(null);
        meta.setText(metaText);
    }

    /** Highlights the cited chunk and scrolls it into view, or says why it could not be found. */
    private void locate(FileContentView content, DocumentReference focus) {
        int[] range = range(content, focus);
        if (range == null) {
            meta.setText(meta.getText() + "  ·  未能定位引用 " + focus.sourceLocation()
                    + "（文件内容可能已在索引后改动）");
            return;
        }
        try {
            body.getHighlighter().addHighlight(range[0], range[1],
                    new DefaultHighlighter.DefaultHighlightPainter(FOCUS));
        } catch (BadLocationException unusable) {
            return;
        }
        meta.setText(meta.getText() + "  ·  已定位引用 " + focus.sourceLocation());
        body.setCaretPosition(range[0]);
        SwingUtilities.invokeLater(() -> scrollTo(range[0]));
    }

    /** Verbatim chunk text first; the reader's own line labels are the fallback. */
    private static int[] range(FileContentView content, DocumentReference focus) {
        String chunk = focus.content() == null ? "" : focus.content().strip();
        if (!chunk.isEmpty()) {
            int offset = content.text().indexOf(chunk);
            if (offset >= 0) return new int[]{offset, offset + chunk.length()};
        }
        return labelRange(content, focus);
    }

    private static int[] labelRange(FileContentView content, DocumentReference focus) {
        List<String> labels = content.lineLabels();
        int first = labels.indexOf(Integer.toString(focus.startLine()));
        if (first < 0) return null;
        int last = Math.max(first, labels.lastIndexOf(Integer.toString(focus.endLine())));
        String[] lines = content.text().split("\n", -1);
        int start = 0;
        for (int index = 0; index < first && index < lines.length; index++) start += lines[index].length() + 1;
        int end = start;
        for (int index = first; index <= last && index < lines.length; index++) end += lines[index].length() + 1;
        return new int[]{start, Math.min(content.text().length(), Math.max(start, end - 1))};
    }

    private void scrollTo(int offset) {
        try {
            Rectangle2D view = body.modelToView2D(offset);
            if (view == null) return;
            int top = (int) Math.max(0, view.getY() - 60);
            body.scrollRectToVisible(new Rectangle(0, top, 1, (int) view.getHeight() + 140));
        } catch (BadLocationException ignored) {
            // Nothing to scroll to; the highlight is already in place.
        }
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    private static String metaLine(FileNodeView node) {
        StringBuilder line = new StringBuilder();
        if (node.directory()) {
            return line.append("目录  ·  修改于 ").append(FileStatusStyle.timestamp(node.modifiedAt()))
                    .append("  ·  ").append(node.indexedDescendants()).append(" 个已索引文件").toString();
        }
        line.append(FileStatusStyle.size(node.size()))
                .append("  ·  修改于 ").append(FileStatusStyle.timestamp(node.modifiedAt()));
        if (!node.readerId().isEmpty()) line.append("  ·  reader ").append(node.readerId());
        if (node.state() == FileIndexState.INDEXED || node.state() == FileIndexState.MODIFIED
                || node.state() == FileIndexState.DELETED) {
            line.append("  ·  ").append(node.chunkCount()).append(" 个片段");
        }
        if (!node.contentHash().isBlank()) {
            String hash = node.contentHash();
            line.append("  ·  sha ").append(hash.length() > 10 ? hash.substring(0, 10) : hash);
        }
        return line.toString();
    }

    private void setBody(String text, List<String> labels) {
        body.getHighlighter().removeAllHighlights();
        body.setText(text);
        body.setCaretPosition(0);
        int width = labels.stream().mapToInt(String::length).max().orElse(0);
        StringBuilder numbers = new StringBuilder();
        for (String label : labels) {
            numbers.append(" ".repeat(Math.max(0, width - label.length()))).append(label).append('\n');
        }
        gutter.setText(numbers.toString());
        gutter.setColumns(Math.max(2, width));
        gutter.setCaretPosition(0);
    }

    private void buttons(boolean enabled) {
        open.setEnabled(enabled);
        reveal.setEnabled(enabled);
        copy.setEnabled(enabled);
    }

    private void withCurrent(Consumer<Path> action, boolean parent) {
        if (current == null) return;
        Path path = parent && !current.directory() ? current.path().getParent() : current.path();
        if (path != null) action.accept(path);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 4));
        header.setOpaque(false);
        JPanel labels = new JPanel();
        labels.setOpaque(false);
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        title.setForeground(Theme.TEXT);
        title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 15f));
        location.setForeground(Theme.MUTED);
        location.setFont(Theme.UI_FONT.deriveFont(10f));
        state.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 11f));
        state.setIconTextGap(6);
        meta.setForeground(Theme.MUTED);
        meta.setFont(Theme.UI_FONT.deriveFont(10f));
        labels.add(title);
        labels.add(Box.createVerticalStrut(3));
        labels.add(location);
        labels.add(Box.createVerticalStrut(6));
        labels.add(state);
        labels.add(Box.createVerticalStrut(3));
        labels.add(meta);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        actions.setOpaque(false);
        for (JButton button : List.of(copy, reveal, open)) {
            Theme.styleButton(button, button == open);
            button.setMargin(new Insets(6, 10, 6, 10));
            actions.add(button);
        }
        open.setToolTipText("用系统默认程序打开该文件");
        reveal.setToolTipText("在文件资源管理器中打开所在目录");
        header.add(labels, BorderLayout.CENTER);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private JScrollPane buildBody() {
        body.setEditable(false);
        body.setFont(Theme.contentFont(Theme.MONO_FONT, BODY_SIZE));
        body.setBackground(Theme.PANEL_ALT);
        body.setForeground(new Color(218, 226, 230));
        body.setCaretColor(Theme.ACCENT);
        body.setTabSize(4);
        body.setBorder(Theme.padding(10, 10, 10, 10));
        gutter.setEditable(false);
        gutter.setFont(Theme.contentFont(Theme.MONO_FONT, BODY_SIZE));
        gutter.setBackground(Theme.PANEL);
        gutter.setForeground(new Color(104, 116, 124));
        gutter.setBorder(Theme.padding(10, 8, 10, 8));
        gutter.setFocusable(false);
        JScrollPane scroll = new JScrollPane(body);
        scroll.setRowHeaderView(gutter);
        scroll.getViewport().setBackground(Theme.PANEL_ALT);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        return scroll;
    }
}
