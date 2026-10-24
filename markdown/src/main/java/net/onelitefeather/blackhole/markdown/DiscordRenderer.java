package net.onelitefeather.blackhole.markdown;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a block tree as Discord text. Every character that Discord would read as markup or as a mention is escaped, so a message
 * only ever shows what the tree says; links are written {@code text <https://...>} so no embed appears. Callers still post with
 * empty allowed mentions. The reverse mapping, from Discord text to a source, belongs to the bot.
 */
public final class DiscordRenderer {

    /** Discord's limit for one message. */
    public static final int MAX_MESSAGE = 2000;

    private static final String ZWSP = "​";

    private DiscordRenderer() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** The Discord text of blocks; blocks are separated by a blank line. */
    public static String render(List<Node> blocks) {
        List<String> parts = new ArrayList<>();
        for (Node block : blocks) {
            parts.add(block(block));
        }
        return String.join("\n\n", parts);
    }

    /**
     * Splits the rendering into posts of at most {@code max} characters: at block boundaries where they fit, else at lines, and a
     * line longer than {@code max} is cut. A code block that is split keeps its fence in every part.
     */
    public static List<String> chunks(List<Node> blocks, int max) {
        int limit = Math.max(8, max);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (Node node : blocks) {
            String text = block(node);
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() > limit) {
                flush(chunks, current);
                chunks.addAll(split(node, text, limit));
            } else if (current.length() == 0) {
                current.append(text);
            } else if (current.length() + 2 + text.length() <= limit) {
                current.append("\n\n").append(text);
            } else {
                flush(chunks, current);
                current.append(text);
            }
        }
        flush(chunks, current);
        return chunks;
    }

    private static void flush(List<String> chunks, StringBuilder current) {
        if (current.length() > 0) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }

    private static List<String> split(Node node, String text, int limit) {
        if (node instanceof Node.CodeBlock code) {
            String fence = fence(code.text());
            int capacity = limit - 2 * (fence.length() + 1);
            if (capacity > 0) {
                List<String> parts = new ArrayList<>();
                for (String body : lines(code.text(), capacity)) {
                    parts.add(fence + "\n" + body + "\n" + fence);
                }
                return parts;
            }
        }
        return lines(text, limit);
    }

    /** Groups lines up to {@code limit} characters; a line that is longer is cut. */
    private static List<String> lines(String text, int limit) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String rest = line;
            while (rest.length() > limit) {
                flush(parts, current);
                int cut = limit;
                if (Character.isHighSurrogate(rest.charAt(cut - 1))) {
                    cut--;
                }
                parts.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            if (current.length() == 0) {
                current.append(rest);
            } else if (current.length() + 1 + rest.length() <= limit) {
                current.append('\n').append(rest);
            } else {
                flush(parts, current);
                current.append(rest);
            }
        }
        flush(parts, current);
        return parts;
    }

    // ---- blocks ----

    private static String block(Node node) {
        return switch (node) {
            case Node.Paragraph paragraph -> inline(paragraph.children());
            case Node.CodeBlock code -> {
                String fence = fence(code.text());
                yield fence + "\n" + code.text() + "\n" + fence;
            }
            case Node.Quote quote -> PlainTextRenderer.prefix(render(quote.children()), "> ", ">");
            case Node.ListNode list -> list(list);
            case Node.Item item -> render(item.children());
            default -> inline(List.of(node));
        };
    }

    private static String list(Node.ListNode list) {
        List<String> items = new ArrayList<>();
        int number = list.start();
        for (Node.Item item : list.items()) {
            String marker = list.ordered() ? number++ + ". " : "- ";
            items.add(PlainTextRenderer.hang(marker, LinkText.itemContent(item.children(), DiscordRenderer::block)));
        }
        return String.join("\n", items);
    }

    /** A fence one backtick longer than the longest run inside, and at least three. */
    private static String fence(String text) {
        return "`".repeat(Math.max(3, longestRun(text) + 1));
    }

    private static int longestRun(String text) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }

    // ---- inline ----

    private static String inline(List<Node> nodes) {
        StringBuilder out = new StringBuilder();
        boolean[] lineStart = {true};
        for (Node node : nodes) {
            inline(node, out, lineStart);
        }
        return out.toString();
    }

    private static void inline(Node node, StringBuilder out, boolean[] lineStart) {
        switch (node) {
            case Node.Text text -> text(text.text(), out, lineStart);
            case Node.Break ignored -> {
                out.append('\n');
                lineStart[0] = true;
            }
            case Node.Code code -> {
                String fence = "`".repeat(longestRun(code.text()) + 1);
                boolean pad = !code.text().isEmpty() && (code.text().charAt(0) == '`' || code.text().charAt(0) == ' '
                        || code.text().charAt(code.text().length() - 1) == '`' || code.text().charAt(code.text().length() - 1) == ' ');
                out.append(fence).append(pad ? " " : "").append(code.text()).append(pad ? " " : "").append(fence);
                lineStart[0] = false;
            }
            case Node.Strong strong -> wrap("**", strong.children(), out, lineStart);
            case Node.Em em -> wrap("*", em.children(), out, lineStart);
            case Node.Del del -> wrap("~~", del.children(), out, lineStart);
            case Node.Link link -> {
                String shown = PlainTextRenderer.inline(link.children());
                if (!LinkText.isTarget(shown, link.href())) {
                    for (Node child : link.children()) {
                        inline(child, out, lineStart);
                    }
                    out.append(' ');
                }
                out.append('<').append(link.href()).append('>');
                lineStart[0] = false;
            }
            default -> {
                out.append(block(node));
                lineStart[0] = false;
            }
        }
    }

    private static void wrap(String mark, List<Node> children, StringBuilder out, boolean[] lineStart) {
        out.append(mark);
        lineStart[0] = false;
        for (Node child : children) {
            inline(child, out, lineStart);
        }
        out.append(mark);
        lineStart[0] = false;
    }

    private static void text(String text, StringBuilder out, boolean[] lineStart) {
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            boolean start = lineStart[0];
            lineStart[0] = false;
            if (c == '\n') {
                out.append(c);
                lineStart[0] = true;
                i++;
                continue;
            }
            if (start && c >= '0' && c <= '9') {
                int j = i;
                while (j < n && text.charAt(j) >= '0' && text.charAt(j) <= '9') {
                    j++;
                }
                out.append(text, i, j);
                if (j < n && text.charAt(j) == '.') {
                    out.append("\\.");
                    j++;
                }
                i = j;
                continue;
            }
            switch (c) {
                case '\\', '*', '_', '~', '`', '[', ']', '(', ')', '<', '>', '|' -> out.append('\\').append(c);
                case '#', '-' -> out.append(start ? "\\" : "").append(c);
                case '@' -> out.append('@').append(ZWSP);
                case ':' -> {
                    out.append(':');
                    if (text.startsWith("//", i + 1)) {
                        out.append(ZWSP);
                    }
                }
                default -> out.append(c);
            }
            i++;
        }
    }
}
