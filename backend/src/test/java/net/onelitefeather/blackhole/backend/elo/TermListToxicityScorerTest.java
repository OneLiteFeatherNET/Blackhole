package net.onelitefeather.blackhole.backend.elo;

import net.onelitefeather.blackhole.backend.elo.toxicity.AllowList;
import net.onelitefeather.blackhole.backend.elo.toxicity.ChatNormaliser;
import net.onelitefeather.blackhole.backend.elo.toxicity.TermTagger;
import net.onelitefeather.blackhole.backend.elo.toxicity.ToxicityCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TermListToxicityScorerTest {

    private static final Map<ToxicityCategory, Double> WEIGHTS = Map.of(
            ToxicityCategory.INSULT, 0.6,
            ToxicityCategory.THREAT, 1.0,
            ToxicityCategory.HATE, 0.9,
            ToxicityCategory.SEXUAL, 0.8,
            ToxicityCategory.SPAM, 0.5);

    private static final Map<ToxicityCategory, List<String>> TERMS = Map.of(
            ToxicityCategory.INSULT, List.of("idiot", "ass", "trottel", "hässlich"),
            ToxicityCategory.THREAT, List.of("kill"),
            ToxicityCategory.SPAM, List.of("buy now"));

    private static TermListToxicityScorer scorer() {
        return scorer(AllowList.empty());
    }

    private static TermListToxicityScorer scorer(AllowList allowList) {
        return new TermListToxicityScorer(new TermTagger(new ChatNormaliser(true), TERMS), allowList, WEIGHTS);
    }

    @Test
    void anObfuscatedInsultWrittenInLeetIsMatched() {
        assertEquals(0.6, scorer().score("1d10t").score(), "1d10t must match idiot at the INSULT weight");
    }

    @Test
    void aFormattingCodeAroundAnInsultIsIgnored() {
        assertEquals(0.6, scorer().score("§aidiot").score(), "a colour code must not hide the term");
    }

    @Test
    void aSpacedOutInsultIsMatched() {
        assertEquals(0.6, scorer().score("i.d.i.o.t").score(), "punctuation between letters must not hide the term");
    }

    @Test
    void aTermInsideAnotherWordIsNotMatched() {
        assertEquals(0.0, scorer().score("class").score(), "ass must not match inside class");
    }

    @Test
    void aShortTermIsNotMatchedThroughSeparators() {
        assertEquals(0.0, scorer().score("a.s.s").score(), "a term of three letters must not match through separators");
    }

    @Test
    void aGermanInsultIsMatchedInUpperCase() {
        assertEquals(0.6, scorer().score("TROTTEL").score(), "German matching must ignore case");
    }

    @Test
    void anUmlautTermMatchesBothSpellings() {
        assertEquals(0.6, scorer().score("hässlich").score(), "the umlaut spelling must match");
        assertEquals(0.6, scorer().score("haesslich").score(), "the ae spelling must match");
    }

    @Test
    void aThreatAtWeightOneReachesTheDefaultThreshold() {
        assertEquals(1.0, scorer().score("I will kill you").score(), "a THREAT match scores its weight 1.0");
    }

    @Test
    void aSpamMatchScoresItsWeight() {
        assertEquals(0.5, scorer().score("buy now!").score(), "a SPAM match scores its weight 0.5");
    }

    @Test
    void aMessageMatchingTwoCategoriesScoresTheHigherWeight() {
        TermListToxicityScorer scorer = scorer();

        ToxicityVerdict verdict = scorer.score("you idiot, I will kill you");

        assertEquals(1.0, verdict.score(), "the highest matched weight wins");
        assertEquals(Optional.of(ToxicityCategory.THREAT), verdict.category(), "the category of the highest weight is reported");
    }

    @Test
    void anAllowListedPhraseIsNotMatched() {
        assertEquals(0.0, scorer(AllowList.of(List.of("kill the dragon"))).score("kill the dragon").score(),
                "an allow-listed game phrase must not tag THREAT");
    }

    @Test
    void theSamePhraseStillMatchesWhenAnotherTermIsPresent() {
        assertEquals(1.0, scorer(AllowList.of(List.of("kill the dragon"))).score("kill the dragon, I will kill you").score(),
                "a second, non-allow-listed match still tags");
    }

    @Test
    void aBlankMessageScoresZero() {
        assertEquals(0.0, scorer().score("   ").score(), "a blank message must score 0");
    }

    @Test
    void aWeightOutsideZeroToOneIsRejectedAtConstruction() {
        TermTagger tagger = new TermTagger(new ChatNormaliser(true), TERMS);

        assertThrows(IllegalArgumentException.class,
                () -> new TermListToxicityScorer(tagger, AllowList.empty(), Map.of(ToxicityCategory.INSULT, 1.5)),
                "a weight above 1 must be rejected");
    }

    @Test
    void aTermListForAnUnknownCategoryIsRejectedAtConfigurationTime() {
        assertThrows(IllegalArgumentException.class,
                () -> TermListToxicityScorer.fromWordLists(Map.of("slurs", "x"), "", Map.of()),
                "an unknown category key must fail at startup, not be ignored");
    }
}
