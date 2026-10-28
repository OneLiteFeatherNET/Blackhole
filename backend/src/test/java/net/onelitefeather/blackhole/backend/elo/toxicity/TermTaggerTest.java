package net.onelitefeather.blackhole.backend.elo.toxicity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TermTaggerTest {

    private final TermTagger tagger = new TermTagger(new ChatNormaliser(false), Map.of(
            ToxicityCategory.INSULT, List.of("idiot", "ass"),
            ToxicityCategory.THREAT, List.of("kill"),
            ToxicityCategory.HATE, List.of("scheisse")));

    @Test
    void aLeetDisguisedTermIsTagged() {
        assertEquals(Set.of(ToxicityCategory.INSULT), tagger.tag("1d10t", AllowList.empty()), "1d10t must match idiot");
    }

    @Test
    void aSpacedOutTermIsTaggedWhenItHasFourOrMoreLetters() {
        assertEquals(Set.of(ToxicityCategory.INSULT), tagger.tag("i.d.i.o.t", AllowList.empty()), "squashed idiot must match");
    }

    @Test
    void aSpacedOutTermShorterThanFourLettersIsNotTagged() {
        assertEquals(Set.of(), tagger.tag("a.s.s", AllowList.empty()), "a squashed term of three letters must not match");
    }

    @Test
    void aPlainTermInsideAnotherWordIsNotTagged() {
        assertEquals(Set.of(), tagger.tag("classic", AllowList.empty()), "a plain match needs word boundaries");
    }

    @Test
    void aGermanTermIsTaggedWhenWrittenWithSharpS() {
        assertEquals(Set.of(ToxicityCategory.HATE), tagger.tag("Scheiße", AllowList.empty()), "sharp s must match ss");
    }
}
