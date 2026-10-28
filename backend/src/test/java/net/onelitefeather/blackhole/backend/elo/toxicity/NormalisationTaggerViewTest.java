package net.onelitefeather.blackhole.backend.elo.toxicity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NormalisationTaggerViewTest {

    private final ChatNormaliser normaliser = new ChatNormaliser(false);

    @Test
    void cyrillicConfusablesAreMappedToLatin() {
        // The first letter is Cyrillic a (U+0430).
        assertEquals("attack", normaliser.taggerView("аttack").folded(), "look-alike letters must map to Latin");
    }

    @Test
    void leetSpellingIsDecodedInTheTaggerView() {
        assertEquals("idiot", normaliser.taggerView("1d10t").folded(), "1d10t must read as idiot for the tagger");
    }

    @Test
    void germanSharpSIsFoldedToSs() {
        assertEquals("scheisse", normaliser.taggerView("Scheiße").folded(), "sharp s must fold to ss");
    }

    @Test
    void umlautsAreFoldedToTheirBaseLetterAndToTheExpandedSpelling() {
        var view = normaliser.taggerView("Müll");
        assertEquals("mull", view.folded(), "the folded form drops the umlaut");
        assertEquals("muell", view.expanded(), "the expanded form spells the umlaut out");
    }

    @Test
    void repeatedLettersCollapseToAtMostTwo() {
        assertEquals("soo", normaliser.taggerView("sooooo").folded(), "runs of one letter collapse to two");
    }

    @Test
    void spacingInsideAWordIsRemovedOnlyInTheSquashedForm() {
        var view = normaliser.taggerView("i.d.i.o.t");
        assertEquals("idiot", view.squashed(), "the squashed form drops every non-letter");
        assertEquals("i.d.i.o.t", view.folded(), "the plain form keeps the separators");
    }
}
