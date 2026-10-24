package net.onelitefeather.blackhole.markdown;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkRulesTest {

    private final LinkRules rules = LinkRules.defaults();

    private record Case(String target, String href) {
    }

    @TestFactory
    Stream<DynamicTest> acceptsAndNormalises() {
        return Stream.of(
                new Case("https://example.org/a?b=1", "https://example.org/a?b=1"),
                new Case("  https://example.org  ", "https://example.org/"),
                new Case("HTTPS://Example.ORG/Path", "https://example.org/Path"),
                new Case("http://example.org:8080/x#frag", "http://example.org:8080/x#frag"),
                new Case("https://example.org./x", "https://example.org/x"),
                new Case("https://xn--bcher-kva.example/", "https://xn--bcher-kva.example/"),
                new Case("https://127.0.0.1/", "https://127.0.0.1/"),
                new Case("https://[::1]:443/", "https://[::1]:443/")
        ).map(c -> DynamicTest.dynamicTest("accepts " + c.target(), () -> {
            Optional<LinkRules.Target> accepted = this.rules.accept(c.target());
            assertTrue(accepted.isPresent(), "the target " + c.target() + " is a valid http(s) link");
            assertEquals(c.href(), accepted.get().href(), "the href is normalised");
        }));
    }

    @TestFactory
    Stream<DynamicTest> rejects() {
        List<String> targets = List.of(
                "javascript:alert(1)", "JaVaScRiPt:alert(1)", "java\tscript:alert(1)", "java\nscript:alert(1)", "&#106;avascript:alert(1)",
                "%6Aavascript:alert(1)", "data:text/html;base64,AAAA", "vbscript:x", "file:///etc/passwd", "//evil.example", "/relative",
                "relative/path", "mailto:a@b.example", "ftp://example.org/", "https:///path", "https://", "https://user:pass@host.example/",
                "https://trusted.example@evil.example/", "https://example.org:0/", "https://example.org:65536/", "https://example.org:99999999999/",
                "https://exa mple.org/", "https://example.org/a b", "https://example.org/\\evil", "https://example.org/<x>", "https://example.org/\"x",
                "https://example.org/\u0000", "https://example.org/\u0007", "https://exämple.org/", "https://example.org/ä", "https://example.org/%zz",
                "", "   ", "https://" + "a".repeat(2100) + ".example/");
        return targets.stream().map(target -> DynamicTest.dynamicTest("rejects " + target.replaceAll("\\p{Cntrl}", "?"),
                () -> assertTrue(this.rules.accept(target).isEmpty(), "the target must not become a link")));
    }

    @Test
    void acceptsATargetOfExactlyTheMaximumLength() {
        String path = "/" + "a".repeat(LinkRules.MAX_TARGET - "https://example.org/".length());
        assertTrue(this.rules.accept("https://example.org" + path).isPresent(), "2048 characters are allowed");
        assertTrue(this.rules.accept("https://example.org" + path + "a").isEmpty(), "2049 characters are not");
    }

    @Test
    void trustsTheDomainAndItsTrueSubdomains() {
        assertFalse(this.rules.accept("https://onelitefeather.net/x").orElseThrow().external(), "the domain itself is trusted");
        assertFalse(this.rules.accept("https://appeal.onelitefeather.net/").orElseThrow().external(), "a subdomain is trusted");
        assertFalse(this.rules.accept("https://A.B.OneLiteFeather.NET./").orElseThrow().external(), "case and a trailing dot do not matter");
    }

    @Test
    void doesNotTrustLookAlikeHosts() {
        assertTrue(this.rules.accept("https://evilonelitefeather.net/").orElseThrow().external(), "a longer name that ends the same is external");
        assertTrue(this.rules.accept("https://onelitefeather.net.evil.example/").orElseThrow().external(), "the trusted name as a prefix is external");
        assertTrue(this.rules.accept("https://onelitefeather.net@evil.example/").isEmpty(), "user info is refused, not trusted");
        assertTrue(this.rules.accept("https://example.org/onelitefeather.net").orElseThrow().external(), "the trusted name in a path is external");
    }

    @Test
    void anEmptyListMakesEveryLinkExternal() {
        LinkRules none = new LinkRules(List.of());
        assertTrue(none.accept("https://onelitefeather.net/").orElseThrow().external(), "nothing is trusted");
    }

    @Test
    void configuredDomainsAreNormalised() {
        LinkRules custom = new LinkRules(List.of(" Example.ORG. ", "", "  "));
        assertFalse(custom.accept("https://www.example.org/").orElseThrow().external(), "the configured name is trimmed and lower-cased");
        assertTrue(custom.accept("https://onelitefeather.net/").orElseThrow().external(), "the default is replaced, not extended");
    }

    @Test
    void aNullTargetIsRefused() {
        assertTrue(this.rules.accept(null).isEmpty(), "null is not a link");
    }
}
