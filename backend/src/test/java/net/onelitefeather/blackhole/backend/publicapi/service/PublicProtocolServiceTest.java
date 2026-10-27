package net.onelitefeather.blackhole.backend.publicapi.service;

import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.publicapi.PlayerScope;
import net.onelitefeather.blackhole.backend.publicapi.PublicAppealReader;
import net.onelitefeather.blackhole.backend.publicapi.PublicConversationReader;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicProtocolDTO;
import net.onelitefeather.blackhole.backend.security.PlayerSession;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The player's copy: staff by the name recorded with each message, "Moderation team" for a legacy one. Fake readers, no database. */
class PublicProtocolServiceTest {

    private static final String PUNISHMENT = "punishment-1";
    private static final String AVATAR_KEY = "avatar-key-7f3a";

    private static PlayerSession session() {
        return new PlayerSession("hash", PUNISHMENT, PlayerScope.FULL, "csrf", Long.MAX_VALUE);
    }

    private static PublicAppealReader appealsDecided() {
        return punishment -> List.of(new PublicAppealReader.Row(UUID.randomUUID(), AppealStatus.DENIED, 1_000, 2_000, false));
    }

    private static PublicConversationReader.Entry player(long seq, String text) {
        return new PublicConversationReader.Entry(seq, seq * 1_000, "MESSAGE", "PLAYER", "m-" + seq, text, false);
    }

    private static PublicConversationReader.Entry staff(long seq, String text, PublicConversationReader.Staff staff) {
        return new PublicConversationReader.Entry(seq, seq * 1_000, "MESSAGE", "STAFF", "m-" + seq, text, false, null, null, staff);
    }

    private static PublicProtocolService service(PublicConversationReader.Entry... entries) {
        PublicConversationReader reader = (punishment, after, limit) -> new PublicConversationReader.Page(List.of(entries), null);
        return new PublicProtocolService(reader, appealsDecided());
    }

    private static String text(PublicProtocolService service) {
        PublicProtocolDTO.Download download = assertInstanceOf(PublicProtocolDTO.Download.class, service.read(session(), "text"));
        return new String(download.content(), StandardCharsets.UTF_8);
    }

    @Test
    void aVersionTwoStaffMessageShowsItsSnapshotName() {
        String rendered = text(service(staff(2, "We checked the logs.", new PublicConversationReader.Staff("Mia K.", AVATAR_KEY))));

        assertTrue(rendered.contains("Mia K.\n    We checked the logs."), "the name recorded with the message is shown");
        assertFalse(rendered.contains("Moderation team"), "a named message never falls back to the generic label");
    }

    @Test
    void aLegacyStaffMessageKeepsModerationTeam() {
        String rendered = text(service(staff(2, "We checked the logs.", null)));

        assertTrue(rendered.contains("Moderation team\n    We checked the logs."), "version 1 is unchanged");
    }

    @Test
    void aStaffMessageWithoutASnapshotNameKeepsModerationTeam() {
        String rendered = text(service(staff(2, "We checked the logs.", new PublicConversationReader.Staff(null, AVATAR_KEY))));

        assertTrue(rendered.contains("Moderation team\n    We checked the logs."));
    }

    @Test
    void thePlayerIsYouAndTheCopyCarriesNoAvatarKeyOrIdentifier() {
        String rendered = text(service(player(1, "I did not cheat."),
                staff(2, "Thanks.", new PublicConversationReader.Staff("Mia K.", AVATAR_KEY))));

        assertTrue(rendered.contains("You\n    I did not cheat."));
        assertFalse(rendered.contains(AVATAR_KEY), "the head key is not part of the player copy");
        assertFalse(rendered.contains(PUNISHMENT), "no identifier of the case");
    }

    @Test
    void theJsonCopyNamesTheStaffMemberByTheSnapshot() {
        PublicProtocolDTO.Download download = assertInstanceOf(PublicProtocolDTO.Download.class, service(
                staff(2, "Thanks.", new PublicConversationReader.Staff("Mia K.", null))).read(session(), "json"));
        String json = new String(download.content(), StandardCharsets.UTF_8);

        assertTrue(json.contains("\"author\" : \"Mia K.\""), json);
        assertEquals("application/json", download.contentType());
    }

    @Test
    void aRemovedStaffMessageShowsNoTextButKeepsTheName() {
        String rendered = text(service(new PublicConversationReader.Entry(2, 2_000, "MESSAGE", "STAFF", "m-2", null, true, null, null,
                new PublicConversationReader.Staff("Mia K.", null))));

        assertTrue(rendered.contains("Mia K.\n    [removed]"));
    }
}
