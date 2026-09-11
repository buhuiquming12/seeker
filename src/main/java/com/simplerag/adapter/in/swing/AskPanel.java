package com.simplerag.adapter.in.swing;

import com.simplerag.application.conversation.AnswerDelta;
import com.simplerag.application.conversation.ChatMessage;
import com.simplerag.application.dto.CitationView;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Knowledge-question page: multi-turn chat transcript + composer + citations sidebar. */
public final class AskPanel extends JPanel {
    private static final int BUBBLE_INNER_PAD_X = 16;
    private static final int BUBBLE_INNER_PAD_Y = 12;
    private static final int USER_MAX_WIDTH_RATIO = 78;
    private static final int ASSISTANT_MAX_WIDTH_RATIO = 96;
    /** Design sizes of the two text surfaces that follow the reading scale. */
    private static final float COMPOSER_SIZE = 13.5f;
    private static final float THINKING_SIZE = 11f;

    private final JCheckBox localOnly = new JCheckBox("仅本地 RAG（禁止远程发送）");
    private final JLabel policyStatus = new JLabel(" ");
    private final JTextArea question = new JTextArea(3, 30);
    private final JLabel conversationTitle = new JLabel("对话");
    private final JLabel conversationMeta = new JLabel("多轮上下文已启用 · 切换知识库或版本会自动清空");
    private final JButton clearChat = new JButton("清空对话");
    private final JButton ask = new JButton("发送");
    private final DefaultListModel<CitationView> citations = new DefaultListModel<>();
    private final JList<CitationView> citationList = new JList<>(citations);
    private final JPanel transcript = new JPanel();
    private final JScrollPane transcriptScroll;
    private final JPanel emptyState;
    private BubblePanel streamingAssistant;
    private final List<BubblePanel> bubbles = new ArrayList<>();
    /** Divider marking where the model's history restarted; kept out of {@link #bubbles}. */
    private JPanel lastBreak;
    private String lastBreakMessage = "";
    private final Runnable onOpenCitation;

