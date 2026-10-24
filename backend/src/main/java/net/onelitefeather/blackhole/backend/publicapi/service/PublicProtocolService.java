package net.onelitefeather.blackhole.backend.publicapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.publicapi.PlayerScope;
import net.onelitefeather.blackhole.backend.publicapi.PublicAppealReader;
import net.onelitefeather.blackhole.backend.publicapi.PublicConversationReader;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorKind;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicProtocolDTO;
import net.onelitefeather.blackhole.backend.security.PlayerSession;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The player's copy of the appeal protocol, rendered on demand from the same query and allow-list as the portal
 * conversation ({@link PublicConversationReader}: visibility {@code PLAYER}, allow-listed types, filtered in SQL).
 * It is derived, never stored, so it cannot be stale and needs no redaction coupling; the stored staff protocol
 * (which holds {@code STAFF} entries and staff names) is never read here. The player is "You", staff are
 * "Moderation team", nobody is named.
 */
@Singleton
public class PublicProtocolService {

    private static final int PAGE = 500;
    private static final String REMOVED = "[removed]";
    private static final ObjectWriter JSON = new ObjectMapper().writer(printer());

    private final PublicConversationReader reader;
    private final PublicAppealReader appeals;

    public PublicProtocolService(PublicConversationReader reader, PublicAppealReader appeals) {
        this.reader = reader;
        this.appeals = appeals;
    }

    private static DefaultPrettyPrinter printer() {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }

    /**
     * @param format {@code text} (default) or {@code json}
     * @return the {@link PublicProtocolDTO.Download}, or an error: 400 {@code VALIDATION} for an unknown format, 403
     *         {@code FORBIDDEN} for a leaked Ban ID, 409 {@code NOT_DECIDED} while no appeal is decided
     */
    public PublicProtocolDTO read(PlayerSession session, @Nullable String format) {
        String chosen = format == null ? "text" : format.toLowerCase(Locale.ROOT);
        if (!chosen.equals("text") && !chosen.equals("json")) {
            return error("VALIDATION", PublicErrorKind.VALIDATION);
        }
        if (session.scope() == PlayerScope.LIMITED) {
            return error("FORBIDDEN", PublicErrorKind.FORBIDDEN);
        }
        PublicAppealReader.Row decided = this.appeals.list(session.punishmentIdentifier()).stream()
                .filter(row -> row.status() == AppealStatus.GRANTED_FULL_LIFT || row.status() == AppealStatus.GRANTED_DURATION_REDUCTION
                        || row.status() == AppealStatus.DENIED)
                .findFirst().orElse(null);
        if (decided == null) {
            return error("NOT_DECIDED", PublicErrorKind.CONFLICT);
        }
        List<PublicConversationReader.Entry> entries = new ArrayList<>();
        Long cursor = null;
        while (true) {
            PublicConversationReader.Page page = this.reader.read(session.punishmentIdentifier(), cursor, PAGE);
            entries.addAll(page.entries());
            if (page.entries().size() < PAGE) {
                break;
            }
            cursor = page.cursor();
        }
        boolean json = chosen.equals("json");
        String decision = decided.status().name();
        byte[] content = (json ? json(decision, entries) : text(decision, entries)).getBytes(StandardCharsets.UTF_8);
        return new PublicProtocolDTO.Download("appeal-protocol" + (json ? ".json" : ".txt"),
                json ? "application/json" : "text/plain; charset=utf-8", content);
    }

    private static String who(String author) {
        return switch (author) {
            case "PLAYER" -> "You";
            case "STAFF" -> "Moderation team";
            default -> "System";
        };
    }

    private static String marker(String kind) {
        return switch (kind) {
            case "APPEAL_FILED" -> "Appeal filed";
            case "APPEAL_DECIDED" -> "Appeal decided";
            case "FROZEN" -> "Conversation frozen";
            case "FORFEITED" -> "Appeal right forfeited";
            default -> kind;
        };
    }

    private static String text(String decision, List<PublicConversationReader.Entry> entries) {
        StringBuilder out = new StringBuilder("Appeal protocol\nDecision: ").append(decision).append("\nTimes are UTC.\n");
        for (PublicConversationReader.Entry entry : entries) {
            out.append('\n').append(Instant.ofEpochMilli(entry.occurredAt())).append("  ");
            if (entry.kind().equals("MESSAGE")) {
                out.append(who(entry.author())).append('\n');
                String body = entry.removed() || entry.text() == null ? REMOVED : entry.text();
                body.lines().forEach(line -> out.append("    ").append(line).append('\n'));
            } else {
                out.append(marker(entry.kind())).append('\n');
            }
        }
        return out.toString();
    }

    private static String json(String decision, List<PublicConversationReader.Entry> entries) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", 1);
        document.put("decision", decision);
        List<Map<String, Object>> shown = new ArrayList<>();
        for (PublicConversationReader.Entry entry : entries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("at", Instant.ofEpochMilli(entry.occurredAt()).toString());
            item.put("kind", entry.kind());
            if (entry.kind().equals("MESSAGE")) {
                item.put("author", who(entry.author()));
                item.put("removed", entry.removed());
                item.put("text", entry.removed() ? null : entry.text());
            }
            shown.add(item);
        }
        document.put("entries", shown);
        try {
            return JSON.writeValueAsString(document) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not write the protocol", e);
        }
    }

    private static PublicProtocolDTO.Error error(String code, PublicErrorKind kind) {
        return new PublicProtocolDTO.Error(code, null, kind);
    }
}
