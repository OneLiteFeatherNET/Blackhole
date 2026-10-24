package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.client.model.ClientRuleKind;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.client.model.ServerTargetKind;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.cloudnet.FakeCloudNetView;
import net.onelitefeather.blackhole.velocity.cloudnet.ServerResolver;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static net.onelitefeather.blackhole.velocity.clientrule.Rules.rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A rule covers a server exactly when the section targeting would. */
class ClientRuleCoverageTest {

    private final List<String> warnings = new ArrayList<>();
    private final FakeCloudNetView cloudNet = new FakeCloudNetView(true).service("BedWars-1", "BedWars", "Game").service("Lobby-1", "Lobby");

    private ClientRuleCoverage coverage(SectionInfo... sections) {
        return new ClientRuleCoverage(TestData.registry(sections), new ServerResolver(cloudNet, TestData.registry(sections), warnings::add));
    }

    private static ClientRule inSections(String... keys) {
        return rule("r", ClientRuleKind.BRAND).patterns("x*").sections(keys).build();
    }

    @Test
    void aRuleNamingASectionCoversTheServersTheSectionCovers() {
        ClientRuleCoverage coverage = coverage(TestData.BEDWARS);

        assertTrue(coverage.covers(inSections("bedwars"), "bedwars-3"));
        assertFalse(coverage.covers(inSections("bedwars"), "lobby-1"));
    }

    @Test
    void aSectionEditMovesTheRuleBecauseTheSectionIsReadLive() {
        SectionInfo moved = new SectionInfo("bedwars", "Bedwars", EnforcementKind.SERVER, List.of("lobby-*"));

        assertTrue(coverage(moved).covers(inSections("bedwars"), "lobby-1"));
        assertFalse(coverage(moved).covers(inSections("bedwars"), "bedwars-1"));
    }

    @Test
    void aMissingOrNonServerSectionCoversNothing() {
        ClientRuleCoverage coverage = coverage(TestData.BEDWARS, TestData.NETWORK);

        assertFalse(coverage.covers(inSections("deleted"), "bedwars-1"), "fail open for a deleted section");
        assertFalse(coverage.covers(inSections("network"), "bedwars-1"), "a BAN section has no servers");
    }

    @Test
    void aRuleWithOwnPatternTargetsCoversMatchingServers() {
        ClientRule rule = rule("r", ClientRuleKind.BRAND).patterns("x*").servers(ServerTargetKind.PATTERN, "lobby-*").build();

        assertTrue(coverage().covers(rule, "lobby-7"));
        assertFalse(coverage().covers(rule, "bedwars-7"));
    }

    @Test
    void aRuleWithCloudNetTargetsUsesWhatCloudNetKnows() {
        ClientRule byTask = rule("r", ClientRuleKind.BRAND).patterns("x*").servers(ServerTargetKind.CLOUDNET_TASK, "BedWars").build();
        ClientRule byGroup = rule("r", ClientRuleKind.BRAND).patterns("x*").servers(ServerTargetKind.CLOUDNET_GROUP, "game").build();

        assertTrue(coverage().covers(byTask, "BedWars-1"));
        assertFalse(coverage().covers(byTask, "Lobby-1"));
        assertTrue(coverage().covers(byGroup, "BedWars-1"));
    }

    @Test
    void aServerCloudNetDoesNotKnowIsNotCoveredByACloudNetTarget() {
        ClientRule byTask = rule("r", ClientRuleKind.BRAND).patterns("x*").servers(ServerTargetKind.CLOUDNET_TASK, "BedWars").build();

        assertFalse(coverage().covers(byTask, "Unknown-1"));
    }

    @Test
    void aRuleCoversAServerWhenEitherItsSectionOrItsOwnTargetsDo() {
        ClientRule both = new ClientRule("r", "R", ClientRuleKind.BRAND, List.of("x*"), null, null, false,
                net.onelitefeather.blackhole.client.model.ClientRuleScope.SERVERS, List.of("bedwars"), ServerTargetKind.PATTERN,
                List.of("lobby-*"), net.onelitefeather.blackhole.client.model.ClientRuleAction.NOTIFY, null, false);

        ClientRuleCoverage coverage = coverage(TestData.BEDWARS);

        assertTrue(coverage.covers(both, "bedwars-1"));
        assertTrue(coverage.covers(both, "lobby-1"));
        assertEquals(false, coverage.covers(both, "survival-1"));
    }
}
