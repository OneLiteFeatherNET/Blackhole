package net.onelitefeather.blackhole.backend.publicbans;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicBanRoutesTest {

    @Test
    void theListPathIsTheListTemplate() {
        assertEquals(PublicBanRoutes.LIST, PublicBanRoutes.template("/public/bans"), "the list route");
        assertEquals(PublicBanRoutes.LIST, PublicBanRoutes.template("/public/bans/"), "a trailing slash is the list route");
    }

    @Test
    void theSummaryPathIsTheSummaryTemplate() {
        assertEquals(PublicBanRoutes.SUMMARY, PublicBanRoutes.template("/public/bans/summary"), "the summary route");
    }

    @Test
    void anyOtherPathIsTheEntryTemplateWhateverItSubmits() {
        assertEquals(PublicBanRoutes.ENTRY, PublicBanRoutes.template("/public/bans/AAAAAAAAAAAAAAAAAAAAAA"), "a hash names the entry template");
        assertEquals(PublicBanRoutes.ENTRY, PublicBanRoutes.template("/public/bans/a/b/c"), "an unrouted path never leaks into instance");
        assertEquals("entry", PublicBanRoutes.label("/public/bans/a/b/c"), "the metric label is bounded");
    }
}
