package net.onelitefeather.blackhole.backend.security;

import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.dto.StaffLinkDTO;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.StaffLinkTestBed;
import net.onelitefeather.blackhole.backend.support.TestClock;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffLinkServiceTest {

    private static final UUID ALICE = UUID.fromString("a1000000-0000-4000-8000-000000000001");
    private static final UUID BOB = UUID.fromString("b2000000-0000-4000-8000-000000000002");
    private static final UUID ALICE_MC = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID ALICE_ALT_MC = UUID.fromString("c1000000-0000-4000-8000-000000000009");
    private static final UUID BOB_MC = UUID.fromString("c2000000-0000-4000-8000-000000000002");
    private static final UUID PROXY_OID = UUID.fromString("d0000000-0000-4000-8000-000000000003");

    private static final Actor ALICE_ACTOR = new Actor(ALICE, "Alice", ActorKind.HUMAN, false);
    private static final Actor BOB_ACTOR = new Actor(BOB, "Bob", ActorKind.HUMAN, false);
    private static final Actor PROXY = new Actor(PROXY_OID, null, ActorKind.SERVICE, true);

    private final TestClock clock = new TestClock();
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final StaffLinkTestBed bed = new StaffLinkTestBed(this.events, this.clock);
    private final StaffLinkService service = this.bed.service;

    private String issue(Actor who) {
        return assertInstanceOf(StaffLinkDTO.CodeResponse.class, this.service.issueCode(who), "a code was issued").code();
    }

    private StaffLinkDTO confirm(String code, UUID minecraft) {
        return this.service.confirm(new StaffLinkDTO.CreateRequest(code, minecraft), PROXY);
    }

    private static void assertInvalidCode(StaffLinkDTO result, String why) {
        StaffLinkDTO.Error error = assertInstanceOf(StaffLinkDTO.Error.class, result, why);
        assertEquals(ErrorKind.INVALID_CODE, error.kind(), why);
    }

    @Test
    void aCodeIsEightCharactersShownWithADash() {
        String code = issue(ALICE_ACTOR);

        assertEquals(9, code.length(), "ABCD-EFGH");
        assertEquals('-', code.charAt(4), "the dash splits the two halves");
    }

    @Test
    void aCodeExpiresFiveMinutesAheadOfTheClock() {
        StaffLinkDTO.CodeResponse issued = assertInstanceOf(StaffLinkDTO.CodeResponse.class, this.service.issueCode(ALICE_ACTOR));

        assertEquals(this.clock.millis() + Duration.ofMinutes(5).toMillis(), issued.expiresAt(), "valid for the configured lifetime");
    }

    @Test
    void onlyTheHashOfACodeIsStored() {
        String code = issue(ALICE_ACTOR);

        StaffLinkCodeEntity stored = this.bed.codes.all().getFirst();
        assertEquals(StaffLinkCodeGenerator.hash(StaffLinkCodeGenerator.normalize(code).orElseThrow()), stored.getCodeHash(), "the hash is stored");
        assertFalse(stored.getCodeHash().contains(code.replace("-", "")), "the code itself is not stored");
        assertEquals(1, this.bed.codes.all().size(), "one live code");
    }

    @Test
    void aServicePrincipalCannotRequestACode() {
        StaffLinkDTO result = this.service.issueCode(PROXY);

        assertEquals(ErrorKind.FORBIDDEN, assertInstanceOf(StaffLinkDTO.Error.class, result).kind(), "403 for a machine");
        assertTrue(this.bed.codes.all().isEmpty(), "nothing was issued");
    }

    @Test
    void aNewCodeReplacesThePreviousOneOfTheSameStaffMember() {
        String first = issue(ALICE_ACTOR);
        String second = issue(ALICE_ACTOR);

        assertInvalidCode(confirm(first, ALICE_MC), "the first code stopped working");
        assertInstanceOf(StaffLinkDTO.LinkedResponse.class, confirm(second, ALICE_MC), "the second code works");
    }

    @Test
    void confirmingALinkStoresTheAvatarKeyOfTheAccountAndUnlinkingClearsIt() {
        this.bed.givenActor(ALICE, "Alice");
        String code = issue(ALICE_ACTOR);

        confirm(code, ALICE_MC);
        String key = this.bed.profiles.find(ALICE).orElseThrow().avatarKey();

        assertEquals(this.bed.avatarJob.png(key).isPresent(), false, "the test source has no skin, so no head is cached");
        assertTrue(key != null && !key.contains(ALICE_MC.toString()), "the key stands in for the account");
        this.service.unlinkSelf(ALICE_ACTOR);
        assertEquals(null, this.bed.profiles.find(ALICE).orElseThrow().avatarKey(), "unlinking clears the key");
    }

    @Test
    void confirmingALiveCodeLinksTheUuidToTheStaffMemberAndPublishesCreated() {
        this.bed.givenActor(ALICE, "Alice");
        String code = issue(ALICE_ACTOR);

        StaffLinkDTO.LinkedResponse linked = assertInstanceOf(StaffLinkDTO.LinkedResponse.class, confirm(code, ALICE_MC));

        assertEquals(ALICE, linked.oid(), "bound to the oid the code was issued to");
        assertEquals("Alice", linked.displayName(), "names the person");
        assertEquals(ALICE_MC, this.bed.linkOf(ALICE).orElseThrow().getMinecraftUuid(), "the link exists");
        assertTrue(this.bed.codes.all().isEmpty(), "the code was consumed");
        var event = this.events.eventsOfType(StaffLinkService.CREATED_EVENT).getFirst();
        assertEquals(ALICE.toString(), event.payload().get("oid"), "the person is identified by oid");
        assertEquals(List.of("linkedBy", "oid"), event.payload().keySet().stream().sorted().toList(), "no code in the event");
    }

    @Test
    void aCodeTypedInLowerCaseWithoutDashAndWithLookAlikesStillWorks() {
        String code = issue(ALICE_ACTOR);
        String typed = code.replace("-", "").toLowerCase().replace('1', 'l').replace('0', 'o');

        assertInstanceOf(StaffLinkDTO.LinkedResponse.class, confirm(typed, ALICE_MC), "normalized input is accepted");
    }

    @Test
    void aHumanTokenCannotConfirm() {
        String code = issue(ALICE_ACTOR);

        StaffLinkDTO result = this.service.confirm(new StaffLinkDTO.CreateRequest(code, ALICE_MC), BOB_ACTOR);

        assertEquals(ErrorKind.FORBIDDEN, assertInstanceOf(StaffLinkDTO.Error.class, result).kind(), "403 for a person");
        assertTrue(this.bed.links.all().isEmpty(), "nothing linked");
    }

    @Test
    void confirmingWithoutOnBehalfOfIsAValidationError() {
        String code = issue(ALICE_ACTOR);

        StaffLinkDTO result = this.service.confirm(new StaffLinkDTO.CreateRequest(code, null), PROXY);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(StaffLinkDTO.Error.class, result).kind(), "400");
    }

    @Test
    void anExpiredCodeIsInvalidOnceTheClockPassesItsLifetime() {
        String code = issue(ALICE_ACTOR);
        this.clock.advance(Duration.ofMinutes(5).plusMillis(1));

        assertInvalidCode(confirm(code, ALICE_MC), "expired");
        assertTrue(this.bed.links.all().isEmpty(), "no link exists");
    }

    @Test
    void aCodeIsStillValidJustBeforeItExpires() {
        String code = issue(ALICE_ACTOR);
        this.clock.advance(Duration.ofMinutes(5).minusMillis(1));

        assertInstanceOf(StaffLinkDTO.LinkedResponse.class, confirm(code, ALICE_MC), "still live");
    }

    @Test
    void aReusedCodeIsInvalidAndLeavesTheLinkUnchanged() {
        String code = issue(ALICE_ACTOR);
        confirm(code, ALICE_MC);

        assertInvalidCode(confirm(code, BOB_MC), "already used");
        assertEquals(ALICE_MC, this.bed.linkOf(ALICE).orElseThrow().getMinecraftUuid(), "the link is unchanged");
        assertTrue(this.bed.linkOf(BOB).isEmpty(), "no second link");
    }

    @Test
    void aCodeThatWasNeverIssuedIsInvalid() {
        assertInvalidCode(confirm("ABCD-EFGH", ALICE_MC), "wrong");
        assertInvalidCode(confirm("not a code", ALICE_MC), "garbage");
        assertInvalidCode(confirm(null, ALICE_MC), "missing");
        assertTrue(this.bed.links.all().isEmpty(), "no link created");
    }

    @Test
    void wrongExpiredAndReusedCodesAreAnsweredIdentically() {
        String used = issue(ALICE_ACTOR);
        confirm(used, ALICE_MC);
        String expired = issue(BOB_ACTOR);
        this.clock.advance(Duration.ofMinutes(6));

        StaffLinkDTO.Error wrong = assertInstanceOf(StaffLinkDTO.Error.class, confirm("ABCD-EFGH", BOB_MC));
        StaffLinkDTO.Error reused = assertInstanceOf(StaffLinkDTO.Error.class, confirm(used, BOB_MC));
        StaffLinkDTO.Error late = assertInstanceOf(StaffLinkDTO.Error.class, confirm(expired, BOB_MC));

        assertEquals(wrong, reused, "a prober cannot tell wrong from used");
        assertEquals(wrong, late, "a prober cannot tell wrong from expired");
    }

    @Test
    void failedAttemptsAreNotCountedSoTheCorrectCodeStillLinks() {
        String code = issue(ALICE_ACTOR);
        for (int i = 0; i < 200; i++) {
            assertInvalidCode(confirm("0000-" + String.format("%04d", i), BOB_MC), "wrong guess " + i);
        }

        assertInstanceOf(StaffLinkDTO.LinkedResponse.class, confirm(code, ALICE_MC), "no attempt cap or limiter");
    }

    @Test
    void aStaffMemberLinkingASecondAccountReplacesTheFirstAndReportsItRemoved() {
        this.bed.givenLink(ALICE, ALICE_MC);
        String code = issue(ALICE_ACTOR);

        confirm(code, ALICE_ALT_MC);

        assertEquals(List.of(ALICE_ALT_MC), this.bed.links.all().stream().map(StaffLinkEntity::getMinecraftUuid).toList(), "only the new link");
        var removed = this.events.eventsOfType(StaffLinkService.REMOVED_EVENT);
        assertEquals(1, removed.size(), "the old link was reported");
        assertEquals("REPLACED", removed.getFirst().payload().get("reason"), "with reason REPLACED");
        assertEquals(ALICE.toString(), removed.getFirst().payload().get("oid"), "for Alice");
    }

    @Test
    void aUuidLinkedToSomeoneElseMovesToTheNewStaffMember() {
        this.bed.givenLink(ALICE, ALICE_MC);
        String code = issue(BOB_ACTOR);

        confirm(code, ALICE_MC);

        assertTrue(this.bed.linkOf(ALICE).isEmpty(), "A lost the link");
        assertEquals(ALICE_MC, this.bed.linkOf(BOB).orElseThrow().getMinecraftUuid(), "B has it");
        var removed = this.events.eventsOfType(StaffLinkService.REMOVED_EVENT);
        assertEquals(ALICE.toString(), removed.getFirst().payload().get("oid"), "A's removal is audited");
        assertEquals("REPLACED", removed.getFirst().payload().get("reason"), "as REPLACED");
    }

    @Test
    void confirmingTheSamePairAgainChangesNothingAndRemovesNothing() {
        this.bed.givenLink(ALICE, ALICE_MC);
        String code = issue(ALICE_ACTOR);

        assertInstanceOf(StaffLinkDTO.LinkedResponse.class, confirm(code, ALICE_MC), "still a success");
        assertTrue(this.events.eventsOfType(StaffLinkService.REMOVED_EVENT).isEmpty(), "nothing removed");
        assertEquals(1, this.bed.links.all().size(), "one link");
    }

    @Test
    void theStatusShowsTheOwnLinkAndNeverACode() {
        this.bed.givenLink(ALICE, ALICE_MC);

        StaffLinkDTO.StatusResponse mine = assertInstanceOf(StaffLinkDTO.StatusResponse.class, this.service.status(ALICE_ACTOR));
        StaffLinkDTO.StatusResponse theirs = assertInstanceOf(StaffLinkDTO.StatusResponse.class, this.service.status(BOB_ACTOR));

        assertTrue(mine.linked(), "Alice is linked");
        assertEquals(ALICE_MC, mine.minecraftUuid(), "to her account");
        assertFalse(theirs.linked(), "Bob is not");
        assertNull(theirs.minecraftUuid(), "no uuid for an unlinked person");
    }

    @Test
    void aStaffMemberCanUnlinkThemselves() {
        this.bed.givenLink(ALICE, ALICE_MC);

        assertInstanceOf(StaffLinkDTO.RemovedResponse.class, this.service.unlinkSelf(ALICE_ACTOR), "removed");

        assertFalse(assertInstanceOf(StaffLinkDTO.StatusResponse.class, this.service.status(ALICE_ACTOR)).linked(), "not linked any more");
        var event = this.events.eventsOfType(StaffLinkService.REMOVED_EVENT).getFirst();
        assertEquals("SELF", event.payload().get("reason"), "audited as SELF");
    }

    @Test
    void unlinkingWithoutALinkIsNotFound() {
        StaffLinkDTO result = this.service.unlinkSelf(ALICE_ACTOR);

        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(StaffLinkDTO.Error.class, result).kind(), "404");
    }

    @Test
    void anAdminCanUnlinkAnotherPersonAndIsRecordedAsTheRemover() {
        this.bed.givenLink(BOB, BOB_MC);
        Actor admin = new Actor(ALICE, "Alice", ActorKind.HUMAN, false);

        assertInstanceOf(StaffLinkDTO.RemovedResponse.class, this.service.unlink(BOB, admin), "removed");

        var event = this.events.eventsOfType(StaffLinkService.REMOVED_EVENT).getFirst();
        assertEquals("ADMIN", event.payload().get("reason"), "audited as ADMIN");
        assertEquals(ALICE.toString(), event.payload().get("removedBy"), "by the admin's oid");
        assertTrue(this.bed.linkOf(BOB).isEmpty(), "Bob's link is gone");
    }

    @Test
    void theAdminListShowsEveryLinkWithTheDisplayName() {
        this.bed.givenActor(ALICE, "Alice");
        this.bed.givenLink(ALICE, ALICE_MC);
        this.bed.givenLink(BOB, BOB_MC);

        StaffLinkDTO.ListResponse list = assertInstanceOf(StaffLinkDTO.ListResponse.class, this.service.list());

        assertEquals(2, list.items().size(), "both links");
        assertEquals("Alice", list.items().stream().filter(i -> i.oid().equals(ALICE)).findFirst().orElseThrow().displayName(), "named");
    }

    @Test
    void lookingUpALinkedMinecraftAccountNamesThePerson() {
        this.bed.givenActor(ALICE, "Alice");
        this.bed.givenLink(ALICE, ALICE_MC);

        StaffLinkDTO.LookupResponse found = assertInstanceOf(StaffLinkDTO.LookupResponse.class, this.service.byMinecraft(ALICE_MC));
        StaffLinkDTO.LookupResponse unknown = assertInstanceOf(StaffLinkDTO.LookupResponse.class, this.service.byMinecraft(BOB_MC));

        assertTrue(found.linked(), "linked");
        assertEquals("Alice", found.displayName(), "to Alice");
        assertFalse(unknown.linked(), "unknown account");
    }

    @Test
    void anExpiredCodeRowIsDeletedOnTheNextIssue() {
        issue(ALICE_ACTOR);
        this.clock.advance(Duration.ofMinutes(10));

        issue(BOB_ACTOR);

        assertEquals(List.of(BOB), this.bed.codes.all().stream().map(StaffLinkCodeEntity::getOid).toList(), "only the live code remains");
    }

    @Test
    void aLinkedUuidIsAttributedToItsStaffMemberAndAnUnlinkedOneToNobody() {
        this.bed.givenLink(ALICE, ALICE_MC);

        Attribution linked = this.service.withLinkedActor(new Attribution(ALICE_MC, PROXY_OID));
        Attribution unlinked = this.service.withLinkedActor(new Attribution(BOB_MC, PROXY_OID));
        Attribution web = this.service.withLinkedActor(new Attribution(BOB, BOB));

        assertEquals(ALICE, linked.actorOid(), "linked");
        assertEquals(ALICE_MC, linked.source(), "the source is never rewritten");
        assertNull(unlinked.actorOid(), "unlinked stays NULL");
        assertNull(web.actorOid(), "a web caller needs no actor oid");
        assertNotEquals(linked, unlinked, "distinguishable");
    }

    @Test
    void neitherTheCodeNorItsHashIsEverLogged() {
        Logger logger = (Logger) LoggerFactory.getLogger(StaffLinkService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String code = issue(ALICE_ACTOR);
            confirm("ABCD-EFGH", BOB_MC);
            confirm(code, ALICE_MC);

            String plain = code.replace("-", "");
            String hash = StaffLinkCodeGenerator.hash(plain);
            assertFalse(appender.list.isEmpty(), "precondition: the service logs the link");
            for (ILoggingEvent line : appender.list) {
                String text = line.getFormattedMessage();
                assertFalse(text.contains(plain) || text.contains(code) || text.contains(hash), "no secret in: " + text);
            }
        } finally {
            logger.detachAppender(appender);
        }
    }
}
