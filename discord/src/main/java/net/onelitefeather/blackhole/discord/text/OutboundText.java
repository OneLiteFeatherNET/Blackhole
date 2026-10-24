package net.onelitefeather.blackhole.discord.text;

import net.onelitefeather.blackhole.markdown.DiscordRenderer;
import net.onelitefeather.blackhole.markdown.MarkdownParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Case text to what the bot posts into a ticket channel. The source is parsed with the one shared parser and written with
 * the Discord renderer, which escapes every markup and mention form and writes links as {@code text <url>}; the result is
 * split into posts of at most {@value #MAX_MESSAGE} characters. Callers still post with empty allowed mentions.
 */
public final class OutboundText {

    /** Discord's limit for one message. */
    public static final int MAX_MESSAGE = DiscordRenderer.MAX_MESSAGE;

    private static final String ZWSP = "\u200B";

    private OutboundText() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * @param source the case message's Markdown source
     * @return the Discord posts, in order; empty when the source holds nothing to show
     */
    public static @NotNull List<String> chunks(@Nullable String source) {
        return DiscordRenderer.chunks(new MarkdownParser().parse(source), MAX_MESSAGE);
    }

    /** @return text that shows as written inside a line of a post: no mention, no markup */
    public static @NotNull String literal(@Nullable String text) {
        StringBuilder out = new StringBuilder();
        for (char c : neutralise(text).toCharArray()) {
            if ("\\*_~`|>[]()<#-".indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /** @return an at sign followed by a zero-width space everywhere, so literal text can never ping */
    public static @NotNull String neutralise(@Nullable String literal) {
        return literal == null ? "" : literal.replace("@", "@" + ZWSP);
    }

    /**
     * The lines of an excerpt as code blocks of at most {@value #MAX_MESSAGE} characters each, split between lines (a line that is
     * longer is cut). The fence is longer than any backtick run inside.
     */
    public static @NotNull List<String> codeBlocks(@NotNull String lines) {
        String text = neutralise(lines);
        int run = 0;
        int longest = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(Math.max(3, longest + 1));
        int capacity = MAX_MESSAGE - 2 * (fence.length() + 1);
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String rest = line;
            while (rest.length() > capacity) {
                flush(blocks, current, fence);
                int cut = capacity;
                if (Character.isHighSurrogate(rest.charAt(cut - 1))) {
                    cut--;
                }
                current.append(rest, 0, cut);
                flush(blocks, current, fence);
                rest = rest.substring(cut);
            }
            if (current.length() > 0 && current.length() + 1 + rest.length() > capacity) {
                flush(blocks, current, fence);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(rest);
        }
        flush(blocks, current, fence);
        return blocks;
    }

    private static void flush(List<String> blocks, StringBuilder current, String fence) {
        if (current.length() > 0) {
            blocks.add(fence + "\n" + current + "\n" + fence);
            current.setLength(0);
        }
    }
}
