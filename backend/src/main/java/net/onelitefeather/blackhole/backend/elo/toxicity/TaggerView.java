package net.onelitefeather.blackhole.backend.elo.toxicity;

/**
 * The tagger's input in its two spellings. {@code folded} drops umlauts to their base letter, {@code expanded}
 * spells them out (ä to ae). A term matches on either spelling.
 */
public record TaggerView(String folded, String expanded) {

    /** The folded form with every non-letter removed, so that {@code i.d.i.o.t} reads as {@code idiot}. */
    public String squashed() {
        return squash(folded);
    }

    static String squash(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().filter(Character::isLetter).forEach(out::appendCodePoint);
        return out.toString();
    }
}
