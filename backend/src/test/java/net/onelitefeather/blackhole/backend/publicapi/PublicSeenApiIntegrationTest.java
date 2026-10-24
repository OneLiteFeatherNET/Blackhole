package net.onelitefeather.blackhole.backend.publicapi;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The seen marker over HTTP: the player's conversation read is what the team sees as "Seen" on the case. */
@Tag("integration")
class PublicSeenApiIntegrationTest extends PublicApiTestBase {

    @Test
    void theCaseShowsNoSeenSeqBeforeThePlayerReadAnything() {
        String punishment = punish("e1".repeat(64), "seen-none");
        assertEquals(200, post("/case/" + punishment + "/messages", Map.of("text", "We are looking into it."), this.jwt.moderator()).status());

        assertEquals(0, ((Number) get("/case/" + punishment, this.jwt.viewer()).json().get("playerSeenSeq")).longValue());
    }

    @Test
    void thePlayersConversationReadMovesTheSeenSeqThatTheCaseReports() {
        String punishment = punish("e2".repeat(64), "seen-read");
        String token = sessionFor(punishment);
        assertEquals(200, post("/case/" + punishment + "/messages", Map.of("text", "We are looking into it."), this.jwt.moderator()).status());

        Raw read = playerGet("/public/conversation", token);

        assertEquals(200, read.status(), read.body());
        Object cursor = read.json().get("cursor");
        assertNotNull(cursor, "the read returned an entry");
        assertEquals(((Number) cursor).longValue(),
                ((Number) get("/case/" + punishment, this.jwt.viewer()).json().get("playerSeenSeq")).longValue(),
                "staff see the seq the player's read reached");
        @SuppressWarnings("unchecked") List<Map<String, Object>> entries = (List<Map<String, Object>>) read.json().get("entries");
        assertEquals("We are looking into it.", entries.getLast().get("text"));
    }
}
