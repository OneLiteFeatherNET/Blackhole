package net.onelitefeather.blackhole.backend.discord.ingest;

import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.MessageAuthorKind;
import net.onelitefeather.blackhole.backend.discord.ticket.ParticipantKind;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The author table of the Discord design (decision 6), one row per test. */
class AuthorResolverTest {

    private static final String PLAYER = "111111111111111111";
    private static final String STAFF = "222222222222222222";
    private static final String STRANGER = "333333333333333333";
    private static final UUID OID = UUID.fromString("6a1d3a52-0000-4000-8000-000000000001");

    @Test
    void theBoundUserIsThePlayerWithPlayerTrustAndAPlayerVisibleMessage() {
        AuthorResolver.Resolution resolution = AuthorResolver.resolve(PLAYER, false, false, PLAYER, null);

        assertEquals(MessageAuthorKind.PLAYER, resolution.kind());
        assertEquals(ActorTrust.PLAYER, resolution.trust());
        assertEquals(ParticipantKind.PLAYER, resolution.participantKind());
        assertTrue(resolution.boundPlayer(), "the participant is the ticket's bound one");
        assertEquals("PLAYER", resolution.visibility());
        assertNull(resolution.staffOid());
    }

    @Test
    void aStaffRoleHolderWithALiveLinkIsVerifiedStaffWithTheLinkedOidAndNoParticipant() {
        AuthorResolver.Resolution resolution = AuthorResolver.resolve(STAFF, false, true, PLAYER, OID);

        assertEquals(MessageAuthorKind.STAFF, resolution.kind());
        assertEquals(ActorTrust.VERIFIED, resolution.trust());
        assertEquals(OID, resolution.staffOid());
        assertNull(resolution.participantKind(), "a verified person is identified by the link, no Discord id is stored for the message");
        assertEquals("PLAYER", resolution.visibility());
    }

    @Test
    void aStaffRoleHolderWithoutALinkIsUnverifiedStaffWithAParticipant() {
        AuthorResolver.Resolution resolution = AuthorResolver.resolve(STAFF, false, true, PLAYER, null);

        assertEquals(MessageAuthorKind.STAFF, resolution.kind());
        assertEquals(ActorTrust.UNVERIFIED, resolution.trust());
        assertEquals(ParticipantKind.STAFF, resolution.participantKind());
        assertNull(resolution.staffOid());
    }

    @Test
    void aLinkWithoutTheStaffRoleDoesNotVerifyAnyone() {
        AuthorResolver.Resolution resolution = AuthorResolver.resolve(STAFF, false, false, PLAYER, OID);

        assertEquals(MessageAuthorKind.EXTERNAL, resolution.kind(), "a role alone or a link alone proves nothing");
        assertEquals(ActorTrust.UNVERIFIED, resolution.trust());
        assertNull(resolution.staffOid());
    }

    @Test
    void anyOtherHumanIsExternalAndUnverified() {
        AuthorResolver.Resolution resolution = AuthorResolver.resolve(STRANGER, false, false, PLAYER, null);

        assertEquals(MessageAuthorKind.EXTERNAL, resolution.kind());
        assertEquals(ActorTrust.UNVERIFIED, resolution.trust());
        assertEquals(ParticipantKind.EXTERNAL, resolution.participantKind());
        assertEquals("PLAYER", resolution.visibility(), "the player could read it in the channel");
        assertFalse(resolution.boundPlayer());
    }

    @Test
    void anotherBotIsABotWithSystemTrustAndStaffVisibility() {
        AuthorResolver.Resolution resolution = AuthorResolver.resolve(STRANGER, true, false, PLAYER, null);

        assertEquals(MessageAuthorKind.BOT, resolution.kind());
        assertEquals(ActorTrust.SYSTEM, resolution.trust());
        assertEquals(ParticipantKind.BOT, resolution.participantKind());
        assertEquals("STAFF", resolution.visibility(), "automation noise stays in the record, not in the portal");
    }

    @Test
    void aBotWithTheStaffRoleIsStillABot() {
        assertEquals(MessageAuthorKind.BOT, AuthorResolver.resolve(STRANGER, true, true, PLAYER, OID).kind());
    }

    @Test
    void aBoundPlayerWhoAlsoHoldsTheStaffRoleIsThePlayer() {
        assertEquals(MessageAuthorKind.PLAYER, AuthorResolver.resolve(PLAYER, false, true, PLAYER, OID).kind());
    }

    @Test
    void whenTheBoundParticipantWasErasedNobodyIsThePlayerAnymore() {
        AuthorResolver.Resolution resolution = AuthorResolver.resolve(PLAYER, false, false, null, null);

        assertEquals(MessageAuthorKind.EXTERNAL, resolution.kind());
    }
}
