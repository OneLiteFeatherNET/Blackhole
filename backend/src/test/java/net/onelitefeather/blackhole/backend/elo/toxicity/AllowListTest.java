package net.onelitefeather.blackhole.backend.elo.toxicity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AllowListTest {

    private final ChatNormaliser normaliser = new ChatNormaliser(false);
    private final TermTagger tagger = new TermTagger(normaliser, Map.of(ToxicityCategory.THREAT, List.of("kill")));
    private final String message = "kill the dragon";

    @Test
    void anAllowListedGamePhraseSuppressesTheTagMatch() {
        AllowList allowList = AllowList.of(List.of("kill the dragon"));

        assertEquals(Set.of(), tagger.tag(message, allowList), "a game phrase on the allow-list must not be tagged");
    }

    @Test
    void theSameMessageIsTaggedWhenNothingIsAllowListed() {
        assertEquals(Set.of(ToxicityCategory.THREAT), tagger.tag(message, AllowList.empty()), "the control case must match");
    }

    @Test
    void theAllowListDoesNotChangeTheModelView() {
        assertEquals(message, normaliser.modelView(message), "the allow-list applies to the tagger only, never to the model text");
    }
}
