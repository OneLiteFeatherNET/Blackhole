package net.onelitefeather.blackhole.discord.text;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reverse mapping of {@code DiscordRenderer}: Discord text to the chat Markdown source the case stores. The marks the
 * subset shares (bold, italic, strike, code, quotes, lists, links) are kept as written; what the subset lacks loses its
 * markers and keeps its text (underline, spoiler, headings), and mentions and custom emoji become names so no Discord id
 * enters the case. Code spans and fences are left alone, except that an id inside them is replaced like anywhere else.
 */
public final class DiscordMarkdown {

    private static final Pattern USER = Pattern.compile("<@!?\\d+>");
    private static final Pattern ROLE = Pattern.compile("<@&\\d+>");
    private static final Pattern CHANNEL = Pattern.compile("<#\\d+>");
    private static final Pattern EMOJI = Pattern.compile("<a?:([A-Za-z0-9_]{1,64}):\\d+>");
    private static final Pattern BARE_LINK = Pattern.compile("<(https?://[^\\s<>]+)>");
    private static final Pattern UNDERLINE = Pattern.compile("(?<![\\\\_])__(?=[^\\s_])([^\\n]*?[^\\s\\\\])__(?!_)");
    private static final Pattern SPOILER = Pattern.compile("(?<!\\\\)\\|\\|(.+?)(?<!\\\\)\\|\\|", Pattern.DOTALL);
    private static final Pattern HEADING = Pattern.compile("^(?:#{1,3}|-#) +");

    private DiscordMarkdown() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * @param discordText the text of a Discord message
     * @return the case source
     */
    public static @NotNull String toSource(String discordText) {
        if (discordText == null || discordText.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(discordText.length());
        int n = discordText.length();
        int plainStart = 0;
        int i = 0;
        while (i < n) {
            if (discordText.charAt(i) == '\\') {
                i += 2;
                continue;
            }
            if (discordText.charAt(i) != '`') {
                i++;
                continue;
            }
            int run = 1;
            while (i + run < n && discordText.charAt(i + run) == '`') {
                run++;
            }
            int close = closingRun(discordText, i + run, run);
            if (close < 0) {
                i += run;
                continue;
            }
            plain(discordText, plainStart, i, out);
            out.append(ids(discordText.substring(i, close + run)));
            i = close + run;
            plainStart = i;
        }
        plain(discordText, plainStart, n, out);
        return out.toString();
    }

    private static int closingRun(String text, int from, int run) {
        int i = from;
        while (i < text.length()) {
            if (text.charAt(i) != '`') {
                i++;
                continue;
            }
            int length = 1;
            while (i + length < text.length() && text.charAt(i + length) == '`') {
                length++;
            }
            if (length == run) {
                return i;
            }
            i += length;
        }
        return -1;
    }

    private static void plain(String text, int from, int to, StringBuilder out) {
        if (from >= to) {
            return;
        }
        boolean lineStart = from == 0 || text.charAt(from - 1) == '\n';
        String segment = text.substring(from, to);
        StringBuilder lines = new StringBuilder(segment.length());
        String[] parts = segment.split("\n", -1);
        for (int k = 0; k < parts.length; k++) {
            String line = parts[k];
            if (k > 0) {
                lines.append('\n');
            }
            if (k > 0 || lineStart) {
                line = HEADING.matcher(line).replaceFirst("");
            }
            lines.append(line);
        }
        String result = ids(lines.toString());
        result = replaceGroup(UNDERLINE, result);
        result = replaceGroup(SPOILER, result);
        result = BARE_LINK.matcher(result).replaceAll("$1");
        out.append(result);
    }

    private static String replaceGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
    }

    private static String ids(String text) {
        String result = USER.matcher(text).replaceAll("@user");
        result = ROLE.matcher(result).replaceAll("@role");
        result = CHANNEL.matcher(result).replaceAll("#channel");
        return EMOJI.matcher(result).replaceAll(m -> Matcher.quoteReplacement(":" + m.group(1) + ":"));
    }
}
