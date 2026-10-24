package net.onelitefeather.blackhole.markdown;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a block tree as plain text for people: marks are dropped, a link shows its target after the text, blocks are separated by a
 * blank line. Nothing is escaped, because the output is not for a markup target.
 */
public final class PlainTextRenderer {

    private PlainTextRenderer() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** The plain text of blocks. */
    public static String render(List<Node> blocks) {
        List<String> parts = new ArrayList<>();
        for (Node block : blocks) {
            parts.add(block(block));
        }
        return String.join("\n\n", parts);
    }

    /** The plain text of the inline nodes of a block, or of the children of any node. */
    public static String inline(List<Node> nodes) {
        StringBuilder out = new StringBuilder();
        for (Node node : nodes) {
            inline(node, out);
        }
        return out.toString();
    }

    private static void inline(Node node, StringBuilder out) {
        switch (node) {
            case Node.Text text -> out.append(text.text());
            case Node.Break ignored -> out.append('\n');
            case Node.Code code -> out.append(code.text());
            case Node.Strong strong -> strong.children().forEach(child -> inline(child, out));
            case Node.Em em -> em.children().forEach(child -> inline(child, out));
            case Node.Del del -> del.children().forEach(child -> inline(child, out));
            case Node.Link link -> {
                String shown = inline(link.children());
                if (LinkText.isTarget(shown, link.href())) {
                    out.append(link.href());
                } else {
                    out.append(shown).append(" (").append(link.href()).append(')');
                }
            }
            default -> out.append(block(node));
        }
    }

    private static String block(Node node) {
        return switch (node) {
            case Node.Paragraph paragraph -> inline(paragraph.children());
            case Node.CodeBlock code -> code.text();
            case Node.Quote quote -> prefix(render(quote.children()), "> ", ">");
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
            items.add(hang(marker, LinkText.itemContent(item.children(), PlainTextRenderer::block)));
        }
        return String.join("\n", items);
    }

    /** The first line gets the marker, the others as many spaces. */
    static String hang(String marker, String content) {
        StringBuilder out = new StringBuilder();
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            if (i == 0) {
                out.append(marker).append(lines[i]);
            } else if (!lines[i].isEmpty()) {
                out.append(" ".repeat(marker.length())).append(lines[i]);
            }
        }
        return out.toString();
    }

    static String prefix(String content, String with, String forBlank) {
        StringBuilder out = new StringBuilder();
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(lines[i].isEmpty() ? forBlank : with + lines[i]);
        }
        return out.toString();
    }
}
