package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.client.model.ClientRuleKind;
import net.onelitefeather.blackhole.client.model.ServerTargetKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static net.onelitefeather.blackhole.velocity.clientrule.Rules.rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineTest {

    private static final Set<ClientRuleKind> ALL = Set.of(ClientRuleKind.BRAND, ClientRuleKind.MOD, ClientRuleKind.CHANNEL, ClientRuleKind.PROTOCOL);

    /** Covers exactly the servers whose name starts with "mini". */
    private final RuleEngine engine = new RuleEngine((rule, server) -> server.startsWith("mini"));

    private static ClientTraits traits(int protocol, String brand, List<String> channels, List<String> mods) {
        return ClientTraits.unknown().withProtocol(protocol).withBrand(brand).withChannels(channels).withMods(mods);
    }

    private List<String> hitKeys(ClientTraits traits, String server, ClientRule... rules) {
        return engine.hits(traits, server, ALL, List.of(rules)).stream().map(ClientRule::key).toList();
    }

    // ---- brand -------------------------------------------------------------------------

    @Test
    void aBrandRuleHitsWhenTheBrandMatchesAGlobIgnoringCase() {
        ClientRule rule = rule("lunar", ClientRuleKind.BRAND).patterns("lunar*").build();

        assertEquals(List.of("lunar"), hitKeys(traits(767, "LunarClient:abc", List.of(), List.of()), null, rule));
        assertEquals(List.of(), hitKeys(traits(767, "vanilla", List.of(), List.of()), null, rule));
    }

    @Test
    void anUnknownBrandNeverHitsNotEvenANegatedRule() {
        ClientRule plain = rule("plain", ClientRuleKind.BRAND).patterns("*").build();
        ClientRule negated = rule("only-vanilla", ClientRuleKind.BRAND).patterns("vanilla").negate().build();

        assertEquals(List.of(), hitKeys(traits(767, null, List.of(), List.of()), null, plain, negated),
                "a client that never sends a brand is not hit: an allowlist is bypassable by suppressing it");
    }

    @Test
    void aNegatedBrandRuleHitsEveryKnownBrandOutsideItsMatch() {
        ClientRule negated = rule("only-vanilla", ClientRuleKind.BRAND).patterns("vanilla", "fabric").negate().build();

        assertEquals(List.of(), hitKeys(traits(767, "vanilla", List.of(), List.of()), null, negated));
        assertEquals(List.of(), hitKeys(traits(767, "Fabric", List.of(), List.of()), null, negated));
        assertEquals(List.of("only-vanilla"), hitKeys(traits(767, "forge", List.of(), List.of()), null, negated));
    }

    // ---- channel and mod ---------------------------------------------------------------

    @Test
    void aChannelRuleHitsWhenAnyRegisteredChannelMatches() {
        ClientRule rule = rule("meteor", ClientRuleKind.CHANNEL).patterns("meteor-client:*").build();

        assertEquals(List.of("meteor"), hitKeys(traits(767, "fabric", List.of("fabric:registry/sync", "meteor-client:main"), List.of()), null, rule));
        assertEquals(List.of(), hitKeys(traits(767, "fabric", List.of("fabric:registry/sync"), List.of()), null, rule));
        assertEquals(List.of(), hitKeys(traits(767, "fabric", List.of(), List.of()), null, rule), "no channel yet");
    }

    @Test
    void aModRuleMatchesTheModIdOrIdAndVersion() {
        ClientRule byId = rule("xray", ClientRuleKind.MOD).patterns("xray").build();
        ClientRule byVersion = rule("old-jei", ClientRuleKind.MOD).patterns("jei@4.*").build();
        ClientTraits modded = traits(340, "fml,forge", List.of(), List.of("xray@1.0", "jei@4.16.1"));

        assertEquals(List.of("xray", "old-jei"), hitKeys(modded, null, byId, byVersion));
        assertEquals(List.of(), hitKeys(traits(340, "forge", List.of(), List.of("jei@5.0")), null, byId, byVersion));
    }

    // ---- protocol ----------------------------------------------------------------------

    @Test
    void aProtocolRuleHitsInsideItsRangeEdgesIncluded() {
        ClientRule rule = rule("old", ClientRuleKind.PROTOCOL).range(47, 340).build();

        assertEquals(List.of("old"), hitKeys(traits(47, null, List.of(), List.of()), null, rule));
        assertEquals(List.of("old"), hitKeys(traits(340, null, List.of(), List.of()), null, rule));
        assertEquals(List.of(), hitKeys(traits(341, null, List.of(), List.of()), null, rule));
        assertEquals(List.of(), hitKeys(traits(46, null, List.of(), List.of()), null, rule));
    }

    @Test
    void aNegatedProtocolRuleIsAnAllowlist() {
        ClientRule rule = rule("only-new", ClientRuleKind.PROTOCOL).range(765, 774).negate().build();

        assertEquals(List.of("only-new"), hitKeys(traits(47, null, List.of(), List.of()), null, rule));
        assertEquals(List.of(), hitKeys(traits(767, null, List.of(), List.of()), null, rule));
    }

    @Test
    void anUnknownProtocolNeverHits() {
        ClientRule rule = rule("only-new", ClientRuleKind.PROTOCOL).range(765, 774).negate().build();

        assertEquals(List.of(), hitKeys(ClientTraits.unknown(), null, rule));
    }

    // ---- scope -------------------------------------------------------------------------

    @Test
    void aGlobalRuleAppliesEverywhereEvenWithoutAServer() {
        ClientRule rule = rule("lunar", ClientRuleKind.BRAND).patterns("lunar*").build();
        ClientTraits lunar = traits(767, "lunarclient", List.of(), List.of());

        assertEquals(List.of("lunar"), hitKeys(lunar, null, rule));
        assertEquals(List.of("lunar"), hitKeys(lunar, "lobby", rule));
    }

    @Test
    void aScopedRuleAppliesOnlyOnACoveredServer() {
        ClientRule rule = rule("lunar", ClientRuleKind.BRAND).patterns("lunar*").sections("minigames").build();
        ClientTraits lunar = traits(767, "lunarclient", List.of(), List.of());

        assertEquals(List.of("lunar"), hitKeys(lunar, "minigame-1", rule));
        assertEquals(List.of(), hitKeys(lunar, "lobby", rule), "not covered");
        assertEquals(List.of(), hitKeys(lunar, null, rule), "no server known: fail open");
    }

    @Test
    void theRuleOwnTargetsAreHandedToTheCoverage() {
        ClientRule rule = rule("lunar", ClientRuleKind.BRAND).patterns("lunar*").servers(ServerTargetKind.CLOUDNET_TASK, "BedWars").build();
        RuleEngine byTask = new RuleEngine((candidate, server) -> candidate.targetKind() == ServerTargetKind.CLOUDNET_TASK
                && candidate.servers().contains("BedWars") && server.equals("BedWars-1"));
        ClientTraits lunar = traits(767, "lunarclient", List.of(), List.of());

        assertEquals(1, byTask.hits(lunar, "BedWars-1", ALL, List.of(rule)).size());
        assertEquals(0, byTask.hits(lunar, "SkyWars-1", ALL, List.of(rule)).size());
    }

    // ---- kinds and several rules -------------------------------------------------------

    @Test
    void onlyTheAskedKindsAreChecked() {
        ClientRule brand = rule("brand", ClientRuleKind.BRAND).patterns("*client*").build();
        ClientRule protocol = rule("old", ClientRuleKind.PROTOCOL).range(0, 1000).build();
        ClientTraits all = traits(767, "lunarclient", List.of(), List.of());

        assertEquals(List.of("old"), engine.hits(all, null, Set.of(ClientRuleKind.PROTOCOL), List.of(brand, protocol)).stream().map(ClientRule::key).toList());
    }

    @Test
    void commandRulesAreNeverHitByTraits() {
        ClientRule command = rule("op", ClientRuleKind.COMMAND).patterns("op").build();

        assertTrue(engine.hits(traits(767, "op", List.of("op"), List.of("op")), null, Set.of(ClientRuleKind.COMMAND, ClientRuleKind.BRAND), List.of(command)).isEmpty());
    }

    @Test
    void severalRulesHitInTheOrderOfTheList() {
        ClientRule first = rule("b", ClientRuleKind.BRAND).patterns("*").build();
        ClientRule second = rule("a", ClientRuleKind.CHANNEL).patterns("x:*").build();

        assertEquals(List.of("b", "a"), hitKeys(traits(767, "fabric", List.of("x:y"), List.of()), null, first, second));
    }
}
