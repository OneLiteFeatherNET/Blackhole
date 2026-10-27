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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The player's copy. Fake readers, no database. */
class PublicProtocolServiceTest {

    private static final String PUNISHMENT = "punishment-1";

    private static PlayerSession session() {
        return new PlayerSession("hash", PUNISHMENT, PlayerScope.FULL, "csrf", Long.MAX_VALUE);
    }

    private static PublicAppealReader appealsDecided() {
        return punishment -> List.of(new PublicAppealReader.Row(UUID.randomUUID(), AppealStatus.DENIED, 1_000, 2_000, false));
    }

    private static PublicConversationReader.Entry marker(long seq, String kind) {
        return new PublicConversationReader.Entry(seq, seq * 1_000, kind, "SYSTEM", null, null, false);
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
    void theHeaderStatesTheStaffNotice() {
        String rendered = text(service(marker(1, "APPEAL_FILED")));

        assertTrue(rendered.contains("Staff members appear by the display name they chose. Their names are recorded in this chat.\n"));
    }
}
