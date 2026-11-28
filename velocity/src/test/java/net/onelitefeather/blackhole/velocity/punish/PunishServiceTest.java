package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.client.model.PunishEntryDTO;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.velocity.config.ChatCaptureConfig;
import net.onelitefeather.blackhole.velocity.evidence.ChatBuffer;
import net.onelitefeather.blackhole.velocity.evidence.ChatEvidence;
import net.onelitefeather.blackhole.velocity.evidence.ChatSnapshotBuilder;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static net.onelitefeather.blackhole.velocity.TestData.OWNER;
import static net.onelitefeather.blackhole.velocity.TestData.standardRegistry;
import static net.onelitefeather.blackhole.velocity.punish.StubGateway.template;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishServiceTest {

    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID PLAYER = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    private final StubGateway backend = new StubGateway();
    private final PunishService service = new PunishService(backend, standardRegistry(), ChatEvidence.create(
            new ChatSnapshotBuilder(new ChatBuffer(Clock.systemUTC(), new ChatCaptureConfig()), new ChatCaptureConfig()), backend, new ChatCaptureConfig()));

    private PunishService.ApplyOutcome apply(String key, java.util.Set<EnforcementKind> allowed) {
        return service.apply(OWNER, null, key, STAFF, allowed, false);
    }

    @Test
    void aWarnApplyIsSentWithTheWarningOnlyFlag() {
        backend.templates = List.of(template("spam", "network"));

        service.apply(OWNER, null, "spam", STAFF, PunishService.ANY_KINDS, true);

        assertEquals(List.of(true), backend.warningOnlyFlags, "the backend refuses the apply unless the step is a warning");
    }

    @Test
    void theTargetsUuidIsHandedToTheBackendWithTheApply() {
        backend.templates = List.of(template("hacking", "network"));

        service.apply(OWNER, PLAYER, "hacking", STAFF, PunishService.BAN_KINDS, false);

        assertEquals(List.of(PLAYER), backend.appliedPlayers, "the public ban list needs the raw UUID of the player");
    }

    private void givenActive(String section, boolean permanent) {
        backend.profile = Optional.of(new PunishProfileDTO().owner(OWNER)
                .active(Map.of(section, new PunishEntryDTO().identifier("p1").section(section).permanent(permanent))));
    }

    // ---- apply -----------------------------------------------------------------------------

    @Test
    void aBanTemplateOfABanSectionIsAppliedByTheBanCommand() {
        backend.templates = List.of(template("hacking", "network"));

        var outcome = assertInstanceOf(PunishService.ApplyOutcome.Applied.class, apply("hacking", PunishService.BAN_KINDS));

        assertEquals("network", outcome.section().key());
        assertEquals(List.of("hacking"), backend.applied);
    }

    @Test
    void aServerSectionTemplateIsAlsoAcceptedByTheBanCommand() {
        backend.templates = List.of(template("camping", "bedwars"));

        assertInstanceOf(PunishService.ApplyOutcome.Applied.class, apply("camping", PunishService.BAN_KINDS));
    }

    @Test
    void aMuteTemplateIsRefusedByTheBanCommandAndNothingIsApplied() {
        backend.templates = List.of(template("spam", "chat"));

        var outcome = assertInstanceOf(PunishService.ApplyOutcome.WrongSection.class, apply("spam", PunishService.BAN_KINDS));

        assertEquals(EnforcementKind.MUTE, outcome.kind());
        assertTrue(backend.applied.isEmpty(), "a wrong-section template must not reach the backend");
    }

    @Test
    void aBanTemplateIsRefusedByTheMuteCommand() {
        backend.templates = List.of(template("hacking", "network"));

        assertInstanceOf(PunishService.ApplyOutcome.WrongSection.class, apply("hacking", PunishService.MUTE_KINDS));
    }

    @Test
    void thePunishCommandAcceptsTemplatesOfEverySection() {
        backend.templates = List.of(template("hacking", "network"), template("spam", "chat"), template("camping", "bedwars"));

        for (String key : List.of("hacking", "spam", "camping")) {
            assertInstanceOf(PunishService.ApplyOutcome.Applied.class, apply(key, PunishService.ANY_KINDS), key);
        }
    }

    @Test
    void anUnknownKeyIsTemplateNotFoundWithTheEnteredKey() {
        backend.templates = List.of(template("hacking", "network"));

        var outcome = assertInstanceOf(PunishService.ApplyOutcome.TemplateNotFound.class, apply("hackin", PunishService.BAN_KINDS));

        assertEquals("hackin", outcome.key(), "the message names what the staff member typed");
    }

    @Test
    void theKeyMustMatchExactly() {
        backend.templates = List.of(template("hacking", "network"));

        assertInstanceOf(PunishService.ApplyOutcome.TemplateNotFound.class, apply("Hacking", PunishService.BAN_KINDS));
    }

    @Test
    void aTemplateWhoseSectionIsUnknownToTheProxyIsWrongSection() {
        backend.templates = List.of(template("odd", "ghost"));

        assertInstanceOf(PunishService.ApplyOutcome.WrongSection.class, apply("odd", PunishService.ANY_KINDS));
    }

    @Test
    void aBackendRefusalIsReportedWithItsStatusInsteadOfThrowing() {
        backend.templates = List.of(template("hacking", "network"));
        backend.applyFailure = new ApiException(409, "already permanently punished");

        var outcome = assertInstanceOf(PunishService.ApplyOutcome.Failed.class, apply("hacking", PunishService.BAN_KINDS));

        assertEquals(409, outcome.status());
    }

    @Test
    void unreachableTemplatesAreReportedAsFailureNotAsAMissingTemplate() {
        backend.templatesFailure = new ApiException(0, "down");

        assertInstanceOf(PunishService.ApplyOutcome.Failed.class, apply("hacking", PunishService.BAN_KINDS));
    }

    // ---- revoke ----------------------------------------------------------------------------

    @Test
    void anActiveTemporaryBanIsRevoked() {
        givenActive("network", false);

        var outcome = service.revoke(OWNER, "network", STAFF, false, PunishService.BAN_KINDS);

        assertInstanceOf(PunishService.RevokeOutcome.Revoked.class, outcome);
        assertEquals(List.of("network"), backend.revoked);
    }

    @Test
    void aPermanentBanNeedsThePardonPermission() {
        givenActive("network", true);

        var outcome = service.revoke(OWNER, "network", STAFF, false, PunishService.BAN_KINDS);

        assertInstanceOf(PunishService.RevokeOutcome.PermanentNotAllowed.class, outcome);
        assertTrue(backend.revoked.isEmpty(), "nothing may be revoked without the permission");
    }

    @Test
    void aPermanentBanIsRevokedWithThePardonPermission() {
        givenActive("network", true);

        assertInstanceOf(PunishService.RevokeOutcome.Revoked.class, service.revoke(OWNER, "network", STAFF, true, PunishService.BAN_KINDS));
    }

    @Test
    void aSectionWithoutActivePunishmentHasNothingToRevoke() {
        givenActive("chat", false);

        var outcome = service.revoke(OWNER, "network", STAFF, false, PunishService.BAN_KINDS);

        assertInstanceOf(PunishService.RevokeOutcome.NothingActive.class, outcome);
        assertTrue(backend.revoked.isEmpty());
    }

    @Test
    void aPlayerWithoutAnyProfileHasNothingToRevoke() {
        assertInstanceOf(PunishService.RevokeOutcome.NothingActive.class, service.revoke(OWNER, "network", STAFF, false, PunishService.BAN_KINDS));
    }

    @Test
    void unbanRefusesAMuteSection() {
        var outcome = service.revoke(OWNER, "chat", STAFF, false, PunishService.BAN_KINDS);

        assertEquals(EnforcementKind.MUTE, assertInstanceOf(PunishService.RevokeOutcome.WrongSection.class, outcome).kind());
    }

    @Test
    void unbanCanRevokeAServerSection() {
        givenActive("bedwars", false);

        assertInstanceOf(PunishService.RevokeOutcome.Revoked.class, service.revoke(OWNER, "bedwars", STAFF, false, PunishService.BAN_KINDS));
    }

    @Test
    void anUnknownSectionIsReportedAsSuch() {
        assertInstanceOf(PunishService.RevokeOutcome.UnknownSection.class, service.revoke(OWNER, "ghost", STAFF, false, PunishService.BAN_KINDS));
    }

    @Test
    void aBackendFailureWhileRevokingIsReportedWithItsStatus() {
        givenActive("network", false);
        backend.revokeFailure = new ApiException(500, "boom");

        assertEquals(500, assertInstanceOf(PunishService.RevokeOutcome.Failed.class,
                service.revoke(OWNER, "network", STAFF, false, PunishService.BAN_KINDS)).status());
    }

    @Test
    void aRevokeThatRacesWithAnExpiryIsNothingActive() {
        givenActive("network", false);
        backend.revokeFailure = new ApiException(404, "no active punishment");

        assertInstanceOf(PunishService.RevokeOutcome.NothingActive.class, service.revoke(OWNER, "network", STAFF, false, PunishService.BAN_KINDS));
    }

    // ---- suggestions -----------------------------------------------------------------------

    @Test
    void suggestionsContainOnlyKeysOfEligibleSectionsMatchingTheTypedText() {
        backend.templates = List.of(template("spam", "chat"), template("spam-bot", "chat"), template("hacking", "network"), template("sports", "bedwars"));

        assertEquals(List.of("spam", "spam-bot"), service.suggest("sp", PunishService.MUTE_KINDS), "only MUTE keys containing 'sp'");
        assertEquals(List.of("sports"), service.suggest("sp", PunishService.BAN_KINDS), "ban and server keys containing 'sp'");
    }

    @Test
    void suggestionsIgnoreCase() {
        backend.templates = List.of(template("Hacking", "network"));

        assertEquals(List.of("Hacking"), service.suggest("HACK", PunishService.BAN_KINDS));
    }

    @Test
    void suggestionsAreEmptyWhenTemplatesCannotBeLoaded() {
        backend.templatesFailure = new ApiException(0, "down");

        assertTrue(service.suggest("a", PunishService.ANY_KINDS).isEmpty(), "tab completion must not fail");
    }

    // ---- similar keys ----------------------------------------------------------------------

    @Test
    void anUnknownKeyIsAnsweredWithTheSimilarKeysOfTheRightKind() {
        backend.templates = List.of(template("spam_1", "network"), template("spam_2", "bedwars"), template("spam_chat", "chat"));

        var outcome = assertInstanceOf(PunishService.ApplyOutcome.TemplateNotFound.class, apply("spamm", PunishService.BAN_KINDS));

        assertEquals(List.of("spam_1", "spam_2"), outcome.similar(), "the mute template must not be suggested for a ban");
    }

    @Test
    void anUnknownKeyWithNothingSimilarCarriesNoSuggestions() {
        backend.templates = List.of(template("hacking", "network"));

        var outcome = assertInstanceOf(PunishService.ApplyOutcome.TemplateNotFound.class, apply("zzzzzz", PunishService.BAN_KINDS));

        assertTrue(outcome.similar().isEmpty(), "nothing resembles the typed key");
    }

    @Test
    void keysContainingTheTextRankBeforeSharedPrefixesBeforeNearMisses() {
        List<String> ranked = PunishService.closest("spam", List.of("spax", "xspam", "spamming", "pam"), 5);

        assertEquals(List.of("spamming", "xspam", "spax", "pam"), ranked, "contains first (alphabetical), then near misses by distance");
    }

    @Test
    void aSharedPrefixOfThreeCharactersCounts() {
        assertEquals(List.of("hacking_1"), PunishService.closest("hackxyzabc", List.of("hacking_1", "flying"), 5));
    }

    @Test
    void aKeyWithinTwoEditsCounts() {
        assertEquals(List.of("spam_1"), PunishService.closest("spamm", List.of("spam_1", "grief"), 5));
    }

    @Test
    void aKeyThreeEditsAwayDoesNotCount() {
        assertTrue(PunishService.closest("abcdef", List.of("xyzuvw1"), 5).isEmpty(), "too different");
    }

    @Test
    void atMostFiveKeysAreReturned() {
        List<String> keys = List.of("a1", "a2", "a3", "a4", "a5", "a6", "a7");

        assertEquals(List.of("a1", "a2", "a3", "a4", "a5"), PunishService.closest("a", keys, PunishService.SIMILAR_LIMIT));
    }

    @Test
    void rankingIgnoresCase() {
        assertEquals(List.of("Spam_1"), PunishService.closest("SPAM", List.of("Spam_1"), 5));
    }

    @Test
    void nothingTypedSuggestsNothing() {
        assertTrue(PunishService.closest("", List.of("spam"), 5).isEmpty(), "an empty text resembles everything, so it suggests nothing");
    }
}
