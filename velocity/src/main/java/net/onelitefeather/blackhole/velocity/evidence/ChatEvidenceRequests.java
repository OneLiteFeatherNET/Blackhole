package net.onelitefeather.blackhole.velocity.evidence;

import net.onelitefeather.blackhole.client.model.EvidenceCreateRequest;
import net.onelitefeather.blackhole.client.model.EvidenceKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the API request for a chat snapshot: a {@code CHAT_LOG} addressed by owner hash and
 * correlation id, whose lines carry the snapshot's aliases and redacted texts only.
 */
public final class ChatEvidenceRequests {

    private ChatEvidenceRequests() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    public static @NotNull EvidenceCreateRequest create(
            @NotNull String owner, @NotNull String correlationId, @NotNull ChatSnapshot snapshot,
            @NotNull String sourceSystem, @Nullable UUID source, long capturedAt
    ) {
        List<Map<String, Object>> lines = new ArrayList<>(snapshot.size());
        for (ChatSnapshot.Entry entry : snapshot.lines()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("timestamp", entry.timestamp());
            line.put("speaker", entry.speaker());
            line.put("text", entry.text());
            lines.add(line);
        }
        return new EvidenceCreateRequest()
                .owner(owner)
                .correlationId(correlationId)
                .kind(EvidenceKind.CHAT_LOG)
                .sourceSystem(sourceSystem)
                .capturedAt(capturedAt)
                .onBehalfOf(source)
                .payload(Map.of("lines", lines));
    }
}
