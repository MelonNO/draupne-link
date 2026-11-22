package de.droiddrone.pc.gui;

/**
 * Text this app did not write - a phone's device and version text from its name reply, a host name as Tailscale has
 * it, a release's tag, the flight controller's mode names, what a pilot PC tells its spectators - on its way into a
 * label, list or table cell, tooltip or message box: shown as the characters it is. Swing lays a label's text out as
 * HTML when it begins with {@code <html>}, and the app's own wrapping lines are HTML; such a text in either would be
 * read as markup (and a picture named in it asked for).
 */
final class AsText {
    private AsText() {
    }

    /** For a text put into one of the app's own HTML lines: its characters, none of them markup. Null reads as "". */
    static String html(String text) {
        if (text == null) return "";
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String as = c == '&' ? "&amp;" : c == '<' ? "&lt;" : c == '>' ? "&gt;" : c == '"' ? "&quot;" : c == '\'' ? "&#39;" : null;
            if (as == null) {
                if (out != null) out.append(c);
                continue;
            }
            if (out == null) out = new StringBuilder(text.length() + 16).append(text, 0, i);
            out.append(as);
        }
        return out == null ? text : out.toString();
    }

    /**
     * For a text that is a label's, a cell's, a tooltip's or a message's own (or the start of one, or of one of a
     * message's lines - a message box makes a label of each line): a line that begins with {@code <html>} gets a space
     * before it, which is all it takes for Swing to show it as it is.
     */
    static String label(String text) {
        if (text == null || text.indexOf('<') < 0) return text;
        StringBuilder out = null;
        int from = 0;
        for (int at = 0; at <= text.length() - 6; at++) {
            boolean lineStart = at == 0 || text.charAt(at - 1) == '\n' || text.charAt(at - 1) == '\r';
            if (!lineStart || !text.regionMatches(true, at, "<html>", 0, 6)) continue;
            if (out == null) out = new StringBuilder(text.length() + 4);
            out.append(text, from, at).append(' ');
            from = at;
        }
        return out == null ? text : out.append(text, from, text.length()).toString();
    }
}
