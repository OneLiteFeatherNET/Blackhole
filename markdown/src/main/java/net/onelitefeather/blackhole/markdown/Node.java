package net.onelitefeather.blackhole.markdown;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A node of the sanitised block tree. The tree is the only thing that leaves the parser: there is no raw HTML node, no image
 * node and a {@link Link} always carries a validated absolute http or https URL. Clients draw it with text nodes and elements.
 */
public sealed interface Node {

    /** The {@code type} the node has on the wire. */
    String type();

    /** The wire form: a map with {@code type}, the node's own fields and {@code children} where it has any. */
    Map<String, Object> toMap();

    /** Whether the node is a block (a paragraph, a code block, a list or a quote). */
    default boolean block() {
        return false;
    }

    /** The wire form of a list of nodes. */
    static List<Map<String, Object>> toMaps(List<? extends Node> nodes) {
        List<Map<String, Object>> maps = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            maps.add(node.toMap());
        }
        return maps;
    }

    /** Literal text; it may contain line feeds only in the remainder paragraph of an oversized message. */
    record Text(String text) implements Node {
        @Override
        public String type() {
            return "text";
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", "text");
            map.put("text", this.text);
            return map;
        }
    }

    /** A line break inside a paragraph. */
    record Break() implements Node {
        @Override
        public String type() {
            return "br";
        }

        @Override
        public Map<String, Object> toMap() {
            return new LinkedHashMap<>(Map.of("type", "br"));
        }
    }

    /** Bold. */
    record Strong(List<Node> children) implements Node {
        public Strong {
            children = List.copyOf(children);
        }

        @Override
        public String type() {
            return "strong";
        }

        @Override
        public Map<String, Object> toMap() {
            return parent("strong", this.children);
        }
    }

    /** Italic. */
    record Em(List<Node> children) implements Node {
        public Em {
            children = List.copyOf(children);
        }

        @Override
        public String type() {
            return "em";
        }

        @Override
        public Map<String, Object> toMap() {
            return parent("em", this.children);
        }
    }

    /** Strikethrough. */
    record Del(List<Node> children) implements Node {
        public Del {
            children = List.copyOf(children);
        }

        @Override
        public String type() {
            return "del";
        }

        @Override
        public Map<String, Object> toMap() {
            return parent("del", this.children);
        }
    }

    /** Inline code; no markup inside. */
    record Code(String text) implements Node {
        @Override
        public String type() {
            return "code";
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", "code");
            map.put("text", this.text);
            return map;
        }
    }

    /**
     * A link.
     *
     * @param href     the normalised absolute http or https URL
     * @param external {@code false} only for a trusted domain or a true subdomain of one
     */
    record Link(String href, boolean external, List<Node> children) implements Node {
        public Link {
            children = List.copyOf(children);
        }

        @Override
        public String type() {
            return "link";
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", "link");
            map.put("href", this.href);
            map.put("external", this.external);
            map.put("children", toMaps(this.children));
            return map;
        }
    }

    /** A paragraph of inline nodes. */
    record Paragraph(List<Node> children) implements Node {
        public Paragraph {
            children = List.copyOf(children);
        }

        @Override
        public String type() {
            return "paragraph";
        }

        @Override
        public boolean block() {
            return true;
        }

        @Override
        public Map<String, Object> toMap() {
            return parent("paragraph", this.children);
        }
    }

    /** A fenced code block; the info string is dropped and nothing inside is parsed. */
    record CodeBlock(String text) implements Node {
        @Override
        public String type() {
            return "code_block";
        }

        @Override
        public boolean block() {
            return true;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", "code_block");
            map.put("text", this.text);
            return map;
        }
    }

    /** A block quote. */
    record Quote(List<Node> children) implements Node {
        public Quote {
            children = List.copyOf(children);
        }

        @Override
        public String type() {
            return "quote";
        }

        @Override
        public boolean block() {
            return true;
        }

        @Override
        public Map<String, Object> toMap() {
            return parent("quote", this.children);
        }
    }

    /**
     * A bullet or numbered list.
     *
     * @param start the number of the first item of an ordered list, 1 otherwise
     * @param items the {@link Item}s
     */
    record ListNode(boolean ordered, int start, List<Item> items) implements Node {
        public ListNode {
            items = List.copyOf(items);
        }

        @Override
        public String type() {
            return "list";
        }

        @Override
        public boolean block() {
            return true;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", "list");
            map.put("ordered", this.ordered);
            map.put("start", this.start);
            map.put("children", toMaps(this.items));
            return map;
        }
    }

    /** One item of a list; it holds blocks. */
    record Item(List<Node> children) implements Node {
        public Item {
            children = List.copyOf(children);
        }

        @Override
        public String type() {
            return "item";
        }

        @Override
        public Map<String, Object> toMap() {
            return parent("item", this.children);
        }
    }

    private static Map<String, Object> parent(String type, List<Node> children) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("children", toMaps(children));
        return map;
    }
}
