package net.onelitefeather.blackhole.markdown;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses the chat Markdown subset into a sanitised block tree. Everything outside the subset is literal text, never an error: raw
 * HTML, images, headings, tables, reference links, {@code __x__}, and any link whose target {@link LinkRules} refuses.
 *
 * <p>The scanner is hand written, uses no regular expressions on user text and does linear work: every loop counts a step in
 * {@link #steps()}, so tests assert the bound with the counter instead of a clock. Containers (quotes and lists) nest up to
 * {@value #MAX_LEVELS} levels, then the rest is literal text; more than {@value #MAX_NODES} nodes turn the rest of the message into
 * one literal paragraph. Inline marks never nest the same kind twice and at most three are open at once. The parser never throws.
 *
 * <p>An instance keeps the step counter of its last {@link #parse(String)}, so it is not for concurrent use; make one per call.
 */
public final class MarkdownParser {

    /** Containers (quotes and lists) nest this deep; the rest is literal text. */
    public static final int MAX_LEVELS = 3;

    /** More nodes than this turn the remainder of the message into one literal paragraph. */
    public static final int MAX_NODES = 500;

    /** At most this many inline marks (bold, italic, strike, link text) are open at the same time. */
    private static final int MAX_OPEN_MARKS = 3;

    private static final int STRONG = 0;
    private static final int EM = 1;
    private static final int DEL = 2;
    private static final int BRACKET = 3;

    private final LinkRules rules;
    private long steps;

    /** A parser with the default link rules. */
    public MarkdownParser() {
        this(LinkRules.defaults());
    }

    public MarkdownParser(LinkRules rules) {
        this.rules = rules;
    }

    /** The work the last {@link #parse(String)} did, in steps; each is a constant amount of work. */
    public long steps() {
        return this.steps;
    }

    /**
     * Parses a message.
     *
     * @param source the stored message; {@code null} is nothing
     * @return the blocks, never {@code null}
     */
    public List<Node> parse(String source) {
        this.steps = 0;
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<String> lines = lines(source);
        List<Node> out = new ArrayList<>();
        int nodes = 0;
        int i = 0;
        while (i < lines.size()) {
            step();
            if (blank(lines.get(i))) {
                i++;
                continue;
            }
            Parsed parsed = block(lines, i, 0);
            nodes += count(parsed.node());
            if (nodes > MAX_NODES) {
                out.add(new Node.Paragraph(List.of(new Node.Text(String.join("\n", lines.subList(i, lines.size()))))));
                this.steps += lines.size() - i;
                break;
            }
            out.add(parsed.node());
            i = parsed.next();
        }
        return out;
    }

    // ---- lines ----

    /** Normalises line ends, drops control and bidirectional override characters, turns tabs into spaces, splits into lines. */
    private List<String> lines(String source) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int n = source.length();
        for (int i = 0; i < n; i++) {
            step();
            char c = source.charAt(i);
            if (c == '\r') {
                if (i + 1 < n && source.charAt(i + 1) == '\n') {
                    i++;
                }
                lines.add(line.toString());
                line.setLength(0);
            } else if (c == '\n') {
                lines.add(line.toString());
                line.setLength(0);
            } else if (c == '\t') {
                line.append("    ");
            } else if (c < 0x20 || (c >= 0x7f && c <= 0x9f) || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069)) {
                continue;
            } else {
                line.append(c);
            }
        }
        lines.add(line.toString());
        return lines;
    }

    private boolean blank(String line) {
        for (int i = 0; i < line.length(); i++) {
            step();
            if (line.charAt(i) != ' ') {
                return false;
            }
        }
        return true;
    }

    private int indent(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            step();
            i++;
        }
        return i;
    }

    private static int count(Node node) {
        int total = 1;
        switch (node) {
            case Node.Strong n -> total += count(n.children());
            case Node.Em n -> total += count(n.children());
            case Node.Del n -> total += count(n.children());
            case Node.Link n -> total += count(n.children());
            case Node.Paragraph n -> total += count(n.children());
            case Node.Quote n -> total += count(n.children());
            case Node.Item n -> total += count(n.children());
            case Node.ListNode n -> total += count(n.items());
            case Node.Text ignored -> {
            }
            case Node.Break ignored -> {
            }
            case Node.Code ignored -> {
            }
            case Node.CodeBlock ignored -> {
            }
        }
        return total;
    }

    private static int count(List<? extends Node> nodes) {
        int total = 0;
        for (Node node : nodes) {
            total += count(node);
        }
        return total;
    }

    // ---- blocks ----

    private record Parsed(Node node, int next) {
    }

    private record Marker(boolean ordered, int number, int offset) {
    }

    private List<Node> blocks(List<String> lines, int level) {
        List<Node> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            step();
            if (blank(lines.get(i))) {
                i++;
                continue;
            }
            Parsed parsed = block(lines, i, level);
            out.add(parsed.node());
            i = parsed.next();
        }
        return out;
    }

    /** The block that starts at a non-blank line; {@code level} counts the containers around it. */
    private Parsed block(List<String> lines, int i, int level) {
        String line = lines.get(i);
        String rest = line.substring(indent(line));
        int fence = fenceLength(rest);
        if (fence > 0) {
            return fence(lines, i, fence);
        }
        if (level < MAX_LEVELS) {
            if (rest.startsWith(">")) {
                return quote(lines, i, level);
            }
            Marker marker = marker(rest);
            if (marker != null) {
                return list(lines, i, level, marker);
            }
        }
        return paragraph(lines, i, level);
    }

    /** The length of the backtick run that opens a fence on this line, or 0 when the line does not open one. */
    private int fenceLength(String rest) {
        int run = 0;
        while (run < rest.length() && rest.charAt(run) == '`') {
            step();
            run++;
        }
        if (run < 3) {
            return 0;
        }
        for (int i = run; i < rest.length(); i++) {
            step();
            if (rest.charAt(i) == '`') {
                return 0;
            }
        }
        return run;
    }

    private Parsed fence(List<String> lines, int i, int fence) {
        StringBuilder text = new StringBuilder();
        int j = i + 1;
        boolean first = true;
        while (j < lines.size()) {
            step();
            String line = lines.get(j);
            if (closesFence(line, fence)) {
                return new Parsed(new Node.CodeBlock(text.toString()), j + 1);
            }
            if (!first) {
                text.append('\n');
            }
            text.append(line);
            first = false;
            j++;
        }
        return new Parsed(new Node.CodeBlock(text.toString()), j);
    }

    private boolean closesFence(String line, int fence) {
        int start = indent(line);
        int run = 0;
        for (int k = start; k < line.length(); k++) {
            step();
            if (line.charAt(k) != '`') {
                return false;
            }
            run++;
        }
        return run >= fence;
    }

    private Parsed quote(List<String> lines, int i, int level) {
        List<String> inner = new ArrayList<>();
        int j = i;
        while (j < lines.size()) {
            step();
            String line = lines.get(j);
            int ind = indent(line);
            if (ind >= line.length() || line.charAt(ind) != '>') {
                break;
            }
            int from = ind + 1;
            if (from < line.length() && line.charAt(from) == ' ') {
                from++;
            }
            inner.add(line.substring(from));
            j++;
        }
        return new Parsed(new Node.Quote(blocks(inner, level + 1)), j);
    }

    /** A list marker at the start of the (indent-stripped) line, or {@code null}. A marker needs a space and some text after it. */
    private Marker marker(String rest) {
        if (rest.length() < 3) {
            return null;
        }
        char c = rest.charAt(0);
        int numberEnd = 0;
        boolean ordered = false;
        int number = 0;
        if (c == '-' || c == '*') {
            numberEnd = 1;
        } else if (c >= '0' && c <= '9') {
            while (numberEnd < rest.length() && numberEnd < 10 && rest.charAt(numberEnd) >= '0' && rest.charAt(numberEnd) <= '9') {
                step();
                number = number * 10 + (rest.charAt(numberEnd) - '0');
                numberEnd++;
            }
            if (numberEnd > 9 || numberEnd >= rest.length() || rest.charAt(numberEnd) != '.') {
                return null;
            }
            ordered = true;
            numberEnd++;
        } else {
            return null;
        }
        if (numberEnd >= rest.length() || rest.charAt(numberEnd) != ' ') {
            return null;
        }
        int offset = numberEnd;
        while (offset < rest.length() && rest.charAt(offset) == ' ') {
            step();
            offset++;
        }
        if (offset >= rest.length()) {
            return null;
        }
        return new Marker(ordered, ordered ? number : 1, offset);
    }

    private Parsed list(List<String> lines, int i, int level, Marker first) {
        int base = indent(lines.get(i));
        List<Node.Item> items = new ArrayList<>();
        int j = i;
        while (j < lines.size()) {
            step();
            String line = lines.get(j);
            if (blank(line)) {
                int k = nextNonBlank(lines, j);
                if (k >= lines.size() || !sibling(lines.get(k), first.ordered(), base)) {
                    break;
                }
                j = k;
                continue;
            }
            if (!sibling(line, first.ordered(), base)) {
                break;
            }
            String rest = line.substring(indent(line));
            Marker marker = marker(rest);
            List<String> inner = new ArrayList<>();
            inner.add(rest.substring(marker.offset()));
            j++;
            while (j < lines.size()) {
                step();
                String next = lines.get(j);
                if (blank(next)) {
                    int k = nextNonBlank(lines, j);
                    if (k < lines.size() && indent(lines.get(k)) >= base + 2) {
                        inner.add("");
                        j = k;
                        continue;
                    }
                    break;
                }
                if (indent(next) >= base + 2) {
                    inner.add(stripIndent(next, base + 2));
                    j++;
                } else {
                    break;
                }
            }
            items.add(new Node.Item(blocks(inner, level + 1)));
        }
        return new Parsed(new Node.ListNode(first.ordered(), first.number(), items), j);
    }

    private int nextNonBlank(List<String> lines, int from) {
        int k = from;
        while (k < lines.size() && blank(lines.get(k))) {
            step();
            k++;
        }
        return k;
    }

    private boolean sibling(String line, boolean ordered, int base) {
        int ind = indent(line);
        if (ind > base + 1) {
            return false;
        }
        Marker marker = marker(line.substring(ind));
        return marker != null && marker.ordered() == ordered;
    }

    private static String stripIndent(String line, int max) {
        int i = 0;
        while (i < line.length() && i < max && line.charAt(i) == ' ') {
            i++;
        }
        return line.substring(i);
    }

    private boolean startsBlock(String line, int level) {
        String rest = line.substring(indent(line));
        if (fenceLength(rest) > 0) {
            return true;
        }
        return level < MAX_LEVELS && (rest.startsWith(">") || marker(rest) != null);
    }

    private Parsed paragraph(List<String> lines, int i, int level) {
        List<String> text = new ArrayList<>();
        int j = i;
        while (j < lines.size()) {
            step();
            String line = lines.get(j);
            if (blank(line) || (j > i && startsBlock(line, level))) {
                break;
            }
            text.add(line.strip());
            j++;
        }
        return new Parsed(new Node.Paragraph(new Inline(String.join("\n", text)).run()), j);
    }

    // ---- inline ----

    private void step() {
        this.steps++;
    }

    private static boolean punctuation(char c) {
        return c > 0x20 && c < 0x7f && !Character.isLetterOrDigit(c);
    }

    private static boolean space(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    private static boolean alphanumeric(char c) {
        return Character.isLetterOrDigit(c);
    }

    /** An open mark waiting for its closer. */
    private record Pending(int index, Delim delim) {
    }

    /** The characters of a mark that has not (yet) been matched; they become text if it never is. */
    private static final class Delim {
        final String literal;
        final char c;
        final boolean image;

        Delim(String literal, char c, boolean image) {
            this.literal = literal;
            this.c = c;
            this.image = image;
        }
    }

    /** A token of an inline run: a finished node or a delimiter that may still be matched. */
    private record Tok(Node node, Delim delim) {
    }

    private static final class IntList {
        int[] values = new int[4];
        int size;

        void add(int value) {
            if (this.size == this.values.length) {
                this.values = java.util.Arrays.copyOf(this.values, this.size * 2);
            }
            this.values[this.size++] = value;
        }
    }

    /** The inline scanner for one paragraph. */
    private final class Inline {

        private final String s;
        private final int n;
        private final List<Tok> out = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private final Pending[] pending = new Pending[4];
        private final Map<Integer, IntList> runs = new HashMap<>();
        private final Map<Integer, Integer> cursors = new HashMap<>();

        Inline(String s) {
            this.s = s;
            this.n = s.length();
        }

        List<Node> run() {
            indexBackticks();
            int i = 0;
            while (i < this.n) {
                step();
                char c = this.s.charAt(i);
                switch (c) {
                    case '\\' -> {
                        if (i + 1 < this.n && punctuation(this.s.charAt(i + 1))) {
                            this.text.append(this.s.charAt(i + 1));
                            i += 2;
                        } else {
                            this.text.append(c);
                            i++;
                        }
                    }
                    case '\n' -> {
                        flush();
                        this.out.add(new Tok(new Node.Break(), null));
                        i++;
                    }
                    case '`' -> i = code(i);
                    case '*', '_', '~' -> i = delimiter(i, c);
                    case '[' -> i = openBracket(i, false);
                    case '!' -> {
                        if (i + 1 < this.n && this.s.charAt(i + 1) == '[') {
                            i = openBracket(i + 1, true);
                        } else {
                            this.text.append(c);
                            i++;
                        }
                    }
                    case ']' -> i = closeBracket(i);
                    case '<' -> i = angle(i);
                    case 'h', 'H' -> i = bare(i);
                    default -> {
                        this.text.append(c);
                        i++;
                    }
                }
            }
            flush();
            return nodes(0, this.out.size());
        }

        private void flush() {
            if (this.text.length() > 0) {
                this.out.add(new Tok(new Node.Text(this.text.toString()), null));
                this.text.setLength(0);
            }
        }

        // -- tokens to nodes --

        /** The nodes of tokens {@code [from, to)}; unmatched delimiters become text and neighbouring texts are merged. */
        private List<Node> nodes(int from, int to) {
            List<Node> nodes = new ArrayList<>();
            StringBuilder merged = new StringBuilder();
            for (int k = from; k < to; k++) {
                step();
                Tok tok = this.out.get(k);
                if (tok.delim() != null) {
                    merged.append(tok.delim().literal);
                } else if (tok.node() instanceof Node.Text t) {
                    merged.append(t.text());
                } else {
                    if (merged.length() > 0) {
                        nodes.add(new Node.Text(merged.toString()));
                        merged.setLength(0);
                    }
                    nodes.add(tok.node());
                }
            }
            if (merged.length() > 0) {
                nodes.add(new Node.Text(merged.toString()));
            }
            return nodes;
        }

        private int openCount() {
            int open = 0;
            for (Pending p : this.pending) {
                if (p != null) {
                    open++;
                }
            }
            return open;
        }

        /** Replaces everything from {@code index} on by one node and forgets the marks that were inside. */
        private void collapse(int index, Node node) {
            this.out.subList(index, this.out.size()).clear();
            this.out.add(new Tok(node, null));
            for (int k = 0; k < this.pending.length; k++) {
                if (this.pending[k] != null && this.pending[k].index() >= index) {
                    this.pending[k] = null;
                }
            }
        }

        // -- code --

        private void indexBackticks() {
            int i = 0;
            while (i < this.n) {
                step();
                if (this.s.charAt(i) == '`') {
                    int start = i;
                    while (i < this.n && this.s.charAt(i) == '`') {
                        step();
                        i++;
                    }
                    this.runs.computeIfAbsent(i - start, k -> new IntList()).add(start);
                } else {
                    i++;
                }
            }
        }

        private int code(int i) {
            int k = 0;
            while (i + k < this.n && this.s.charAt(i + k) == '`') {
                step();
                k++;
            }
            IntList same = this.runs.get(k);
            int closer = -1;
            if (same != null) {
                int cursor = this.cursors.getOrDefault(k, 0);
                while (cursor < same.size && same.values[cursor] < i + k) {
                    step();
                    cursor++;
                }
                this.cursors.put(k, cursor);
                if (cursor < same.size) {
                    closer = same.values[cursor];
                }
            }
            if (closer < 0) {
                this.text.append(this.s, i, i + k);
                return i + k;
            }
            String content = this.s.substring(i + k, closer).replace('\n', ' ');
            if (content.length() > 2 && content.charAt(0) == ' ' && content.charAt(content.length() - 1) == ' ' && !content.isBlank()) {
                content = content.substring(1, content.length() - 1);
            }
            flush();
            this.out.add(new Tok(new Node.Code(content), null));
            return closer + k;
        }

        // -- emphasis --

        private int delimiter(int i, char c) {
            int r = 0;
            while (i + r < this.n && this.s.charAt(i + r) == c) {
                step();
                r++;
            }
            if ((c == '_' && r != 1) || (c == '~' && r != 2)) {
                this.text.append(this.s, i, i + r);
                return i + r;
            }
            char prev = i > 0 ? this.s.charAt(i - 1) : ' ';
            char next = i + r < this.n ? this.s.charAt(i + r) : ' ';
            boolean left = !space(next);
            boolean right = !space(prev);
            boolean canOpen = c == '_' ? left && !alphanumeric(prev) : left;
            boolean canClose = c == '_' ? right && !alphanumeric(next) : right;
            boolean em = c == '*' ? r % 2 == 1 : c == '_';
            int strong = c == '*' ? r / 2 : 0;
            boolean del = c == '~';
            flush();
            boolean closing = canClose && (em && matches(EM, c) || strong > 0 && this.pending[STRONG] != null || del && this.pending[DEL] != null);
            if (closing) {
                close(c, em, strong, del);
            } else if (canOpen) {
                open(c, em, strong, del);
            } else {
                this.text.append(this.s, i, i + r);
            }
            return i + r;
        }

        private boolean matches(int kind, char c) {
            return this.pending[kind] != null && this.pending[kind].delim().c == c;
        }

        private void open(char c, boolean em, int strong, boolean del) {
            if (em) {
                push(EM, String.valueOf(c), c);
            }
            for (int k = 0; k < strong; k++) {
                push(STRONG, "**", c);
            }
            if (del) {
                push(DEL, "~~", c);
            }
        }

        private void push(int kind, String literal, char c) {
            if (this.pending[kind] != null || openCount() >= MAX_OPEN_MARKS) {
                this.text.append(literal);
                return;
            }
            flush();
            Delim delim = new Delim(literal, c, false);
            this.out.add(new Tok(null, delim));
            this.pending[kind] = new Pending(this.out.size() - 1, delim);
        }

        /** Closes the innermost open mark first, so that {@code ***x***} nests the same way it opened. */
        private void close(char c, boolean em, int strong, boolean del) {
            boolean emLeft = em;
            int strongLeft = strong;
            boolean delLeft = del;
            while (emLeft || strongLeft > 0 || delLeft) {
                int kind = -1;
                int best = -1;
                if (emLeft && matches(EM, c)) {
                    kind = EM;
                    best = this.pending[EM].index();
                }
                if (strongLeft > 0 && this.pending[STRONG] != null && this.pending[STRONG].index() > best) {
                    kind = STRONG;
                    best = this.pending[STRONG].index();
                }
                if (delLeft && this.pending[DEL] != null && this.pending[DEL].index() > best) {
                    kind = DEL;
                }
                if (kind < 0) {
                    if (emLeft) {
                        this.text.append(c);
                    }
                    this.text.append(String.valueOf(c).repeat(2 * strongLeft));
                    if (delLeft) {
                        this.text.append("~~");
                    }
                    return;
                }
                finish(kind, c);
                switch (kind) {
                    case EM -> emLeft = false;
                    case STRONG -> strongLeft--;
                    default -> delLeft = false;
                }
            }
        }

        private void finish(int kind, char c) {
            Pending open = this.pending[kind];
            flush();
            List<Node> children = nodes(open.index() + 1, this.out.size());
            if (children.isEmpty()) {
                this.pending[kind] = null;
                this.text.append(kind == STRONG ? "**" : kind == DEL ? "~~" : String.valueOf(c));
                return;
            }
            Node node = switch (kind) {
                case STRONG -> new Node.Strong(children);
                case EM -> new Node.Em(children);
                default -> new Node.Del(children);
            };
            collapse(open.index(), node);
        }

        // -- links --

        private int openBracket(int bracket, boolean image) {
            String literal = image ? "![" : "[";
            int end = bracket + 1;
            if (this.pending[BRACKET] == null && openCount() >= MAX_OPEN_MARKS) {
                this.text.append(literal);
                return end;
            }
            flush();
            Delim delim = new Delim(literal, '[', image);
            this.out.add(new Tok(null, delim));
            this.pending[BRACKET] = new Pending(this.out.size() - 1, delim);
            return end;
        }

        private int closeBracket(int i) {
            Pending open = this.pending[BRACKET];
            if (open == null) {
                this.text.append(']');
                return i + 1;
            }
            this.pending[BRACKET] = null;
            if (i + 1 < this.n && this.s.charAt(i + 1) == '(') {
                int close = destinationEnd(i + 2);
                if (close >= 0) {
                    String raw = this.s.substring(i + 2, close);
                    Optional<LinkRules.Target> target = open.delim().image ? Optional.empty() : MarkdownParser.this.rules.accept(raw);
                    if (target.isPresent()) {
                        flush();
                        List<Node> children = nodes(open.index() + 1, this.out.size());
                        if (children.isEmpty()) {
                            children = List.of(new Node.Text(target.get().href()));
                        }
                        collapse(open.index(), new Node.Link(target.get().href(), target.get().external(), children));
                        return close + 1;
                    }
                    this.text.append(this.s, i, close + 1);
                    return close + 1;
                }
            }
            this.text.append(']');
            return i + 1;
        }

        /**
         * The index of the {@code )} that closes a link target starting at {@code from}, or -1. The scan stops at the first character a
         * target cannot hold, and none of the stopping characters may appear inside it, so the scanned stretches of different links
         * never overlap and the total work stays linear.
         */
        private int destinationEnd(int from) {
            int depth = 1;
            for (int p = from; p < this.n; p++) {
                step();
                char ch = this.s.charAt(p);
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                    if (depth == 0) {
                        return p;
                    }
                } else if (ch <= ' ' || ch == '<' || ch == '>' || ch == '"' || ch == '\\' || ch == '[' || ch == ']') {
                    return -1;
                }
            }
            return -1;
        }

        // -- autolinks --

        private int angle(int i) {
            if (this.pending[BRACKET] == null && (this.s.regionMatches(true, i + 1, "http://", 0, 7) || this.s.regionMatches(true, i + 1, "https://", 0, 8))) {
                int end = i + 1;
                while (end < this.n) {
                    step();
                    char ch = this.s.charAt(end);
                    if (ch == '>' || ch <= ' ' || ch == '<') {
                        break;
                    }
                    end++;
                }
                if (end < this.n && this.s.charAt(end) == '>') {
                    String raw = this.s.substring(i + 1, end);
                    Optional<LinkRules.Target> target = MarkdownParser.this.rules.accept(raw);
                    if (target.isPresent()) {
                        flush();
                        this.out.add(new Tok(new Node.Link(target.get().href(), target.get().external(), List.of(new Node.Text(raw))), null));
                        return end + 1;
                    }
                }
            }
            this.text.append('<');
            return i + 1;
        }

        private int bare(int i) {
            boolean scheme = this.s.regionMatches(true, i, "http://", 0, 7) || this.s.regionMatches(true, i, "https://", 0, 8);
            if (!scheme || this.pending[BRACKET] != null || (i > 0 && alphanumeric(this.s.charAt(i - 1)))) {
                this.text.append(this.s.charAt(i));
                return i + 1;
            }
            int end = i;
            boolean paren = false;
            while (end < this.n) {
                step();
                char ch = this.s.charAt(end);
                if (ch <= ' ' || ch == '<' || ch == '>') {
                    break;
                }
                paren |= ch == '(';
                end++;
            }
            while (end > i && trailing(this.s.charAt(end - 1), paren)) {
                step();
                end--;
            }
            String raw = this.s.substring(i, end);
            Optional<LinkRules.Target> target = MarkdownParser.this.rules.accept(raw);
            if (target.isPresent()) {
                flush();
                this.out.add(new Tok(new Node.Link(target.get().href(), target.get().external(), List.of(new Node.Text(raw))), null));
            } else {
                this.text.append(raw);
            }
            return end;
        }

        /** Whether the last character of a bare URL is punctuation that belongs to the sentence around it; {@code paren}: the URL has a {@code (}. */
        private boolean trailing(char last, boolean paren) {
            if (last == ')') {
                return !paren;
            }
            return ".,;:!?'*_~]}".indexOf(last) >= 0;
        }
    }
}
