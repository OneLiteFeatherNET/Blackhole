package net.onelitefeather.blackhole.backend.elo.toxicity;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns a raw chat message into the two texts the scorer needs.
 *
 * <p>The model view is light: Minecraft colour codes and invisible characters are removed, then NFKC is
 * applied. The model is trained on natural text, so case and spelling stay as typed.
 *
 * <p>The tagger view is full normalisation for term matching: confusable letters, leet spelling, German
 * folding, repeated letters. Both views are pure functions of the input. The instance holds only an
 * immutable option.
 */
public final class ChatNormaliser {

    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)§[0-9a-fk-or]");
    private static final Pattern AMPERSAND_CODE = Pattern.compile("(?i)&[0-9a-fk-or]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Map<Character, Character> CONFUSABLES = Map.of(
            'а', 'a', 'е', 'e', 'о', 'o', 'р', 'p', 'с', 'c', 'х', 'x', 'ο', 'o');

    private static final Map<Character, Character> LEET = Map.of(
            '0', 'o', '1', 'i', '3', 'e', '4', 'a', '5', 's', '7', 't', '@', 'a', '$', 's', '!', 'i');

    private final boolean ampersandCodes;

    /**
     * @param ampersandCodes whether {@code &} colour codes are removed as well as {@code §} codes
     */
    public ChatNormaliser(boolean ampersandCodes) {
        this.ampersandCodes = ampersandCodes;
    }

    /** The model view: colour codes and invisible characters removed, NFKC applied, no other change. */
    public String modelView(String raw) {
        String text = LEGACY_CODE.matcher(raw).replaceAll("");
        if (ampersandCodes) {
            text = AMPERSAND_CODE.matcher(text).replaceAll("");
        }
        text = removeInvisible(text);
        text = Normalizer.normalize(text, Normalizer.Form.NFKC);
        return WHITESPACE.matcher(text).replaceAll(" ").strip();
    }

    /** The tagger view: the model view, then the full normalisation of decision 4, in two spellings. */
    public TaggerView taggerView(String raw) {
        String lowered = modelView(raw).toLowerCase(Locale.ROOT);
        String letters = decodeLeet(mapConfusables(lowered)).replace("ß", "ss");
        return new TaggerView(
                collapseRepeats(fold(letters, false)),
                collapseRepeats(fold(letters, true)));
    }

    private static String removeInvisible(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (cp == '\t' || cp == '\n' || cp == '\r' || cp == '\u000B' || cp == '\f') {
                out.append(' ');
                return;
            }
            int type = Character.getType(cp);
            if (type != Character.FORMAT && type != Character.CONTROL) {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }

    private static String mapConfusables(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            out.append(CONFUSABLES.getOrDefault(c, c));
        }
        return out.toString();
    }

    /** Leet characters are decoded only where a letter touches them, so "100" stays a number. */
    private static String decodeLeet(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Character replacement = LEET.get(c);
            boolean nextToLetter = (i > 0 && Character.isLetter(text.charAt(i - 1)))
                    || (i + 1 < text.length() && Character.isLetter(text.charAt(i + 1)));
            out.append(replacement != null && nextToLetter ? replacement : c);
        }
        return out.toString();
    }

    private static String fold(String text, boolean expandUmlauts) {
        if (expandUmlauts) {
            return text.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue");
        }
        return text.replace("ä", "a").replace("ö", "o").replace("ü", "u");
    }

    /** Runs of the same letter longer than two collapse to two. Other characters are untouched. */
    private static String collapseRepeats(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            run = i > 0 && c == text.charAt(i - 1) ? run + 1 : 1;
            if (!Character.isLetter(c) || run <= 2) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
