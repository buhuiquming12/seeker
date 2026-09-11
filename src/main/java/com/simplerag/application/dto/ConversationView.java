package com.simplerag.application.dto;

/**
 * One saved conversation, as the session list shows it.
 *
 * <p>Deliberately the shape both ports speak: the repository reads rows straight into it and the page
 * renders it unchanged. A second, identical record for the outbound direction would be ceremony -
 * there is no behaviour here to keep on one side of the boundary.
 */
public record ConversationView(String id, String knowledgeBaseId, String title, int messageCount,
                               long createdAt, long updatedAt) {
    /** Characters of the first question kept as the name; longer titles are elided. */
    public static final int TITLE_LIMIT = 24;
    private static final String UNTITLED = "新对话";

    /** What the list shows. A conversation is created before it has anything to be named after. */
    public String label() {
        return title == null || title.isBlank() ? UNTITLED : title;
    }

    public boolean empty() {
        return messageCount == 0;
    }

    /**
     * Names a conversation after its first question. Line breaks and runs of spaces collapse because
     * the list draws one line: a pasted stack trace would otherwise become a title of blanks.
     */
    public static String titleFrom(String question) {
        if (question == null) return "";
        String compact = question.replaceAll("\\s+", " ").strip();
        if (compact.isEmpty()) return "";
        return compact.length() <= TITLE_LIMIT ? compact : compact.substring(0, TITLE_LIMIT) + "…";
    }
}