    public AskPanel(Runnable onAsk, Runnable onSave, Runnable onOpenCitation, Runnable onClearChat) {
        super(new BorderLayout());
        this.onOpenCitation = onOpenCitation;
        Theme.opaque(this, Theme.BACKGROUND);
        transcript.setLayout(new BoxLayout(transcript, BoxLayout.Y_AXIS));
        Theme.opaque(transcript, Theme.BACKGROUND);
        transcript.setBorder(Theme.padding(12, 16, 20, 16));
        emptyState = buildEmptyState();
        transcript.add(emptyState);
        transcriptScroll = new JScrollPane(transcript);
        transcriptScroll.setBorder(null);
        transcriptScroll.getViewport().setBackground(Theme.BACKGROUND);
        transcriptScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        transcriptScroll.getVerticalScrollBar().setUnitIncrement(18);
        transcriptScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                relayoutBubbles();
            }
        });
        add(buildPrivacyPanel(onSave), BorderLayout.NORTH);
        add(buildChatPanel(onAsk, onClearChat), BorderLayout.CENTER);
        citationList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) onOpenCitation.run();
            }
        });
    }

    public JTextArea questionArea() { return question; }
    public String question() { return question.getText().strip(); }
    public void clearQuestion() { question.setText(""); }
    public CitationView selectedCitation() { return citationList.getSelectedValue(); }
    public boolean localOnly() { return localOnly.isSelected(); }
    public void localOnly(boolean value) { localOnly.setSelected(value); }

    /** Feedback for the per-knowledge-base remote-send policy, shown next to the switch itself. */
    public void policyStatus(String text, Color color) {
        policyStatus.setText(text == null || text.isBlank() ? " " : text);
        policyStatus.setForeground(color);
    }

    String policyStatusText() { return policyStatus.getText(); }

    public void clearCitations() { citations.clear(); }

    /**
     * Opens the source behind a {@code [n]} marker clicked in an answer: the sidebar selection moves
     * to that citation and the page reuses the same open action a double click there would.
     *
     * <p>Nothing happens when the number is not in the current turn's citations — history keeps the
     * answer text but not its chunks, so an old marker has nothing left to point at.
     */
    void activateCitation(int number) {
        for (int index = 0; index < citations.size(); index++) {
            if (citations.get(index).number() != number) continue;
            citationList.setSelectedIndex(index);
            citationList.ensureIndexIsVisible(index);
            if (onOpenCitation != null) onOpenCitation.run();
            return;
        }
    }

    public void citations(List<CitationView> values) {
        citations.clear();
        values.forEach(citations::addElement);
    }

    public void conversationTitle(String text) { conversationTitle.setText(text); }
    public void conversationMeta(String text) { conversationMeta.setText(text); }

    /** Plain-text transcript used by the copy action and UI tests. */
    public String conversationText() {
        StringBuilder result = new StringBuilder();
        for (BubblePanel bubble : bubbles) {
            if (!result.isEmpty()) result.append("\n\n");
            result.append(bubble.user ? "你" : "助手").append("：\n").append(bubble.text());
        }
        return result.toString();
    }

    public String latestAnswerWithCitations() {
        String answer = "";
        for (int index = bubbles.size() - 1; index >= 0; index--) {
            if (!bubbles.get(index).user) {
                answer = bubbles.get(index).text();
                break;
            }
        }
        String references = citationText();
        if (answer.isBlank()) return references;
        return references.isBlank() ? answer : answer + "\n\n引用：\n" + references;
    }

    /** Reasoning of the most recent assistant turn. Never part of the transcript or the clipboard. */
    public String latestThinking() {
        BubblePanel bubble = lastAssistantBubble();
        return bubble == null ? "" : bubble.thinkingText();
    }

    public boolean assistantThinkingExpanded() {
        BubblePanel bubble = lastAssistantBubble();
        return bubble != null && bubble.thinkingExpanded();
    }

    private BubblePanel lastAssistantBubble() {
        for (int index = bubbles.size() - 1; index >= 0; index--) {
            if (!bubbles.get(index).user) return bubbles.get(index);
        }
        return null;
    }

    /** Rendered view of the most recent answer; panel tests assert on its styled document. */
    MarkdownPane latestAnswerPane() {
        BubblePanel bubble = lastAssistantBubble();
        return bubble == null ? null : bubble.body;
    }

    /** Height the transcript row allows the latest answer, which a stale layout cap would shrink. */
    int latestAnswerRowHeight() {
        BubblePanel bubble = lastAssistantBubble();
        return bubble == null || bubble.getParent() == null
                ? 0 : bubble.getParent().getMaximumSize().height;
    }

    public void showMessages(List<ChatMessage> messages) {
        resetTranscript();
        for (ChatMessage message : messages) {
            addBubble(message.role() == ChatMessage.Role.USER, message.content(), false);
        }
        if (bubbles.isEmpty()) {
            transcript.add(emptyState);
        }
        revalidateTranscript(true);
    }

    public void beginTurn(String userText) {
        removeEmptyState();
        clearCitations();
        addBubble(true, userText, false);
        streamingAssistant = addBubble(false, "", true);
        conversationTitle("AI 正在检索相关文件…");
        conversationMeta("模型会评估证据并按需追加检索（最多 4 轮）");
        revalidateTranscript(true);
    }

    public void appendAssistantDelta(AnswerDelta delta) {
        if (delta == null || delta.isEmpty()) return;
        if (streamingAssistant == null) {
            removeEmptyState();
            streamingAssistant = addBubble(false, "", true);
        }
        if (delta.reasoning()) {
            streamingAssistant.appendThinking(delta.text());
            conversationTitle(delta.stage() == AnswerDelta.Stage.PLANNING ? "AI 正在规划检索…" : "AI 正在思考…");
            revalidateTranscript(true);
            return;
        }
        if (streamingAssistant.isEmpty()) {
            // The answer has started, so the scratch work folds away by itself.
            streamingAssistant.expandThinking(false);
            conversationTitle("正在生成回答…");
            conversationMeta("AI 已完成多轮检索 · 本轮引用 " + citations.size() + " 个片段");
        }
        streamingAssistant.append(delta.text());
        revalidateTranscript(true);
    }

    public void finishAssistant(String fullText, String model) {
        finishAssistant(fullText, model, "");
    }

    public void finishAssistant(String fullText, String model, String reasoning) {
        if (streamingAssistant != null) {
            if (fullText != null && !fullText.isBlank() && streamingAssistant.isEmpty()) {
                streamingAssistant.setText(fullText);
            }
            // The non-streaming fallback reports its thinking only on the finished answer.
            streamingAssistant.setThinking(reasoning);
            streamingAssistant.expandThinking(false);
            streamingAssistant.setStreaming(false);
            streamingAssistant = null;
        } else if (fullText != null && !fullText.isBlank()) {
            BubblePanel bubble = addBubble(false, fullText, false);
            bubble.setThinking(reasoning);
            bubble.expandThinking(false);
        }
        conversationTitle(model == null || model.isBlank() ? "对话" : "对话 · " + model);
        conversationMeta("多轮上下文与 AI 自主检索已启用 · 历史不含引用片段");
        revalidateTranscript(true);
    }

    public void failAssistant(String message) {
        if (streamingAssistant != null) {
            streamingAssistant.setText(message == null || message.isBlank() ? "生成失败" : message);
            streamingAssistant.markError();
            streamingAssistant.setStreaming(false);
            streamingAssistant = null;
        } else {
            BubblePanel error = addBubble(false, message == null ? "生成失败" : message, false);
            error.markError();
        }
        conversationTitle("生成失败");
        conversationMeta("失败轮次不会写入多轮历史");
        revalidateTranscript(true);
    }

    public void stopAssistant() {
        if (streamingAssistant != null) {
            if (streamingAssistant.isEmpty()) {
                streamingAssistant.setText("（已停止）");
            }
            streamingAssistant.setStreaming(false);
            streamingAssistant = null;
        }
        conversationTitle("已停止");
        conversationMeta("已取消本轮生成");
        revalidateTranscript(false);
    }

    public void resetConversation(String title, String meta) {
        resetTranscript();
        transcript.add(emptyState);
        conversationTitle(title);
        conversationMeta(meta);
        clearCitations();
        revalidateTranscript(false);
    }

    /**
     * Marks the point where the model's history restarted. Sessions are bound to
     * knowledgeBaseId + sourceRevision, so a source change hands out a fresh session while the
     * bubbles above stay on screen; without this row the page would claim a continuity the model
     * does not have. Repeated calls replace the trailing marker instead of stacking dividers.
     */
    public void contextBreak(String message) {
        if (bubbles.isEmpty()) return;
        int last = transcript.getComponentCount() - 1;
        if (lastBreak != null && last >= 0 && transcript.getComponent(last) == lastBreak) {
            transcript.remove(lastBreak);
        }
        lastBreakMessage = message == null ? "" : message;
        lastBreak = buildContextBreak(lastBreakMessage);
        transcript.add(lastBreak);
        conversationMeta(lastBreakMessage);
        revalidateTranscript(true);
    }

    /** The marker currently shown, or empty when the transcript claims one continuous context. */
    String contextBreakMessage() {
        return lastBreak != null && lastBreak.getParent() == transcript ? lastBreakMessage : "";
    }

    private JPanel buildContextBreak(String message) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createCompoundBorder(new EmptyBorder(14, 2, 8, 2),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
                        new EmptyBorder(8, 0, 0, 0))));
        JLabel label = new JLabel(message, SwingConstants.CENTER);
        label.setForeground(Theme.AMBER);
        label.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 10f));
        row.add(label, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    public void asking(boolean value) {
        ask.setText(value ? "停止" : "发送");
        question.setEnabled(!value);
        clearChat.setEnabled(!value);
    }

    /**
     * Re-renders the transcript and the composer at the current reading scale, keeping the scroll
     * position: zooming is something a reader does part-way through a conversation.
     */
    public void applyContentScale() {
        question.setFont(Theme.contentFont(Theme.UI_FONT, COMPOSER_SIZE));
        for (BubblePanel bubble : bubbles) bubble.rescale();
        relayoutBubbles();
    }

    private void resetTranscript() {
        transcript.removeAll();
        bubbles.clear();
        streamingAssistant = null;
        lastBreak = null;
        lastBreakMessage = "";
    }

    private void removeEmptyState() {
        if (emptyState.getParent() == transcript) {
            transcript.remove(emptyState);
        }
    }

    private BubblePanel addBubble(boolean user, String text, boolean streaming) {
        BubblePanel bubble = new BubblePanel(user, text, streaming, this::activateCitation,
                () -> revalidateTranscript(true));
        bubbles.add(bubble);

        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(new EmptyBorder(4, 0, 4, 0));

        if (user) {
            row.add(Box.createHorizontalGlue());
            row.add(bubble);
        } else {
            row.add(bubble);
            row.add(Box.createHorizontalGlue());
        }

        // Keep rows content-sized so BoxLayout does not stretch them into empty space.
        Dimension preferred = row.getPreferredSize();
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));

        transcript.add(row);
        return bubble;
    }

    private void relayoutBubbles() {
        int available = availableBubbleWidth();
        for (BubblePanel bubble : bubbles) {
            bubble.applyAvailableWidth(available);
        }
        for (Component child : transcript.getComponents()) {
            if (child instanceof JPanel row && child != emptyState) {
                // Measuring a bubble resizes its text components and leaves them invalid, so the
                // row's BoxLayout stops being told anything changed and would answer with the
                // requirements it cached before the answer arrived. That cap is why an explicit
                // invalidate has to come before the measurement.
                row.invalidate();
                Dimension preferred = row.getPreferredSize();
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
            }
        }
        transcript.revalidate();
        transcript.repaint();
    }

    private int availableBubbleWidth() {
        int width = transcriptScroll.getViewport().getWidth();
        if (width <= 0) {
            width = transcript.getWidth();
        }
        if (width <= 0) {
            width = getWidth() > 0 ? Math.max(360, getWidth() - 280) : 720;
        }
        return Math.max(240, width - 32);
    }

    private void revalidateTranscript(boolean scrollToBottom) {
        relayoutBubbles();
        if (scrollToBottom) {
            SwingUtilities.invokeLater(() -> {
                JScrollBar bar = transcriptScroll.getVerticalScrollBar();
                bar.setValue(bar.getMaximum());
            });
        }
    }

    private JPanel buildChatPanel(Runnable onAsk, Runnable onClearChat) {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        Theme.opaque(panel, Theme.BACKGROUND);
        panel.setBorder(Theme.padding(0, 0, 0, 0));

        JPanel header = new JPanel(new BorderLayout(12, 0));
        Theme.opaque(header, Theme.BACKGROUND);
        header.setBorder(Theme.padding(12, 18, 6, 18));
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        conversationTitle.setForeground(Theme.TEXT);
        conversationTitle.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 16f));
        conversationMeta.setForeground(Theme.MUTED);
        conversationMeta.setFont(Theme.UI_FONT.deriveFont(11f));
        titles.add(conversationTitle);
        titles.add(Box.createVerticalStrut(3));
        titles.add(conversationMeta);
        header.add(titles, BorderLayout.CENTER);
        Theme.styleButton(clearChat, false);
        clearChat.setMargin(new Insets(7, 12, 7, 12));
        clearChat.addActionListener(e -> onClearChat.run());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        JButton copyConversation = new JButton("复制对话");
        Theme.styleButton(copyConversation, false);
        copyConversation.setMargin(new Insets(7, 12, 7, 12));
        copyConversation.addActionListener(e -> copy(conversationText()));
        JButton copyAnswer = new JButton("复制回答+引用");
        Theme.styleButton(copyAnswer, false);
        copyAnswer.setMargin(new Insets(7, 12, 7, 12));
        copyAnswer.addActionListener(e -> copy(latestAnswerWithCitations()));
        actions.add(copyAnswer);
        actions.add(Box.createHorizontalStrut(8));
        actions.add(copyConversation);
        actions.add(Box.createHorizontalStrut(8));
        actions.add(clearChat);
        header.add(actions, BorderLayout.EAST);

        JPanel citationPanel = new JPanel(new BorderLayout(0, 8));
        Theme.opaque(citationPanel, Theme.PANEL);
        citationPanel.setBorder(Theme.padding(14, 12, 12, 12));
        JLabel citationTitle = new JLabel("本轮引用");
        citationTitle.setForeground(Theme.TEXT);
        citationTitle.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 12f));
        JLabel citationHint = new JLabel("双击在应用内定位 · 历史不保留片段");
        citationHint.setForeground(Theme.MUTED);
        citationHint.setFont(Theme.UI_FONT.deriveFont(10f));
        JPanel citationHeader = new JPanel(new BorderLayout(6, 0));
        citationHeader.setOpaque(false);
        JPanel citationLabels = new JPanel();
        citationLabels.setOpaque(false);
        citationLabels.setLayout(new BoxLayout(citationLabels, BoxLayout.Y_AXIS));
        citationLabels.add(citationTitle); citationLabels.add(citationHint);
        JButton copyCitations = new JButton("复制引用");
        Theme.styleButton(copyCitations, false);
        copyCitations.setMargin(new Insets(5, 8, 5, 8));
        copyCitations.addActionListener(event -> copy(citationText()));
        citationHeader.add(citationLabels, BorderLayout.CENTER);
        citationHeader.add(copyCitations, BorderLayout.EAST);
        citationPanel.add(citationHeader, BorderLayout.NORTH);
        citationList.setBackground(Theme.PANEL);
        citationList.setFixedCellHeight(68);
        citationList.setCellRenderer(new CitationRenderer());
        citationPanel.add(scroll(citationList), BorderLayout.CENTER);
        citationPanel.setPreferredSize(new Dimension(260, 100));
        citationPanel.setMinimumSize(new Dimension(220, 100));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, transcriptScroll, citationPanel);
        split.setResizeWeight(0.78);
        split.setDividerLocation(820);
        split.setDividerSize(1);
        split.setBorder(null);
        split.setBackground(Theme.BORDER);

        panel.add(header, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        panel.add(composer(onAsk), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildEmptyState() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(Theme.padding(36, 24, 24, 24));
        JLabel badge = new JLabel("RAG CHAT");
        badge.setForeground(Theme.ACCENT);
        badge.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 11f));
        badge.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel title = new JLabel("向当前知识库提问");
        title.setForeground(Theme.TEXT);
        title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 20f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel body = new JLabel("<html><div style='text-align:center;width:420px;color:#919CA4;line-height:1.55'>"
                + "支持多轮追问。每一轮都会重新做 freshness 检查并检索知识库；"
                + "对话历史只保留问答文本，不会沿用旧引用片段。"
                + "</div></html>");
        body.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(badge);
        panel.add(Box.createVerticalStrut(10));
        panel.add(title);
        panel.add(Box.createVerticalStrut(10));
        panel.add(body);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));
        return panel;
    }

    private JPanel composer(Runnable onAsk) {
        JPanel shell = new JPanel(new BorderLayout());
        Theme.opaque(shell, Theme.BACKGROUND);
        shell.setBorder(Theme.padding(8, 18, 14, 18));

        JPanel card = new JPanel(new BorderLayout(10, 0));
        Theme.opaque(card, Theme.PANEL_ALT);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                Theme.padding(10, 12, 10, 12)));
        // The question area has no border of its own, so the card it sits in shows where focus is.
        question.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) { outline(card, Theme.ACCENT); }
            @Override public void focusLost(java.awt.event.FocusEvent event) { outline(card, Theme.BORDER); }
        });

        question.setLineWrap(true);
        question.setWrapStyleWord(true);
        question.setFont(Theme.contentFont(Theme.UI_FONT, COMPOSER_SIZE));
        question.setBackground(Theme.PANEL_ALT);
        question.setForeground(Theme.TEXT);
        question.setCaretColor(Theme.TEXT);
        question.setBorder(null);
        question.setRows(2);

        JScrollPane scroll = new JScrollPane(question);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.PANEL_ALT);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        Theme.styleButton(ask, true);
        ask.setPreferredSize(new Dimension(96, 44));
        ask.addActionListener(e -> onAsk.run());

        JLabel hint = new JLabel("Enter 发送 · Shift+Enter 换行 · 回答文本可选择，右键或 Ctrl+C 复制");
        hint.setForeground(Theme.MUTED);
        hint.setFont(Theme.UI_FONT.deriveFont(10f));
        hint.setBorder(new EmptyBorder(8, 2, 0, 0));

        card.add(scroll, BorderLayout.CENTER);
        card.add(ask, BorderLayout.EAST);
        shell.add(card, BorderLayout.CENTER);
        shell.add(hint, BorderLayout.SOUTH);
        return shell;
    }

    private static JScrollPane scroll(Component component) {
        JScrollPane pane = new JScrollPane(component);
        pane.setBorder(null);
        pane.getViewport().setBackground(Theme.PANEL);
        return pane;
    }

    private static void outline(JPanel card, Color color) {
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(color), Theme.padding(10, 12, 10, 12)));
        card.repaint();
    }

    /** Compact per-knowledge-base privacy control; provider credentials live on Settings page. */
    private JPanel buildPrivacyPanel(Runnable onSave) {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        Theme.opaque(panel, Theme.PANEL_ALT);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), Theme.padding(9, 16, 9, 16)));
        JLabel hint = new JLabel("模型配置请前往“设置”页 · 远程发送前仍会逐知识库确认");
        hint.setForeground(Theme.MUTED);
        hint.setFont(Theme.UI_FONT.deriveFont(10f));
        panel.add(hint, BorderLayout.WEST);
        // Toggling the switch writes to the database, so the confirmation belongs next to the switch.
        policyStatus.setForeground(Theme.MUTED);
        policyStatus.setFont(Theme.UI_FONT.deriveFont(10f));
        policyStatus.setHorizontalAlignment(SwingConstants.RIGHT);
        policyStatus.setBorder(Theme.padding(0, 12, 0, 12));
        panel.add(policyStatus, BorderLayout.CENTER);
        localOnly.setOpaque(false);
        localOnly.setForeground(Theme.MUTED);
        localOnly.setFont(Theme.UI_FONT.deriveFont(10f));
        panel.add(localOnly, BorderLayout.EAST);
        localOnly.addActionListener(event -> onSave.run());
        return panel;
    }

    private String citationText() {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < citations.size(); index++) {
            CitationView citation = citations.get(index);
            if (!result.isEmpty()) result.append('\n');
            result.append('[').append(citation.number()).append("] ")
                    .append(citation.document().path()).append(" · ")
                    .append(citation.document().sourceLocation());
        }
        return result.toString();
    }

    private static void copy(String text) {
        if (text == null || text.isBlank()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private static final class BubblePanel extends JPanel {
        /** Gap BorderLayout leaves between the header block and the body. */
        private static final int ROW_GAP = 4;
        private static final int STREAMING_MIN_HEIGHT = 56;

        private final boolean user;
        private final MarkdownPane body;
        private final JLabel role = new JLabel();
        private final JPanel header = new JPanel(new BorderLayout());
        /** Chain of thought and retrieval planning. Never part of {@link #text()}. */
        private final JTextArea thinking = new JTextArea();
        private final JButton thinkingToggle = new JButton();
        private final JPanel thinkingSection = new JPanel(new BorderLayout(0, ROW_GAP));
        /** The message as the model wrote it: what gets copied, measured and re-rendered. */
        private final StringBuilder raw = new StringBuilder();
        private boolean thinkingExpanded = true;
        private long thinkingStartedNanos;
        private boolean streaming;
        private boolean error;
        private int availableWidth = 720;

        private BubblePanel(boolean user, String text, boolean streaming, IntConsumer onCitation,
                            Runnable onRendered) {
            super(new BorderLayout(0, ROW_GAP));
            this.user = user;
            this.streaming = streaming;
            // Questions are shown as typed; only answers carry markdown and citation markers.
            this.body = new MarkdownPane(user ? new Color(9, 30, 25) : Theme.TEXT,
                    user ? new Color(28, 110, 91) : Theme.ACCENT_DARK, Theme.TEXT,
                    user ? null : onCitation, onRendered);
            setOpaque(false);
            setBorder(Theme.padding(BUBBLE_INNER_PAD_Y, BUBBLE_INNER_PAD_X, BUBBLE_INNER_PAD_Y, BUBBLE_INNER_PAD_X));
            role.setText(user ? "你" : "助手");
            role.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 10f));
            role.setForeground(user ? new Color(9, 30, 25) : Theme.ACCENT);
            setText(text);
            installCopyMenu();
            header.setOpaque(false);
            header.add(role, BorderLayout.WEST);
            JButton copy = new JButton("复制");
            copy.setFocusable(false);
            copy.setBorderPainted(false);
            copy.setContentAreaFilled(false);
            copy.setForeground(user ? new Color(9, 30, 25) : Theme.MUTED);
            copy.setFont(Theme.UI_FONT.deriveFont(9f));
            copy.setMargin(new Insets(0, 4, 0, 4));
            // Deliberately body-only: thinking is scratch work and must not land in the clipboard.
            copy.addActionListener(event -> AskPanel.copy(text()));
            header.add(copy, BorderLayout.EAST);
            JPanel north = new JPanel();
            north.setOpaque(false);
            north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
            header.setAlignmentX(Component.LEFT_ALIGNMENT);
            north.add(header);
            north.add(buildThinkingSection());
            add(north, BorderLayout.NORTH);
            add(body, BorderLayout.CENTER);
            setAlignmentX(user ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
        }

        private JPanel buildThinkingSection() {
            thinking.setEditable(false);
            thinking.setLineWrap(true);
            thinking.setWrapStyleWord(true);
            thinking.setOpaque(false);
            thinking.setFont(Theme.contentFont(Theme.UI_FONT, THINKING_SIZE));
            thinking.setForeground(Theme.MUTED);
            thinking.setBorder(new EmptyBorder(0, 8, 0, 0));
            thinking.setFocusable(true);
            thinkingToggle.setFocusable(false);
            thinkingToggle.setBorderPainted(false);
            thinkingToggle.setContentAreaFilled(false);
            thinkingToggle.setForeground(Theme.MUTED);
            thinkingToggle.setFont(Theme.UI_FONT.deriveFont(10f));
            thinkingToggle.setMargin(new Insets(0, 0, 0, 0));
            thinkingToggle.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
            thinkingToggle.addActionListener(event -> expandThinking(!thinkingExpanded));
            thinkingSection.setOpaque(false);
            thinkingSection.setAlignmentX(Component.LEFT_ALIGNMENT);
            thinkingSection.add(thinkingToggle, BorderLayout.NORTH);
            thinkingSection.add(thinking, BorderLayout.CENTER);
            thinkingSection.setVisible(false);
            return thinkingSection;
        }

        /** Appends one piece of reasoning, revealing the section the first time anything arrives. */
        void appendThinking(String delta) {
            if (delta == null || delta.isEmpty()) return;
            if (!thinkingSection.isVisible()) {
                thinkingSection.setVisible(true);
                thinkingStartedNanos = System.nanoTime();
            }
            thinking.append(delta);
            refreshThinkingLabel();
            invalidate();
        }

        void setThinking(String text) {
            if (text == null || text.isBlank() || !thinking.getText().isBlank()) return;
            thinkingSection.setVisible(true);
            if (thinkingStartedNanos == 0L) thinkingStartedNanos = System.nanoTime();
            thinking.setText(text);
            refreshThinkingLabel();
            invalidate();
        }

        void expandThinking(boolean expanded) {
            if (!thinkingSection.isVisible()) return;
            thinkingExpanded = expanded;
            thinking.setVisible(expanded);
            refreshThinkingLabel();
            invalidate();
            revalidate();
            repaint();
        }

        boolean thinkingExpanded() {
            return thinkingSection.isVisible() && thinkingExpanded;
        }

        String thinkingText() {
            return thinking.getText();
        }

        private void refreshThinkingLabel() {
            long seconds = thinkingStartedNanos == 0L ? 0L
                    : (System.nanoTime() - thinkingStartedNanos) / 1_000_000_000L;
            // ▲/▼ are the triangles GB2312 covers, so Microsoft YaHei UI has them; the thin ▾/▸ pair
            // this used to draw is not in the font and came out as tofu. The arrow shows the action.
            thinkingToggle.setText((thinkingExpanded ? "▲ 思考过程" : "▼ 思考过程")
                    + (seconds > 0 ? " · " + seconds + "s" : ""));
        }

        /** The message as written, not as rendered: markup belongs in the clipboard too. */
        String text() { return raw.toString(); }

        private void installCopyMenu() {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem selected = new JMenuItem("复制选中文本");
            JMenuItem whole = new JMenuItem("复制本条消息");
            selected.addActionListener(event -> AskPanel.copy(body.getSelectedText()));
            whole.addActionListener(event -> AskPanel.copy(text()));
            menu.add(selected); menu.add(whole);
            body.addMouseListener(new MouseAdapter() {
                private void show(MouseEvent event) {
                    if (!event.isPopupTrigger()) return;
                    selected.setEnabled(body.getSelectedText() != null && !body.getSelectedText().isBlank());
                    menu.show(body, event.getX(), event.getY());
                }
                @Override public void mousePressed(MouseEvent event) { show(event); }
                @Override public void mouseReleased(MouseEvent event) { show(event); }
            });
        }

        void append(String delta) {
            raw.append(delta);
            body.streaming(raw.toString(), !user);
            invalidate();
        }

        void setText(String text) {
            raw.setLength(0);
            raw.append(text == null ? "" : text);
            body.set(raw.toString(), !user);
            invalidate();
        }

        boolean isEmpty() {
            return raw.toString().isBlank();
        }

        void setStreaming(boolean streaming) {
            this.streaming = streaming;
            // Whatever the coalescing timer still owes is due now that the turn has settled.
            if (!streaming) body.flush();
            repaint();
        }

        void markError() {
            this.error = true;
            role.setForeground(Theme.RED);
            body.markError();
            repaint();
        }

        void applyAvailableWidth(int available) {
            this.availableWidth = Math.max(240, available);
            revalidate();
        }

        /** Re-renders this message at the current reading scale; the page re-measures the rows. */
        void rescale() {
            thinking.setFont(Theme.contentFont(Theme.UI_FONT, THINKING_SIZE));
            body.rescale();
            invalidate();
        }

        private int maxBubbleWidth() {
            int ratio = user ? USER_MAX_WIDTH_RATIO : ASSISTANT_MAX_WIDTH_RATIO;
            return Math.max(200, availableWidth * ratio / 100);
        }

        /**
         * The one place that knows how tall this bubble is. The panel reports its own preferred size,
         * so anything not counted here simply does not get drawn: every wrapping child is measured at
         * the content width, everything else contributes its natural height.
         */
        private int heightAt(int bubbleWidth) {
            int content = Math.max(80, bubbleWidth - BUBBLE_INNER_PAD_X * 2);
            int height = BUBBLE_INNER_PAD_Y * 2 + header.getPreferredSize().height + ROW_GAP;
            if (thinkingSection.isVisible()) {
                height += thinkingToggle.getPreferredSize().height;
                if (thinkingExpanded) height += ROW_GAP + wrappedHeight(thinking, content);
            }
            height += wrappedHeight(body, content);
            return streaming ? Math.max(height, STREAMING_MIN_HEIGHT) : height;
        }

        /** Wrapped text only reports its height once it has a width to wrap at. */
        private static int wrappedHeight(javax.swing.text.JTextComponent text, int width) {
            text.setSize(new Dimension(width, Short.MAX_VALUE));
            return text.getPreferredSize().height;
        }

        private int preferredBubbleWidth() {
            int max = maxBubbleWidth();
            String text = raw.toString();
            // Reasoning is long and wraps badly in a narrow bubble, so an expanded thinking area takes
            // the full column even before the first answer token arrives.
            if (!user && thinkingExpanded()) return max;
            if (text.isBlank()) {
                return user ? Math.min(max, 220) : Math.min(max, Math.max(280, availableWidth * 70 / 100));
            }

            // Prefer filling most of the chat column for multi-line answers (screenshot style).
            int lines = text.split("\\R", -1).length;
            boolean multiline = lines > 1 || text.length() > 48;
            if (!user && multiline) {
                return max;
            }

            // Single-line / short messages hug content, but stay readable.
            int textWidth = body.getFontMetrics(body.getFont()).stringWidth(text.replace('\n', ' '));
            int desired = textWidth + BUBBLE_INNER_PAD_X * 2 + 8;
            int min = user ? 120 : 180;
            return Math.min(max, Math.max(min, desired));
        }

        @Override
        public Dimension getPreferredSize() {
            int width = preferredBubbleWidth();
            return new Dimension(width, heightAt(width));
        }

        @Override
        public Dimension getMinimumSize() {
            int width = Math.min(200, maxBubbleWidth());
            return new Dimension(width, heightAt(width));
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill;
            if (error) {
                fill = new Color(60, 28, 28);
            } else if (user) {
                fill = Theme.ACCENT;
            } else {
                fill = Theme.PANEL_ALT;
            }
            int arc = user ? 20 : 16;
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            if (!user && !error) {
                g2.setColor(Theme.BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            }
            if (streaming) {
                g2.setColor(Theme.ACCENT_DARK);
                g2.fillOval(getWidth() - 18, getHeight() - 18, 8, 8);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static final class CitationRenderer extends JPanel implements javax.swing.ListCellRenderer<CitationView> {
        private final JLabel title = new JLabel();
        private final JLabel meta = new JLabel();

        private CitationRenderer() {
            super(new BorderLayout(0, 4));
            title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 11f));
            meta.setFont(Theme.UI_FONT.deriveFont(9f));
            add(title, BorderLayout.CENTER);
            add(meta, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends CitationView> list, CitationView value,
                                                      int index, boolean selected, boolean focused) {
            setBackground(selected ? Theme.HOVER : Theme.PANEL);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                    Theme.padding(8, 8, 8, 8)));
            title.setText("[" + value.number() + "] " + value.document().fileName());
            title.setForeground(Theme.TEXT);
            meta.setText(value.document().sourceLocation()
                    + "  ·  " + Math.round(value.score() * 100) + "%");
            meta.setForeground(Theme.MUTED);
            setToolTipText(value.document().path().toString());
            return this;
        }
    }
}
