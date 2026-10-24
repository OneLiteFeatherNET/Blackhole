package net.onelitefeather.blackhole.backend.casetimeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micronaut.core.annotation.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the conversation and lifecycle of a case as a transcript, in {@code occurredAt}/{@code seq}
 * order. Notes are left out unless asked for; a redacted entry is a marker and never carries text. The
 * payloads exported are the ones stored (ids, enums, message text), so nothing is exported that the
 * timeline would not show a viewer, and a Ban ID is in none of them.
 */
public final class TranscriptRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final String REMOVED = "[removed for data protection]";

    /** A rendered transcript. */
    public record Rendered(String json, String text, int entries) {
    }

    private TranscriptRenderer() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** Whether an event of this type is part of a transcript. */
    public static boolean included(String type, boolean includeNotes) {
        if (type.startsWith("note.")) {
            return includeNotes;
        }
        return MessageThread.isMessageEvent(type) || type.startsWith("ban_id.") || type.startsWith("appeal.")
                || type.startsWith("punishment.") || type.startsWith("evidence.") || type.startsWith("ticket.") || type.equals(CaseEventPayload.STATUS_CHANGED);
    }

    /**
     * @param shortId the display short id of the punishment
     * @param entries every event of the case with its actor's name, in timeline order
     */
    public static Rendered render(String punishmentIdentifier, String shortId, boolean includeNotes, List<CaseReader.Entry> entries) {
        List<CaseReader.Entry> shown = entries.stream().filter(entry -> included(entry.event().type(), includeNotes)).toList();
        List<Map<String, Object>> items = new ArrayList<>();
        for (CaseReader.Entry entry : shown) {
            items.add(item(entry));
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("punishment", punishmentIdentifier);
        document.put("shortId", shortId);
        document.put("includeNotes", includeNotes);
        document.put("entryCount", items.size());
        document.put("entries", items);
        String json;
        try {
            json = MAPPER.writeValueAsString(document) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not write the transcript", e);
        }
        return new Rendered(json, text(punishmentIdentifier, shortId, includeNotes, shown), items.size());
    }

    private static Map<String, Object> item(CaseReader.Entry entry) {
        CaseEventRecord event = entry.event();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("seq", event.seq());
        item.put("at", Instant.ofEpochMilli(event.occurredAt()).toString());
        item.put("type", event.type());
        item.put("visibility", event.visibility());
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("trust", event.actorTrust().name());
        if (entry.actorName() != null) {
            actor.put("name", entry.actorName());
        }
        item.put("actor", actor);
        item.put("redacted", event.redactedAt() != null);
        item.put("payload", event.body());
        return item;
    }

    private static String text(String punishmentIdentifier, String shortId, boolean includeNotes, List<CaseReader.Entry> shown) {
        StringBuilder out = new StringBuilder();
        out.append("Transcript of punishment ").append(punishmentIdentifier).append(" (").append(shortId).append(")\n");
        out.append("Entries: ").append(shown.size()).append(". Notes ").append(includeNotes ? "included" : "excluded").append(". Times are UTC.\n");
        for (CaseReader.Entry entry : shown) {
            CaseEventRecord event = entry.event();
            out.append('\n').append('#').append(event.seq()).append("  ").append(Instant.ofEpochMilli(event.occurredAt()))
                    .append("  ").append(event.type()).append("  [").append(event.visibility()).append("]  ")
                    .append(actorLabel(event, entry.actorName())).append('\n');
            String detail = detail(event);
            if (detail != null) {
                detail.lines().forEach(line -> out.append("    ").append(line).append('\n'));
            }
            if (event.redactedAt() != null && detail == null) {
                out.append("    ").append(REMOVED).append('\n');
            }
        }
        return out.toString();
    }

    private static String actorLabel(CaseEventRecord event, @Nullable String name) {
        return switch (event.actorTrust()) {
            case PLAYER -> "player";
            case SYSTEM -> "system";
            case VERIFIED -> (name != null ? name : "staff member") + " (verified)";
            case UNVERIFIED -> (name != null ? name : "staff member") + " (unverified)";
        };
    }

    private static @Nullable String detail(CaseEventRecord event) {
        CaseEventPayload payload = event.payload();
        return switch (payload) {
            case CaseEventPayload.MessageAdded added -> added.text() != null ? added.text() : REMOVED;
            case CaseEventPayload.MessageEdited edited -> "(edited) " + (edited.text() != null ? edited.text() : REMOVED);
            case CaseEventPayload.MessageDeleted deleted -> "(deleted by " + deleted.deletedBy().name().toLowerCase(java.util.Locale.ROOT) + ")";
            case CaseEventPayload.MessageRedacted redacted -> "(redacted for data protection: " + redacted.reason() + ")";
            case CaseEventPayload.NoteAdded note -> note.text();
            case CaseEventPayload.NoteEdited note -> "(edited) " + note.text();
            default -> event.body().isEmpty() ? null : event.body().toString();
        };
    }
}
