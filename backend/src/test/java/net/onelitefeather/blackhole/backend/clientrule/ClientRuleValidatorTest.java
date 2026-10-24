package net.onelitefeather.blackhole.backend.clientrule;

import net.onelitefeather.blackhole.backend.section.ServerTargetKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRuleValidatorTest {

    /** A valid CHANNEL rule; tests change one thing at a time. */
    private static ClientRuleSpec.Builder channel() {
        return ClientRuleSpec.builder("meteor", "Meteor client", ClientRuleKind.CHANNEL).patterns("meteor-client:*");
    }

    private static ClientRuleSpec.Builder protocol() {
        return ClientRuleSpec.builder("old", "Old", ClientRuleKind.PROTOCOL).range(1, 100);
    }

    private static List<String> validate(ClientRuleSpec.Builder rule) {
        return ClientRuleValidator.validate(rule.build());
    }

    private static boolean mentions(List<String> errors, String field) {
        return errors.stream().anyMatch(error -> error.contains(field));
    }

    @Test
    void aMinimalChannelRuleIsValidAndDefaultsToNotifyAndGlobal() {
        ClientRuleSpec spec = channel().build();

        assertEquals(List.of(), ClientRuleValidator.validate(spec));
        assertEquals(ClientRuleAction.NOTIFY, spec.action(), "NOTIFY is the default");
        assertEquals(ClientRuleScope.GLOBAL, spec.scope(), "GLOBAL is the default");
    }

    @Test
    void patternsAreStoredInLowerCase() {
        assertEquals(List.of("meteor-client:*"),
                ClientRuleSpec.builder("a", "A", ClientRuleKind.CHANNEL).patterns("Meteor-Client:*").build().patterns());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Upper", "under_score", "has space", "dots.in.key", "catalogue", "hits", "hit"})
    void keysOutsideTheFormatOrReservedAreRejected(String key) {
        assertTrue(mentions(validate(channel().key(key)), "key"), "key '" + key + "' must be rejected");
    }

    @Test
    void aKeyOfSixtyFiveCharactersIsRejectedAndSixtyFourIsAccepted() {
        assertTrue(mentions(validate(channel().key("a".repeat(65))), "key"));
        assertEquals(List.of(), validate(channel().key("a".repeat(64))));
    }

    @Test
    void aBlankOrOverlongDisplayNameIsRejected() {
        assertTrue(mentions(validate(channel().displayName(" ")), "displayName"));
        assertTrue(mentions(validate(channel().displayName("x".repeat(129))), "displayName"));
    }

    @Test
    void patternKindsNeedBetweenOneAndThirtyTwoPatterns() {
        for (ClientRuleKind kind : List.of(ClientRuleKind.BRAND, ClientRuleKind.MOD, ClientRuleKind.CHANNEL, ClientRuleKind.COMMAND)) {
            assertTrue(mentions(validate(ClientRuleSpec.builder("a", "A", kind)), "patterns"), kind + " without patterns");
        }
        String[] many = IntStream.rangeClosed(1, 33).mapToObj(i -> "p" + i).toArray(String[]::new);
        assertTrue(mentions(validate(channel().patterns(many)), "patterns"), "33 patterns");
        assertEquals(List.of(), validate(channel().patterns(Arrays.copyOf(many, 32))), "32 patterns");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a b", "a,b", "a\\d+", "(a|b)", "a[0-9]", "ä", "a;b", "a\nb"})
    void patternsWithCharactersOutsideTheAllowedSetAreRejected(String pattern) {
        assertTrue(mentions(validate(channel().patterns(pattern)), "patterns"), "pattern '" + pattern + "'");
    }

    @Test
    void aPatternOfOneHundredTwentyNineCharactersIsRejected() {
        assertTrue(mentions(validate(channel().patterns("a".repeat(129))), "patterns"));
        assertEquals(List.of(), validate(channel().patterns("a".repeat(128))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "**", "?", "*?*"})
    void aPatternThatCouldMatchEverythingIsRejected(String pattern) {
        assertTrue(mentions(validate(channel().patterns(pattern)), "patterns"), "pattern '" + pattern + "' has no literal character");
    }

    @Test
    void aProtocolRuleNeedsAnOrderedRange() {
        assertEquals(List.of(), validate(ClientRuleSpec.builder("old", "Old", ClientRuleKind.PROTOCOL).range(765, 774)));
        assertEquals(List.of(), validate(ClientRuleSpec.builder("old", "Old", ClientRuleKind.PROTOCOL).range(765, 765)), "a single version");
        assertTrue(mentions(validate(ClientRuleSpec.builder("old", "Old", ClientRuleKind.PROTOCOL).range(774, 765)), "protocol"), "min above max");
        assertTrue(mentions(validate(ClientRuleSpec.builder("old", "Old", ClientRuleKind.PROTOCOL)), "protocol"), "no range");
        assertTrue(mentions(validate(ClientRuleSpec.builder("old", "Old", ClientRuleKind.PROTOCOL).range(-1, 5)), "protocol"), "negative");
    }

    @Test
    void aProtocolRuleMustNotCarryPatterns() {
        assertTrue(mentions(validate(protocol().patterns("x")), "patterns"));
    }

    @Test
    void aRangeOnAPatternKindIsRejected() {
        assertTrue(mentions(validate(channel().range(1, 2)), "protocol"));
        assertTrue(mentions(validate(ClientRuleSpec.builder("a", "A", ClientRuleKind.BRAND).patterns("vanilla").range(1, 2)), "protocol"));
    }

    @Test
    void negateIsAllowedForBrandAndProtocolOnly() {
        assertEquals(List.of(), validate(ClientRuleSpec.builder("a", "A", ClientRuleKind.BRAND).patterns("vanilla").negate()));
        assertEquals(List.of(), validate(ClientRuleSpec.builder("a", "A", ClientRuleKind.PROTOCOL).range(765, 774).negate()));
        for (ClientRuleKind kind : List.of(ClientRuleKind.MOD, ClientRuleKind.CHANNEL, ClientRuleKind.COMMAND)) {
            assertTrue(mentions(validate(ClientRuleSpec.builder("a", "A", kind).patterns("x").negate()), "negate"), kind + " cannot be negated");
        }
    }

    @Test
    void hidingIsForCommandRulesOnly() {
        assertEquals(List.of(), validate(ClientRuleSpec.builder("a", "A", ClientRuleKind.COMMAND).patterns("op").hideCommand()));
        assertTrue(mentions(validate(channel().hideCommand()), "hideCommand"));
    }

    @Test
    void aGlobalRuleMustNotListSectionsOrServers() {
        assertTrue(mentions(validate(channel().sections("minigames")), "scope"));
        assertTrue(mentions(validate(channel().servers("lobby-*")), "scope"));
    }

    @Test
    void aServerRuleNeedsSectionsOrServers() {
        assertTrue(mentions(validate(channel().serversScope()), "scope"), "SERVERS without any target");
        assertEquals(List.of(), validate(channel().serversScope().sections("minigames")));
        assertEquals(List.of(), validate(channel().serversScope().servers("lobby-*")));
    }

    @Test
    void serverTargetsFollowTheSectionRulesForTheirKind() {
        assertTrue(mentions(validate(channel().serversScope().servers("bad name")), "servers"), "glob charset");
        assertTrue(mentions(validate(channel().serversScope().targetKind(ServerTargetKind.CLOUDNET_TASK).servers("Bed*")), "servers"),
                "CloudNet names are exact");
        assertEquals(List.of(), validate(channel().serversScope().targetKind(ServerTargetKind.CLOUDNET_TASK).servers("BedWars")));
    }

    @Test
    void aMalformedSectionKeyIsRejected() {
        assertTrue(mentions(validate(channel().serversScope().sections("Not Kebab")), "sectionKeys"));
    }

    @Test
    void denyMayCarryAMessageUpToTwoHundredCharactersWithoutControlCharacters() {
        assertEquals(List.of(), validate(channel().action(ClientRuleAction.DENY).message("This client is not allowed here.")));
        assertEquals(List.of(), validate(channel().action(ClientRuleAction.DENY)), "no message uses the default");
        assertTrue(mentions(validate(channel().action(ClientRuleAction.DENY).message("x".repeat(201))), "denyMessage"));
        assertEquals(List.of(), validate(channel().action(ClientRuleAction.DENY).message("x".repeat(200))));
        assertTrue(mentions(validate(channel().action(ClientRuleAction.DENY).message("line\nbreak")), "denyMessage"));
        assertTrue(mentions(validate(channel().action(ClientRuleAction.DENY).message("§cred")), "denyMessage"), "formatting code");
    }

    @Test
    void aMessageOnANonDenyRuleIsRejected() {
        assertTrue(mentions(validate(channel().message("hello")), "denyMessage"));
    }

    @Test
    void templateActionNeedsATemplateKeyAndOtherActionsMustNotHaveOne() {
        assertTrue(mentions(validate(protocol().action(ClientRuleAction.TEMPLATE)), "templateKey"));
        assertEquals(List.of(), validate(protocol().action(ClientRuleAction.TEMPLATE).template("old-client")));
        assertTrue(mentions(validate(channel().template("old-client")), "templateKey"));
    }

    @Test
    void punishingOnASpoofableSignalNeedsTheAcknowledgement() {
        for (ClientRuleKind kind : List.of(ClientRuleKind.BRAND, ClientRuleKind.MOD, ClientRuleKind.CHANNEL)) {
            ClientRuleSpec.Builder rule = ClientRuleSpec.builder("a", "A", kind).patterns("x").action(ClientRuleAction.TEMPLATE).template("t");
            assertTrue(mentions(validate(rule), "acknowledgedSpoofable"), kind + " without the acknowledgement");
            assertEquals(List.of(), validate(rule.acknowledged()), kind + " acknowledged");
        }
    }

    @Test
    void protocolAndCommandRulesNeedNoAcknowledgement() {
        assertEquals(List.of(), validate(protocol().action(ClientRuleAction.TEMPLATE).template("t")));
        assertEquals(List.of(), validate(ClientRuleSpec.builder("a", "A", ClientRuleKind.COMMAND).patterns("op").action(ClientRuleAction.TEMPLATE).template("t")));
    }

    @Test
    void theAcknowledgementIsNotKeptWhereItDoesNotApply() {
        assertFalse(channel().acknowledged().build().acknowledgedSpoofable(), "NOTIFY does not keep a stale flag");
        assertFalse(protocol().action(ClientRuleAction.TEMPLATE).template("t").acknowledged().build().acknowledgedSpoofable(), "a PROTOCOL rule does not keep it");
        assertTrue(channel().action(ClientRuleAction.TEMPLATE).template("t").acknowledged().build().acknowledgedSpoofable(), "kept where it applies");
    }

    @Test
    void requiresAcknowledgementIsExactlyTemplateOnBrandModOrChannel() {
        assertTrue(ClientRuleValidator.requiresAcknowledgement(ClientRuleKind.BRAND, ClientRuleAction.TEMPLATE));
        assertFalse(ClientRuleValidator.requiresAcknowledgement(ClientRuleKind.BRAND, ClientRuleAction.DENY));
        assertFalse(ClientRuleValidator.requiresAcknowledgement(ClientRuleKind.COMMAND, ClientRuleAction.TEMPLATE));
        assertFalse(ClientRuleValidator.requiresAcknowledgement(ClientRuleKind.PROTOCOL, ClientRuleAction.TEMPLATE));
    }
}
