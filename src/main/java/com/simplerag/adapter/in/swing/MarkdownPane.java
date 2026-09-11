package com.simplerag.adapter.in.swing;

import javax.swing.JTextPane;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * One chat message as read-only styled text: the {@link MarkdownText} subset, plus citation markers
 * that open their source when clicked.
 *
 * <p>Renders are coalesced while a message streams in, so a long answer costs a bounded number of
 * document rebuilds instead of one per token. {@link #flush()} settles whatever is pending.
 */
final class MarkdownPane extends JTextPane {
    /** Marks a run as citation {@code n}; read back by hit-testing so a click knows what to open. */
    private static final Object CITATION = new Object();
    private static final int RENDER_DELAY_MS = 90;
    private static final int BASE_SIZE = 13;
    private static final int CODE_SIZE = 12;
    private static final Color CODE_TEXT = new Color(206, 218, 224);
    private static final Color CODE_BACKGROUND = Theme.BACKGROUND;
    /** Swing silently resolves a missing family to a proportional default, which code cannot use. */
    private static final String MONO_FAMILY = monoFamily();

    private final Color textColor;
    private final IntConsumer onCitation;
    private final Runnable onRendered;
    private final Timer coalesce;
    private String source = "";
    private boolean markdown;
    private boolean error;
    private boolean pending;
    /** A zoom re-renders every pane at once; the page re-measures them itself afterwards. */
    private boolean rescaling;
    MarkdownPane(Color foreground, Color selection, Color selectedText, IntConsumer onCitation,
                 Runnable onRendered) {
        this.textColor = foreground;
        this.onCitation = onCitation;
        this.onRendered = onRendered;
        this.coalesce = new Timer(RENDER_DELAY_MS, event -> render());
        coalesce.setRepeats(false);
        setEditable(false);
        setOpaque(false);
        setBorder(null);
        setFocusable(true);
        setFont(Theme.UI_FONT.deriveFont(Theme.contentSize(BASE_SIZE)));
        setForeground(foreground);
        setSelectionColor(selection);
        setSelectedTextColor(selectedText);
        setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
        // Re-rendering must not move the caret: the transcript decides what is on screen.
        ((DefaultCaret) getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        installCitationClicks();
    }

    /** Immediate render, for text that is already complete: history, a question, an error. */
    void set(String value, boolean asMarkdown) {
        source = value == null ? "" : value;
        markdown = asMarkdown;
        coalesce.stop();
        render();
    }

    /** Coalesced render, for text still arriving token by token. */
    void streaming(String value, boolean asMarkdown) {
        source = value == null ? "" : value;
        markdown = asMarkdown;
        pending = true;
        coalesce.restart();
    }

    void flush() {
        coalesce.stop();
        if (pending) render();
    }

    /** Failed turns are shown verbatim in red; a provider message is not markdown. */
    void markError() {
        error = true;
        set(source, false);
    }

    /**
     * Re-renders at the current reading scale. Deliberately silent: a zoom touches every bubble on the
     * page, and letting each one ask the transcript to re-measure and scroll would drag the reader to
     * the bottom of a conversation they were reading the middle of.
     */
    void rescale() {
        setFont(Theme.UI_FONT.deriveFont(Theme.contentSize(BASE_SIZE)));
        coalesce.stop();
        rescaling = true;
        try {
            render();
        } finally {
            rescaling = false;
        }
    }

    /**
     * The transcript owns scrolling. Without this a re-render mid-stream would drag the view back to
     * this bubble's caret and fight the scroll-to-bottom the page just did.
     */
    @Override
    public void scrollRectToVisible(Rectangle rectangle) {
    }
    private void render() {
        pending = false;
        StyledDocument document = (StyledDocument) getDocument();
        try {
            document.remove(0, document.getLength());
            if (markdown && !error) write(document, MarkdownText.parse(source));
            else document.insertString(0, source, plain(false, false));
        } catch (BadLocationException unexpected) {
            setText(source);
        }
        if (onRendered != null && !rescaling) onRendered.run();
    }

    private void write(StyledDocument document, MarkdownText text) throws BadLocationException {
        List<MarkdownText.Line> lines = text.lines();
        for (int index = 0; index < lines.size(); index++) {
            MarkdownText.Line line = lines.get(index);
            // Spacing comes from the paragraph attributes, so a blank source line adds nothing.
            if (line.block() == MarkdownText.Block.PARAGRAPH && line.runs().isEmpty()) continue;
            int start = document.getLength();
            if (line.block() == MarkdownText.Block.RULE) {
                document.insertString(start, "─".repeat(28) + "\n", rule());
            } else {
                if (!line.marker().isEmpty()) {
                    document.insertString(start, line.marker() + "  ", marker());
                }
                for (MarkdownText.Run run : line.runs()) {
                    document.insertString(document.getLength(), body(line, run), style(line, run));
                }
                document.insertString(document.getLength(), "\n", plain(false, false));
            }
            document.setParagraphAttributes(start, document.getLength() - start,
                    paragraph(line, blockAt(lines, index - 1), blockAt(lines, index + 1)), true);
        }
    }

    /**
     * Swing paints a run's background around its glyphs only, so an inline code span would sit flush
     * against the words next to it. Thin spaces inside the run are the padding.
     */
    private static String body(MarkdownText.Line line, MarkdownText.Run run) {
        boolean inlineCode = run.code() && line.block() != MarkdownText.Block.CODE;
        return inlineCode ? " " + run.text() + " " : run.text();
    }

    private static MarkdownText.Block blockAt(List<MarkdownText.Line> lines, int index) {
        return index < 0 || index >= lines.size() ? null : lines.get(index).block();
    }

    private SimpleAttributeSet style(MarkdownText.Line line, MarkdownText.Run run) {
        if (run.citation() > 0) return citation(run.citation());
        if (line.block() == MarkdownText.Block.CODE || run.code()) return code();
        SimpleAttributeSet attributes = plain(run.bold(), run.italic());
        switch (line.block()) {
            case HEADING -> {
                StyleConstants.setBold(attributes, true);
                StyleConstants.setFontSize(attributes,
                        Math.round(Theme.contentSize(Math.max(BASE_SIZE, 17 - line.level()))));
            }
            case QUOTE -> {
                StyleConstants.setForeground(attributes, error ? Theme.RED : Theme.MUTED);
                StyleConstants.setItalic(attributes, true);
            }
            default -> { }
        }
        return attributes;
    }
    private SimpleAttributeSet plain(boolean bold, boolean italic) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setFontFamily(attributes, Theme.UI_FONT.getFamily());
        StyleConstants.setFontSize(attributes, Math.round(Theme.contentSize(BASE_SIZE)));
        StyleConstants.setForeground(attributes, error ? Theme.RED : textColor);
        StyleConstants.setBold(attributes, bold);
        StyleConstants.setItalic(attributes, italic);
        return attributes;
    }

    private SimpleAttributeSet code() {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setFontFamily(attributes, MONO_FAMILY);
        StyleConstants.setFontSize(attributes, Math.round(Theme.contentSize(CODE_SIZE)));
        StyleConstants.setForeground(attributes, error ? Theme.RED : CODE_TEXT);
        StyleConstants.setBackground(attributes, CODE_BACKGROUND);
        return attributes;
    }

    private SimpleAttributeSet citation(int number) {
        SimpleAttributeSet attributes = plain(true, false);
        StyleConstants.setForeground(attributes, Theme.ACCENT);
        StyleConstants.setUnderline(attributes, true);
        attributes.addAttribute(CITATION, number);
        return attributes;
    }

    private SimpleAttributeSet marker() {
        SimpleAttributeSet attributes = plain(false, false);
        StyleConstants.setForeground(attributes, error ? Theme.RED : Theme.MUTED);
        return attributes;
    }

    private SimpleAttributeSet rule() {
        SimpleAttributeSet attributes = plain(false, false);
        StyleConstants.setForeground(attributes, Theme.BORDER);
        return attributes;
    }

    /**
     * Indent and spacing per block. Hanging indents keep wrapped list text clear of the bullet, and a
     * run of code lines is spaced as one block rather than line by line.
     */
    private static SimpleAttributeSet paragraph(MarkdownText.Line line, MarkdownText.Block previous,
                                                MarkdownText.Block next) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setSpaceBelow(attributes, 8f);
        switch (line.block()) {
            case CODE -> {
                StyleConstants.setLeftIndent(attributes, 12f);
                StyleConstants.setSpaceAbove(attributes, previous == MarkdownText.Block.CODE ? 0f : 8f);
                StyleConstants.setSpaceBelow(attributes, next == MarkdownText.Block.CODE ? 0f : 8f);
            }
            case BULLET, NUMBERED -> {
                StyleConstants.setLeftIndent(attributes, 16f + line.level() * 14f);
                StyleConstants.setFirstLineIndent(attributes, -14f);
                StyleConstants.setSpaceBelow(attributes, 3f);
            }
            case HEADING -> {
                StyleConstants.setSpaceAbove(attributes, previous == null ? 0f : 10f);
                StyleConstants.setSpaceBelow(attributes, 5f);
            }
            case QUOTE -> {
                StyleConstants.setLeftIndent(attributes, 12f);
                StyleConstants.setSpaceAbove(attributes, 2f);
            }
            default -> {
                if (previous == MarkdownText.Block.BULLET || previous == MarkdownText.Block.NUMBERED) {
                    StyleConstants.setSpaceAbove(attributes, 6f);
                }
            }
        }
        return attributes;
    }
    private void installCitationClicks() {
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getButton() != MouseEvent.BUTTON1 || onCitation == null) return;
                int number = citationAt(event.getPoint());
                if (number > 0) onCitation.accept(number);
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent event) {
                setCursor(Cursor.getPredefinedCursor(citationAt(event.getPoint()) > 0
                        ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
            }
        });
    }

    /**
     * Citation number under {@code point}, or {@code 0}. {@code viewToModel2D} answers with the
     * nearest position even for a click past the end of a line, so the character's own bounds decide
     * whether the marker was really hit.
     */
    int citationAt(Point point) {
        int position = viewToModel2D(point);
        if (position < 0) return 0;
        Element element = ((StyledDocument) getDocument()).getCharacterElement(position);
        Object value = element.getAttributes().getAttribute(CITATION);
        if (!(value instanceof Integer number)) return 0;
        try {
            Rectangle2D bounds = modelToView2D(position);
            if (bounds == null) return 0;
            return point.getX() >= bounds.getX() - 2 && point.getX() <= bounds.getMaxX() + 2 ? number : 0;
        } catch (BadLocationException unexpected) {
            return 0;
        }
    }

    /** Citation number carried by the run at {@code offset}, for tests that have no geometry. */
    int citationAtOffset(int offset) {
        Object value = ((StyledDocument) getDocument()).getCharacterElement(offset)
                .getAttributes().getAttribute(CITATION);
        return value instanceof Integer number ? number : 0;
    }

    private static String monoFamily() {
        String requested = Theme.MONO_FONT.getFamily();
        return new Font(requested, Font.PLAIN, CODE_SIZE).getFamily().equalsIgnoreCase(requested)
                ? requested : Font.MONOSPACED;
    }
}
