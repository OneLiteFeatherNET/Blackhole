package net.onelitefeather.blackhole.velocity.evidence;

import net.onelitefeather.blackhole.client.model.EvidenceCreateRequest;
import net.onelitefeather.blackhole.client.model.EvidenceKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatEvidenceRequestsTest {

    private static final String OWNER = "a".repeat(128);
    private static final UUID STAFF = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    private final ChatSnapshot snapshot = new ChatSnapshot(List.of(
            new ChatSnapshot.Entry(1000L, "Spieler A", "hello"),
            new ChatSnapshot.Entry(2000L, "Spieler B", "[url]")));

    private EvidenceCreateRequest request() {
        return ChatEvidenceRequests.create(OWNER, "corr-1", snapshot, "velocity", STAFF, 3000L);
    }

    @Test
    void theRequestIsAChatLogForTheOwnerUnderTheCorrelationId() {
        EvidenceCreateRequest request = request();

        assertEquals(EvidenceKind.CHAT_LOG, request.getKind());
        assertEquals(OWNER, request.getOwner());
        assertEquals("corr-1", request.getCorrelationId());
        assertNull(request.getPunishment(), "evidence ahead of its punishment is addressed by owner and correlation id, not by punishment");
        assertEquals("velocity", request.getSourceSystem());
        assertEquals(3000L, request.getCapturedAt());
        assertEquals(STAFF, request.getSource());
    }

    @Test
    void theLinesCarryTimestampAliasAndText() {
        Map<String, Object> payload = request().getPayload();

        assertEquals(
                Map.of("lines", List.of(
                        Map.of("timestamp", 1000L, "speaker", "Spieler A", "text", "hello"),
                        Map.of("timestamp", 2000L, "speaker", "Spieler B", "text", "[url]"))),
                payload,
                "the payload must hold exactly the snapshot, nothing that identifies a player");
    }
}
