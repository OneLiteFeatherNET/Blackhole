package net.onelitefeather.blackhole.backend.discord;

import net.onelitefeather.blackhole.backend.discord.dto.DiscordLinkDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.security.StaffLinkCodeGenerator;
import net.onelitefeather.blackhole.backend.support.DiscordLinkTestBed;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordLinkServiceTest {

    private static final UUID ALICE = UUID.fromString("a1000000-0000-4000-8000-000000000001");
    private static final UUID BOB = UUID.fromString("b2000000-0000-4000-8000-000000000002");
    private static final Actor ALICE_ACTOR = new Actor(ALICE, "Alice", ActorKind.HUMAN, false);
    private static final Actor BOB_ACTOR = new Actor(BOB, "Bob", ActorKind.HUMAN, false);
    private static final Actor PROXY = new Actor(UUID.fromString("d0000000-0000-4000-8000-000000000003"), null, ActorKind.SERVICE, true);
    private static final Actor NOT_PROXY = new Actor(UUID.fromString("d0000000-0000-4000-8000-000000000004"), null, ActorKind.SERVICE, false);
    private static final String OWNER = "a".repeat(128);
    private static final String OTHER_OWNER = "b".repeat(128);
    private static final String DISCORD_1 = "111111111111111111";
    private static final String DISCORD_2 = "222222222222222222";

    private final TestClock clock = new TestClock();
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final DiscordLinkTestBed bed = new DiscordLinkTestBed(this.events, this.clock);
    private final DiscordLinkService service = this.bed.service;

    private String staffCode(Actor who) {
        return assertInstanceOf(DiscordLinkDTO.CodeResponse.class, this.service.issueStaffCode(who), "a code was issued").code();
    }

    private String playerCode(String owner) {
        return assertInstanceOf(DiscordLinkDTO.CodeResponse.class,
                this.service.issuePlayerCode(new DiscordLinkDTO.PlayerCodeRequest(owner), PROXY), "a code was issued").code();
    }

    private DiscordLinkDTO confirm(String code, String discordUserId) {
        return this.service.confirm(new DiscordLinkDTO.ConfirmRequest(code, discordUserId));
    }

    private static void assertInvalidCode(DiscordLinkDTO result, String why) {
        assertEquals(ErrorKind.INVALID_CODE, assertInstanceOf(DiscordLinkDTO.Error.class, result, why).kind(), why);
    }

    @Test
    void aStaffCodeIsEightCharactersShownWithADashAndExpiresAfterTheTtl() {
        DiscordLinkDTO.CodeResponse issued = assertInstanceOf(DiscordLinkDTO.CodeResponse.class, this.service.issueStaffCode(ALICE_ACTOR));

        assertEquals(9, issued.code().length(), "ABCD-EFGH");
        assertEquals('-', issued.code().charAt(4), "the dash splits the halves");
        assertEquals(this.clock.millis() + DiscordLinkTestBed.TTL.toMillis(), issued.expiresAt(), "valid for the configured lifetime");
    }

    @Test
    void onlyTheHashOfACodeIsStored() {
        String code = staffCode(ALICE_ACTOR);

        DiscordLinkCodeEntity stored = this.bed.codes.all().getFirst();
        assertEquals(StaffLinkCodeGenerator.hash(StaffLinkCodeGenerator.normalize(code).orElseThrow()), stored.getCodeHash(), "the hash is stored");
        assertFalse(stored.getCodeHash().contains(code.replace("-", "")), "the code itself is not stored");
    }

    @Test
    void aServicePrincipalCannotRequestAStaffCode() {
        DiscordLinkDTO result = this.service.issueStaffCode(PROXY);

        assertEquals(ErrorKind.FORBIDDEN, assertInstanceOf(DiscordLinkDTO.Error.class, result).kind(), "403 for a machine");
        assertTrue(this.bed.codes.all().isEmpty(), "nothing was issued");
    }

    @Test
    void onlyTheProxyCanIssueAPlayerCode() {
        DiscordLinkDTO result = this.service.issuePlayerCode(new DiscordLinkDTO.PlayerCodeRequest(OWNER), NOT_PROXY);

        assertEquals(ErrorKind.FORBIDDEN, assertInstanceOf(DiscordLinkDTO.Error.class, result).kind(), "403 for a non-proxy");
    }

    @Test
    void aPlayerCodeNeedsAHashedOwnerNotARawUuid() {
        DiscordLinkDTO raw = this.service.issuePlayerCode(new DiscordLinkDTO.PlayerCodeRequest(UUID.randomUUID().toString()), PROXY);
        DiscordLinkDTO missing = this.service.issuePlayerCode(new DiscordLinkDTO.PlayerCodeRequest(null), PROXY);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(DiscordLinkDTO.Error.class, raw).kind(), "a raw UUID is refused");
        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(DiscordLinkDTO.Error.class, missing).kind(), "missing owner");
        assertTrue(this.bed.codes.all().isEmpty(), "nothing was issued");
    }

    @Test
    void aNewCodeReplacesThePreviousOneOfTheSameSubject() {
        String first = staffCode(ALICE_ACTOR);
        String second = staffCode(ALICE_ACTOR);

        assertInvalidCode(confirm(first, DISCORD_1), "the first code stopped working");
        assertInstanceOf(DiscordLinkDTO.LinkedResponse.class, confirm(second, DISCORD_1), "the second code works");
    }

    @Test
    void confirmingAStaffCodeLinksTheDiscordUserToTheOidAndPublishesCreatedWithoutTheDiscordId() {
        this.bed.givenActor(ALICE, "Alice");
        String code = staffCode(ALICE_ACTOR);

        DiscordLinkDTO.LinkedResponse linked = assertInstanceOf(DiscordLinkDTO.LinkedResponse.class, confirm(code, DISCORD_1));

        assertEquals(DiscordLinkKind.STAFF, linked.kind(), "a staff link");
        assertEquals("Alice", linked.displayName(), "names the person");
        assertEquals(DISCORD_1, this.bed.linkOf(DiscordLinkKind.STAFF, ALICE.toString()).orElseThrow().getDiscordUserId(), "the link exists");
        assertTrue(this.bed.codes.all().isEmpty(), "the code was consumed");
        var event = this.events.eventsOfType(DiscordLinkService.CREATED_EVENT).getFirst();
        assertEquals(List.of("kind", "subject"), event.payload().keySet().stream().sorted().toList(), "no code and no Discord id in the event");
        assertFalse(event.payload().containsValue(DISCORD_1), "the Discord id never leaves the link row");
    }

    @Test
    void confirmingAPlayerCodeLinksTheHashedOwnerAndNeverNamesAStaffMember() {
        String code = playerCode(OWNER);

        DiscordLinkDTO.LinkedResponse linked = assertInstanceOf(DiscordLinkDTO.LinkedResponse.class, confirm(code, DISCORD_1));

        assertEquals(DiscordLinkKind.PLAYER, linked.kind(), "a player link");
        assertNull(linked.displayName(), "players have no display name");
        assertEquals(DISCORD_1, this.bed.linkOf(DiscordLinkKind.PLAYER, OWNER).orElseThrow().getDiscordUserId(), "bound to the hashed owner");
    }

    @Test
    void aCodeTypedInLowerCaseWithoutDashAndWithLookAlikesStillWorks() {
        String code = staffCode(ALICE_ACTOR);
        String typed = code.replace("-", "").toLowerCase().replace('1', 'l').replace('0', 'o');

        assertInstanceOf(DiscordLinkDTO.LinkedResponse.class, confirm(typed, DISCORD_1), "normalized input is accepted");
    }

    @Test
    void aMalformedDiscordUserIdIsAValidationErrorAndKeepsTheCode() {
        String code = staffCode(ALICE_ACTOR);

        DiscordLinkDTO result = confirm(code, "not-a-snowflake");

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(DiscordLinkDTO.Error.class, result).kind(), "400");
        assertInstanceOf(DiscordLinkDTO.LinkedResponse.class, confirm(code, DISCORD_1), "the code was not burned by the bad request");
    }

    @Test
    void anExpiredCodeIsInvalidOnceTheClockPassesItsLifetime() {
        String code = staffCode(ALICE_ACTOR);
        this.clock.advance(DiscordLinkTestBed.TTL.plusMillis(1));

        assertInvalidCode(confirm(code, DISCORD_1), "expired");
        assertTrue(this.bed.allLinks().isEmpty(), "no link exists");
    }

    @Test
    void aCodeIsStillValidJustBeforeItExpires() {
        String code = staffCode(ALICE_ACTOR);
        this.clock.advance(DiscordLinkTestBed.TTL.minusMillis(1));

        assertInstanceOf(DiscordLinkDTO.LinkedResponse.class, confirm(code, DISCORD_1), "still live");
    }

    @Test
    void aReusedCodeIsInvalidAndLeavesTheLinkUnchanged() {
        String code = staffCode(ALICE_ACTOR);
        confirm(code, DISCORD_1);

        assertInvalidCode(confirm(code, DISCORD_2), "already used");
        assertEquals(DISCORD_1, this.bed.linkOf(DiscordLinkKind.STAFF, ALICE.toString()).orElseThrow().getDiscordUserId(), "unchanged");
    }

    @Test
    void wrongExpiredAndReusedCodesAreAnsweredIdentically() {
        String used = staffCode(ALICE_ACTOR);
        confirm(used, DISCORD_1);
        String expired = staffCode(BOB_ACTOR);
        this.clock.advance(Duration.ofMinutes(11));

        DiscordLinkDTO.Error wrong = assertInstanceOf(DiscordLinkDTO.Error.class, confirm("ABCD-EFGH", DISCORD_2));
        DiscordLinkDTO.Error reused = assertInstanceOf(DiscordLinkDTO.Error.class, confirm(used, DISCORD_2));
        DiscordLinkDTO.Error late = assertInstanceOf(DiscordLinkDTO.Error.class, confirm(expired, DISCORD_2));

        assertEquals(wrong, reused, "wrong equals reused");
        assertEquals(wrong, late, "wrong equals expired");
        assertFalse(wrong.errorMessage().contains("ABCD"), "the typed code is not echoed");
    }

    @Test
    void aNewPairingReplacesTheOldLinkOfTheSameSubjectAndPublishesReplaced() {
        confirm(staffCode(ALICE_ACTOR), DISCORD_1);
        confirm(staffCode(ALICE_ACTOR), DISCORD_2);

        assertEquals(1, this.bed.allLinks().size(), "only the new link exists");
        assertEquals(DISCORD_2, this.bed.linkOf(DiscordLinkKind.STAFF, ALICE.toString()).orElseThrow().getDiscordUserId(), "the new account");
        assertEquals("REPLACED", this.events.eventsOfType(DiscordLinkService.REMOVED_EVENT).getFirst().payload().get("reason"), "audited");
    }

    @Test
    void aDiscordAccountMovesToTheSubjectThatLinksItLast() {
        confirm(staffCode(ALICE_ACTOR), DISCORD_1);
        confirm(staffCode(BOB_ACTOR), DISCORD_1);

        assertTrue(this.bed.linkOf(DiscordLinkKind.STAFF, ALICE.toString()).isEmpty(), "Alice lost it");
        assertEquals(DISCORD_1, this.bed.linkOf(DiscordLinkKind.STAFF, BOB.toString()).orElseThrow().getDiscordUserId(), "Bob has it");
    }

    @Test
    void aStaffMemberCanBeAlsoAPlayerWithTheSameDiscordAccount() {
        confirm(staffCode(ALICE_ACTOR), DISCORD_1);
        confirm(playerCode(OWNER), DISCORD_1);

        DiscordLinkDTO.WhoamiResponse who = assertInstanceOf(DiscordLinkDTO.WhoamiResponse.class, this.service.whoami(DISCORD_1));
        assertEquals(List.of(DiscordLinkKind.STAFF, DiscordLinkKind.PLAYER), who.links().stream().map(DiscordLinkDTO.WhoamiEntry::kind).toList(), "both kinds");
    }

    @Test
    void whoamiOfAnUnlinkedUserIsEmpty() {
        assertTrue(assertInstanceOf(DiscordLinkDTO.WhoamiResponse.class, this.service.whoami(DISCORD_1)).links().isEmpty(), "nothing linked");
    }

    @Test
    void unlinkingByDiscordUserRemovesEveryLinkOfThatUser() {
        this.bed.givenLink(DiscordLinkKind.STAFF, ALICE.toString(), DISCORD_1);
        this.bed.givenLink(DiscordLinkKind.PLAYER, OWNER, DISCORD_1);
        this.bed.givenLink(DiscordLinkKind.PLAYER, OTHER_OWNER, DISCORD_2);

        DiscordLinkDTO result = this.service.unlinkDiscordUser(DISCORD_1);

        assertEquals(2, assertInstanceOf(DiscordLinkDTO.RemovedResponse.class, result).removed(), "both removed");
        assertEquals(1, this.bed.allLinks().size(), "the other user's link stays");
        assertEquals("SELF", this.events.eventsOfType(DiscordLinkService.REMOVED_EVENT).getFirst().payload().get("reason"), "audited");
    }

    @Test
    void unlinkingAnUnlinkedDiscordUserIsNotFound() {
        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(DiscordLinkDTO.Error.class, this.service.unlinkDiscordUser(DISCORD_1)).kind(), "404");
    }

    @Test
    void aStaffMemberSeesAndRemovesTheirOwnLink() {
        this.bed.givenLink(DiscordLinkKind.STAFF, ALICE.toString(), DISCORD_1);

        assertTrue(assertInstanceOf(DiscordLinkDTO.StatusResponse.class, this.service.staffStatus(ALICE_ACTOR)).linked(), "linked");
        assertFalse(assertInstanceOf(DiscordLinkDTO.StatusResponse.class, this.service.staffStatus(BOB_ACTOR)).linked(), "Bob is not");
        assertInstanceOf(DiscordLinkDTO.RemovedResponse.class, this.service.unlinkStaff(ALICE_ACTOR));
        assertTrue(this.bed.allLinks().isEmpty(), "the Discord id is gone");
        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(DiscordLinkDTO.Error.class, this.service.unlinkStaff(ALICE_ACTOR)).kind(), "404 the second time");
    }

    @Test
    void theAdminListNamesStaffAndNeverContainsADiscordId() {
        this.bed.givenActor(ALICE, "Alice");
        this.bed.givenLink(DiscordLinkKind.STAFF, ALICE.toString(), DISCORD_1);
        this.bed.givenLink(DiscordLinkKind.PLAYER, OWNER, DISCORD_2);

        DiscordLinkDTO.ListResponse list = assertInstanceOf(DiscordLinkDTO.ListResponse.class, this.service.list());

        assertEquals(2, list.items().size(), "both links");
        assertEquals("Alice", list.items().stream().filter(i -> i.kind() == DiscordLinkKind.STAFF).findFirst().orElseThrow().displayName(), "staff are named");
        assertFalse(list.toString().contains(DISCORD_1) || list.toString().contains(DISCORD_2), "no Discord id in the list");
    }

    @Test
    void anAdminRemovesALinkById() {
        DiscordLinkEntity link = this.bed.givenLink(DiscordLinkKind.PLAYER, OWNER, DISCORD_1);

        assertInstanceOf(DiscordLinkDTO.RemovedResponse.class, this.service.adminRemove(link.getId()));

        assertTrue(this.bed.allLinks().isEmpty(), "removed");
        assertEquals("ADMIN", this.events.eventsOfType(DiscordLinkService.REMOVED_EVENT).getFirst().payload().get("reason"), "audited");
        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(DiscordLinkDTO.Error.class, this.service.adminRemove(link.getId())).kind(), "gone");
    }

    @Test
    void erasingAPlayerRemovesTheirDiscordLink() {
        this.bed.givenLink(DiscordLinkKind.PLAYER, OWNER, DISCORD_1);

        this.service.removeOnErase(OWNER.toUpperCase());

        assertTrue(this.bed.allLinks().isEmpty(), "the Discord id is erased with the profile");
    }

    @Test
    void theStaffLinksListHoldsEveryLiveStaffLinkAndNoPlayerLink() {
        this.bed.givenLink(DiscordLinkKind.STAFF, ALICE.toString(), DISCORD_1);
        this.bed.givenLink(DiscordLinkKind.PLAYER, OWNER, DISCORD_2);

        DiscordLinkDTO.StaffLinksResponse links = assertInstanceOf(DiscordLinkDTO.StaffLinksResponse.class, this.service.staffLinks());

        assertEquals(List.of(DISCORD_1), links.links().stream().map(DiscordLinkDTO.StaffLink::discordUserId).toList());
    }

    @Test
    void aStaffMemberWhoUnlinksIsNoLongerInTheStaffLinksList() {
        this.bed.givenLink(DiscordLinkKind.STAFF, ALICE.toString(), DISCORD_1);

        this.service.unlinkDiscordUser(DISCORD_1);

        assertTrue(assertInstanceOf(DiscordLinkDTO.StaffLinksResponse.class, this.service.staffLinks()).links().isEmpty());
    }
}
