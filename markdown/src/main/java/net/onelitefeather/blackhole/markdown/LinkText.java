package net.onelitefeather.blackhole.markdown;

import java.util.List;
import java.util.function.Function;

/** Rules the two text renderers share. */
final class LinkText {

    private LinkText() {
    }

    /** Whether the shown text of a link is just its target, so the target need not be written twice. */
    static boolean isTarget(String shown, String href) {
        return shown.equals(href) || (href.endsWith("/") && shown.equals(href.substring(0, href.length() - 1)));
    }

    /**
     * The text of the blocks of a list item. A paragraph directly followed by a list, a quote or a code block needs no blank line
     * (those interrupt it); everything else is separated by one.
     */
    static String itemContent(List<Node> blocks, Function<Node, String> render) {
        StringBuilder out = new StringBuilder();
        Node previous = null;
        for (Node block : blocks) {
            if (previous != null) {
                boolean tight = previous instanceof Node.Paragraph
                        && (block instanceof Node.ListNode || block instanceof Node.Quote || block instanceof Node.CodeBlock);
                out.append(tight ? "\n" : "\n\n");
            }
            out.append(render.apply(block));
            previous = block;
        }
        return out.toString();
    }
}
